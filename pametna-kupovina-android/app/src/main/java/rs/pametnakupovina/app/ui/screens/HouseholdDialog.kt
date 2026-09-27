package rs.pametnakupovina.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.BarcodeScanner
import rs.pametnakupovina.app.data.NotAHouseholdCode
import rs.pametnakupovina.app.data.ScanCancelled
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.serverMessage
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.Barcode
import retrofit2.HttpException
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.asUiText
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.components.TonalActionButton
import rs.pametnakupovina.app.ui.components.cardBorder

data class HouseholdUiState(
    val inviteQr: String? = null,
    val busy: Boolean = false,
    val joined: Boolean = false,
    val message: UiText? = null
)

@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val scanner: BarcodeScanner
) : ViewModel() {

    private val _uiState = MutableStateFlow(HouseholdUiState())
    val uiState: StateFlow<HouseholdUiState> = _uiState.asStateFlow()

    fun invite() = run(uiText(R.string.household_invite_failed)) {
        val qr = repository.householdInvite()
        _uiState.update { it.copy(inviteQr = qr) }
        null
    }

    fun join(activityContext: Context) = run(uiText(R.string.household_join_failed)) {
        repository.joinHousehold(scanner.anyBarcode(activityContext).value)
        _uiState.update { it.copy(joined = true) }
        uiText(R.string.household_joined)
    }

    private fun run(failure: UiText, action: suspend () -> UiText?) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            val message = try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: ScanCancelled) {
                null
            } catch (_: NotAHouseholdCode) {
                uiText(R.string.household_not_a_code)
            } catch (error: HttpException) {
                error.serverMessage()?.asUiText() ?: failure
            } catch (_: Exception) {
                failure
            }
            _uiState.update { it.copy(busy = false, message = message) }
        }
    }
}

/**
 * Domaćinstvo deli jedan nalog: isti spisak, računi i kartice na više
 * telefona. Poziv je QR kod na ekranu, jer su ukućani ionako jedan pored
 * drugog — nema šta da se prekucava ni da se pošalje pogrešnom čoveku.
 */
@Composable
fun HouseholdDialog(
    onDismiss: () -> Unit,
    onJoined: () -> Unit,
    viewModel: HouseholdViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val close = {
        if (state.joined) onJoined()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.household_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val qr = state.inviteQr
                if (qr != null) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Color.White,
                        border = cardBorder
                    ) {
                        Column(
                            modifier = Modifier.padding(AppSpacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            Barcode(
                                qr,
                                "QR_CODE",
                                modifier = Modifier.size(200.dp).testTag("household-qr"),
                                // Kod je ključ naloga; čitač ekrana ne treba da ga izgovara.
                                contentDescription = stringResource(R.string.household_qr_description)
                            )
                            StatusPill(stringResource(R.string.household_code_validity), StatusTone.WARNING)
                        }
                    }
                    Text(
                        stringResource(R.string.household_join_instructions),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        stringResource(R.string.household_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                state.message?.let {
                    Text(
                        it.asString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("household-message")
                    )
                }
                TonalActionButton(
                    text = stringResource(
                        if (state.inviteQr == null) R.string.household_invite else R.string.household_new_code
                    ),
                    icon = R.drawable.ic_add,
                    primary = true,
                    enabled = !state.busy,
                    onClick = viewModel::invite,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("household-invite")
                )
                TonalActionButton(
                    text = stringResource(R.string.household_join),
                    icon = R.drawable.ic_camera,
                    enabled = !state.busy && !state.joined,
                    onClick = { viewModel.join(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("household-join")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = close) { Text(stringResource(R.string.common_close)) }
        }
    )
}
