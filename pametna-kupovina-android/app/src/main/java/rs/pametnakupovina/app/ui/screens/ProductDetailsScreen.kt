package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CanonicalProductOfferDto
import rs.pametnakupovina.app.data.network.CanonicalProductPricePointDto
import rs.pametnakupovina.app.ui.ProductDetailsViewModel
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.SectionHeader
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.date
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.shortDate

@Composable
fun ProductDetailsScreen(
    onBack: () -> Unit,
    viewModel: ProductDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppTopBar(title = "Cene proizvoda", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> LoadingState("Učitavam ponude…")
                state.errorMessage != null -> ErrorState(
                    title = "Cene nisu dostupne",
                    message = requireNotNull(state.errorMessage),
                    onRetry = viewModel::load
                )
                state.product != null -> ProductDetailsContent(requireNotNull(state.product))
            }
        }
    }
}

/**
 * Cheapest first, so the answer to "where" is the top row. A price to be
 * checked goes last however low it is: it is most likely for one piece or one
 * kilogram of a bigger pack.
 */
@Composable
private fun ProductDetailsContent(product: CanonicalProductDetailsDto) {
    val offers = product.offers.sortedWith(
        compareBy({ it.priceNeedsCheck }, { it.effectivePrice })
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppSpacing.lg,
            end = AppSpacing.lg,
            top = AppSpacing.md,
            bottom = AppSpacing.xl
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text(product.name, style = MaterialTheme.typography.headlineSmall)
                productMetadata(product).takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Cene za ${date(product.requestedDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item(key = "offers-header") {
            SectionHeader("Aktuelne ponude", trailing = offers.size.toString())
        }
        if (offers.isEmpty()) {
            item(key = "no-offers") {
                NoticeBanner(text = "Za ovaj proizvod još nema važećih cena.")
            }
        } else {
            item(key = "offers") {
                GroupedRows(offers) { index, offer ->
                    OfferRow(offer, cheapest = index == 0 && offers.size > 1 && !offer.priceNeedsCheck)
                }
            }
        }

        if (product.priceHistory.isNotEmpty()) {
            item(key = "history-header") { SectionHeader("Poslednje promene cena") }
            item(key = "history") {
                GroupedRows(product.priceHistory) { _, point -> PriceHistoryRow(point) }
            }
        }
    }
}

@Composable
private fun <T> GroupedRows(
    rows: List<T>,
    content: @Composable (Int, T) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                content(index, row)
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun OfferRow(offer: CanonicalProductOfferDto, cheapest: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(offer.retailerName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${offerScope(offer)} · cene od ${shortDate(offer.priceDate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (
                offer.discountedPrice != null &&
                offer.regularPrice != null &&
                offer.discountedPrice < offer.regularPrice
            ) {
                Text(
                    "Akcija, redovno ${money(offer.regularPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (offer.priceNeedsCheck) {
                Text(
                    "Manje od pola uobičajene cene u drugim lancima",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusPill("Proveri cenu", StatusTone.WARNING)
            }
            if (cheapest) {
                StatusPill("Najjeftinije", StatusTone.POSITIVE)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                money(offer.effectivePrice),
                style = MaterialTheme.typography.titleMedium,
                color = if (cheapest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            offer.unitPrice?.let {
                Text(
                    "jed. ${money(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PriceHistoryRow(point: CanonicalProductPricePointDto) {
    Row(
        modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(point.retailerName, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(date(point.priceDate), point.storeName, point.storeFormatName)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(money(point.effectivePrice), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun productMetadata(product: CanonicalProductDetailsDto): String =
    listOfNotNull(
        product.brand,
        product.quantityValue?.let { quantity ->
            product.baseUnit?.let { amountLabel(quantity, it, product.packageCount) }
        },
        product.barcode?.let { "barkod $it" }
    ).joinToString(" · ")

private fun offerScope(offer: CanonicalProductOfferDto): String = when (offer.priceScope) {
    "STORE" -> offer.storeName ?: "Jedan objekat"
    "STORE_FORMAT" -> "Format ${offer.storeFormatName ?: "nepoznat"}"
    else -> "Svi objekti lanca"
}
