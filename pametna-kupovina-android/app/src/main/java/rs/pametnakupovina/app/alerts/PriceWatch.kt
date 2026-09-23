package rs.pametnakupovina.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rs.pametnakupovina.app.MainActivity
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.screens.isCaseOf

/*
 * Cenovni alarm bez Firebase-a: telefon sam, jednom dnevno, pita server za
 * cene proizvoda koje prati i javi kad neka padne. Server ne zna šta ko prati.
 * ponytail: kasni do ~24 h i zavisi od toga kad Android pusti posao; FCM push
 * sa servera kad bude trebalo odmah posle uvoza cena.
 */

@Serializable
data class WatchedProduct(
    val canonicalProductId: Long,
    val name: String,
    val price: Double
)

/** Najniža cena koju ekran proizvoda i sam ističe kao najpovoljniju. */
fun bestPrice(product: CanonicalProductDetailsDto) = product.offers
    .filter { !it.priceNeedsCheck && !isCaseOf(it, product) }
    .minByOrNull { it.effectivePrice }

private val Context.priceWatchDataStore by preferencesDataStore(name = "price_watch")
private val WATCHED = stringPreferencesKey("watched")
private const val WORK = "price-watch"

@Singleton
class PriceWatchStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val watched: Flow<List<WatchedProduct>> = context.priceWatchDataStore.data
        .map { preferences -> decode(preferences[WATCHED]) }

    suspend fun watch(product: WatchedProduct) {
        update { list -> list.filterNot { it.canonicalProductId == product.canonicalProductId } + product }
        schedule(context)
    }

    suspend fun unwatch(canonicalProductId: Long) {
        update { list -> list.filterNot { it.canonicalProductId == canonicalProductId } }
        // Ništa se ne prati: telefon ne treba da se budi svaki dan uzalud.
        if (watched.first().isEmpty()) WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    suspend fun update(change: (List<WatchedProduct>) -> List<WatchedProduct>) {
        context.priceWatchDataStore.edit { preferences ->
            preferences[WATCHED] = Json.encodeToString(change(decode(preferences[WATCHED])))
        }
    }

    private fun decode(stored: String?): List<WatchedProduct> =
        stored?.let { Json.decodeFromString<List<WatchedProduct>>(it) } ?: emptyList()

    private fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PriceWatchWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        )
    }
}

@HiltWorker
class PriceWatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val repository: ShoppingRepository,
    private val store: PriceWatchStore
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val latest = mutableMapOf<Long, Double>()
        store.watched.first().forEach { watched ->
            val offer = try {
                bestPrice(repository.getProductDetails(watched.canonicalProductId))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null // Sutra opet; jedan proizvod ne sme da zaustavi ostale.
            } ?: return@forEach
            if (offer.effectivePrice < watched.price - 0.005) {
                notifyDrop(applicationContext, watched, offer.effectivePrice, offer.retailerName)
            }
            latest[watched.canonicalProductId] = offer.effectivePrice
        }
        // Sledeći pad se meri od današnje cene, pa i poskupljenje pomera prag.
        store.update { list -> list.map { it.copy(price = latest[it.canonicalProductId] ?: it.price) } }
        return Result.success()
    }
}

private const val CHANNEL = "price-drops"

private fun notifyDrop(context: Context, watched: WatchedProduct, price: Double, retailer: String) {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) return

    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Pojeftinjenja", NotificationManager.IMPORTANCE_DEFAULT)
    )
    val open = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )
    NotificationManagerCompat.from(context).notify(
        watched.canonicalProductId.toInt(),
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_local_offer)
            .setContentTitle("Pojeftinilo: ${watched.name}")
            .setContentText("${money(watched.price)} → ${money(price)} ($retailer)")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    )
}
