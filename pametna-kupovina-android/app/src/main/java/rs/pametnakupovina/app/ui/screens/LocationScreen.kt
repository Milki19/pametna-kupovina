package rs.pametnakupovina.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.function.Consumer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    onBack: () -> Unit,
    onCalculate: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    var latitudeText by remember { mutableStateOf("") }
    var longitudeText by remember { mutableStateOf("") }
    var isResolving by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }

    val useResolvedLocation: (Double, Double) -> Unit = { latitude, longitude ->
        latitudeText = latitude.toString()
        longitudeText = longitude.toString()
        isResolving = false
        locationMessage = "Lokacija je pronađena. Proveri je i pokreni računanje."
    }

    val resolveLocation = remember(context) {
        {
            isResolving = true
            locationMessage = null
            requestCurrentLocation(
                context = context,
                onSuccess = useResolvedLocation,
                onError = { message ->
                    isResolving = false
                    locationMessage = message
                }
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            resolveLocation()
        } else {
            isResolving = false
            locationMessage =
                "Dozvola nije odobrena. Unesi latitude i longitude ručno."
        }
    }

    val latitude = latitudeText.replace(',', '.').toDoubleOrNull()
    val longitude = longitudeText.replace(',', '.').toDoubleOrNull()
    val coordinatesValid = latitude != null && latitude in -90.0..90.0 &&
        longitude != null && longitude in -180.0..180.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lokacija za računanje") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Nazad") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Zašto tražimo lokaciju?",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Koristi se samo za ovo računanje udaljenosti do " +
                            "prodavnica. Aplikacija ne traži pozadinsku " +
                            "lokaciju, a backend je ne čuva."
                    )
                }
            }

            OutlinedButton(
                enabled = !isResolving,
                onClick = {
                    if (hasLocationPermission(context)) {
                        resolveLocation()
                    } else {
                        isResolving = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isResolving) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Koristi trenutnu lokaciju")
                }
            }

            Text(
                "Ručni unos",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = latitudeText,
                onValueChange = { latitudeText = it },
                label = { Text("Latitude, npr. 44.7866") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = longitudeText,
                onValueChange = { longitudeText = it },
                label = { Text("Longitude, npr. 20.4489") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                modifier = Modifier.fillMaxWidth()
            )

            locationMessage?.let { message ->
                Text(
                    message,
                    color = if (coordinatesValid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Button(
                enabled = coordinatesValid && !isResolving,
                onClick = {
                    onCalculate(
                        requireNotNull(latitude),
                        requireNotNull(longitude)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Prikaži tri scenarija")
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun requestCurrentLocation(
    context: Context,
    onSuccess: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    if (!hasLocationPermission(context)) {
        onError("Dozvola za lokaciju nije odobrena.")
        return
    }

    val manager = context.getSystemService(LocationManager::class.java)
    val provider = when {
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
            LocationManager.NETWORK_PROVIDER
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
            LocationManager.GPS_PROVIDER
        else -> {
            onError("Lokacijske usluge nisu uključene. Unesi koordinate ručno.")
            return
        }
    }

    val handle: (Location?) -> Unit = { location ->
        if (location == null) {
            onError("Lokacija nije pronađena. Unesi koordinate ručno.")
        } else {
            onSuccess(location.latitude, location.longitude)
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.getCurrentLocation(
            provider,
            null,
            context.mainExecutor,
            Consumer(handle)
        )
    } else {
        @Suppress("DEPRECATION")
        manager.requestSingleUpdate(
            provider,
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    handle(location)
                }

                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?
                ) = Unit
            },
            Looper.getMainLooper()
        )
    }
}
