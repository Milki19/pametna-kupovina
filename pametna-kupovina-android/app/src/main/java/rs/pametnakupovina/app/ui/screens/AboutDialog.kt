package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.testTag
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
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore
import rs.pametnakupovina.app.ui.components.AppSpacing

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val clientIdentityStore: ClientIdentityStore
) : ViewModel() {

    private val _deviceToken = MutableStateFlow<String?>(null)
    val deviceToken: StateFlow<String?> = _deviceToken.asStateFlow()

    init {
        viewModelScope.launch {
            _deviceToken.value = clientIdentityStore.getOrCreateToken()
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
    val deviceToken by viewModel.deviceToken.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("O aplikaciji") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text(
                    "Pametna kupovina ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Cene su iz zvaničnih cenovnika trgovaca. Merodavna je " +
                        "cena u prodavnici.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Broj uređaja (pošalji ga uz zahtev za brisanje podataka):",
                    style = MaterialTheme.typography.bodyMedium
                )
                SelectionContainer {
                    Text(
                        deviceToken ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("about-device-token")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onOpenLink(PRIVACY_URL) },
                modifier = Modifier.testTag("about-privacy")
            ) {
                Text("Politika privatnosti")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onOpenLink(TERMS_URL) },
                modifier = Modifier.testTag("about-terms")
            ) {
                Text("Uslovi korišćenja")
            }
        }
    )
}

private val PRIVACY_URL =
    BuildConfig.BACKEND_BASE_URL.trimEnd('/') + "/privatnost"
private val TERMS_URL =
    BuildConfig.BACKEND_BASE_URL.trimEnd('/') + "/uslovi"
