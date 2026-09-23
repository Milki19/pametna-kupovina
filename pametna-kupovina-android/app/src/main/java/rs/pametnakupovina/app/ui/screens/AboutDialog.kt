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

data class AboutUiState(
    val deviceToken: String? = null,
    val signedIn: Boolean = false,
    val household: Boolean = false,
    val signingIn: Boolean = false,
    val deleting: Boolean = false,
    val message: String? = null
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
            _uiState.update {
                it.copy(deviceToken = clientIdentityStore.getOrCreateToken())
            }
            refreshAccount()
        }
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
                    it.copy(deleting = false, message = "Brisanje nije uspelo. Proveri internet i probaj ponovo.")
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
                if (state.signedIn) "Prijavljen si. Spiskovi su sada vezani za nalog." else null
            } catch (cancelled: SignInCancelled) {
                null
            } catch (missing: NoGoogleAccount) {
                "Na telefonu nema nijednog Google naloga."
            } catch (failure: Exception) {
                "Prijava nije uspela. Probaj ponovo kasnije."
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (state.household) "Izađi i obriši?" else "Obrisati sve?") },
            text = {
                Text(
                    if (state.household) {
                        "Ovaj telefon izlazi iz domaćinstva i briše sve sa sebe. " +
                            "Zajednički spisak, računi i kartice ostaju ukućanima."
                    } else {
                        "Brišu se spiskovi, skenirani računi, kartice i prijava, i na " +
                            "serveru i na telefonu. Ne može da se vrati. Aplikacija će se zatvoriti."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = { viewModel.deleteEverything(context) },
                    modifier = Modifier.testTag("about-delete-confirm")
                ) { Text(if (state.deleting) "Brišem…" else "Obriši", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Otkaži") } }
        )
    }

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
                if (state.signedIn) {
                    Text(
                        "Prijavljen si preko Google-a, pa ćeš spiskove naći i " +
                            "na drugom telefonu.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "Spiskovi su vezani za ovaj telefon. Prijavi se da ih " +
                            "nađeš i na drugom.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = { viewModel.signIn(context) },
                        enabled = !state.signingIn,
                        modifier = Modifier.testTag("about-sign-in")
                    ) {
                        Text(
                            if (state.signingIn) "Prijavljujem…"
                            else "Prijavi se preko Google-a"
                        )
                    }
                }

                state.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("about-message")
                    )
                }

                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.testTag("about-delete")
                ) {
                    Text(
                        if (state.household) "Izađi iz domaćinstva i obriši podatke" else "Obriši moje podatke",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    "Broj uređaja (za pitanja o podacima):",
                    style = MaterialTheme.typography.bodyMedium
                )
                SelectionContainer {
                    Text(
                        state.deviceToken ?: "…",
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
