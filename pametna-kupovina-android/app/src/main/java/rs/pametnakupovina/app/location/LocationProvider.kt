package rs.pametnakupovina.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.uiText

data class Coordinates(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude je van opsega." }
        require(longitude in -180.0..180.0) { "Longitude je van opsega." }
    }
}

class LocationUnavailableException(text: UiText) : UserFacingException(text)

private val CyrillicToLatin = "абвгдђежзијклљмнњопрстћуфхцчџш".toList().zip(
    listOf(
        "a", "b", "v", "g", "d", "đ", "e", "ž", "z", "i", "j", "k", "l", "lj", "m",
        "n", "nj", "o", "p", "r", "s", "t", "ć", "u", "f", "h", "c", "č", "dž", "š"
    )
).toMap()

/** Geocoder vraća srpske adrese ćirilicom i kad se traži latinica. */
internal fun toLatin(text: String): String = buildString {
    text.forEach { letter ->
        val latin = CyrillicToLatin[letter.lowercaseChar()]
        when {
            latin == null -> append(letter)
            letter.isUpperCase() -> append(latin.replaceFirstChar(Char::uppercase))
            else -> append(latin)
        }
    }
}

/**
 * Ulica sa brojem i mesto, bez poštanskog broja i države; bez ulice prvi deo
 * pune adrese (često naziv kraja ili objekta).
 */
internal fun shortAddress(street: String?, number: String?, place: String?, fullLine: String?): String? {
    val streetPart = street?.takeIf { it.isNotBlank() && !it.equals("Unnamed Road", ignoreCase = true) }
        ?.let { name -> number?.takeIf(String::isNotBlank)?.let { "$name $it" } ?: name }
    val first = streetPart ?: fullLine?.substringBefore(',')?.trim()?.takeIf {
        it.isNotBlank() && !it.equals("Unnamed Road", ignoreCase = true) && it.any(Char::isLetter) &&
            '+' !in it // Plus kod („MF8X+9V") nije adresa.
    }
    return listOfNotNull(first, place?.takeIf { it.isNotBlank() && it != first })
        .joinToString(", ")
        .ifBlank { null }
}

/**
 * Lokacija preko Google Play usluga, a bez njih (GrapheneOS, LineageOS, telefoni
 * bez Google-a) ili kad one ne vrate ništa, preko samog Android-a: GPS i mrežni
 * provajder iz LocationManager-a.
 */
