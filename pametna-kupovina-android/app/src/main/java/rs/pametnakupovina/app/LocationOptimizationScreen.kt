package rs.pametnakupovina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.math.BigDecimal
import java.math.RoundingMode

private const val TEST_LATITUDE = 44.7866
private const val TEST_LONGITUDE = 20.4489
private const val TEST_COST_PER_KM = 20.0

@Composable
fun LocationOptimizationScreen(
    listId: Long,
    onBack: () -> Unit,
    viewModel: LocationOptimizationViewModel = viewModel()
) {
    val state = viewModel.uiState

    BackHandler(onBack = onBack)

    LaunchedEffect(listId) {
        viewModel.loadOptimization(
            listId = listId,
            latitude = TEST_LATITUDE,
            longitude = TEST_LONGITUDE,
            costPerKm = TEST_COST_PER_KM
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("← Nazad na listu")
        }

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null -> {
                LocationOptimizationError(
                    message = state.errorMessage,
                    onRetry = {
                        viewModel.loadOptimization(
                            listId = listId,
                            latitude = TEST_LATITUDE,
                            longitude = TEST_LONGITUDE,
                            costPerKm = TEST_COST_PER_KM
                        )
                    }
                )
            }

            state.result != null -> {
                LocationOptimizationContent(state.result)
            }
        }
    }
}

@Composable
private fun ColumnScope.LocationOptimizationContent(
    result: LocationOptimizationDto
) {
    Text(
        text = "Optimizacija kupovine",
        style = MaterialTheme.typography.headlineMedium
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = result.listName,
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(16.dp))

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LocationInformationCard(result)
        }

        item {
            RecommendedStrategyCard(
                recommendation = result.recommendation,
                strategy = result.recommendedStrategy
            )
        }

        item {
            Text(
                text = "Poređenje jedne prodavnice",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        itemsIndexed(
            items = result.singleStoreStrategies
        ) { index, strategy ->
            SingleStoreStrategyCard(
                index = index,
                strategy = strategy
            )
        }

        item {
            Text(
                text = "Napomena: udaljenost je trenutno pravolinijska procena. " +
                        "Kasnije ćemo povezati stvarne putne rute.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(
                    top = 8.dp,
                    bottom = 16.dp
                )
            )
        }
    }
}

@Composable
private fun LocationInformationCard(
    result: LocationOptimizationDto
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Test lokacija: Beograd",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text("Latitude: ${result.latitude}")
            Text("Longitude: ${result.longitude}")
            Text(
                "Trošak puta: " +
                        "${formatOptimizationMoney(result.costPerKm)} po km"
            )
        }
    }
}

@Composable
private fun RecommendedStrategyCard(
    recommendation: String,
    strategy: PurchaseStrategyDto?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = recommendationTitle(recommendation),
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (strategy == null) {
                Text(
                    text = "Trenutno nema kompletne opcije kupovine. " +
                            "Za jednu ili više stavki nije pronađena cena."
                )
                return@Column
            }

            if (!strategy.available) {
                Text(
                    text = strategy.reason
                        ?: "Ova strategija trenutno nije dostupna."
                )
                return@Column
            }

            if (strategy.retailerCodes.isNotEmpty()) {
                Text(
                    text = "Prodavnice: " +
                            strategy.retailerCodes.joinToString()
                )
            }

            Text(
                text = "Cena korpe: " +
                        formatOptimizationMoney(strategy.basketTotal)
            )

            Text(
                text = "Ukupna udaljenost: " +
                        formatDistance(strategy.routeDistanceKm)
            )

            strategy.travelCost?.let { travelCost ->
                Text(
                    text = "Trošak puta: " +
                            formatOptimizationMoney(travelCost)
                )
            }

            strategy.finalTotal?.let { finalTotal ->
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Korpa + put: " +
                            formatOptimizationMoney(finalTotal),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (strategy.route.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Ruta",
                    style = MaterialTheme.typography.titleMedium
                )

                strategy.route.forEach { stop ->
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "${stop.order}. ${stop.locationName}" +
                                if (stop.city.isNullOrBlank()) {
                                    ""
                                } else {
                                    " — ${stop.city}"
                                }
                    )

                    Text(
                        text = "Od prethodne tačke: " +
                                formatDistance(stop.distanceFromPreviousKm),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SingleStoreStrategyCard(
    index: Int,
    strategy: PurchaseStrategyDto
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Opcija ${index + 1}",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (!strategy.available) {
                Text(
                    text = strategy.reason
                        ?: "Kupovina nije moguća u ovoj prodavnici.",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "Prodavnica: " +
                            strategy.retailerCodes.joinToString()
                )

                Text(
                    text = "Cena korpe: " +
                            formatOptimizationMoney(strategy.basketTotal)
                )

                strategy.finalTotal?.let {
                    Text(
                        text = "Korpa + put: " +
                                formatOptimizationMoney(it)
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationOptimizationError(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Greška pri optimizaciji",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(message)

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onRetry) {
            Text("Pokušaj ponovo")
        }
    }
}

private fun recommendationTitle(recommendation: String): String {
    return when (recommendation) {
        "SINGLE_STORE" -> "Preporuka: jedna prodavnica"
        "MULTI_STORE" -> "Preporuka: više prodavnica"
        "MULTI_STORE_REQUIRED" -> "Potrebno je više prodavnica"
        "NO_COMPLETE_OPTION" -> "Nema kompletne opcije"
        else -> "Preporučena kupovina"
    }
}

private fun formatOptimizationMoney(value: Double): String {
    return BigDecimal
        .valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString() + " RSD"
}

private fun formatDistance(value: Double): String {
    return BigDecimal
        .valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString() + " km"
}