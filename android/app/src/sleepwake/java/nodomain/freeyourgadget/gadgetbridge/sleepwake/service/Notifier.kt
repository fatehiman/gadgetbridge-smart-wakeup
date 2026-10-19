package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.sleepwake.ui.MorningRatingActivity
import nodomain.freeyourgadget.gadgetbridge.sleepwake.ui.NightActivity

object Notifier {
    private const val CH_SESSION = "sleepwake_session"
    private const val CH_WAKE = "sleepwake_wake_v2"
    const val ID_SESSION = 7101
    const val ID_WAKE = 7102
    const val ID_RATING = 7103

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(ctx: Context) {
        val n = nm(ctx)
        n.createNotificationChannel(NotificationChannel(CH_SESSION, "Night session", NotificationManager.IMPORTANCE_LOW))
        n.createNotificationChannel(
            NotificationChannel(CH_WAKE, "Wake up", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun openNight(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, NightActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openNightFromWake(ctx: Context) = PendingIntent.getActivity(
        ctx, 2, Intent(ctx, NightActivity::class.java).putExtra(NightActivity.EXTRA_FROM_WAKE, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Persistent notification of the running session: state, next action, last sync, connection. */
    fun session(ctx: Context, title: String, text: String): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_SESSION)
            .setSmallIcon(R.drawable.ic_bedtime_on)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openNight(ctx))
            .build()
    }

    fun updateSession(ctx: Context, title: String, text: String) = nm(ctx).notify(ID_SESSION, session(ctx, title, text))

    fun showWake(ctx: Context, text: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_WAKE)
            .setSmallIcon(R.drawable.ic_alarm_smart_wake)
            .setContentTitle("Time to wake up")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(openNightFromWake(ctx), true)
            .setContentIntent(openNightFromWake(ctx))
            // works from the lock screen without unlocking the phone
            .addAction(NotificationCompat.Action.Builder(0, "I'm awake", SwAlarms.ackIntent(ctx)).setAuthenticationRequired(false).build())
            .build()
        nm(ctx).notify(ID_WAKE, n)
    }

    fun cancelWake(ctx: Context) = nm(ctx).cancel(ID_WAKE)

    fun showRating(ctx: Context, sessionUuid: String) {
        ensureChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MorningRatingActivity::class.java).putExtra(MorningRatingActivity.EXTRA_SESSION, sessionUuid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm(ctx).notify(
            ID_RATING,
            NotificationCompat.Builder(ctx, CH_SESSION).setSmallIcon(R.drawable.ic_bedtime_on)
                .setContentTitle("Good morning").setContentText("How do you feel? Tap to rate (1-5).")
                .setAutoCancel(true).setContentIntent(pi).build(),
        )
    }

    fun cancelAll(ctx: Context) {
        nm(ctx).cancel(ID_SESSION)
        nm(ctx).cancel(ID_WAKE)
    }
}