@Singleton
class FusedLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: FusedLocationProviderClient
) {

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Coordinates {
        if (!hasLocationPermission(context)) {
            throw LocationUnavailableException(
                uiText(R.string.location_permission_missing)
            )
        }

        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            throw LocationUnavailableException(uiText(R.string.location_services_off))
        }

        val playServices = hasPlayServices()
        val location = (if (playServices) fusedCurrentLocation() else null)
            // Without Play services the fused client fails at once; with them sandboxed
            // (GrapheneOS) it can come back empty. The OS providers answer either way.
            ?: platformCurrentLocation(
                if (playServices) PLATFORM_FALLBACK_TIMEOUT_MILLIS else PLATFORM_TIMEOUT_MILLIS
            )
            ?: platformLastLocation(FALLBACK_MAX_AGE_MILLIS)
            ?: throw LocationUnavailableException(
                uiText(R.string.location_not_found_enable_services)
            )

        return Coordinates(location.latitude, location.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedCurrentLocation(): Location? {
        val priority = if (hasFineLocationPermission(context)) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        return resolveLocationWithFallback(
            freshLocation = {
                withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS + 1_000L) {
                    awaitCurrentLocation(
                        locationRequest(priority, REQUEST_TIMEOUT_MILLIS, MAX_LOCATION_AGE_MILLIS)
                    )
                }
            },
            // Indoors a satellite fix often does not arrive within ten seconds, and
            // right after an install there is nothing cached. The position from the
            // network in the last few minutes moves the plan by a street at most.
            lastLocation = {
                withTimeoutOrNull(FALLBACK_TIMEOUT_MILLIS + 1_000L) {
                    awaitCurrentLocation(
                        locationRequest(
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            FALLBACK_TIMEOUT_MILLIS,
                            FALLBACK_MAX_AGE_MILLIS
                        )
                    )
                } ?: withTimeoutOrNull(2_000L) { awaitLastLocation() }
            },
            isAcceptable = { location ->
                hasUsableCoordinates(location) &&
                    isRecentLocation(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
            },
            isAcceptableFallback = { location ->
                hasUsableCoordinates(location) &&
                    isUsableLocation(
                        location.elapsedRealtimeNanos,
                        SystemClock.elapsedRealtimeNanos(),
                        FALLBACK_MAX_AGE_MILLIS * 1_000_000L
                    )
            }
        )
    }

    /**
     * Za redosled pretrage: poslednja poznata lokacija, zaokružena na oko
     * kilometar — bez paljenja GPS-a i bez pitanja za dozvolu. Null kad
     * dozvole ili lokacije nema, i pretraga tada radi kao i pre.
     */
    suspend fun roughLocation(): Coordinates? {
        if (!hasLocationPermission(context)) return null
        val location = lastKnownLocation() ?: return null
        return Coordinates(roughly(location.latitude), roughly(location.longitude))
    }

    /**
     * Kao [roughLocation], ali poslednja lokacija važi samo dok je sveža (pola
     * sata): telefon koji je putovao ili emulator bi inače tražio akcije u
     * drugom gradu. Bez sveže traži novu od mreže, najviše nekoliko sekundi,
     * a bez nje vraća null, pa lista pokazuje celu zemlju.
     */
    suspend fun nearbyLocation(): Coordinates? {
        if (!hasLocationPermission(context)) return null
        val now = SystemClock.elapsedRealtimeNanos()
        val fresh = { location: Location ->
            hasUsableCoordinates(location) &&
                isUsableLocation(location.elapsedRealtimeNanos, now, NEARBY_MAX_AGE_MILLIS * 1_000_000L)
        }
        val playServices = hasPlayServices()
        val location = lastKnownLocation()?.takeIf(fresh)
            ?: (if (playServices) {
                runCatching {
                    withTimeoutOrNull(NEARBY_TIMEOUT_MILLIS + 1_000L) {
                        awaitCurrentLocation(
                            locationRequest(
                                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                NEARBY_TIMEOUT_MILLIS,
                                NEARBY_MAX_AGE_MILLIS
                            )
                        )
                    }
                }.getOrNull()
            } else {
                null
            })
            ?: platformCurrentLocation(NEARBY_TIMEOUT_MILLIS, preciseProviders = false)
            ?: return null
        return Coordinates(roughly(location.latitude), roughly(location.longitude))
    }

    private fun roughly(degrees: Double) = Math.round(degrees * 100) / 100.0

    /**
     * Kraj ili adresa („Karaburma", „Bulevar kralja Aleksandra 73") kao
     * polazna tačka, bez mape i bez API ključa: Android-ov Geocoder, samo
     * unutar Srbije, da „Karaburma" ne ode u drugu državu.
     */
    @Suppress("DEPRECATION") // Verzija sa listener-om postoji tek od API 33.
    suspend fun findAddress(query: String): Pair<Coordinates, String> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) {
            throw LocationUnavailableException(
                uiText(R.string.location_geocoder_missing)
            )
        }
        val geocoder = Geocoder(context, Locale.forLanguageTag("sr-Latn-RS"))
        val search = { geocoder.getFromLocationName(query, 1, 42.2, 18.8, 46.2, 23.1) }
        val found = runCatching(search)
            // Prvi poziv „na hladno" ume da vrati UNAVAILABLE, drugi prođe.
            .recoverCatching { search() }
            .getOrElse {
                throw LocationUnavailableException(
                    uiText(R.string.location_geocoder_unavailable)
                )
            }
            ?.firstOrNull()
            ?: throw LocationUnavailableException(
                uiText(R.string.location_address_not_found)
            )
        val label = shortAddress(
            street = found.thoroughfare,
            number = found.subThoroughfare,
            place = found.locality ?: found.subAdminArea,
            fullLine = found.getAddressLine(0)
        ) ?: query
        Coordinates(found.latitude, found.longitude) to toLatin(label)
    }

    /**
     * Adresa tačke za prikaz („Kneza Mihaila 10, Beograd") umesto brojeva.
     * Samo za oznaku: bez Geocoder-a, mreže ili pogotka vraća null, pa se
     * prikazuju koordinate.
     */
    @Suppress("DEPRECATION") // Verzija sa listener-om postoji tek od API 33.
    suspend fun addressOf(coordinates: Coordinates): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.forLanguageTag("sr-Latn-RS"))
        runCatching { geocoder.getFromLocation(coordinates.latitude, coordinates.longitude, 1) }
            .getOrNull()
            ?.firstOrNull()
            ?.let { found ->
                shortAddress(
                    street = found.thoroughfare,
                    number = found.subThoroughfare,
                    place = found.locality ?: found.subAdminArea,
                    fullLine = found.getAddressLine(0)
                )
            }
            ?.let(::toLatin)
    }

    private fun locationRequest(
        priority: Int,
        durationMillis: Long,
        maxUpdateAgeMillis: Long
    ): CurrentLocationRequest = CurrentLocationRequest.Builder()
        .setPriority(priority)
        .setDurationMillis(durationMillis)
        .setMaxUpdateAgeMillis(maxUpdateAgeMillis)
        .build()

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(
        request: CurrentLocationRequest
    ): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellation.cancel() }
        client.getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { location ->
                if (continuation.isActive) continuation.resume(location)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitLastLocation(): Location? =
        suspendCancellableCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
        }

    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun hasPlayServices(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** Poslednja poznata lokacija: od Play usluga ako ih ima, inače od Android-a. */
    private suspend fun lastKnownLocation(): Location? {
        if (hasPlayServices()) {
            runCatching { withTimeoutOrNull(1_000L) { awaitLastLocation() } }
                .getOrNull()
                ?.takeIf(::hasUsableCoordinates)
                ?.let { return it }
        }
        return platformLastLocation(Long.MAX_VALUE / 1_000_000L)
    }

    /** Najsvežija lokacija koju Android već ima, ako nije starija od [maxAgeMillis]. */
    @SuppressLint("MissingPermission")
    private fun platformLastLocation(maxAgeMillis: Long): Location? {
        val manager = locationManager
        val now = SystemClock.elapsedRealtimeNanos()
        return (platformProviders(manager, preciseProviders = true) + LocationManager.PASSIVE_PROVIDER)
            .distinct()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { location ->
                hasUsableCoordinates(location) &&
                    isUsableLocation(location.elapsedRealtimeNanos, now, maxAgeMillis * 1_000_000L)
            }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    /**
     * Nova lokacija od Android-a: GPS i mrežni provajder istovremeno, prva
     * koja stigne. Null kad nijedan ne odgovori za [timeoutMillis].
     */
    @SuppressLint("MissingPermission")
    private suspend fun platformCurrentLocation(
        timeoutMillis: Long,
        preciseProviders: Boolean = true
    ): Location? {
        val manager = locationManager
        val providers = platformProviders(manager, preciseProviders).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return null
        return withTimeoutOrNull(timeoutMillis) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val signals = providers.map { CancellationSignal() }
                    val finished = AtomicBoolean(false)
                    val remaining = AtomicInteger(providers.size)
                    val finish: (Location?) -> Unit = { location ->
                        if (finished.compareAndSet(false, true)) {
                            signals.forEach(CancellationSignal::cancel)
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }
                    val giveUpOne: () -> Unit = { if (remaining.decrementAndGet() == 0) finish(null) }
                    continuation.invokeOnCancellation { signals.forEach(CancellationSignal::cancel) }
                    providers.forEachIndexed { index, provider ->
                        runCatching {
                            LocationManagerCompat.getCurrentLocation(
                                manager,
                                provider,
                                signals[index],
                                ContextCompat.getMainExecutor(context)
                            ) { location ->
                                if (location != null && hasUsableCoordinates(location)) {
                                    finish(location)
                                } else {
                                    giveUpOne()
                                }
                            }
                        }.onFailure { giveUpOne() }
                    }
                }
            }
        }
    }

    /** GPS samo uz preciznu dozvolu i kad se traži tačna lokacija; mreža uvek. */
    private fun platformProviders(manager: LocationManager, preciseProviders: Boolean): List<String> =
        buildList {
            if (preciseProviders && hasFineLocationPermission(context)) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { provider ->
            runCatching { LocationManagerCompat.hasProvider(manager, provider) }.getOrDefault(false)
        }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MAX_LOCATION_AGE_MILLIS = 30_000L
        const val FALLBACK_TIMEOUT_MILLIS = 5_000L
        const val FALLBACK_MAX_AGE_MILLIS = 300_000L
        const val NEARBY_TIMEOUT_MILLIS = 5_000L
        const val NEARBY_MAX_AGE_MILLIS = 1_800_000L
        const val PLATFORM_TIMEOUT_MILLIS = 20_000L
        const val PLATFORM_FALLBACK_TIMEOUT_MILLIS = 10_000L
    }
}

fun hasLocationPermission(context: Context): Boolean =
    hasFineLocationPermission(context) ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

private fun hasFineLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

internal fun isRecentLocation(timestampNanos: Long, nowNanos: Long): Boolean =
    isUsableLocation(timestampNanos, nowNanos, 30_000_000_000L)

internal fun isUsableLocation(
    timestampNanos: Long,
    nowNanos: Long,
    maxAgeNanos: Long
): Boolean =
    timestampNanos > 0 && nowNanos >= timestampNanos &&
        nowNanos - timestampNanos <= maxAgeNanos

private fun hasUsableCoordinates(location: Location): Boolean =
    runCatching { Coordinates(location.latitude, location.longitude) }.isSuccess

internal suspend fun <T> resolveLocationWithFallback(
    freshLocation: suspend () -> T?,
    lastLocation: suspend () -> T?,
    isAcceptable: (T) -> Boolean = { true },
    isAcceptableFallback: (T) -> Boolean = isAcceptable
): T? {
    val fresh = try {
        freshLocation()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
    if (fresh != null && isAcceptable(fresh)) return fresh

    return try {
        lastLocation()?.takeIf(isAcceptableFallback)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
