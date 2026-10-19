package nodomain.freeyourgadget.gadgetbridge.sleepwake.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings
import java.util.concurrent.TimeUnit

/** WorkManager worker: network constraint, exponential backoff when the server is not reachable. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val settings = SwSettings(applicationContext)
        if (!settings.syncEnabled) return Result.success()
        val r = ServerSync(applicationContext).run()
        if (r.ok) return Result.success()
        // configuration errors do not get better by retrying; network/server errors do
        val retry = r.message.startsWith("network") || r.message.startsWith("HTTP 5") || r.message.startsWith("HTTP 429")
        return if (retry) Result.retry() else Result.failure()
    }
}

object SyncScheduler {
    private const val PERIODIC = "sleepwake_sync_periodic"
    private const val ONCE = "sleepwake_sync_once"

    private fun constraints(ctx: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (SwSettings(ctx).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** (Re)applies the settings: periodic upload while enabled, nothing when disabled. */
    fun apply(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val s = SwSettings(ctx)
        if (!s.syncEnabled) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(s.syncIntervalMin.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints(ctx)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** One-shot upload (after a session ends, or "Sync now"). */
    fun enqueueNow(ctx: Context, delaySeconds: Long = 0) {
        if (!SwSettings(ctx).syncEnabled) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(constraints(ctx)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }
}
