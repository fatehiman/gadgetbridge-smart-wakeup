package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Clock
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.DetectorFactory
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Json
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Mode
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.NoopDetector
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.WakeDetector
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.WakeEngine
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.WakeReason
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.WakeWindow
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SleepWakeStore
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSession
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings
import nodomain.freeyourgadget.gadgetbridge.sleepwake.sync.SyncScheduler
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Session life cycle. Everything here reads and writes the DB, never memory, so the service, the alarm
 * receiver, the notification button and the UI can all call it, also after the process was killed.
 */
object NightSessionManager {
    private const val TAG = "SleepWake"

    /** The detector comes from the downloaded config (if any), else from the local numbers. */
    fun buildDetector(settings: SwSettings, mode: Mode): WakeDetector =
        if (mode == Mode.SMART_WAKE) DetectorFactory.create(settings.detectorConfigJson, settings.heuristic) else NoopDetector

    fun buildEngine(s: SwSession, settings: SwSettings): WakeEngine {
        val mode = Mode.fromId(s.mode)
        return WakeEngine(
            mode, WakeWindow(Instant.ofEpochSecond(s.targetTime), s.toleranceMin), Instant.ofEpochSecond(s.startedAt),
            buildDetector(settings, mode), settings.monitorWakeAt,
        )
    }

    /** Starts a session: row, hard alarm, first tick, service. Never blocks on a missing watch (the checklist only warns). */
    fun start(ctx: Context, clock: Clock = Clock.SYSTEM): SwSession {
        val store = SleepWakeStore.get(ctx)
        store.activeSession()?.let { return it } // already running
        val settings = SwSettings(ctx)
        val zone = ZoneId.systemDefault()
        val now = clock.now()
        val window = WakeWindow.forNextTarget(now, LocalTime.ofSecondOfDay(settings.targetMinutes * 60L), zone, settings.toleranceMin)
        val mode = settings.mode
        val detector = buildDetector(settings, mode)
        val engine = WakeEngine(mode, window, now, detector, settings.monitorWakeAt)
        val device = DeviceAccess.device(ctx)
        val session = SwSession(
            uuid = UUID.randomUUID().toString(), startedAt = now.epochSecond, endedAt = null, mode = mode.id,
            targetTime = window.target.epochSecond, toleranceMin = settings.toleranceMin,
            windowStart = window.windowStart.epochSecond, deadline = window.deadline.epochSecond,
            fireAt = engine.hardDeadline().epochSecond, detectorId = detector.id,
            detectorParamsJson = Json.encode(detector.params() + ("config_version" to settings.detectorConfigVersion)), timezone = zone.id,
            deviceAddress = device?.address ?: "", wakeReason = null, wakeTs = null, ackedAt = null, uploaded = false,
        )
        store.insertSession(session)
        val configured = device?.let { configureDevice(ctx, it) } ?: emptyMap()
        store.addEvent(
            session.uuid, "session_started",
            mapOf(
                "mode" to mode.id, "target" to session.targetTime, "tolerance_min" to session.toleranceMin,
                "fire_at" to session.fireAt, "device" to device?.name, "device_state" to device?.state?.name,
                "battery" to device?.getBatteryLevel(0), "fetch_interval_min" to settings.fetchIntervalMin,
                "exact_alarm_ok" to SwAlarms.canScheduleExact(ctx), "device_config" to configured,
            ),
        )
        SwAlarms.scheduleFire(ctx, session.fireAt * 1000)
        SwAlarms.scheduleTick(ctx, System.currentTimeMillis() + 3000)
        startService(ctx, NightSessionService.ACTION_START)
        return session
    }

