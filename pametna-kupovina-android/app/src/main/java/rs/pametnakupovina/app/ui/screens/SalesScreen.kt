package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.SaleItemDto
import rs.pametnakupovina.app.data.network.SaleSortDto
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.ui.SalesViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.DiscountBadge
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.components.StruckPrice
import rs.pametnakupovina.app.ui.components.TonalActionButton
import rs.pametnakupovina.app.ui.components.cardBorder
import rs.pametnakupovina.app.ui.components.discountColors
import rs.pametnakupovina.app.ui.components.saleTextColor
import rs.pametnakupovina.app.ui.distance
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.shortDate

private val SortLabels = listOf(
    SaleSortDto.DISCOUNT to R.string.sale_sort_discount,
    SaleSortDto.SAVING to R.string.sale_sort_saving,
    SaleSortDto.PRICE to R.string.sale_sort_price
)

/**
 * Šta je danas na akciji, najveći popust prvi. Sa lokacijom samo lanci koji
 * imaju radnju u blizini; proizvod ide na spisak, a plan bira radnju.
 */
@Composable
fun SalesScreen(
    onBack: () -> Unit,
    onOpenProduct: (Long) -> Unit,
    viewModel: SalesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.sale_title),
                onBack = onBack,
                subtitle = if (state.nearby) stringResource(R.string.sale_nearby_subtitle) else null
            )
        }
    ) { padding ->
        when {
            state.loading && state.items.isEmpty() -> Box(Modifier.padding(padding)) {
                LoadingState(stringResource(R.string.sale_loading))
            }

            state.errorMessage != null && state.items.isEmpty() -> Box(Modifier.padding(padding)) {
                ErrorState(
                    title = stringResource(R.string.sale_title),
                    message = state.errorMessage!!.asString(),
                    onRetry = viewModel::retry
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("sales-list"),
                contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                state.notice?.let { notice ->
                    item(key = "notice") {
                        NoticeBanner(
                            text = notice.asString(),
                            tone = StatusTone.POSITIVE,
                            actionLabel = stringResource(R.string.dashboard_ok),
                            onAction = viewModel::dismissNotice
                        )
                    }
                }

                item(key = "sort") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        items(SortLabels, key = { it.first.name }) { (sort, label) ->
                            FilterChip(
                                selected = state.sort == sort,
                                onClick = { viewModel.chooseSort(sort) },
                                label = { Text(stringResource(label)) },
                                modifier = Modifier.testTag("sale-sort-${sort.name.lowercase()}")
                            )
                        }
                    }
                }

                if (state.categories.isNotEmpty()) {
                    item(key = "categories") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            item(key = "all") {
                                FilterChip(
                                    selected = state.category == null,
                                    onClick = { viewModel.chooseCategory(null) },
                                    label = { Text(stringResource(R.string.sale_all_categories)) },
                                    modifier = Modifier.testTag("sale-category-all")
                                )
                            }
                            items(state.categories, key = { it.code }) { category ->
                                FilterChip(
                                    selected = state.category == category.code,
                                    onClick = { viewModel.chooseCategory(category.code) },
                                    label = {
                                        Text(stringResource(R.string.sale_category_chip, category.name, category.productCount))
                                    }
                                )
                            }
                        }
                    }
                }

                item(key = "count") {
                    Text(
                        if (state.loading) {
                            stringResource(R.string.sale_loading)
                        } else {
                            pluralStringResource(
                                R.plurals.sale_count,
                                state.totalElements.toInt(),
                                state.totalElements.toInt()
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!state.loading && state.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(
                                if (state.nearby) R.string.sale_empty_nearby else R.string.sale_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(state.items, key = { it.productFamilyId }) { item ->
                    SaleCard(
                        item = item,
                        added = item.productFamilyId in state.added,
                        onAdd = { viewModel.addToList(item) },
                        onOpen = item.canonicalProductId?.let { id -> { onOpenProduct(id) } }
                    )
                }

                state.errorMessage?.let { message ->
                    item(key = "more-error") {
                        NoticeBanner(
                            text = message.asString(),
                            tone = StatusTone.ERROR,
                            actionLabel = stringResource(R.string.common_retry),
                            onAction = viewModel::loadMore
                        )
                    }
                }

                if (state.hasNext) {
                    item(key = "more") {
                        OutlinedButton(
                            enabled = !state.loadingMore,
                            onClick = viewModel::loadMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sale-load-more")
                        ) {
                            Text(
                                if (state.loadingMore) {
                                    stringResource(R.string.picker_loading_more)
                                } else {
                                    stringResource(R.string.picker_show_more, state.items.size, state.totalElements)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bela kartica sa trakom na vrhu u boji popusta (kao kartica plana), cenom
 * u istoj boji i precrtanom redovnom cenom.
 */
@Composable
private fun SaleCard(
    item: SaleItemDto,
    added: Boolean,
    onAdd: () -> Unit,
    onOpen: (() -> Unit)?
) {
    val (stripe, _) = discountColors(item.discountPercent)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sale-${item.productFamilyId}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(stripe)
            )
            Column(
                modifier = Modifier.padding(
                    start = AppSpacing.lg,
                    end = AppSpacing.md,
                    top = AppSpacing.md,
                    bottom = AppSpacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    DiscountBadge(item.discountPercent, large = true)
                    item.saleEndDate?.let {
                        Text(
                            stringResource(R.string.sale_until, shortDate(it)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                saleDetails(item).takeIf(String::isNotBlank)?.let { details ->
                    Text(
                        details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        money(item.salePrice),
                        style = MaterialTheme.typography.headlineSmall,
                        color = saleTextColor(item.discountPercent)
                    )
                    StruckPrice(item.regularPrice)
                }
                Text(
                    listOfNotNull(
                        stringResource(R.string.sale_at_chain, item.retailerName),
                        item.nearestStoreMeters?.let {
                            stringResource(R.string.sale_nearest_store, distance(it / 1000))
                        },
                        item.otherChainCount.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.sale_other_chains, it, it)
                        }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Sa krupnim slovima dugmad prelaze jedno ispod drugog
                // umesto da se tekst seče.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    if (onOpen != null) {
                        TextButton(onClick = onOpen) {
                            Text(stringResource(R.string.sale_all_prices))
                        }
                    } else {
                        Box(Modifier.size(0.dp))
                    }
                    TonalActionButton(
                        text = stringResource(if (added) R.string.sale_added else R.string.sale_add_to_list),
                        onClick = onAdd,
                        enabled = !added,
                        icon = if (added) R.drawable.ic_check else R.drawable.ic_add,
                        primary = !added,
                        modifier = Modifier.testTag("sale-add-${item.productFamilyId}")
                    )
                }
            }
        }
    }
}

private fun saleDetails(item: SaleItemDto): String =
    listOfNotNull(
        item.brand,
        item.quantityValue?.let { quantity ->
            item.baseUnit?.let { amountLabel(quantity, it, item.packageCount) }
        }
    ).joinToString(" · ")

/**
 * Prečica sa početnog ekrana: bela kartica sa ikonom etikete, kao ostale
 * kartice, a ne nova tamna kartica pored spiska.
 */
@Composable
fun SalesShortcutCard(onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("open-sales")
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            val (container, content) = discountColors(40)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = container,
                contentColor = content,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_local_offer, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.sale_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.sale_shortcut_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppIcon(R.drawable.ic_chevron_right, contentDescription = null)
        }
    }
}
