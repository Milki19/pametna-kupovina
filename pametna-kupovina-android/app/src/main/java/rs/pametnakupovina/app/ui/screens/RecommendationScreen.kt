package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.math.RoundingMode
import rs.pametnakupovina.app.data.network.OptimizationScenarioDto
import rs.pametnakupovina.app.data.network.RecommendationItemDto
import rs.pametnakupovina.app.data.network.RecommendationItemStatusDto
import rs.pametnakupovina.app.data.network.RecommendationScenarioTypeDto
import rs.pametnakupovina.app.data.network.RecommendationStoreDto
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.ui.RecommendationViewModel
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationScreen(
    listId: Long,
    location: Pair<Double, Double>?,
    onBack: () -> Unit,
    viewModel: RecommendationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(listId, location) {
        location?.let { (latitude, longitude) ->
            viewModel.load(listId, latitude, longitude)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preporuke") },
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
        ) {
            when {
                location == null -> ErrorState(
                    title = "Lokacija više nije dostupna",
                    message = "Vrati se i ponovo izaberi lokaciju za računanje.",
                    onRetry = onBack
                )
                state.isLoading -> LoadingState("Računam tri scenarija…")
                state.errorMessage != null -> ErrorState(
                    title = "Rezultat nije dostupan",
                    message = requireNotNull(state.errorMessage),
                    onRetry = {
                        val (latitude, longitude) = requireNotNull(location)
                        viewModel.load(
                            listId,
                            latitude,
                            longitude
                        )
                    }
                )
                state.result != null -> RecommendationContent(
                    result = requireNotNull(state.result)
                )
            }
        }
    }
}

@Composable
private fun RecommendationContent(result: ShoppingRecommendationDto) {
    var selectedTypeName by rememberSaveable {
        mutableStateOf(RecommendationScenarioTypeDto.RECOMMENDED_BALANCE.name)
    }
    val scenarios = listOf(
        result.singleStore,
        result.recommendedBalance,
        result.lowestPrice
    )
    val selected = scenarios.firstOrNull {
        it.type.name == selectedTypeName
    } ?: result.recommendedBalance

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(result.listName, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Datum računanja: ${result.requestedDate}",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        scenarios.forEach { scenario ->
            item(key = scenario.type.name) {
                ScenarioSummaryCard(
                    scenario = scenario,
                    selected = scenario.type == selected.type,
                    onClick = { selectedTypeName = scenario.type.name }
                )
            }
        }

        item {
            Text(
                "Plan kupovine — ${scenarioTitle(selected.type)}",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (!selected.available) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        selected.explanation,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            selected.stores.sortedBy { it.stopOrder }.forEach { store ->
                item(key = "store-${selected.type}-${store.storeId}") {
                    StoreAllocationCard(
                        store = store,
                        items = selected.items.filter { it.storeId == store.storeId }
                    )
                }
            }

            val unresolved = selected.items.filter {
                it.resultStatus != RecommendationItemStatusDto.AVAILABLE
            }
            if (unresolved.isNotEmpty()) {
                item {
                    UnresolvedItemsCard(unresolved)
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Važno", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Podaci o cenama za scenario: " +
                            (selected.dataAsOf ?: "nema važećih cena")
                    )
                    if (
                        selected.dataAsOf != null &&
                        selected.dataAsOf != result.requestedDate
                    ) {
                        Text(
                            "Cene nisu sa datuma računanja; proveri ih pre kupovine.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        selected.disclaimer.ifBlank { result.disclaimer }
                    )
                }
            }
        }

        item {
            AssumptionsCard(result)
        }
    }
}

