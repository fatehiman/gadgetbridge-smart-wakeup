package nodomain.freeyourgadget.gadgetbridge.sleepwake.ui

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.MonitorWakeAt
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.Fallback
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings
import nodomain.freeyourgadget.gadgetbridge.sleepwake.sync.ServerSync
import nodomain.freeyourgadget.gadgetbridge.sleepwake.sync.SyncScheduler
import java.text.DateFormat
import java.util.Date

class SleepWakeSettingsActivity : AbstractGBActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SleepWake settings"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
        }
    }

    class Fragment : PreferenceFragmentCompat() {
        /** Maps preference keys to [SwSettings] (so ints, enums and the encrypted token need no special code in the UI). */
        private inner class Store(val s: SwSettings) : PreferenceDataStore() {
            override fun getString(key: String, defValue: String?): String? = when (key) {
                "fetch_interval_min" -> s.fetchIntervalMin.toString()
                "monitor_wake_at" -> s.monitorWakeAt.name.lowercase()
                "fallback" -> s.fallback.id
                "h_hr_delta" -> s.heuristic.hrDeltaBpm.toString()
                "h_recent_min" -> s.heuristic.recentMinutes.toString()
                "h_base_pct" -> s.heuristic.basePercentile.toString()
                "h_onset_quiet_min" -> s.heuristic.onsetQuietMinutes.toString()
                "sync_base_url" -> s.baseUrl
                "sync_interval_min" -> s.syncIntervalMin.toString()
                "api_token" -> if (s.apiToken.isEmpty()) "" else "••••••••"
                else -> defValue
            }

            override fun putString(key: String, value: String?) {
                val v = value.orEmpty()
                val n = v.trim().toIntOrNull()
                when (key) {
                    "fetch_interval_min" -> n?.let { s.fetchIntervalMin = it }
                    "monitor_wake_at" -> s.monitorWakeAt = if (v == "deadline") MonitorWakeAt.DEADLINE else MonitorWakeAt.TARGET
                    "fallback" -> s.fallback = Fallback.fromId(v)
                    "h_hr_delta" -> n?.let { s.heuristic = s.heuristic.copy(hrDeltaBpm = it) }
                    "h_recent_min" -> n?.let { s.heuristic = s.heuristic.copy(recentMinutes = it) }
                    "h_base_pct" -> n?.let { s.heuristic = s.heuristic.copy(basePercentile = it) }
                    "h_onset_quiet_min" -> n?.let { s.heuristic = s.heuristic.copy(onsetQuietMinutes = it) }
                    "sync_base_url" -> s.baseUrl = v
                    "sync_interval_min" -> n?.let { s.syncIntervalMin = it }
                    "api_token" -> if (v.isNotBlank() && !v.startsWith("•")) s.apiToken = v
                }
                if (key.startsWith("sync_") || key == "api_token") SyncScheduler.apply(requireContext())
                refreshSummaries()
            }

            override fun getBoolean(key: String, defValue: Boolean) = when (key) {
                "never_wake_early" -> s.neverWakeEarly
                "sync_enabled" -> s.syncEnabled
                "sync_wifi_only" -> s.wifiOnly
                else -> defValue
            }

            override fun putBoolean(key: String, value: Boolean) {
                when (key) {
                    "never_wake_early" -> s.neverWakeEarly = value
                    "sync_enabled" -> s.syncEnabled = value
                    "sync_wifi_only" -> s.wifiOnly = value
                }
                SyncScheduler.apply(requireContext())
                refreshSummaries()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val settings = SwSettings(requireContext())
            preferenceManager.preferenceDataStore = Store(settings)
            setPreferencesFromResource(R.xml.sleepwake_preferences, rootKey)

            findPreference<Preference>("sync_test")?.setOnPreferenceClickListener {
                Thread {
                    val r = ServerSync(requireContext()).ping()
                    activity?.runOnUiThread { Toast.makeText(context, (if (r.ok) "Connected. " else "Failed: ") + r.message, Toast.LENGTH_LONG).show() }
                }.start()
                true
            }
            findPreference<Preference>("sync_now")?.setOnPreferenceClickListener {
                Toast.makeText(context, "Syncing…", Toast.LENGTH_SHORT).show()
                Thread {
                    val r = ServerSync(requireContext()).run()
                    activity?.runOnUiThread { refreshSummaries(); Toast.makeText(context, r.message, Toast.LENGTH_LONG).show() }
                }.start()
                true
            }
            if (!BuildConfig.INTERNET_ACCESS) {
                findPreference<Preference>("cat_sync")?.isVisible = false
            }
            refreshSummaries()
        }

        private fun refreshSummaries() {
            val s = SwSettings(requireContext())
            findPreference<Preference>("detector_info")?.summary = if (s.detectorConfigVersion > 0)
                "version ${s.detectorConfigVersion}: ${s.detectorConfigJson.take(300)}" else "none yet (built-in heuristic is used)"
            findPreference<Preference>("sync_status")?.summary = if (s.lastSyncTs > 0)
                "${DateFormat.getDateTimeInstance().format(Date(s.lastSyncTs * 1000))}: ${s.lastSyncStatus}" else s.lastSyncStatus
        }
    }
}
