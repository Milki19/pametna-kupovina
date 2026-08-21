package rs.pametnakupovina.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    onLoadMore: () -> Unit
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
            "Unesi najmanje 2 karaktera. Izbor canonical proizvoda " +
                "odmah potvrđuje stavku, čak i kada barkod nije poznat.",
            style = MaterialTheme.typography.bodySmall
        )
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
                Text("Nema pronađenih proizvoda.")
            }
    }

    items(
        items = searchState.results,
        key = { it.canonicalProductId }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("product-result-${product.canonicalProductId}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
            Text(
                "Poklapanje: ${(product.score * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium
            )
            Button(
                onClick = onChoose,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("product-choose-${product.canonicalProductId}")
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
                "Izabran canonical proizvod",
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
            TextButton(onClick = onChange) { Text("Promeni izbor") }
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
