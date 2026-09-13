package rs.pametnakupovina.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.location.hasLocationPermission
import rs.pametnakupovina.app.ui.LocationViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.BottomActionBar
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.StatusTone

/**
 * One large action for the usual case. Typing coordinates is a fallback for a
 * phone without location, so it waits behind a fold instead of taking half
 * the screen.
 */
@Composable
fun LocationScreen(
    onBack: () -> Unit,
    onCalculate: (Double, Double) -> Unit,
    viewModel: LocationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var latitudeText by rememberSaveable { mutableStateOf("") }
    var longitudeText by rememberSaveable { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }

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
    val manualVisible = showManual ||
        (!coordinatesValid && (latitudeText.isNotBlank() || longitudeText.isNotBlank()))

    Scaffold(
        topBar = { AppTopBar(title = "Odakle krećeš", onBack = onBack) },
        bottomBar = {
            BottomActionBar {
                if (coordinatesValid) {
                    Text(
                        "Polazna tačka: " +
                            coordinatesLabel(requireNotNull(latitude), requireNotNull(longitude)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PrimaryActionButton(
                    text = "Prikaži preporuke",
                    enabled = coordinatesValid && !state.isResolving,
                    onClick = {
                        onCalculate(requireNotNull(latitude), requireNotNull(longitude))
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Text(
                "Put računamo od polazne tačke do prodavnica u blizini.",
                style = MaterialTheme.typography.bodyLarge
            )

            FilledTonalButton(
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
            ) {
                if (state.isResolving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(AppSpacing.md))
                    Text("Tražim lokaciju…", style = MaterialTheme.typography.titleMedium)
                } else {
                    AppIcon(R.drawable.ic_my_location, contentDescription = null)
                    Spacer(Modifier.width(AppSpacing.md))
                    Text("Koristi trenutnu lokaciju", style = MaterialTheme.typography.titleMedium)
                }
            }

            state.message?.let { message ->
                NoticeBanner(
                    text = message,
                    tone = if (state.isError) StatusTone.ERROR else StatusTone.POSITIVE
                )
            }

            if (state.isError) {
                Column {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("Otvori podešavanja lokacije") }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    }) { Text("Otvori dozvole aplikacije") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                AppIcon(
                    R.drawable.ic_info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(20.dp)
                )
                Text(
                    "Lokacija služi samo za ovo računanje. Aplikacija je ne prati u " +
                        "pozadini, a server je ne čuva.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TextButton(onClick = { showManual = !manualVisible }) {
                Text("Unesi koordinate ručno")
                AppIcon(
                    if (manualVisible) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    contentDescription = null
                )
            }

            if (manualVisible) {
                OutlinedTextField(
                    value = latitudeText,
                    onValueChange = { latitudeText = it },
                    label = { Text("Geografska širina") },
                    placeholder = { Text("npr. 44.8170") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = latitudeText.isNotBlank() &&
                        (latitude == null || latitude !in -90.0..90.0),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = longitudeText,
                    onValueChange = { longitudeText = it },
                    label = { Text("Geografska dužina") },
                    placeholder = { Text("npr. 20.4930") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = longitudeText.isNotBlank() &&
                        (longitude == null || longitude !in -180.0..180.0),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Text(
                        "Koordinate dobijaš u Google mapama kad dugo pritisneš tačku na mapi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

internal fun coordinatesAreValid(
    latitude: Double?,
    longitude: Double?
): Boolean = latitude != null && latitude in -90.0..90.0 &&
    longitude != null && longitude in -180.0..180.0

/** Keeps the dot Google Maps uses, because a comma already separates the pair. */
internal fun coordinatesLabel(latitude: Double, longitude: Double): String =
    String.format(java.util.Locale.ROOT, "%.4f, %.4f", latitude, longitude)
