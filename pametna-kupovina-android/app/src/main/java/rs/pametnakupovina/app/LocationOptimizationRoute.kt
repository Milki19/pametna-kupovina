package rs.pametnakupovina.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

private const val FALLBACK_LATITUDE = 44.7866
private const val FALLBACK_LONGITUDE = 20.4489

@Composable
fun LocationOptimizationRoute(
    listId: Long,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var latitude by rememberSaveable {
        mutableStateOf<Double?>(null)
    }

    var longitude by rememberSaveable {
        mutableStateOf<Double?>(null)
    }

    var isTestLocation by rememberSaveable {
        mutableStateOf(false)
    }

    var isResolvingLocation by rememberSaveable {
        mutableStateOf(true)
    }

    var locationError by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val resolveCurrentLocation: () -> Unit = {
        isResolvingLocation = true
        locationError = null

        requestCurrentLocation(
            context = context,
            onSuccess = { resolvedLatitude, resolvedLongitude ->
                latitude = resolvedLatitude
                longitude = resolvedLongitude
                isTestLocation = false
                isResolvingLocation = false
            },
            onError = { message ->
                locationError = message
                isResolvingLocation = false
            }
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val permissionGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (permissionGranted) {
                resolveCurrentLocation()
            } else {
                isResolvingLocation = false
                locationError =
                    "Dozvola za lokaciju nije odobrena."
            }
        }

    LaunchedEffect(Unit) {
        if (hasLocationPermission(context)) {
            resolveCurrentLocation()
        } else {
            isResolvingLocation = false

            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val resolvedLatitude = latitude
    val resolvedLongitude = longitude

    if (
        resolvedLatitude != null &&
        resolvedLongitude != null
    ) {
        LocationOptimizationScreen(
            listId = listId,
            latitude = resolvedLatitude,
            longitude = resolvedLongitude,
            isTestLocation = isTestLocation,
            onBack = onBack
        )

        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("← Nazad na listu")
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isResolvingLocation) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Određivanje trenutne lokacije...")
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Lokacija nije dostupna",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = locationError
                            ?: "Nije moguće odrediti lokaciju."
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (hasLocationPermission(context)) {
                                resolveCurrentLocation()
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Pokušaj ponovo")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            latitude = FALLBACK_LATITUDE
                            longitude = FALLBACK_LONGITUDE
                            isTestLocation = true
                        }
                    ) {
                        Text("Koristi test lokaciju")
                    }
                }
            }
        }
    }
}

private fun hasLocationPermission(
    context: Context
): Boolean {
    val fineLocationGranted =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val coarseLocationGranted =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    return fineLocationGranted || coarseLocationGranted
}

@SuppressLint("MissingPermission")
private fun requestCurrentLocation(
    context: Context,
    onSuccess: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    val locationClient =
        LocationServices.getFusedLocationProviderClient(context)

    val cancellationTokenSource =
        CancellationTokenSource()

    locationClient.getCurrentLocation(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        cancellationTokenSource.token
    )
        .addOnSuccessListener { location ->
            if (location != null) {
                onSuccess(
                    location.latitude,
                    location.longitude
                )
            } else {
                onError(
                    "Telefon trenutno nema dostupnu lokaciju. " +
                            "Proveri da li je lokacija uključena."
                )
            }
        }
        .addOnFailureListener { exception ->
            onError(
                exception.localizedMessage
                    ?: "Nije moguće dobiti trenutnu lokaciju."
            )
        }
}