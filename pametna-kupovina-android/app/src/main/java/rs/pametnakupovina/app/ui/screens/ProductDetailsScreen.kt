package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.math.RoundingMode
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CanonicalProductOfferDto
import rs.pametnakupovina.app.data.network.CanonicalProductPricePointDto
import rs.pametnakupovina.app.ui.ProductDetailsViewModel
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailsScreen(
    onBack: () -> Unit,
    viewModel: ProductDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cene proizvoda") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Nazad") }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LoadingState("Učitavam ponude…")
            }

            state.errorMessage != null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                ErrorState(
                    title = "Cene nisu dostupne",
                    message = requireNotNull(state.errorMessage),
                    onRetry = viewModel::load
                )
            }

            state.product != null -> ProductDetailsContent(
                product = requireNotNull(state.product),
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun ProductDetailsContent(
    product: CanonicalProductDetailsDto,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(product.name, style = MaterialTheme.typography.headlineSmall)
                productMetadata(product).takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Cene do ${product.requestedDate}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        item {
            Text("Aktuelne ponude", style = MaterialTheme.typography.titleLarge)
        }

        if (product.offers.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Za ovaj proizvod još nema važećih cena.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(
                items = product.offers,
                key = {
                    "${it.retailerProductId}-${it.priceScope}-" +
                        "${it.storeId}-${it.storeFormatCode}"
                }
            ) { offer ->
                OfferCard(offer)
            }
        }

        if (product.priceHistory.isNotEmpty()) {
            item {
                Text(
                    "Poslednje promene cena",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            items(
                items = product.priceHistory,
                key = {
                    "history-${it.retailerProductId}-${it.priceDate}-" +
                        "${it.storeId}-${it.storeFormatName}"
                }
            ) { point ->
                PriceHistoryRow(point)
            }
        }
    }
}

@Composable
private fun OfferCard(offer: CanonicalProductOfferDto) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    offer.retailerName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    money(offer.effectivePrice),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(offerScope(offer), style = MaterialTheme.typography.bodySmall)
            Text("Cena od ${offer.priceDate}")
            if (
                offer.discountedPrice != null &&
                offer.regularPrice != null &&
                offer.discountedPrice < offer.regularPrice
            ) {
                Text(
                    "Redovna ${money(offer.regularPrice)} • akcijska " +
                        money(offer.discountedPrice),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            offer.unitPrice?.let {
                Text(
                    "Jedinična cena: ${money(it)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PriceHistoryRow(point: CanonicalProductPricePointDto) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(point.retailerName)
                Text(
                    listOfNotNull(
                        point.storeName,
                        point.storeFormatName,
                        point.priceDate
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(money(point.effectivePrice))
        }
    }
}

private fun productMetadata(product: CanonicalProductDetailsDto): String =
    listOfNotNull(
        product.brand,
        product.quantityValue?.let {
            "${formatQuantity(it)} ${product.baseUnit.orEmpty()}".trim()
        },
        product.barcode?.let { "Barkod $it" }
    ).joinToString(" • ")

private fun offerScope(offer: CanonicalProductOfferDto): String = when (
    offer.priceScope
) {
    "STORE" -> "Objekat: ${offer.storeName ?: "nepoznat"}"
    "STORE_FORMAT" -> "Format: ${offer.storeFormatName ?: "nepoznat"}"
    else -> "Cena važi za ceo lanac"
}

private fun money(value: Double): String =
    BigDecimal.valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString() + " RSD"
