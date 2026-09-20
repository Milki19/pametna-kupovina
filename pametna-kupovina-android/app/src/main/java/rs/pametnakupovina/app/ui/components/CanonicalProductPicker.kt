package rs.pametnakupovina.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.counted
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.shortDate

internal fun LazyListScope.canonicalProductPicker(
    query: String,
    selectedProduct: CanonicalProductSearchItemDto?,
    searchState: ProductSearchUiState,
    onQueryChange: (String) -> Unit,
    onSelectProduct: (CanonicalProductSearchItemDto) -> Unit,
    onClearSelection: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onIncludeWithoutPrice: (Boolean) -> Unit = {},
    showWithoutPriceFilter: Boolean = true
) {
    item(key = "product-query") {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Naziv ili barkod") },
            leadingIcon = { AppIcon(R.drawable.ic_search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty() && selectedProduct == null) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        AppIcon(R.drawable.ic_close, contentDescription = "Obriši pretragu")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("product-search-field")
        )
    }

    if (selectedProduct != null) {
        item(key = "selected-product") {
            SelectedProductCard(
                product = selectedProduct,
                onChange = onClearSelection
            )
        }
        return
    }

    item(key = "search-help") {
        Column {
            Text(
                "Cena u cenovniku nije potvrda da proizvoda ima na stanju.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showWithoutPriceFilter) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = searchState.includeWithoutPrice,
                        onCheckedChange = onIncludeWithoutPrice,
                        modifier = Modifier.testTag("include-without-price")
                    )
                    Text(
                        "Prikaži i proizvode bez cene",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    when {
        searchState.isSearching -> item(key = "search-loading") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                modifier = Modifier.padding(vertical = AppSpacing.sm)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
                Text("Pretražujem proizvode…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        searchState.errorMessage != null -> item(key = "search-error") {
            NoticeBanner(
                text = searchState.errorMessage,
                tone = StatusTone.ERROR,
                actionLabel = "Pokušaj ponovo",
                onAction = onRetry
            )
        }

        searchState.query.length >= 2 && searchState.results.isEmpty() ->
            item(key = "search-empty") {
                Text(
                    if (searchState.includeWithoutPrice) {
                        "Nema pronađenih proizvoda."
                    } else {
                        "Nema rezultata sa aktuelnom cenom. Promeni upit ili uključi proizvode bez cene."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
    }

    searchState.correctedQuery?.let { corrected ->
        item(key = "search-corrected") {
            Text(
                "Nema rezultata za \u201E${searchState.query.trim()}\u201C. " +
                    "Prikazani su rezultati za \u201E$corrected\u201C.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("search-corrected")
            )
        }
    }

    items(
        items = searchState.results,
        key = { it.productFamilyId ?: it.canonicalProductId ?: it.name }
    ) { product ->
        ProductSearchResultCard(
            product = product,
            onChoose = { onSelectProduct(product) }
        )
    }

    if (searchState.hasNext) {
        item(key = "search-more") {
            OutlinedButton(
                enabled = !searchState.isLoadingMore,
                onClick = onLoadMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product-load-more")
            ) {
                Text(
                    if (searchState.isLoadingMore) {
                        "Učitavam…"
                    } else {
                        "Prikaži još (${searchState.results.size} od ${searchState.totalElements})"
                    }
                )
            }
        }
    }
}

@Composable
private fun ProductSearchResultCard(
    product: CanonicalProductSearchItemDto,
    onChoose: () -> Unit
) {
    var detailsExpanded by remember(product.resultId()) { mutableStateOf(false) }
    var confirmWithoutPrice by remember(product.resultId()) { mutableStateOf(false) }
    if (confirmWithoutPrice) {
        AlertDialog(
            onDismissRequest = { confirmWithoutPrice = false },
            title = { Text("Dodaj proizvod bez cene?") },
            text = {
                Text(
                    "Proizvod poznajemo, ali nemamo aktuelnu cenu. Ne možemo ga " +
                        "uračunati u cenu korpe dok ne pronađemo ponudu."
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm-without-price"),
                    onClick = {
                        confirmWithoutPrice = false
                        onChoose()
                    }
                ) { Text("Dodaj ipak") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWithoutPrice = false }) {
                    Text("Nazad na rezultate")
                }
            }
        )
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product-result-${product.resultId()}")
    ) {
        Column(
            modifier = Modifier.padding(
                start = AppSpacing.lg,
                end = AppSpacing.md,
                top = AppSpacing.md,
                bottom = AppSpacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Text(product.name, style = MaterialTheme.typography.titleMedium)
            productDetails(product).takeIf(String::isNotBlank)?.let { details ->
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ProductPriceSummary(product, showAll = detailsExpanded)
            if (detailsExpanded) {
                listOfNotNull(
                    product.barcode?.let { "Barkod: $it" },
                    product.categoryName?.let { "Kategorija: $it" },
                    product.variantCount.takeIf { it > 1 }?.let { "$it barkod varijante" }
                ).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(if (detailsExpanded) "Manje" else "Više o proizvodu")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (product.hasUsablePrice) onChoose() else confirmWithoutPrice = true
                    },
                    modifier = Modifier.testTag("product-choose-${product.resultId()}")
                ) {
                    Text("Izaberi")
                }
            }
        }
    }
}

@Composable
private fun SelectedProductCard(
    product: CanonicalProductSearchItemDto,
    onChange: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(
                start = AppSpacing.lg,
                end = AppSpacing.md,
                top = AppSpacing.md,
                bottom = AppSpacing.xs
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                AppIcon(
                    R.drawable.ic_check_circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text("Izabran proizvod", style = MaterialTheme.typography.labelLarge)
            }
            Text(product.name, style = MaterialTheme.typography.titleMedium)
            listOfNotNull(
                productDetails(product).takeIf(String::isNotBlank),
                product.barcode?.let { "Barkod: $it" }
            ).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            ProductPriceSummary(product, showAll = true)
            TextButton(onClick = onChange) { Text("Promeni izbor") }
        }
    }
}

/**
 * Price first, chain second, the price list's date quietly between them. The
 * three cheapest chains are enough to choose by; a product sold everywhere
 * would otherwise grow a card taller than the screen.
 */
@Composable
private fun ProductPriceSummary(
    product: CanonicalProductSearchItemDto,
    showAll: Boolean
) {
    if (!product.hasUsablePrice) {
        if (product.knownRetailers.isNotEmpty()) {
            Text(
                "Zabeležen kod: ${product.knownRetailers.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Nemamo aktuelnu cenu",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    // A chain whose every price is to be checked goes last however cheap it
    // looks, and says so where the price list's date would be.
    val offers = product.availability.sortedWith(
        compareBy({ it.priceNeedsCheck }, { it.minimumEffectivePrice ?: Double.MAX_VALUE })
    )
    val shown = if (showAll) offers else offers.take(3)
    Column {
        shown.forEach { offer ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    offer.retailerName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (offer.priceNeedsCheck) "proveri cenu" else shortDate(offer.latestPriceDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (offer.priceNeedsCheck) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = AppSpacing.sm)
                )
                offer.minimumEffectivePrice?.let {
                    Text("od ${money(it)}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (offers.size > shown.size) {
            Text(
                "i u još " + counted(offers.size - shown.size, "lancu", "lanca", "lanaca"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun productDetails(product: CanonicalProductSearchItemDto): String =
    listOfNotNull(
        product.brand,
        product.quantityValue?.let { quantity ->
            product.baseUnit?.let { amountLabel(quantity, it, product.packageCount) }
        }
    ).joinToString(" · ")

private fun CanonicalProductSearchItemDto.resultId(): String =
    (productFamilyId ?: canonicalProductId)?.toString()
        ?: name.hashCode().toString()
