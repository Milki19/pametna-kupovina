package rs.pametnakupovina.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class Coordinates(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude je van opsega." }
        require(longitude in -180.0..180.0) { "Longitude je van opsega." }
    }
}

interface LocationProvider {
    suspend fun currentLocation(): Coordinates
}

class LocationUnavailableException(message: String) : Exception(message)

@Singleton
class FusedLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: FusedLocationProviderClient
) : LocationProvider {

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): Coordinates {
        if (!hasLocationPermission(context)) {
            throw LocationUnavailableException(
                "Dozvola za lokaciju nije odobrena."
            )
        }

        val priority = if (hasFineLocationPermission(context)) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setDurationMillis(REQUEST_TIMEOUT_MILLIS)
            .setMaxUpdateAgeMillis(MAX_LOCATION_AGE_MILLIS)
            .build()

        val location = resolveLocationWithFallback(
            freshLocation = {
                withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS + 1_000L) {
                    awaitCurrentLocation(request)
                }
            },
            lastLocation = ::awaitLastLocation
        ) ?: throw LocationUnavailableException(
                "Lokacija nije pronađena. Uključi lokacijske usluge " +
                    "ili unesi koordinate ručno."
            )

        return Coordinates(location.latitude, location.longitude)
    }

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

internal suspend fun <T> resolveLocationWithFallback(
    freshLocation: suspend () -> T?,
    lastLocation: suspend () -> T?
): T? {
    val fresh = try {
        freshLocation()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
    if (fresh != null) return fresh

    return try {
        lastLocation()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
