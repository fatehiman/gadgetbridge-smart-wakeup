package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings

/**
 * Keeps the watch quiet during a night. Gadgetbridge forwards phone notifications (Telegram ...) to the watch, which
 * then vibrates. The device preference `send_app_notifications` switches that forwarding off for the device: the driver
 * ignores every app notification while it is off. The original value is saved and restored when the night ends.
 * The phone itself is not affected, and phone calls are not touched.
 */
object NotificationGate {
    private const val KEY = "send_app_notifications" // DeviceSettingsPreferenceConst.PREF_SEND_APP_NOTIFICATIONS
    private const val STATE = "sleepwake_state"
    private const val SAVED = "notif_saved" // "1" / "0" while quiet, absent otherwise

    private fun devPrefs(device: GBDevice) = GBApplication.getDevicePrefs(device).preferences

    /** Night starts: stop forwarding. Returns true if it was changed. */
    fun quiet(ctx: Context, device: GBDevice?): Boolean {
        if (device == null || !SwSettings(ctx).silenceNotifications) return false
        val state = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        if (state.contains(SAVED)) return true // already quiet (restart of the service)
        val prefs = devPrefs(device)
        state.edit().putString(SAVED, if (prefs.getBoolean(KEY, true)) "1" else "0").putString("notif_device", device.address).apply()
        prefs.edit().putBoolean(KEY, false).apply()
        return true
    }

    /** The wake alert is itself a notification, so forwarding must be on from the wake until the night ends. */
    fun allowForWake(ctx: Context, device: GBDevice?) {
        if (device == null) return
        devPrefs(device).edit().putBoolean(KEY, true).apply()
    }

    /** Night ends (or the app starts and no night is running): put the original value back. */
    fun restore(ctx: Context) {
        val state = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val saved = state.getString(SAVED, null) ?: return
        val device = state.getString("notif_device", null)?.let { DeviceAccess.byAddress(it) }
        if (device != null) devPrefs(device).edit().putBoolean(KEY, saved == "1").apply()
        state.edit().remove(SAVED).remove("notif_device").apply()
    }

    fun isQuiet(ctx: Context) = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE).contains(SAVED)
}
