package rs.pametnakupovina.app.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

data class NavigationPoint(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude je van opsega."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude je van opsega."
        }
    }

    internal fun queryValue(): String = "$latitude,$longitude"
}

fun googleMapsDirectionsUrl(
    origin: NavigationPoint,
    orderedStops: List<NavigationPoint>
): String {
    val stops = orderedStops.distinct()
    require(stops.isNotEmpty()) { "Ruta mora imati bar jednu prodavnicu." }

    val destination = stops.last()
    val waypoints = stops.dropLast(1)

    return buildString {
        append("https://www.google.com/maps/dir/?api=1")
        append("&origin=")
        append(origin.queryValue())
        append("&destination=")
        append(destination.queryValue())
        if (waypoints.isNotEmpty()) {
            append("&waypoints=")
            append(waypoints.joinToString("%7C") { it.queryValue() })
        }
        append("&travelmode=driving")
        append("&dir_action=navigate")
    }
}

fun launchGoogleMapsDirections(context: Context, url: String) {
    val uri = Uri.parse(url)
    val googleMapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(GOOGLE_MAPS_PACKAGE)
    }

    try {
        context.startActivity(googleMapsIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
