package nodomain.freeyourgadget.gadgetbridge.sleepwake.ui

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Mode
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SleepWakeStore
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings
import nodomain.freeyourgadget.gadgetbridge.sleepwake.service.DeviceAccess
import nodomain.freeyourgadget.gadgetbridge.sleepwake.service.NightSessionManager
import nodomain.freeyourgadget.gadgetbridge.sleepwake.service.SwAlarms
import nodomain.freeyourgadget.gadgetbridge.sleepwake.sync.ServerSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The Night screen: target time, tolerance, mode, pre-sleep checklist, start/stop, "I'm awake". */
class NightActivity : AbstractGBActivity() {
    private lateinit var settings: SwSettings
    private lateinit var store: SleepWakeStore
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var timeButton: Button
    private lateinit var tolText: TextView
    private lateinit var tolSeek: SeekBar
    private lateinit var checklist: LinearLayout
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var awake: Button
    private lateinit var inputs: List<View>

    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SleepWake – Night"
        settings = SwSettings(this)
        store = SleepWakeStore.get(this)
        setContentView(build())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }

        status = label("", 15f)
        root.addView(status)

        root.addView(label("Wake time", 14f, true))
        timeButton = Button(this).apply { setOnClickListener { pickTime() } }
        root.addView(timeButton)

        tolText = label("", 14f)
        root.addView(tolText)
        tolSeek = SeekBar(this).apply {
            max = 60
            progress = settings.toleranceMin
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) settings.toleranceMin = p
                    updateTolText()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        root.addView(tolSeek)

        root.addView(label("Always on: the watch is recorded all night, you are woken at the first light-sleep moment inside the window, and at the latest at the end of the window.", 13f))

        root.addView(label("Pre-sleep checklist", 14f, true))
        checklist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(checklist)

        startStop = Button(this).apply { setOnClickListener { onStartStop() } }
        root.addView(startStop, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })

        awake = Button(this).apply {
            text = "I'm awake"
            setOnClickListener { NightSessionManager.acknowledge(this@NightActivity); refreshUi() }
        }
        root.addView(awake, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(8) })

        root.addView(Button(this).apply {
            text = "Settings (sync, detector, behavior)"
            setOnClickListener { startActivity(Intent(this@NightActivity, SleepWakeSettingsActivity::class.java)) }
        })
        // day mode: stop Gadgetbridge's service and the Bluetooth connection (upstream "Quit"). Not allowed during a night.
        root.addView(Button(this).apply {
            text = "Stop service (day mode)"
            setOnClickListener {
                if (store.activeSession() != null) {
                    android.widget.Toast.makeText(this@NightActivity, "A night is running. Stop the session first.", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this@NightActivity)
                        .setMessage("Stop the app service and disconnect the watch? Open the app again in the evening: it reconnects, and the checklist tells you if not.")
                        .setPositiveButton("Stop") { _, _ -> nodomain.freeyourgadget.gadgetbridge.GBApplication.quit() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        })
        inputs = listOf(timeButton, tolSeek)
        return ScrollView(this).apply { addView(root) }
    }

    private fun timeText(minutes: Int): String {
        val d = Date(0).apply { }
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, minutes / 60); set(java.util.Calendar.MINUTE, minutes % 60) }
        return DateFormat.getTimeFormat(this).format(cal.time).also { d.time }
    }

    private fun pickTime() {
        val m = settings.targetMinutes
        TimePickerDialog(this, { _, h, min -> settings.targetMinutes = h * 60 + min; refreshUi() }, m / 60, m % 60, DateFormat.is24HourFormat(this)).show()
    }

    private fun updateTolText() {
        val t = settings.targetMinutes
        val tol = settings.toleranceMin
        val lo = timeText(((t - tol) % 1440 + 1440) % 1440)
        val hi = timeText((t + tol) % 1440)
        tolText.text = "Max range: ±$tol min  (window $lo – $hi)" + if (settings.neverWakeEarly) "  [data-only: no early wake]" else ""
    }

    // ---------------- checklist ----------------

    private data class Check(val ok: Boolean?, val text: String, val fix: (() -> Unit)? = null)

    private fun checks(): List<Check> {
        val list = mutableListOf<Check>()
        val d = DeviceAccess.device(this)
        list += when {
            d == null -> Check(false, "No watch paired. Pair it with this app first.")
            d.isInitialized -> Check(true, "Watch connected: ${d.name}")
            else -> Check(false, "Watch not connected (${d.state.name.lowercase().replace('_', ' ')}). Tap to reconnect.") { DeviceAccess.connect(d) }
        }
        list += Check(null, "Continuous heart rate: set automatically at start (CMF watch: smart mode only)")
        val battery = d?.getBatteryLevel(0) ?: -1
        list += if (battery in 0..100) Check(battery >= 30, "Watch battery: $battery%" + if (battery < 30) " (low!)" else "") else Check(null, "Watch battery: unknown")
        val notifOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        list += Check(notifOk, if (notifOk) "Notifications allowed" else "Notifications NOT allowed. Tap to allow.") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val exact = SwAlarms.canScheduleExact(this)
        list += Check(exact, if (exact) "Exact alarms allowed" else "Exact alarms NOT allowed. Tap to allow (the wake alarm may be late without it).") {
            if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        list += Check(ignoring, if (ignoring) "Battery optimization: exempt" else "Battery optimization is ON. Tap to exempt this app.") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        list += when {
            !BuildConfig.INTERNET_ACCESS -> Check(null, "Server sync: not available in this build")
            !settings.syncEnabled -> Check(null, "Server sync: off")
            else -> Check(!settings.lastSyncStatus.startsWith("Error"), "Server sync: ${settings.lastSyncStatus}")
        }
        return list
    }

    private fun failing() = checks().filter { it.ok == false }

    private fun refreshUi() {
        val active = store.activeSession()
        timeButton.text = timeText(settings.targetMinutes)
        tolSeek.progress = settings.toleranceMin
        updateTolText()
        inputs.forEach { it.isEnabled = active == null }

        checklist.removeAllViews()
        for (c in checks()) {
            checklist.addView(TextView(this).apply {
                text = (when (c.ok) { true -> "✔  "; false -> "⚠  "; null -> "•  " }) + c.text
                textSize = 14f
                setPadding(0, dp(3), 0, dp(3))
                if (c.ok == false) setTextColor(0xFFC62828.toInt())
                if (c.fix != null && c.ok == false) setOnClickListener { c.fix.invoke() }
            })
        }

        if (active == null) {
            status.text = latencySummary() ?: "No session running."
            startStop.text = "Start night session"
            awake.visibility = View.GONE
        } else {
            val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(active.timezone) }
            val tf = { s: Long -> fmt.format(Date(s * 1000)) }
            val state = when {
                active.ackedAt != null -> "Awake. Closing the night…"
                active.wakeTs != null -> "WAKING YOU NOW (${active.wakeReason})"
                else -> "Session running since ${tf(active.startedAt)}"
            }
            status.text = "$state\nWindow ${tf(active.windowStart)} – ${tf(active.deadline)}, latest wake ${tf(active.fireAt)}\nlast fetch: " + (store.lastEventOfType(active.uuid, "fetch_finished")?.let { fmt.format(Date(it.ts)) } ?: "none yet")
            startStop.text = "Stop session"
            awake.visibility = if (active.wakeTs != null && active.ackedAt == null) View.VISIBLE else View.GONE
        }
    }

    /** From the last finished night: how late the newest sample was when a fetch finished (DISCOVERY.md 3.9). */
    private fun latencySummary(): String? {
        val last = store.recentSessions(1).firstOrNull() ?: return null
        val lat = store.events(last.uuid, "fetch_finished").mapNotNull { e ->
            Regex("\"latency_s\":(-?\\d+)").find(e.payloadJson ?: "")?.groupValues?.get(1)?.toLongOrNull()
        }.sorted()
        if (lat.isEmpty()) return "Last night: no fetch data recorded."
        return "Last night: ${lat.size} fetches, newest sample was ${lat[lat.size / 2] / 60} min old at the median (max ${lat.last() / 60} min)."
    }

    private fun onStartStop() {
        if (store.activeSession() != null) {
            AlertDialog.Builder(this).setMessage("Stop the session now?")
                .setPositiveButton("Stop") { _, _ -> NightSessionManager.requestStop(this); handler.postDelayed({ refreshUi() }, 500) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val bad = failing()
        if (bad.isEmpty()) {
            startSession()
        } else {
            AlertDialog.Builder(this).setTitle("Check before sleeping")
                .setMessage(bad.joinToString("\n\n") { "⚠ " + it.text })
                .setPositiveButton("Start anyway") { _, _ -> startSession() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun startSession() {
        settings.deviceAddress = DeviceAccess.device(this)?.address ?: ""
        if (BuildConfig.INTERNET_ACCESS && settings.syncEnabled) {
            // get the newest detector config first (short timeout, harmless if it fails)
            startStop.isEnabled = false
            Thread {
                ServerSync(this).fetchConfig(quick = true)
                runOnUiThread { startStop.isEnabled = true; NightSessionManager.start(this); refreshUi() }
            }.start()
        } else {
            NightSessionManager.start(this)
            refreshUi()
        }
    }
}
