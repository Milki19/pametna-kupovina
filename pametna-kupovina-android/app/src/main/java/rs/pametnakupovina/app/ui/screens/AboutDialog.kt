package rs.pametnakupovina.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.update
import rs.pametnakupovina.app.data.GoogleSignInClient
import rs.pametnakupovina.app.data.NoGoogleAccount
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.SignInCancelled
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.BuildConfig
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.AccountDeviceDto
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.ui.components.AppSpacing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

data class AboutUiState(
    val deviceId: Long? = null,
    val devices: List<AccountDeviceDto> = emptyList(),
    val signingOut: Boolean = false,
    val signedIn: Boolean = false,
    val household: Boolean = false,
    val signingIn: Boolean = false,
    val deleting: Boolean = false,
    val message: UiText? = null
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val clientIdentityStore: ClientIdentityStore,
    private val repository: ShoppingRepository,
    private val googleSignInClient: GoogleSignInClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            clientIdentityStore.deviceId.collect { deviceId ->
                _uiState.update { it.copy(deviceId = deviceId) }
            }
        }
        viewModelScope.launch { refreshAccount() }
    }

    /**
     * Server je taj koji zna da li je neko prijavljen; ako ne odgovara, ćuti
     * se umesto da se tvrdi bilo šta.
     */
    private suspend fun refreshAccount() {
        runCatching { repository.accountState() }
            .onSuccess { state ->
                _uiState.update { it.copy(signedIn = state.signedIn, household = state.household) }
            }
        if (_uiState.value.signedIn || _uiState.value.household) {
            runCatching { repository.accountDevices() }
                .onSuccess { devices -> _uiState.update { it.copy(devices = devices) } }
        }
    }

    /** Izgubljen ili tuđ telefon prestaje da vidi nalog čim se ukloni. */
    fun removeDevice(deviceId: Long) {
        viewModelScope.launch {
            try {
                repository.removeAccountDevice(deviceId)
                _uiState.update { state ->
                    state.copy(devices = state.devices.filterNot { it.id == deviceId })
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(message = uiText(R.string.about_remove_device_failed)) }
            }
        }
    }

    /**
     * Odjava je samo za prijavljene: nalog ostaje na serveru i vraća se
     * ponovnom prijavom. Telefon briše ono što je držao za taj nalog i
     * zatvara aplikaciju, pa sledeći put kreće kao nov.
     */
    fun signOut(context: Context) {
        if (_uiState.value.signingOut) return

        viewModelScope.launch {
            _uiState.update { it.copy(signingOut = true, message = null) }
            try {
                repository.signOut()
                context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(signingOut = false, message = uiText(R.string.about_sign_out_failed))
                }
            }
        }
    }

    /**
     * Server briše nalog (ili, u domaćinstvu, samo ovaj telefon), a Android
     * briše sve što je aplikacija držala na telefonu i zatvara je — kao
     * „Obriši podatke" u podešavanjima, samo iz aplikacije.
     */
    fun deleteEverything(context: Context) {
        if (_uiState.value.deleting) return

        viewModelScope.launch {
            _uiState.update { it.copy(deleting = true, message = null) }
            try {
                repository.deleteAccount()
                context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(deleting = false, message = uiText(R.string.about_delete_failed))
                }
            }
        }
    }

    fun signIn(activityContext: Context) {
        if (_uiState.value.signingIn) return

        viewModelScope.launch {
            _uiState.update { it.copy(signingIn = true, message = null) }

            val message = try {
                val state = repository.signInWithGoogle(
                    googleSignInClient.idToken(activityContext)
                )
                _uiState.update { it.copy(signedIn = state.signedIn) }
                refreshAccount()
                if (state.signedIn) uiText(R.string.about_signed_in_message) else null
            } catch (cancelled: SignInCancelled) {
                null
            } catch (missing: NoGoogleAccount) {
                uiText(R.string.about_no_google_account)
            } catch (failure: Exception) {
                uiText(R.string.about_sign_in_failed)
            }

            _uiState.update { it.copy(signingIn = false, message = message) }
        }
    }
}

