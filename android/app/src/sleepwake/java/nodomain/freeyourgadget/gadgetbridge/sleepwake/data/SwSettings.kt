package nodomain.freeyourgadget.gadgetbridge.sleepwake.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.HeuristicParams
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Mode
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.MonitorWakeAt

enum class Fallback(val id: String) {
    VIBRATE("vibrate"), ALARM_SOUND("alarm");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: VIBRATE
    }
}

/** All settings of the module. The API token is kept in EncryptedSharedPreferences (Keystore backed). */
class SwSettings(context: Context, skipProvision: Boolean = false) {
    private val ctx = context.applicationContext
    private val p: SharedPreferences = ctx.getSharedPreferences("sleepwake_prefs", Context.MODE_PRIVATE)

    init {
        if (!skipProvision) Provision.applyIfPresent(ctx) // debug builds only
    }

    // ---- night ----
    var targetMinutes: Int
        get() = p.getInt("target_minutes", 8 * 60)
        set(v) = p.edit().putInt("target_minutes", v).apply()
    var toleranceMin: Int
        get() = p.getInt("tolerance_min", 15).coerceIn(0, 60)
        set(v) = p.edit().putInt("tolerance_min", v.coerceIn(0, 60)).apply()
    /**
     * There is only one way to use the app: always record, always try to wake at light sleep inside the window,
     * and always wake at the deadline. The only switch is "never wake early" (collect data only), off by default.
     */
    var neverWakeEarly: Boolean
        get() = p.getBoolean("never_wake_early", false)
        set(v) = p.edit().putBoolean("never_wake_early", v).apply()
    val mode: Mode get() = if (neverWakeEarly) Mode.MONITOR_ONLY else Mode.SMART_WAKE
    var deviceAddress: String
        get() = p.getString("device_address", "") ?: ""
        set(v) = p.edit().putString("device_address", v).apply()

    // ---- behavior ----
    var fetchIntervalMin: Int
        get() = p.getInt("fetch_interval_min", 5).coerceAtLeast(1)
        set(v) = p.edit().putInt("fetch_interval_min", v.coerceAtLeast(1)).apply()
    var monitorWakeAt: MonitorWakeAt
        get() = if (p.getString("monitor_wake_at", "target") == "deadline") MonitorWakeAt.DEADLINE else MonitorWakeAt.TARGET
        set(v) = p.edit().putString("monitor_wake_at", v.name.lowercase()).apply()
    /** how the watch is made to vibrate in the morning: a (silent text) notification, which only vibrates, or find-watch (rings) */
    var wakeMethod: String
        get() = p.getString("wake_method", "notification") ?: "notification"
        set(v) = p.edit().putString("wake_method", v).apply()
    var wakeRepeatSec: Int
        get() = p.getInt("wake_repeat_sec", 15).coerceIn(5, 120)
        set(v) = p.edit().putInt("wake_repeat_sec", v.coerceIn(5, 120)).apply()

    /** during a night the watch ignores phone notifications (Telegram ...); restored when the night ends */
    var silenceNotifications: Boolean
        get() = p.getBoolean("silence_notifications", true)
        set(v) = p.edit().putBoolean("silence_notifications", v).apply()

    /** record the phone's own movement (put the phone on the mattress): the only live movement signal this watch cannot give */
    var phoneMotion: Boolean
        get() = p.getBoolean("phone_motion", true)
        set(v) = p.edit().putBoolean("phone_motion", v).apply()

    var fallback: Fallback
        get() = Fallback.fromId(p.getString("fallback", null))
        set(v) = p.edit().putString("fallback", v.id).apply()

    // ---- heuristic detector ----
    var heuristic: HeuristicParams
        get() {
            val d = HeuristicParams()
            return HeuristicParams(
                recentMinutes = p.getInt("h_recent_min", d.recentMinutes).coerceAtLeast(1),
                hrDeltaBpm = p.getInt("h_hr_delta", d.hrDeltaBpm),
                basePercentile = p.getInt("h_base_pct", d.basePercentile).coerceIn(1, 99),
                onsetQuietMinutes = p.getInt("h_onset_quiet_min", d.onsetQuietMinutes).coerceAtLeast(1),
                maxStaleMinutes = p.getInt("h_max_stale_min", d.maxStaleMinutes).coerceAtLeast(1),
            )
        }
        set(v) = p.edit().putInt("h_recent_min", v.recentMinutes).putInt("h_hr_delta", v.hrDeltaBpm)
            .putInt("h_base_pct", v.basePercentile).putInt("h_onset_quiet_min", v.onsetQuietMinutes)
            .putInt("h_max_stale_min", v.maxStaleMinutes).apply()

    // ---- detector config downloaded from the server ----
    var detectorConfigJson: String
        get() = p.getString("detector_config_json", "") ?: ""
        set(v) = p.edit().putString("detector_config_json", v).apply()
    var detectorConfigVersion: Int
        get() = p.getInt("detector_config_version", 0)
        set(v) = p.edit().putInt("detector_config_version", v).apply()

    // ---- sync ----
    var syncEnabled: Boolean
        get() = p.getBoolean("sync_enabled", false)
        set(v) = p.edit().putBoolean("sync_enabled", v).apply()
    var baseUrl: String
        get() = p.getString("sync_base_url", "") ?: ""
        set(v) = p.edit().putString("sync_base_url", v.trim().trimEnd('/')).apply()
    var syncIntervalMin: Int
        get() = p.getInt("sync_interval_min", 15).coerceAtLeast(15) // WorkManager minimum
        set(v) = p.edit().putInt("sync_interval_min", v.coerceAtLeast(15)).apply()
    var wifiOnly: Boolean
        get() = p.getBoolean("sync_wifi_only", false)
        set(v) = p.edit().putBoolean("sync_wifi_only", v).apply()
    var lastSyncTs: Long
        get() = p.getLong("sync_last_ts", 0)
        set(v) = p.edit().putLong("sync_last_ts", v).apply()
    var lastSyncStatus: String
        get() = p.getString("sync_last_status", "never") ?: "never"
        set(v) = p.edit().putString("sync_last_status", v).apply()

    /** Highest sample ts (epoch s) already uploaded. A trailing lookback is re-sent every time. */
    var uploadedSampleTs: Long
        get() = p.getLong("sync_sample_ts", 0)
        set(v) = p.edit().putLong("sync_sample_ts", v).apply()

    var apiToken: String
        get() = secure().getString("api_token", "") ?: ""
        set(v) = secure().edit().putString("api_token", v.trim()).apply()

    private fun secure(): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "sleepwake_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        const val SAMPLE_LOOKBACK_SECONDS = 16 * 3600L // the watch's sleep stages arrive hours after the night: re-send the whole night
    }
}