    /**
     * Best effort: heart-rate monitoring on at the shortest interval the driver offers, SpO2 all-day on.
     * (CMF Watch Pro 2 offers only OFF and SMART for heart rate, see DISCOVERY.md.)
     */
    private fun configureDevice(ctx: Context, device: nodomain.freeyourgadget.gadgetbridge.impl.GBDevice): Map<String, Any?> {
        val applied = linkedMapOf<String, Any?>()
        try {
            val coordinator = device.deviceCoordinator
            val intervals = coordinator.heartRateMeasurementIntervals
            // seconds: -2 continuous, -1 smart, >0 fixed interval. Prefer continuous, then the shortest fixed interval, then smart.
            val secs = intervals.map { it.intervalSeconds }
            val seconds: Int? = when {
                -2 in secs -> -2
                secs.any { it > 0 } -> secs.filter { it > 0 }.min()
                -1 in secs -> -1
                else -> null
            }
            if (seconds != null) {
                GBApplication.deviceService(device).onSetHeartRateMeasurementInterval(seconds)
                applied["hr_interval_s"] = seconds
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not set HR interval", e)
            applied["hr_interval_error"] = e.javaClass.simpleName
        }
        try {
            val prefs = GBApplication.getDevicePrefs(device)
            val key = "spo2_all_day_monitoring_enabled"
            prefs.preferences.edit().putBoolean(key, true).apply()
            GBApplication.deviceService(device).onSendConfiguration(key)
            applied["spo2_all_day"] = true
        } catch (e: Exception) {
            applied["spo2_error"] = e.javaClass.simpleName
        }
        return applied
    }

    /** The hard alarm fired: wake now, whatever the detector or the connection says. */
    fun onFire(ctx: Context) {
        val s = SleepWakeStore.get(ctx).activeSession() ?: return
        val reason = if (Mode.fromId(s.mode) == Mode.MONITOR_ONLY) WakeReason.MONITOR_TARGET else WakeReason.DEADLINE
        triggerWake(ctx, reason, "hard alarm")
    }

    /** Idempotent: the first call starts the vibration loop, later calls do nothing. */
    @Synchronized
    fun triggerWake(ctx: Context, reason: WakeReason, detail: String?) {
        val store = SleepWakeStore.get(ctx)
        val s = store.activeSession() ?: return
        if (s.wakeTs != null) return
        val now = System.currentTimeMillis()
        val device = if (s.deviceAddress.isNotEmpty()) DeviceAccess.byAddress(s.deviceAddress) else null
        val watchReady = DeviceAccess.isReady(device)
        val finalReason = if (watchReady) reason else WakeReason.FALLBACK
        store.updateSession(s.uuid, mapOf("wake_reason" to finalReason.name, "wake_ts" to now / 1000))
        store.addEvent(
            s.uuid, "wake_triggered",
            mapOf("reason" to finalReason.name, "original_reason" to reason.name, "detail" to detail, "watch_ready" to watchReady),
            now,
        )
        setAttempts(ctx, 0)
        SwAlarms.cancelRepeat(ctx)
        Notifier.showWake(ctx, if (watchReady) "Your watch is vibrating. Tap \"I'm awake\" to stop." else "Watch not connected. Tap \"I'm awake\" to stop.")
        repeat(ctx)
    }

    /** One vibration attempt, then schedules the next one (alarm based, independent of the service). */
    fun repeat(ctx: Context) {
        val store = SleepWakeStore.get(ctx)
        val s = store.activeSession() ?: return
        if (s.wakeTs == null || s.ackedAt != null) return
        val n = getAttempts(ctx) + 1
        setAttempts(ctx, n)
        val r = WakeActuator.attempt(ctx, s.deviceAddress, n)
        if (n == 1 || n % 5 == 0 || r.phoneAlert) {
            store.addEvent(s.uuid, "wake_attempt", mapOf("n" to n, "watch" to r.watchSent, "phone" to r.phoneAlert))
        }
        SwAlarms.scheduleRepeat(ctx, WakeActuator.REPEAT_MS)
    }

    /** "I'm awake": stop everything, close the session after a final fetch, ask for the morning rating. */
    @Synchronized
    fun acknowledge(ctx: Context) {
        val store = SleepWakeStore.get(ctx)
        val s = store.activeSession() ?: return
        if (s.ackedAt != null) return
        val now = System.currentTimeMillis()
        store.updateSession(s.uuid, mapOf("acked_at" to now / 1000))
        store.addEvent(s.uuid, "wake_acknowledged", mapOf("after_s" to ((now / 1000) - (s.wakeTs ?: now / 1000))), now)
        SwAlarms.cancelRepeat(ctx)
        WakeActuator.stopAll(ctx, s.deviceAddress)
        Notifier.cancelWake(ctx)
        Notifier.showRating(ctx, s.uuid)
        startService(ctx, NightSessionService.ACTION_FINALIZE)
    }

    /** Manual stop (also used when the user ends a night early). */
    fun requestStop(ctx: Context) {
        val store = SleepWakeStore.get(ctx)
        val s = store.activeSession() ?: return
        if (s.wakeTs == null) {
            store.addEvent(s.uuid, "wake_triggered", mapOf("reason" to WakeReason.MANUAL.name, "detail" to "stopped by user"))
            store.updateSession(s.uuid, mapOf("wake_reason" to WakeReason.MANUAL.name, "wake_ts" to System.currentTimeMillis() / 1000))
        }
        SwAlarms.cancelAll(ctx)
        WakeActuator.stopAll(ctx, s.deviceAddress)
        Notifier.cancelWake(ctx)
        startService(ctx, NightSessionService.ACTION_FINALIZE)
    }

    /** Called by the service after the final fetch: closes the session row, uploads, cleans up. */
    fun finish(ctx: Context) {
        val store = SleepWakeStore.get(ctx)
        val s = store.activeSession() ?: return
        val now = System.currentTimeMillis()
        store.updateSession(s.uuid, mapOf("ended_at" to (now / 1000).coerceAtLeast(s.startedAt)))
        store.addEvent(s.uuid, "session_stopped", mapOf("wake_reason" to (store.session(s.uuid)?.wakeReason)), now)
        SwAlarms.cancelAll(ctx)
        Notifier.cancelAll(ctx)
        SyncScheduler.enqueueNow(ctx, delaySeconds = 30)
    }

    /** After reboot or app update: put alarms and the service back. */
    fun restore(ctx: Context) {
        val s = SleepWakeStore.get(ctx).activeSession() ?: return
        val fireMs = s.fireAt * 1000
        if (s.wakeTs == null) {
            if (fireMs > System.currentTimeMillis()) SwAlarms.scheduleFire(ctx, fireMs) else onFire(ctx)
        } else if (s.ackedAt == null) {
            repeat(ctx)
        }
        SwAlarms.scheduleTick(ctx, System.currentTimeMillis() + 5000)
        startService(ctx, NightSessionService.ACTION_START)
    }

    fun startService(ctx: Context, action: String) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, NightSessionService::class.java).setAction(action))
        } catch (e: Exception) {
            // e.g. background start not allowed. The alarms still work without the service.
            android.util.Log.w(TAG, "cannot start service: $e")
        }
    }

    private fun getAttempts(ctx: Context) = ctx.getSharedPreferences("sleepwake_state", Context.MODE_PRIVATE).getInt("attempts", 0)
    private fun setAttempts(ctx: Context, n: Int) = ctx.getSharedPreferences("sleepwake_state", Context.MODE_PRIVATE).edit().putInt("attempts", n).apply()
}