/**
 * Politika privatnosti i uslovi moraju da budu dohvatljivi iz same
 * aplikacije, a broj uređaja je jedino po čemu možemo da nađemo nečije
 * podatke kad traži da se obrišu — zato stoje na istom mestu.
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    viewModel: AboutViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deviceToRemove by rememberSaveable { mutableStateOf<Long?>(null) }

    deviceToRemove?.let { deviceId ->
        val device = state.devices.firstOrNull { it.id == deviceId }
        val name = device?.name ?: stringResource(R.string.about_device_unnamed, deviceId)
        AlertDialog(
            onDismissRequest = { deviceToRemove = null },
            title = { Text(stringResource(R.string.about_remove_device_title, name)) },
            text = { Text(stringResource(R.string.about_remove_device_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDevice(deviceId)
                        deviceToRemove = null
                    },
                    modifier = Modifier.testTag("about-remove-device-confirm")
                ) { Text(stringResource(R.string.about_remove_device), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deviceToRemove = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    stringResource(
                        if (state.household) R.string.about_delete_title_household else R.string.about_delete_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (state.household) {
                            R.string.about_delete_text_household
                        } else {
                            R.string.about_delete_text
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = { viewModel.deleteEverything(context) },
                    modifier = Modifier.testTag("about-delete-confirm")
                ) { Text(stringResource(if (state.deleting) R.string.about_deleting else R.string.about_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            // Krupna slova: bez skrolovanja broj uređaja ispada iz dijaloga.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.about_prices_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.signedIn) {
                    Text(
                        stringResource(R.string.about_signed_in),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = { viewModel.signOut(context) },
                        enabled = !state.signingOut,
                        modifier = Modifier.testTag("about-sign-out")
                    ) {
                        Text(
                            stringResource(
                                if (state.signingOut) R.string.about_signing_out
                                else R.string.about_sign_out
                            )
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.about_signed_out),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = { viewModel.signIn(context) },
                        enabled = !state.signingIn,
                        modifier = Modifier.testTag("about-sign-in")
                    ) {
                        Text(
                            stringResource(
                                if (state.signingIn) R.string.about_signing_in
                                else R.string.about_sign_in
                            )
                        )
                    }
                }

                if (state.devices.size > 1) {
                    Text(
                        stringResource(R.string.about_devices),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    state.devices.forEach { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("about-device-${device.id}")
                        ) {
                            val name = device.name
                                ?: stringResource(R.string.about_device_unnamed, device.id)
                            Text(
                                if (device.current) stringResource(R.string.about_device_current, name) else name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (!device.current) {
                                TextButton(
                                    onClick = { deviceToRemove = device.id },
                                    modifier = Modifier.testTag("about-remove-device-${device.id}")
                                ) { Text(stringResource(R.string.about_remove_device)) }
                            }
                        }
                    }
                }

                state.message?.let { message ->
                    Text(
                        message.asString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("about-message")
                    )
                }

                // Dugačak tekst domaćinstva sa krupnim slovima: u TextButton-u je
                // prelazio u novi red, ali je prvo slovo svakog reda bilo odsečeno.
                Text(
                    stringResource(
                        if (state.household) R.string.about_delete_data_household else R.string.about_delete_data
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { confirmDelete = true }
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.md)
                        .testTag("about-delete")
                )

                Text(
                    stringResource(R.string.about_device_number),
                    style = MaterialTheme.typography.bodyMedium
                )
                SelectionContainer {
                    Text(
                        state.deviceId?.let { stringResource(R.string.about_device_id, it) } ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("about-device-id")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onOpenLink(PRIVACY_URL) },
                modifier = Modifier.testTag("about-privacy")
            ) {
                Text(stringResource(R.string.about_privacy_policy))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onOpenLink(TERMS_URL) },
                modifier = Modifier.testTag("about-terms")
            ) {
                Text(stringResource(R.string.about_terms))
            }
        }
    )
}

private val PRIVACY_URL =
    BuildConfig.BACKEND_BASE_URL.trimEnd('/') + "/privatnost"
private val TERMS_URL =
    BuildConfig.BACKEND_BASE_URL.trimEnd('/') + "/uslovi"
