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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.location.hasLocationPermission
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.ui.LocationViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.GlassCard
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.BottomActionBar
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.components.StepCard

/**
 * One large action for the usual case, and an address or a neighbourhood for
 * a phone without location. The starting point always reads as a place,
 * never as coordinates.
 */
@Composable
fun LocationScreen(
    onBack: () -> Unit,
    onCalculate: (latitude: Double, longitude: Double, label: String?) -> Unit,
    viewModel: LocationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val walking by viewModel.walking.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val findAddress = {
        focusManager.clearFocus()
        viewModel.findAddress(address)
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

    val point = state.coordinates

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { AppTopBar(title = stringResource(R.string.location_title), onBack = onBack) },
        bottomBar = {
            BottomActionBar {
                if (point != null) {
                    Text(
                        stringResource(
                            R.string.location_starting_point_value,
                            state.label ?: stringResource(R.string.location_current_place)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.location_show_recommendations),
                    enabled = point != null && !state.isResolving,
                    onClick = {
                        point?.let { onCalculate(it.latitude, it.longitude, state.label) }
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
            StepCard(
                step = stringResource(R.string.location_step),
                title = stringResource(R.string.location_starting_point),
                text = stringResource(R.string.location_step_text)
            )

            GlassCard(
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
                Row(
                    modifier = Modifier.padding(AppSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (state.isResolving) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                AppIcon(R.drawable.ic_my_location, contentDescription = null)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (state.isResolving) R.string.location_resolving else R.string.location_use_current
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.location_use_current_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AppIcon(
                        R.drawable.ic_chevron_right,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.location_address_label)) },
                placeholder = { Text(stringResource(R.string.location_address_placeholder)) },
                trailingIcon = {
                    IconButton(
                        enabled = address.isNotBlank() && !state.isResolving,
                        onClick = findAddress
                    ) {
                        AppIcon(R.drawable.ic_search, contentDescription = stringResource(R.string.location_find_address))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { findAddress() }),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("address-field")
            )

            // Peške: radnje u kraju, bez troška goriva; mape vode peške.
            Text(stringResource(R.string.location_travel_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                FilterChip(
                    selected = !walking,
                    onClick = { viewModel.setWalking(false) },
                    label = { Text(stringResource(R.string.location_travel_driving)) },
                    modifier = Modifier.testTag("travel-driving")
                )
                FilterChip(
                    selected = walking,
                    onClick = { viewModel.setWalking(true) },
                    label = { Text(stringResource(R.string.location_travel_walking)) },
                    modifier = Modifier.testTag("travel-walking")
                )
            }
            if (walking) {
                Text(
                    stringResource(R.string.location_travel_walking_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.message?.let { message ->
                NoticeBanner(
                    text = message.asString(),
                    tone = if (state.isError) StatusTone.ERROR else StatusTone.POSITIVE
                )
            }

            if (state.offerSettings) {
                Column {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text(stringResource(R.string.location_open_location_settings)) }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    }) { Text(stringResource(R.string.location_open_app_permissions)) }
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
                    stringResource(R.string.location_privacy_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
