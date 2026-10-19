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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    /** Raste svaki put kad stignu iznosi sa servera, da polja znaju kad da se popune. */
    val revision: Int = 0,
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
        _uiState.update { TripCostUiState(costs = it.costs, revision = it.revision) }
        load()
    }

    /** Posle okretanja ekrana sve ostaje kako je bilo; učitava se samo ono što fali. */
    fun resume() {
        if (_uiState.value.costs == null) load()
    }

    private fun load() = run(uiText(R.string.trip_load_failed)) {
        val costs = repository.tripCosts()
        _uiState.update { it.copy(costs = costs, revision = it.revision + 1) }
        null
    }

    /** Poruka o prethodnom čuvanju ne važi za iznos koji se upravo kuca. */
    fun edited() {
        if (!_uiState.value.busy) _uiState.update { it.copy(message = null) }
    }

    /** Null vraća podrazumevano. */
    fun save(valuePerHour: Double?, costPerStop: Double?) = run(uiText(R.string.trip_save_failed)) {
        val costs = repository.saveTripCosts(valuePerHour, costPerStop)
        _uiState.update { it.copy(costs = costs, revision = it.revision + 1, saved = true) }
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

/**
 * „1.000“ je u Srbiji hiljada: razdvajanje hiljada tržišta se skida samo kad
 * grupe stvarno imaju po tri cifre, pa „400.5“ i dalje znači 400,5.
 */
internal fun parseAmount(text: String): Double? {
    val symbols = DecimalFormatSymbols.getInstance(CurrentMarket.settings.formatLocale)
    val group = symbols.groupingSeparator.toString()
    val raw = text.filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u202F' }
    val grouped = Regex(
        "\\d{1,3}(" + Regex.escape(group) + "\\d{3})+(" +
            Regex.escape(symbols.decimalSeparator.toString()) + "\\d*)?"
    ).matches(raw)
    val plain = if (grouped) raw.replace(group, "") else raw
    return plain.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Najveći iznos u istom obliku kao polje: bez razdvajanja hiljada, uz valutu. */
private fun limit(value: Double, currency: String) = amountInput(value) + " " + currency

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
    // Okretanje ekrana ne otvara dijalog ponovo: „sačuvano“ i upisani iznosi ostaju.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (opened) {
            viewModel.resume()
        } else {
            opened = true
            viewModel.open()
        }
    }
    val costs = state.costs
    var hour by rememberSaveable { mutableStateOf<String?>(null) }
    var stop by rememberSaveable { mutableStateOf<String?>(null) }
    // Polja se pune samo novim iznosima sa servera, ne preko onoga što je kupac upisao.
    var filledRevision by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(state.revision) {
        if (costs != null && filledRevision != state.revision) {
            hour = amountInput(costs.valuePerHour)
            stop = amountInput(costs.costPerStop)
            filledRevision = state.revision
        }
    }
    val hourValue = hour?.let(::parseAmount)
    val stopValue = stop?.let(::parseAmount)
    val hourWrong = costs != null && hour != null && (hourValue == null || hourValue < 0 || hourValue > costs.maxValuePerHour)
    val stopWrong = costs != null && stop != null && (stopValue == null || stopValue < 0 || stopValue > costs.maxCostPerStop)

    // Dok se čuva, dijalog ostaje otvoren, da plan ne ostane na staroj ceni.
    val close = {
        if (!state.busy) {
            if (state.saved) onSaved()
            onDismiss()
        }
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
                    // Natpis iznad polja: sa krupnim slovima dug natpis u okviru polja prelazi preko ivice.
                    val hourLabel = stringResource(R.string.trip_hour_label, costs.currency)
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Text(hourLabel, style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = hour.orEmpty(),
                            onValueChange = {
                                hour = it
                                viewModel.edited()
                            },
                            enabled = !state.busy,
                            isError = hourWrong,
                            supportingText = if (hourWrong) {
                                { Text(stringResource(R.string.trip_invalid, limit(costs.maxValuePerHour, costs.currency))) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = hourLabel }
                                .testTag("trip-hour")
                        )
                    }
                    val stopLabel = stringResource(R.string.trip_stop_label, costs.currency)
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Text(stopLabel, style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = stop.orEmpty(),
                            onValueChange = {
                                stop = it
                                viewModel.edited()
                            },
                            enabled = !state.busy,
                            isError = stopWrong,
                            supportingText = if (stopWrong) {
                                { Text(stringResource(R.string.trip_invalid, limit(costs.maxCostPerStop, costs.currency))) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = stopLabel }
                                .testTag("trip-stop")
                        )
                    }
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
            TextButton(onClick = close, enabled = !state.busy) { Text(stringResource(R.string.common_close)) }
        }
    )
}
