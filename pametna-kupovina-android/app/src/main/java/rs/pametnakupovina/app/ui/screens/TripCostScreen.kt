package rs.pametnakupovina.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import retrofit2.HttpException
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.market.CurrentMarket
import rs.pametnakupovina.app.data.network.TripCostsDto
import rs.pametnakupovina.app.data.network.serverMessage
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.asUiText
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.BottomActionBar
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.GlassCard
import rs.pametnakupovina.app.ui.components.IconTile
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.money

data class TripCostUiState(
    val costs: TripCostsDto? = null,
    /** Raste svaki put kad stignu iznosi sa servera, da polja znaju kad da se popune. */
    val revision: Int = 0,
    val busy: Boolean = false,
    /** Iznosi nisu stigli sa servera; ekran nudi novi pokušaj. */
    val loadFailed: Boolean = false,
    val message: UiText? = null,
    val messageTone: StatusTone = StatusTone.NEUTRAL
)

@HiltViewModel
class TripCostViewModel @Inject constructor(
    private val repository: ShoppingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripCostUiState())
    val uiState: StateFlow<TripCostUiState> = _uiState.asStateFlow()

    // Ekran ima svoj model, pa okretanje ekrana ne učitava ponovo.
    init {
        load()
    }

    fun load() = run(uiText(R.string.trip_load_failed)) {
        val costs = repository.tripCosts()
        _uiState.update { it.copy(costs = costs, revision = it.revision + 1, loadFailed = false) }
        null
    }

    /** Poruka o prethodnom čuvanju ne važi za iznos koji se upravo kuca. */
    fun edited() {
        if (!_uiState.value.busy) _uiState.update { it.copy(message = null) }
    }

    /**
     * Null vraća podrazumevano. Plan zapamćen pre ovoga se računa ponovo
     * kad se vrati na njega, jer repozitorijum podiže verziju cena puta.
     */
    fun save(valuePerHour: Double?, costPerStop: Double?, costPerKm: Double?) =
        run(uiText(R.string.trip_save_failed)) {
            val costs = repository.saveTripCosts(valuePerHour, costPerStop, costPerKm)
            _uiState.update { it.copy(costs = costs, revision = it.revision + 1) }
            when {
                !costs.customized -> uiText(R.string.trip_saved_default)
                costs.costPerKm != null -> uiText(
                    R.string.trip_saved,
                    money(costs.valuePerHour),
                    money(costs.costPerKm),
                    money(costs.costPerStop)
                )
                else -> uiText(R.string.trip_saved_without_km, money(costs.valuePerHour), money(costs.costPerStop))
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
            _uiState.update {
                it.copy(
                    busy = false,
                    loadFailed = it.costs == null,
                    message = message,
                    messageTone = tone
                )
            }
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

/** Iznos van granica tržišta ili nešto što nije broj. */
private fun wrong(text: String?, value: Double?, max: Double?) =
    max != null && text != null && (value == null || value < 0 || value > max)

/**
 * Koliko kupcu vredi sat u putu, koliko ga košta kilometar kolima i jedno
 * stajanje. Plan njima meri da li se put do jeftinije radnje isplati. Otvara
 * se iz menija, sa polazne tačke (gde se bira kolima ili peške) i iz
 * „Kako je računato“ u planu.
 */
@Composable
fun TripCostScreen(
    onBack: () -> Unit,
    viewModel: TripCostViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val costs = state.costs
    var hour by rememberSaveable { mutableStateOf<String?>(null) }
    var km by rememberSaveable { mutableStateOf<String?>(null) }
    var stop by rememberSaveable { mutableStateOf<String?>(null) }
    // Polja se pune samo novim iznosima sa servera, ne preko onoga što je kupac upisao.
    var filledRevision by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(state.revision) {
        if (costs != null && filledRevision != state.revision) {
            hour = amountInput(costs.valuePerHour)
            km = costs.costPerKm?.let(::amountInput)
            stop = amountInput(costs.costPerStop)
            filledRevision = state.revision
        }
    }
    val hourValue = hour?.let(::parseAmount)
    val kmValue = km?.let(::parseAmount)
    val stopValue = stop?.let(::parseAmount)
    val hourWrong = wrong(hour, hourValue, costs?.maxValuePerHour)
    val kmWrong = wrong(km, kmValue, costs?.maxCostPerKm)
    val stopWrong = wrong(stop, stopValue, costs?.maxCostPerStop)
    // Server bez kilometra (pre V141) nema to polje, pa se ono ne traži.
    val kmReady = costs?.costPerKm == null || kmValue != null

    // Dok se čuva, ekran ostaje otvoren, da plan ne ostane na staroj ceni.
    BackHandler(enabled = state.busy) {}
    val back = { if (!state.busy) onBack() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { AppTopBar(title = stringResource(R.string.trip_title), onBack = back) },
        bottomBar = {
            if (costs != null) {
                BottomActionBar {
                    PrimaryActionButton(
                        text = stringResource(R.string.common_save),
                        enabled = !state.busy && !hourWrong && !kmWrong && !stopWrong &&
                            hourValue != null && stopValue != null && kmReady,
                        onClick = { viewModel.save(hourValue, stopValue, kmValue) },
                        modifier = Modifier.testTag("trip-save")
                    )
                }
            }
        }
    ) { padding ->
        when {
            costs == null && state.loadFailed -> ErrorState(
                title = stringResource(R.string.trip_title),
                message = stringResource(R.string.trip_load_failed),
                onRetry = viewModel::load
            )
            costs == null -> LoadingState()
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                GlassCard(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        Text(stringResource(R.string.trip_intro_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.trip_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TripAmountCard(
                    icon = R.drawable.ic_history,
                    label = stringResource(R.string.trip_hour_label),
                    hint = stringResource(R.string.trip_hour_hint),
                    value = hour,
                    onValue = {
                        hour = it
                        viewModel.edited()
                    },
                    currency = costs.currency,
                    wrong = hourWrong,
                    max = costs.maxValuePerHour,
                    enabled = !state.busy,
                    tag = "trip-hour"
                )
                if (costs.costPerKm != null) {
                    TripAmountCard(
                        icon = R.drawable.ic_directions,
                        label = stringResource(R.string.trip_km_label),
                        hint = stringResource(R.string.trip_km_hint),
                        value = km,
                        onValue = {
                            km = it
                            viewModel.edited()
                        },
                        currency = costs.currency,
                        wrong = kmWrong,
                        max = costs.maxCostPerKm,
                        enabled = !state.busy,
                        tag = "trip-km"
                    )
                }
                TripAmountCard(
                    icon = R.drawable.ic_my_location,
                    label = stringResource(R.string.trip_stop_label),
                    hint = stringResource(R.string.trip_stop_hint),
                    value = stop,
                    onValue = {
                        stop = it
                        viewModel.edited()
                    },
                    currency = costs.currency,
                    wrong = stopWrong,
                    max = costs.maxCostPerStop,
                    enabled = !state.busy,
                    tag = "trip-stop"
                )

                Text(
                    if (costs.defaultCostPerKm != null) {
                        stringResource(
                            R.string.trip_defaults,
                            money(costs.defaultValuePerHour),
                            money(costs.defaultCostPerKm),
                            money(costs.defaultCostPerStop)
                        )
                    } else {
                        stringResource(
                            R.string.trip_defaults_without_km,
                            money(costs.defaultValuePerHour),
                            money(costs.defaultCostPerStop)
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (costs.customized) {
                    TextButton(
                        onClick = { viewModel.save(null, null, null) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("trip-reset")
                    ) { Text(stringResource(R.string.trip_reset)) }
                }
                state.message?.let {
                    NoticeBanner(
                        text = it.asString(),
                        tone = state.messageTone,
                        modifier = Modifier.testTag("trip-message")
                    )
                }
            }
        }
    }
}

/** Jedan iznos: ikonica, šta znači i polje sa valutom tržišta. */
@Composable
private fun TripAmountCard(
    @DrawableRes icon: Int,
    label: String,
    hint: String,
    value: String?,
    onValue: (String) -> Unit,
    currency: String,
    wrong: Boolean,
    max: Double?,
    enabled: Boolean,
    tag: String
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(AppSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            IconTile(icon)
            // Natpis iznad polja: sa krupnim slovima dug natpis u okviru polja prelazi preko ivice.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val description = "$label ($currency)"
                OutlinedTextField(
                    value = value.orEmpty(),
                    onValueChange = onValue,
                    enabled = enabled,
                    isError = wrong,
                    suffix = { Text(currency) },
                    supportingText = if (wrong && max != null) {
                        { Text(stringResource(R.string.trip_invalid, limit(max, currency))) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.xs)
                        .semantics { contentDescription = description }
                        .testTag(tag)
                )
            }
        }
    }
}
