package rs.pametnakupovina.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

@Composable
fun OptimizationMapCard(
    latitude: Double,
    longitude: Double,
    locations: List<RetailerLocationDto>
) {
    val userPosition = LatLng(
        latitude,
        longitude
    )

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            userPosition,
            10f
        )
    }

    var isMapLoaded by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(
        isMapLoaded,
        locations,
        latitude,
        longitude
    ) {
        if (isMapLoaded && locations.isNotEmpty()) {
            val boundsBuilder = LatLngBounds.Builder()

            boundsBuilder.include(userPosition)

            locations.forEach { location ->
                boundsBuilder.include(
                    LatLng(
                        location.latitude,
                        location.longitude
                    )
                )
            }

            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngBounds(
                    boundsBuilder.build(),
                    100
                ),
                durationMs = 800
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Prodavnice na mapi",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (locations.isEmpty()) {
                        "Nema pronađenih lokacija prodavnica."
                    } else {
                        "Pronađeno lokacija: ${locations.size}"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            GoogleMap(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = true,
                    mapToolbarEnabled = false
                ),
                onMapLoaded = {
                    isMapLoaded = true
                }
            ) {
                Marker(
                    state = rememberUpdatedMarkerState(
                        position = userPosition
                    ),
                    title = "Vaša test lokacija",
                    snippet = "Beograd",
                    icon = BitmapDescriptorFactory.defaultMarker(
                        BitmapDescriptorFactory.HUE_AZURE
                    )
                )

                locations.forEach { location ->
                    key(location.id) {
                        Marker(
                            state = rememberUpdatedMarkerState(
                                position = LatLng(
                                    location.latitude,
                                    location.longitude
                                )
                            ),
                            title = location.locationName,
                            snippet = buildLocationDescription(location)
                        )
                    }
                }
            }
        }
    }
}

private fun buildLocationDescription(
    location: RetailerLocationDto
): String {
    val parts = mutableListOf<String>()

    parts += location.retailerName

    if (!location.city.isNullOrBlank()) {
        parts += location.city
    }

    parts += "${location.distanceKm} km"

    return parts.joinToString(" • ")
}