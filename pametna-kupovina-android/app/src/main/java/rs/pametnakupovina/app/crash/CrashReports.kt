package rs.pametnakupovina.app.crash

import android.content.Context
import android.os.Build
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
import java.io.File
import kotlinx.coroutines.CancellationException
import rs.pametnakupovina.app.BuildConfig
import rs.pametnakupovina.app.data.network.CrashReportDto
import rs.pametnakupovina.app.data.network.ShoppingApiService

private const val CRASH_FILE = "last-crash.txt"

/**
 * Pad na tuđem telefonu je inače nevidljiv. Zapis greške se upiše na disk
 * dok aplikacija pada (tada nema vremena za mrežu), a šalje se pri sledećem
 * pokretanju — bez broja uređaja, samo greška, verzija i model telefona.
 */
fun Context.rememberCrashes() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { File(filesDir, CRASH_FILE).writeText(error.stackTraceToString().take(20_000)) }
        previous?.uncaughtException(thread, error)
    }

    if (File(filesDir, CRASH_FILE).exists()) {
        WorkManager.getInstance(this).enqueueUniqueWork(
            "crash-report",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CrashReportWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        )
    }
}

@HiltWorker
class CrashReportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val api: ShoppingApiService
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val file = File(applicationContext.filesDir, CRASH_FILE)
        val trace = file.takeIf(File::exists)?.readText() ?: return Result.success()
        return try {
            api.reportCrash(
                CrashReportDto(
                    appVersion = BuildConfig.VERSION_NAME,
                    androidVersion = Build.VERSION.RELEASE,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    stackTrace = trace
                )
            )
            file.delete()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
