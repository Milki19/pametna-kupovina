package rs.pametnakupovina.app.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import rs.pametnakupovina.app.data.ShoppingRepository

@HiltWorker
class ShoppingSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val repository: ShoppingRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result = try {
        repository.synchronizePending()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<ShoppingSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "shopping-draft-sync"
    }
}
