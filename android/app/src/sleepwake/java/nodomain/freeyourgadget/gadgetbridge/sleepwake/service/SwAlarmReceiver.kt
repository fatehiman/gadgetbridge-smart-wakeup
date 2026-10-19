package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarm entry point. Works without the service: it talks to [NightSessionManager], which only uses the DB. */
class SwAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            when (intent.action) {
                SwAlarms.ACTION_FIRE -> NightSessionManager.onFire(context)
                SwAlarms.ACTION_REPEAT -> NightSessionManager.repeat(context)
                SwAlarms.ACTION_ACK -> NightSessionManager.acknowledge(context)
                SwAlarms.ACTION_TICK -> NightSessionManager.startService(context, NightSessionService.ACTION_TICK)
            }
        } catch (e: Exception) {
            android.util.Log.e("SleepWake", "alarm failed: ${intent.action}", e)
        } finally {
            pending.finish()
        }
    }
}

/** Puts alarms and the service back after a reboot or an app update. */
class SwBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NightSessionManager.restore(context)
        }
    }
}
