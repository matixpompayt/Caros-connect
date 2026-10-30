package pl.mati.carosconnect

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Co ~15 min w tle: dane z CAR OS, pogoda przy aucie, przypomnienia, widżet. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try { Sync.run(ctx, ask = false) } catch (_: Exception) {}
        try { Sync.checkService(ctx) } catch (_: Exception) {}
        try { Sync.checkFrost(ctx) } catch (_: Exception) {}
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("caros-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
