package rs.pametnakupovina.app.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.location.hasLocationPermission
import rs.pametnakupovina.app.ui.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    onBack: () -> Unit,
    onCalculate: (Double, Double) -> Unit,
    viewModel: LocationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var latitudeText by remember { mutableStateOf("") }
    var longitudeText by remember { mutableStateOf("") }

    LaunchedEffect(state.coordinates) {
        state.coordinates?.let { coordinates ->
            latitudeText = coordinates.latitude.toString()
            longitudeText = coordinates.longitude.toString()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.resolveCurrentLocation()
        } else {
            viewModel.permissionDenied()
        }
    }

    val latitude = latitudeText.replace(',', '.').toDoubleOrNull()
    val longitude = longitudeText.replace(',', '.').toDoubleOrNull()
    val coordinatesValid = coordinatesAreValid(latitude, longitude)

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
                .verticalScroll(rememberScrollState())
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
                enabled = !state.isResolving,
                onClick = {
                    if (hasLocationPermission(context)) {
                        viewModel.resolveCurrentLocation()
                    } else {
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
                if (state.isResolving) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Koristi trenutnu lokaciju")
                }
            }

            if (state.isError) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) { Text("Podešavanja lokacije") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)))
                }) { Text("Dozvole aplikacije") }
            }

            Text("Ručni unos", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = latitudeText,
                onValueChange = { latitudeText = it },
                label = { Text("Latitude, npr. 44.2740") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                isError = latitudeText.isNotBlank() &&
                    (latitude == null || latitude !in -90.0..90.0),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = longitudeText,
                onValueChange = { longitudeText = it },
                label = { Text("Longitude, npr. 19.8800") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                isError = longitudeText.isNotBlank() &&
                    (longitude == null || longitude !in -180.0..180.0),
                modifier = Modifier.fillMaxWidth()
            )

            state.message?.let { message ->
                Text(
                    message,
                    color = if (state.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            Button(
                enabled = coordinatesValid && !state.isResolving,
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

internal fun coordinatesAreValid(
    latitude: Double?,
    longitude: Double?
): Boolean = latitude != null && latitude in -90.0..90.0 &&
    longitude != null && longitude in -180.0..180.0
