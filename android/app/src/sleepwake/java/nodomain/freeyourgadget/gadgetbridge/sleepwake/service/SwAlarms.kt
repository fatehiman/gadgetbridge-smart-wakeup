package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import nodomain.freeyourgadget.gadgetbridge.sleepwake.ui.NightActivity

/**
 * All alarms of the module. They are delivered to [SwAlarmReceiver], which does not depend on the
 * service being alive. The hard-deadline alarm uses setAlarmClock (the most Doze-proof kind).
 */
object SwAlarms {
    const val ACTION_FIRE = "nodomain.freeyourgadget.gadgetbridge.sleepwake.FIRE"
    const val ACTION_TICK = "nodomain.freeyourgadget.gadgetbridge.sleepwake.TICK"
    const val ACTION_REPEAT = "nodomain.freeyourgadget.gadgetbridge.sleepwake.REPEAT"
    const val ACTION_ACK = "nodomain.freeyourgadget.gadgetbridge.sleepwake.ACK"

    private const val RC_FIRE = 9101
    private const val RC_TICK = 9102
    private const val RC_REPEAT = 9103

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(ctx: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, rc, Intent(ctx, SwAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun canScheduleExact(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    /** Hard deadline: fires even if the service was killed or the watch is disconnected. */
    fun scheduleFire(ctx: Context, atMs: Long) {
        val show = PendingIntent.getActivity(
            ctx, RC_FIRE, Intent(ctx, NightActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), pi(ctx, ACTION_FIRE, RC_FIRE))
    }

    fun scheduleTick(ctx: Context, atMs: Long) = exact(ctx, atMs, pi(ctx, ACTION_TICK, RC_TICK))

    fun scheduleRepeat(ctx: Context, delayMs: Long) = exact(ctx, System.currentTimeMillis() + delayMs, pi(ctx, ACTION_REPEAT, RC_REPEAT))

    private fun exact(ctx: Context, atMs: Long, pi: PendingIntent) {
        val a = am(ctx)
        if (canScheduleExact(ctx)) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    fun cancelRepeat(ctx: Context) = am(ctx).cancel(pi(ctx, ACTION_REPEAT, RC_REPEAT))

    fun cancelAll(ctx: Context) {
        am(ctx).cancel(pi(ctx, ACTION_FIRE, RC_FIRE))
        am(ctx).cancel(pi(ctx, ACTION_TICK, RC_TICK))
        am(ctx).cancel(pi(ctx, ACTION_REPEAT, RC_REPEAT))
    }

    fun ackIntent(ctx: Context): PendingIntent = pi(ctx, ACTION_ACK, 9104)
}
