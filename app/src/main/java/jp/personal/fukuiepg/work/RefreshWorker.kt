package jp.personal.fukuiepg.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.personal.fukuiepg.EpgApp
import java.util.concurrent.TimeUnit

/** 3時間ごとに番組表を取り直し、通知を予約し直す。 */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as EpgApp
        val r = app.repo.refresh()
        app.alarms.rescheduleAll()
        return if (r.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("epg-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork("epg-refresh-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
