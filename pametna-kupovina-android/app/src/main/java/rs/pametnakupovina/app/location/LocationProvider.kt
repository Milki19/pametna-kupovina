package rs.pametnakupovina.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
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

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(manager)) {
            throw LocationUnavailableException(uiText(R.string.location_services_off))
        }

        val priority = if (hasFineLocationPermission(context)) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val location = resolveLocationWithFallback(
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
                    isRecentLocation(location.elapsedRealtimeNanos, android.os.SystemClock.elapsedRealtimeNanos())
            },
            isAcceptableFallback = { location ->
                hasUsableCoordinates(location) &&
                    isUsableLocation(
                        location.elapsedRealtimeNanos,
                        android.os.SystemClock.elapsedRealtimeNanos(),
                        FALLBACK_MAX_AGE_MILLIS * 1_000_000L
                    )
            }
        ) ?: throw LocationUnavailableException(
                uiText(R.string.location_not_found_enable_services)
            )

        return Coordinates(location.latitude, location.longitude)
    }

    /**
     * Za redosled pretrage: poslednja poznata lokacija, zaokružena na oko
     * kilometar — bez paljenja GPS-a i bez pitanja za dozvolu. Null kad
     * dozvole ili lokacije nema, i pretraga tada radi kao i pre.
     */
    suspend fun roughLocation(): Coordinates? {
        if (!hasLocationPermission(context)) return null
        val location = runCatching { withTimeoutOrNull(1_000L) { awaitLastLocation() } }
            .getOrNull() ?: return null
        return Coordinates(roughly(location.latitude), roughly(location.longitude))
    }

    /**
     * Kao [roughLocation], ali kad telefon još nema poslednju lokaciju
     * (posle instalacije ili restarta), traži je od mreže, najviše nekoliko
     * sekundi. Za listu akcija u blizini, gde bez lokacije stiže cela zemlja.
     */
    suspend fun nearbyLocation(): Coordinates? {
        roughLocation()?.let { return it }
        if (!hasLocationPermission(context)) return null
        val location = runCatching {
            withTimeoutOrNull(NEARBY_TIMEOUT_MILLIS + 1_000L) {
                awaitCurrentLocation(
                    locationRequest(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        NEARBY_TIMEOUT_MILLIS,
                        FALLBACK_MAX_AGE_MILLIS
                    )
                )
            }
        }.getOrNull() ?: return null
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
        Coordinates(found.latitude, found.longitude) to toLatin(found.getAddressLine(0) ?: query)
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

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MAX_LOCATION_AGE_MILLIS = 30_000L
        const val FALLBACK_TIMEOUT_MILLIS = 5_000L
        const val FALLBACK_MAX_AGE_MILLIS = 300_000L
        const val NEARBY_TIMEOUT_MILLIS = 5_000L
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
