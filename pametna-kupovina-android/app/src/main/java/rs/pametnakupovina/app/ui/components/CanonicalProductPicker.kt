package rs.pametnakupovina.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.data.network.ProductRetailerAvailabilityDto
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.shortDate
import rs.pametnakupovina.app.ui.distance

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
    showWithoutPriceFilter: Boolean = true,
    // Null skriva izbor „Samo na akciji".
    onOnlyOnSale: ((Boolean) -> Unit)? = null,
    onScan: (() -> Unit)? = null
) {
    item(key = "product-query") {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.picker_query_label)) },
            leadingIcon = { AppIcon(R.drawable.ic_search, contentDescription = null) },
            trailingIcon = when {
                selectedProduct != null -> null
                query.isNotEmpty() -> {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.picker_clear_search))
                        }
                    }
                }
                onScan != null -> {
                    {
                        IconButton(onClick = onScan, modifier = Modifier.testTag("scan-barcode")) {
                            AppIcon(R.drawable.ic_camera, contentDescription = stringResource(R.string.picker_scan_barcode))
                        }
                    }
                }
                else -> null
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
                stringResource(R.string.picker_price_not_stock),
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
                        stringResource(R.string.picker_include_without_price),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (onOnlyOnSale != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = searchState.onlyOnSale,
                        onCheckedChange = onOnlyOnSale,
                        modifier = Modifier.testTag("only-on-sale")
                    )
                    Text(
                        stringResource(R.string.picker_only_on_sale),
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
                Text(stringResource(R.string.picker_searching), style = MaterialTheme.typography.bodyMedium)
            }
        }

        searchState.errorMessage != null -> item(key = "search-error") {
            NoticeBanner(
                text = searchState.errorMessage.asString(),
                tone = StatusTone.ERROR,
                actionLabel = stringResource(R.string.common_retry),
                onAction = onRetry
            )
        }

        searchState.query.length >= 2 && searchState.results.isEmpty() ->
            item(key = "search-empty") {
                Text(
                    when {
                        searchState.onlyOnSale -> stringResource(R.string.picker_no_results_on_sale)
                        searchState.includeWithoutPrice -> stringResource(R.string.picker_no_results)
                        else -> stringResource(R.string.picker_no_results_with_price)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
    }

    searchState.correctedQuery?.let { corrected ->
        item(key = "search-corrected") {
            Text(
                stringResource(R.string.picker_corrected_query, searchState.query.trim(), corrected),
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
            locationKnown = searchState.locationKnown,
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
                        stringResource(R.string.picker_loading_more)
                    } else {
                        stringResource(
                            R.string.picker_show_more,
                            searchState.results.size,
                            searchState.totalElements
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ProductSearchResultCard(
    product: CanonicalProductSearchItemDto,
    locationKnown: Boolean,
    onChoose: () -> Unit
) {
    var detailsExpanded by remember(product.resultId()) { mutableStateOf(false) }
    var confirmWithoutPrice by remember(product.resultId()) { mutableStateOf(false) }
    if (confirmWithoutPrice) {
        AlertDialog(
            onDismissRequest = { confirmWithoutPrice = false },
            title = { Text(stringResource(R.string.picker_add_without_price_title)) },
            text = {
                Text(stringResource(R.string.picker_add_without_price_text))
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm-without-price"),
                    onClick = {
                        confirmWithoutPrice = false
                        onChoose()
                    }
                ) { Text(stringResource(R.string.picker_add_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWithoutPrice = false }) {
                    Text(stringResource(R.string.picker_back_to_results))
                }
            }
        )
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
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
            // Pre izbora, ne posle računanja: tata je izabrao proizvod koji
            // nijedna radnja blizu njega ne prodaje, pa je plan rekao „nema".
            if (locationKnown && product.hasUsablePrice) {
                val meters = product.nearestStoreMeters
                if (meters == null) {
                    StatusPill(stringResource(R.string.picker_not_nearby), StatusTone.WARNING)
                } else {
                    Text(
                        stringResource(R.string.picker_nearest_store, distance(meters / 1000)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (detailsExpanded) {
                listOfNotNull(
                    product.barcode?.let { stringResource(R.string.picker_barcode_line, it) },
                    product.categoryName?.let { stringResource(R.string.picker_category_line, it) },
                    product.variantCount.takeIf { it > 1 }?.let {
                        stringResource(R.string.picker_barcode_variants, it)
                    }
                ).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Sa krupnim slovima „Izaberi" prelazi ispod „Više o proizvodu"
            // umesto da se tekst seče.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(
                        stringResource(if (detailsExpanded) R.string.picker_less else R.string.picker_more_about)
                    )
                }
                Button(
                    onClick = {
                        if (product.hasUsablePrice) onChoose() else confirmWithoutPrice = true
                    },
                    modifier = Modifier.testTag("product-choose-${product.resultId()}")
                ) {
                    Text(stringResource(R.string.picker_choose), maxLines = 1, softWrap = false)
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
                Text(stringResource(R.string.picker_selected), style = MaterialTheme.typography.labelLarge)
            }
            Text(product.name, style = MaterialTheme.typography.titleMedium)
            listOfNotNull(
                productDetails(product).takeIf(String::isNotBlank),
                product.barcode?.let { stringResource(R.string.picker_barcode_line, it) }
            ).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            ProductPriceSummary(product, showAll = true)
            TextButton(onClick = onChange) { Text(stringResource(R.string.picker_change_selection)) }
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
                stringResource(R.string.picker_recorded_at, product.knownRetailers.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            stringResource(R.string.picker_no_current_price),
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
    val shown = if (showAll) offers else shortList(offers)
    BoxWithConstraints {
        // Datum je tih podatak: kad nema mesta (krupna slova, uvećan ekran),
        // odlazi on, a ne ime lanca.
        val roomForDate = maxWidth > 300.dp * LocalDensity.current.fontScale
        Column {
            shown.forEach { offer ->
                val discount = offer.discountPercent.takeUnless { offer.priceNeedsCheck }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        offer.retailerName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (roomForDate || offer.priceNeedsCheck) {
                        Text(
                            if (offer.priceNeedsCheck) {
                                stringResource(R.string.picker_check_price)
                            } else {
                                shortDate(offer.latestPriceDate)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (offer.priceNeedsCheck) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(start = AppSpacing.sm)
                        )
                    }
                    offer.minimumEffectivePrice?.let {
                        Text(
                            stringResource(R.string.picker_price_from, money(it)),
                            style = MaterialTheme.typography.labelLarge,
                            // Akcijska cena u boji svoje oznake popusta.
                            color = if (discount != null) {
                                saleTextColor(discount)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(start = AppSpacing.sm)
                        )
                    }
                }
                if (discount != null) {
                    SaleLine(
                        discountPercent = discount,
                        regularPrice = offer.saleRegularPrice,
                        saleEndDate = offer.saleEndDate,
                        modifier = Modifier.padding(bottom = AppSpacing.xs)
                    )
                }
            }
            if (offers.size > shown.size) {
                Text(
                    pluralStringResource(
                        R.plurals.picker_more_chains,
                        offers.size - shown.size,
                        offers.size - shown.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

/**
 * Tri najjeftinija lanca, a lanac na akciji uvek među njima: sa filterom
 * „Samo proizvodi na akciji" proizvod je tu zbog akcije, pa je i prikazuje.
 */
internal fun shortList(
    offers: List<ProductRetailerAvailabilityDto>
): List<ProductRetailerAvailabilityDto> {
    val first = offers.take(3)
    if (first.any { it.onSaleHere() }) return first
    val sale = offers.firstOrNull { it.onSaleHere() } ?: return first
    return first.take(2) + sale
}

private fun ProductRetailerAvailabilityDto.onSaleHere() = discountPercent != null && !priceNeedsCheck
