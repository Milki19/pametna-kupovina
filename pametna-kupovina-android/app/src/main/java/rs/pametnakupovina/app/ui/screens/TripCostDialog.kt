package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.market.CurrentMarket
import rs.pametnakupovina.app.data.network.TripCostsDto
import rs.pametnakupovina.app.data.network.serverMessage
import retrofit2.HttpException
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.asUiText
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.money

data class TripCostUiState(
    val costs: TripCostsDto? = null,
    val busy: Boolean = false,
    /** Plan računat pre ovoga više ne važi. */
    val saved: Boolean = false,
    val message: UiText? = null,
    val messageTone: StatusTone = StatusTone.NEUTRAL
)

@HiltViewModel
class TripCostViewModel @Inject constructor(
    private val repository: ShoppingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripCostUiState())
    val uiState: StateFlow<TripCostUiState> = _uiState.asStateFlow()

    /** Dijalog se otvara ispočetka; model ga nadživi. */
    fun open() {
        _uiState.update { TripCostUiState(costs = it.costs) }
        load()
    }

    private fun load() = run(uiText(R.string.trip_load_failed)) {
        _uiState.update { it.copy(costs = repository.tripCosts()) }
        null
    }

    /** Null vraća podrazumevano. */
    fun save(valuePerHour: Double?, costPerStop: Double?) = run(uiText(R.string.trip_save_failed)) {
        val costs = repository.saveTripCosts(valuePerHour, costPerStop)
        _uiState.update { it.copy(costs = costs, saved = true) }
        if (costs.customized) {
            uiText(R.string.trip_saved, money(costs.valuePerHour), money(costs.costPerStop))
        } else {
            uiText(R.string.trip_saved_default)
        }
    }

    private fun run(failure: UiText, action: suspend () -> UiText?) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            val (message, tone) = try {
                action() to StatusTone.POSITIVE
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                // Server kaže koliko najviše može, u valuti tržišta.
                (error.serverMessage()?.takeIf { error.code() == 400 }?.asUiText() ?: failure) to StatusTone.ERROR
            } catch (_: Exception) {
                failure to StatusTone.ERROR
            }
            _uiState.update { it.copy(busy = false, message = message, messageTone = tone) }
        }
    }
}

/** Iznos za polje, bez razdvajanja hiljada, da se ne pročita kao decimala. */
internal fun amountInput(value: Double): String {
    val separator = DecimalFormatSymbols.getInstance(CurrentMarket.settings.formatLocale).decimalSeparator
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', separator)
}

internal fun parseAmount(text: String): Double? =
    text.filterNot { it.isWhitespace() }.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/**
 * Koliko kupcu vredi sat u putu i jedno stajanje. Plan njima meri da li se
 * vožnja do jeftinije radnje isplati; [onSaved] dobija znak da plan računat
 * pre izmene više ne važi.
 */
@Composable
fun TripCostDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    viewModel: TripCostViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.open() }
    val costs = state.costs
    var hour by rememberSaveable { mutableStateOf<String?>(null) }
    var stop by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(costs) {
        if (costs != null) {
            hour = amountInput(costs.valuePerHour)
            stop = amountInput(costs.costPerStop)
        }
    }
    val hourValue = hour?.let(::parseAmount)
    val stopValue = stop?.let(::parseAmount)
    val hourWrong = costs != null && hour != null && (hourValue == null || hourValue < 0 || hourValue > costs.maxValuePerHour)
    val stopWrong = costs != null && stop != null && (stopValue == null || stopValue < 0 || stopValue > costs.maxCostPerStop)

    val close = {
        if (state.saved) onSaved()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.trip_title)) },
        text = {
            // Sa krupnim slovima polja i objašnjenje potisnu dugmad ispod ivice.
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.trip_intro), style = MaterialTheme.typography.bodyMedium)
                if (costs != null) {
                    OutlinedTextField(
                        value = hour.orEmpty(),
                        onValueChange = { hour = it },
                        enabled = !state.busy,
                        label = { Text(stringResource(R.string.trip_hour_label, costs.currency)) },
                        isError = hourWrong,
                        supportingText = if (hourWrong) {
                            { Text(stringResource(R.string.trip_invalid, money(costs.maxValuePerHour))) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("trip-hour")
                    )
                    OutlinedTextField(
                        value = stop.orEmpty(),
                        onValueChange = { stop = it },
                        enabled = !state.busy,
                        label = { Text(stringResource(R.string.trip_stop_label, costs.currency)) },
                        isError = stopWrong,
                        supportingText = if (stopWrong) {
                            { Text(stringResource(R.string.trip_invalid, money(costs.maxCostPerStop))) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("trip-stop")
                    )
                    Text(
                        stringResource(
                            R.string.trip_defaults,
                            money(costs.defaultValuePerHour),
                            money(costs.defaultCostPerStop)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (costs.customized) {
                        TextButton(
                            onClick = { viewModel.save(null, null) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("trip-reset")
                        ) { Text(stringResource(R.string.trip_reset)) }
                    }
                }
                state.message?.let {
                    NoticeBanner(
                        text = it.asString(),
                        tone = state.messageTone,
                        modifier = Modifier.testTag("trip-message")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.save(hourValue, stopValue) },
                enabled = costs != null && !state.busy && !hourWrong && !stopWrong &&
                    hourValue != null && stopValue != null,
                modifier = Modifier.testTag("trip-save")
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = close) { Text(stringResource(R.string.common_close)) }
        }
    )
}
