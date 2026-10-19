package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.Fallback
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings

/**
 * Makes the watch vibrate (find-watch) and, when the watch is not available, the phone.
 * The CMF driver cannot stop find-watch from the phone, and the watch decides how long it vibrates,
 * so the actuator is called again and again (every REPEAT_MS) until the user acknowledges.
 */
object WakeActuator {
    const val REPEAT_MS = 30_000L

    /** After this many unacknowledged watch attempts the phone alerts too (escalation). */
    const val ESCALATE_AFTER = 6

    @Volatile private var ringtone: android.media.Ringtone? = null

    data class Attempt(val watchSent: Boolean, val phoneAlert: Boolean)

    /** One attempt. Returns what was done, for the event log. */
    fun attempt(ctx: Context, address: String, attemptNo: Int): Attempt {
        val settings = SwSettings(ctx)
        val device = if (address.isNotEmpty()) DeviceAccess.byAddress(address) else DeviceAccess.device(ctx)
        val ready = DeviceAccess.isReady(device)
        var watchSent = false
        if (device != null) {
            if (ready) {
                try {
                    DeviceAccess.findWatch(device, true)
                    watchSent = true
                } catch (e: Exception) {
                    android.util.Log.w("SleepWake", "find watch failed", e)
                }
            } else {
                runCatching { DeviceAccess.connect(device) } // try to bring it back for the next attempt
            }
        }
        val phone = !watchSent || attemptNo >= ESCALATE_AFTER
        if (phone) startPhoneAlert(ctx, settings.fallback)
        return Attempt(watchSent, phone)
    }

    fun startPhoneAlert(ctx: Context, fallback: Fallback) {
        vibratePhone(ctx)
        if (fallback == Fallback.ALARM_SOUND && ringtone?.isPlaying != true) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ringtone = RingtoneManager.getRingtone(ctx.applicationContext, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    isLooping = true
                    play()
                }
            }
        }
    }

    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private fun vibratePhone(ctx: Context) {
        runCatching {
            val pattern = longArrayOf(0, 800, 400, 800, 400, 800, 400, 800)
            vibrator(ctx).vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    fun stopAll(ctx: Context, address: String) {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator(ctx).cancel() }
        val device = if (address.isNotEmpty()) DeviceAccess.byAddress(address) else DeviceAccess.device(ctx)
        // no-op on the CMF driver, but other drivers can stop it
        if (device != null && DeviceAccess.isReady(device)) runCatching { DeviceAccess.findWatch(device, false) }
    }
}
