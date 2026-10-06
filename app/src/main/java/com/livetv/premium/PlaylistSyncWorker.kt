package com.livetv.premium

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PlaylistSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            PlaylistRepository.load(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
