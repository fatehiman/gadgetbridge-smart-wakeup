package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.EngineResult
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Mode
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SleepWakeStore
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSession
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Foreground service of a night. Every N minutes (alarm driven, so it survives the service being killed):
 * fetch from the watch, wait for completion, read the new samples, ask the engine.
 * The deadline never depends on this service (see [SwAlarms.scheduleFire]).
 */
class NightSessionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var store: SleepWakeStore
    private lateinit var settings: SwSettings

    private var fetchStartedMs = 0L
    private var awaitingFetch = false
    private var finalizing = false
    private var reconnectDelayMs = 30_000L

    private val newData = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dev = intent.getParcelableExtra<GBDevice>(GBDevice.EXTRA_DEVICE)
            val s = store.activeSession() ?: return
            if (dev != null && s.deviceAddress.isNotEmpty() && dev.address != s.deviceAddress) return
            if (awaitingFetch && !finalizing) handler.postDelayed({ onFetchFinished(true) }, 2000) // data is persisted just before the signal
        }
    }

    private val deviceChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dev = intent.getParcelableExtra<GBDevice>(GBDevice.EXTRA_DEVICE) ?: return
            val s = store.activeSession() ?: return
            if (dev.address != s.deviceAddress) return
            noteConnection(s, DeviceAccess.isReady(dev))
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SleepWakeStore.get(this)
        settings = SwSettings(this)
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(newData, IntentFilter(GBApplication.ACTION_NEW_DATA))
        lbm.registerReceiver(deviceChanged, IntentFilter(GBDevice.ACTION_DEVICE_CHANGED))
    }

    override fun onDestroy() {
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(newData)
        lbm.unregisterReceiver(deviceChanged)
        handler.removeCallbacksAndMessages(null)
        releaseLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = store.activeSession()
        startInForeground(session)
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_FINALIZE -> beginFinalize(session)
            ACTION_START, ACTION_TICK, null -> if (!finalizing) tick(session)
        }
        return START_STICKY
    }

    private fun startInForeground(session: SwSession?) {
        val n = Notifier.session(this, "SleepWake", session?.let { statusText(it) } ?: "No active session")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.ID_SESSION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifier.ID_SESSION, n)
        }
    }

    // ---------- the loop ----------

    private fun tick(session: SwSession) {
        acquireLock(4 * 60_000L)
        val device = DeviceAccess.byAddress(session.deviceAddress)
        noteConnection(session, DeviceAccess.isReady(device))
        logBattery(session, device)
        if (session.wakeTs != null) { // wake already triggered: only the vibration loop and the user matter now
            refreshNotification(session)
            releaseLock()
            return
        }
        if (device != null && DeviceAccess.isReady(device) && !device.isBusy) {
            awaitingFetch = true
            fetchStartedMs = System.currentTimeMillis()
            store.addEvent(session.uuid, "fetch_started", mapOf("interval_min" to settings.fetchIntervalMin))
            DeviceAccess.requestFetch(device)
            handler.postDelayed({ if (awaitingFetch && !finalizing) onFetchFinished(false) }, FETCH_TIMEOUT_MS)
        } else {
            if (device != null && !device.isConnected && !device.isConnecting) {
                runCatching { DeviceAccess.connect(device) }
            }
            store.addEvent(session.uuid, "fetch_failed", mapOf("reason" to if (device == null) "no_device" else if (device.isBusy) "busy" else "not_connected", "state" to device?.state?.name))
            evaluateAndSchedule(session)
        }
    }

    private fun onFetchFinished(ok: Boolean) {
        if (!awaitingFetch) return
        awaitingFetch = false
        val session = store.activeSession() ?: return
        val took = System.currentTimeMillis() - fetchStartedMs
        val device = DeviceAccess.byAddress(session.deviceAddress)
        // latency measurement (DISCOVERY.md 3.9): receive time vs newest sample time
        val samples = device?.let { SampleReader.read(it, session.startedAt, System.currentTimeMillis() / 1000) }.orEmpty()
        val maxTs = SampleReader.maxTimestamp(samples)
        val nowS = System.currentTimeMillis() / 1000
        store.addEvent(
            session.uuid, if (ok) "fetch_finished" else "fetch_failed",
            mapOf(
                "took_ms" to took, "samples" to samples.size, "max_sample_ts" to maxTs,
                "latency_s" to maxTs?.let { nowS - it }, "reason" to if (ok) null else "timeout",
            ),
        )
        if (ok) getSharedPreferences("sleepwake_state", MODE_PRIVATE).edit().putLong("last_sync_s", nowS).apply()
        evaluateAndSchedule(session, samples)
    }

    private fun evaluateAndSchedule(session: SwSession, preloaded: List<nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Sample>? = null) {
        try {
            val device = DeviceAccess.byAddress(session.deviceAddress)
            val now = Instant.now()
            val samples = preloaded ?: device?.let { SampleReader.read(it, session.startedAt, now.epochSecond) }.orEmpty()
            val engine = NightSessionManager.buildEngine(session, settings)
            val result = engine.evaluate(now, samples)
            store.addEvent(
                session.uuid, "detector_evaluated",
                mapOf("decision" to if (result is EngineResult.Wake) "wake" else "wait", "samples" to samples.size, "inputs" to result.inputs) +
                    (if (result is EngineResult.Wake) mapOf("reason" to result.reason.name, "detail" to result.detail) else emptyMap()),
            )
            if (result is EngineResult.Wake) {
                NightSessionManager.triggerWake(this, result.reason, result.detail)
                refreshNotification(store.activeSession() ?: session)
            } else {
                SwAlarms.scheduleTick(this, System.currentTimeMillis() + settings.fetchIntervalMin * 60_000L)
                refreshNotification(session)
            }
        } catch (e: Exception) {
            android.util.Log.e("SleepWake", "evaluate failed", e)
            // never lose the loop because of one bad evaluation
            SwAlarms.scheduleTick(this, System.currentTimeMillis() + settings.fetchIntervalMin * 60_000L)
        } finally {
            releaseLock()
        }
    }

    // ---------- connection watchdog ----------

    private fun noteConnection(session: SwSession, ready: Boolean) {
        val prefs = getSharedPreferences("sleepwake_state", MODE_PRIVATE)
        val was = prefs.getBoolean("watch_ready", true)
        if (was == ready) return
        prefs.edit().putBoolean("watch_ready", ready).apply()
        store.addEvent(session.uuid, if (ready) "connection_restored" else "connection_lost", null)
        if (ready) reconnectDelayMs = 30_000L
        else handler.postDelayed({ reconnectWatchdog() }, reconnectDelayMs)
    }

    /** Reconnect with backoff (30 s, 60 s ... 5 min) while the watch is away. The deadline alarm does not depend on this. */
    private fun reconnectWatchdog() {
        val s = store.activeSession() ?: return
        val d = DeviceAccess.byAddress(s.deviceAddress) ?: return
        if (DeviceAccess.isReady(d) || finalizing) return
        if (!d.isConnected && !d.isConnecting) runCatching { DeviceAccess.connect(d) }
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(5 * 60_000L)
        handler.postDelayed({ reconnectWatchdog() }, reconnectDelayMs)
    }

    private fun logBattery(session: SwSession, device: GBDevice?) {
        val level = device?.getBatteryLevel(0) ?: return
        if (level < 0) return
        val prefs = getSharedPreferences("sleepwake_state", MODE_PRIVATE)
        if (prefs.getInt("last_battery", -2) == level) return
        prefs.edit().putInt("last_battery", level).apply()
        store.addEvent(session.uuid, "battery", mapOf("level" to level, "state" to device.getBatteryState(0)?.name))
    }

    // ---------- finishing ----------

    private fun beginFinalize(session: SwSession) {
        if (finalizing) return
        finalizing = true
        acquireLock(3 * 60_000L)
        val device = DeviceAccess.byAddress(session.deviceAddress)
        if (device != null && DeviceAccess.isReady(device) && !device.isBusy) {
            awaitingFetch = true
            fetchStartedMs = System.currentTimeMillis()
            store.addEvent(session.uuid, "fetch_started", mapOf("final" to true))
            DeviceAccess.requestFetch(device)
            handler.postDelayed({ completeFinalize() }, FINAL_FETCH_TIMEOUT_MS)
            // completion signal ends the wait earlier
            LocalBroadcastManager.getInstance(this).registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    LocalBroadcastManager.getInstance(this@NightSessionService).unregisterReceiver(this)
                    handler.postDelayed({ completeFinalize() }, 2000)
                }
            }, IntentFilter(GBApplication.ACTION_NEW_DATA))
        } else {
            completeFinalize()
        }
    }

    private var finalized = false

    private fun completeFinalize() {
        if (finalized) return
        finalized = true
        awaitingFetch = false
        NightSessionManager.finish(this)
        releaseLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- notification text ----------

    private fun statusText(s: SwSession): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(s.timezone) }
        fun t(sec: Long) = fmt.format(Date(sec * 1000))
        val d = DeviceAccess.byAddress(s.deviceAddress)
        val prefs = getSharedPreferences("sleepwake_state", MODE_PRIVATE)
        val last = prefs.getLong("last_sync_s", 0)
        val mode = if (Mode.fromId(s.mode) == Mode.SMART_WAKE) "Smart wake ${t(s.windowStart)}-${t(s.deadline)}" else "Monitor only, wake ${t(s.fireAt)}"
        val state = when {
            s.ackedAt != null -> "Awake, closing the night"
            s.wakeTs != null -> "Waking you up now"
            else -> "Monitoring"
        }
        val next = if (s.wakeTs != null) "" else "\nNext check ~" + t(System.currentTimeMillis() / 1000 + settings.fetchIntervalMin * 60L) + ", deadline " + t(s.fireAt)
        return "$state · $mode\nWatch: ${d?.state?.name?.lowercase()?.replace('_', ' ') ?: "no device"}" +
            " · last sync ${if (last > 0) t(last) else "never"}$next"
    }

    private fun refreshNotification(s: SwSession) = Notifier.updateSession(this, "SleepWake night", statusText(s))

    private fun acquireLock(ms: Long) {
        releaseLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepwake:tick").apply { acquire(ms) }
    }

    private fun releaseLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "nodomain.freeyourgadget.gadgetbridge.sleepwake.START"
        const val ACTION_TICK = "nodomain.freeyourgadget.gadgetbridge.sleepwake.SERVICE_TICK"
        const val ACTION_FINALIZE = "nodomain.freeyourgadget.gadgetbridge.sleepwake.FINALIZE"
        private const val FETCH_TIMEOUT_MS = 150_000L
        private const val FINAL_FETCH_TIMEOUT_MS = 90_000L
    }
}
