package rs.pametnakupovina.app.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import rs.pametnakupovina.app.location.Coordinates

private fun Coordinates.queryValue(): String = "$latitude,$longitude"

fun googleMapsDirectionsUrl(
    origin: Coordinates?,
    orderedStops: List<Coordinates>
): String {
    val stops = orderedStops.distinct()
    require(stops.isNotEmpty()) { "Ruta mora imati bar jednu prodavnicu." }

    val destination = stops.last()
    val waypoints = stops.dropLast(1)

    return buildString {
        append("https://www.google.com/maps/dir/?api=1")
        origin?.let { append("&origin="); append(it.queryValue()) }
        append("&destination=")
        append(destination.queryValue())
        if (waypoints.isNotEmpty()) {
            append("&waypoints=")
            append(waypoints.joinToString("%7C") { it.queryValue() })
        }
        append("&travelmode=driving")
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
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "Instaliraj Google Maps ili pregledač da otvoriš rutu.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
