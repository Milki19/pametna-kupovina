package rs.pametnakupovina.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.screens.formatQuantity

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
        Text(
            "Pretraži naziv, brend ili barkod. Cena u cenovniku nije potvrda zaliha u prodavnici.",
            style = MaterialTheme.typography.bodySmall
        )
        if (showWithoutPriceFilter) Row {
            Checkbox(checked=searchState.includeWithoutPrice,onCheckedChange=onIncludeWithoutPrice,
                modifier=Modifier.testTag("include-without-price"))
            Text("Prikaži i proizvode bez cene")
        }
    }

    when {
        searchState.isSearching -> item(key = "search-loading") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Text("Pretražujem proizvode…")
            }
        }

        searchState.errorMessage != null -> item(key = "search-error") {
            Column {
                Text(
                    searchState.errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry) { Text("Pokušaj ponovo") }
            }
        }

        searchState.query.length >= 2 && searchState.results.isEmpty() ->
            item(key = "search-empty") {
                Text(if (searchState.includeWithoutPrice) "Nema pronađenih proizvoda."
                    else "Nema rezultata sa aktuelnom cenom. Promeni upit ili uključi proizvode bez cene.")
            }
    }

    items(
        items = searchState.results,
        key = {
            it.productFamilyId ?: it.canonicalProductId ?: it.name
        }
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
                        "Prikaži još (${searchState.results.size}/" +
                            "${searchState.totalElements})"
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
    if (confirmWithoutPrice) AlertDialog(onDismissRequest={ confirmWithoutPrice=false },
        title={ Text("Dodaj proizvod bez cene?") },
        text={ Text("Proizvod poznajemo, ali nemamo aktuelnu cenu. Ne možemo ga uračunati u cenu korpe dok ne pronađemo ponudu.") },
        confirmButton={ TextButton(modifier=Modifier.testTag("confirm-without-price"),onClick={ confirmWithoutPrice=false; onChoose() }) { Text("Dodaj ipak") } },
        dismissButton={ TextButton(onClick={ confirmWithoutPrice=false }) { Text("Nazad na rezultate") } })
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product-result-${product.resultId()}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(product.name, style = MaterialTheme.typography.titleSmall)
            productDetails(product).takeIf(String::isNotBlank)?.let { details ->
                Text(details, style = MaterialTheme.typography.bodySmall)
            }
            ProductPriceSummary(product)
            TextButton(onClick={ detailsExpanded=!detailsExpanded }) { Text(if(detailsExpanded) "Sakrij detalje" else "Detalji") }
            if (detailsExpanded) {
            product.barcode?.let { barcode ->
                Text(
                    "Barkod: $barcode",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            product.categoryName?.let { category ->
                Text(
                    "Kategorija: $category",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (product.variantCount > 1) {
                Text(
                    "${product.variantCount} barkod varijante",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            }
            Button(
                onClick = { if(product.hasUsablePrice) onChoose() else confirmWithoutPrice=true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product-choose-${product.resultId()}")
            ) {
                Text("Izaberi")
            }
        }
    }
}

@Composable
private fun SelectedProductCard(
    product: CanonicalProductSearchItemDto,
    onChange: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Izabran proizvod",
                style = MaterialTheme.typography.labelMedium
            )
            Text(product.name, style = MaterialTheme.typography.titleSmall)
            productDetails(product).takeIf(String::isNotBlank)?.let { details ->
                Text(details, style = MaterialTheme.typography.bodySmall)
            }
            product.barcode?.let { barcode ->
                Text(
                    "Barkod: $barcode",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ProductPriceSummary(product)
            TextButton(onClick = onChange) { Text("Promeni izbor") }
        }
    }
}

@Composable
private fun ProductPriceSummary(product: CanonicalProductSearchItemDto) {
    if (!product.hasUsablePrice) {
        if(product.knownRetailers.isNotEmpty()) Text("Zabeležen kod: ${product.knownRetailers.joinToString()}")
        Text("Nemamo aktuelnu cenu",color=MaterialTheme.colorScheme.error)
    } else {
        product.availability.forEach { offer ->
            Text("${offer.retailerName} · cenovnik ${offer.latestPriceDate}" +
                (offer.minimumEffectivePrice?.let { " · od ${String.format(java.util.Locale.ROOT,"%.2f",it)} RSD" } ?: ""),
                style=MaterialTheme.typography.bodySmall)
        }
    }
}

private fun productDetails(product: CanonicalProductSearchItemDto): String =
    listOfNotNull(
        product.brand,
        product.quantityValue?.let { quantity ->
            "${formatQuantity(quantity)} ${product.baseUnit.orEmpty()}".trim()
        }
    ).joinToString(" • ")

private fun CanonicalProductSearchItemDto.resultId(): String =
    (productFamilyId ?: canonicalProductId)?.toString()
        ?: name.hashCode().toString()