@Composable
private fun ScenarioSummaryCard(
    scenario: OptimizationScenarioDto,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                scenarioTitle(scenario.type),
                style = MaterialTheme.typography.titleLarge
            )
            Text(scenario.explanation)
            if (scenario.available) {
                Text("Cena korpe: ${money(scenario.basketCost)}")
                Text(
                    "Put: ${distance(scenario.routeDistanceKm)} • " +
                        "${duration(scenario.routeDurationSeconds)}"
                )
                Text("Broj stajanja: ${scenario.stopCount}")
                Text(
                    "Ukupno: ${scenario.totalCost?.let(::money) ?: "—"}",
                    style = MaterialTheme.typography.titleMedium
                )
                scenario.savingsComparedWithSingleStore?.let { savings ->
                    Text("Ušteda prema jednoj prodavnici: ${money(savings)}")
                }
                if (scenario.approximateRoute) {
                    Text(
                        "Ruta je aproksimacija (${scenario.distanceMethod}).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Izvori cena: ${scenario.priceSources.joinToString().ifBlank { "nije navedeno" }}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Scenario trenutno nije dostupan.",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StoreAllocationCard(
    store: RecommendationStoreDto,
    items: List<RecommendationItemDto>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "${store.stopOrder}. ${store.retailerName}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(store.storeName)
            listOfNotNull(store.address, store.city)
                .joinToString(", ")
                .takeIf(String::isNotBlank)
                ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Od prethodne tačke: ${distance(store.distanceFromPreviousKm)} • " +
                    duration(store.durationFromPreviousSeconds),
                style = MaterialTheme.typography.bodySmall
            )

            if (items.isEmpty()) {
                Text("Nema dodeljenih stavki.")
            } else {
                items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.requestedName)
                            item.productName
                                ?.takeUnless {
                                    it.equals(item.requestedName, ignoreCase = true)
                                }
                                ?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            itemPriceBreakdown(item)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(item.lineTotal?.let(::money) ?: "—")
                    }
                }
            }
        }
    }
}

@Composable
private fun UnresolvedItemsCard(items: List<RecommendationItemDto>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Neuparene ili nedostupne stavke",
                style = MaterialTheme.typography.titleMedium
            )
            items.forEach { item ->
                Column {
                    Text(item.requestedName)
                    Text(
                        when (item.resultStatus) {
                            RecommendationItemStatusDto.NEEDS_CONFIRMATION ->
                                "Potrebna je potvrda proizvoda"
                            RecommendationItemStatusDto.UNMATCHED -> "Neupareno"
                            RecommendationItemStatusDto.NO_VALID_PRICE ->
                                "Nema važeće cene za izabrani datum"
                            RecommendationItemStatusDto.AVAILABLE -> "Dostupno"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(item.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AssumptionsCard(result: ShoppingRecommendationDto) {
    val assumptions = result.assumptions
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Pretpostavke obračuna", style = MaterialTheme.typography.titleMedium)
            Text("Radijus: ${assumptions.candidateRadiusMeters / 1000.0} km")
            Text("Trošak po km: ${money(assumptions.costPerKm)}")
            Text("Vrednost vremena: ${money(assumptions.valuePerHour)} / h")
            Text("Trošak stajanja: ${money(assumptions.costPerStop)}")
            Text("Razmotreno objekata: ${result.candidateStoreCount}")
        }
    }
}

private fun scenarioTitle(type: RecommendationScenarioTypeDto): String = when (type) {
    RecommendationScenarioTypeDto.SINGLE_STORE -> "Jedna prodavnica"
    RecommendationScenarioTypeDto.RECOMMENDED_BALANCE -> "Preporučeni balans"
    RecommendationScenarioTypeDto.LOWEST_PRICE -> "Najniža cena"
}

private fun money(value: Double): String =
    BigDecimal.valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString() + " RSD"

internal fun itemPriceBreakdown(item: RecommendationItemDto): String? {
    val unitPrice = item.effectivePrice ?: return null
    val quantity = BigDecimal.valueOf(item.requestedQuantity)
        .stripTrailingZeros()
        .toPlainString()
    return "$quantity × ${money(unitPrice)}"
}

private fun distance(value: Double): String =
    BigDecimal.valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString() + " km"

private fun duration(seconds: Long): String {
    val totalMinutes = (seconds + 30) / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} h ${minutes} min" else "${minutes} min"
}
