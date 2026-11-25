package com.yebente.wallet.sync

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
import com.yebente.wallet.YebenteWalletApp
import java.util.concurrent.TimeUnit

class RebalanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val container = (applicationContext as YebenteWalletApp).container
    if (!container.settings.current().hasNode()) {
      return Result.success()
    }
    return try {
      val report = container.settlementEngine.settleAll(container.repository)
      container.repository.pruneExpired()
      if (report.retryLater) Result.retry() else Result.success()
    } catch (_: Exception) {
      Result.retry()
    }
  }

  companion object {
    const val UNIQUE_PERIODIC = "yebente-rebalance"
    const val UNIQUE_NOW = "yebente-rebalance-now"

    private fun constraints(): Constraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
      val request = PeriodicWorkRequestBuilder<RebalanceWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints())
        .build()
      WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
        UNIQUE_PERIODIC,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }

    fun rebalanceNow(context: Context) {
      val request = OneTimeWorkRequestBuilder<RebalanceWorker>()
        .setConstraints(constraints())
        .build()
      WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        UNIQUE_NOW,
        ExistingWorkPolicy.REPLACE,
        request,
      )
    }
  }
}
