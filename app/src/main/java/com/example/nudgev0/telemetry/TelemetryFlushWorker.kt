package com.example.nudgev0.telemetry

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Flushes the offline telemetry queue when the network comes back. Enqueued (with
 * a CONNECTED constraint) whenever an in-app flush fails offline, so queued events
 * are delivered even if the user doesn't reopen the app. Reuses the app's existing
 * WorkManager dependency — no new background infrastructure.
 */
class TelemetryFlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Telemetry.init(applicationContext)
        return if (Telemetry.flushNow()) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "telemetry_flush"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TelemetryFlushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.KEEP, request
            )
        }
    }
}
