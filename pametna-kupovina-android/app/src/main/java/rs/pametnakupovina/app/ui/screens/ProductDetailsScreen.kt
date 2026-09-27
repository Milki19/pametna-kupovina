package rs.pametnakupovina.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import rs.pametnakupovina.app.data.network.ProductReportReasonDto
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CanonicalProductOfferDto
import rs.pametnakupovina.app.data.network.CanonicalProductPricePointDto
import rs.pametnakupovina.app.alerts.bestPrice
import rs.pametnakupovina.app.ui.ProductDetailsViewModel
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.cardBorder
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import rs.pametnakupovina.app.ui.components.LetterTile
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.asUiText
import rs.pametnakupovina.app.text.uiText

@Composable
fun ProductDetailsScreen(
    onBack: () -> Unit,
    viewModel: ProductDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val watching by viewModel.watching.collectAsStateWithLifecycle()
    // Bez dozvole praćenje i dalje radi; samo obaveštenje ne izlazi.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.setWatching(true) }
    val watch: (Boolean) -> Unit = { on ->
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setWatching(on)
        }
    }
    var showReport by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(state.reportMessage) {
        state.reportMessage?.let {
            snackbar.showSnackbar(it.resolve(resources))
            viewModel.clearReportMessage()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.product_title),
                onBack = onBack,
                actions = {
                    if (state.product != null) {
                        TextButton(onClick = { showReport = true }) { Text(stringResource(R.string.product_report_error)) }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> LoadingState(stringResource(R.string.product_loading_offers))
                state.errorMessage != null -> ErrorState(
                    title = stringResource(R.string.product_prices_unavailable),
                    message = requireNotNull(state.errorMessage).asString(),
                    onRetry = viewModel::load
                )
                state.product != null -> ProductDetailsContent(
                    requireNotNull(state.product),
                    watching = watching,
                    onWatch = watch
                )
            }
        }
    }

    if (showReport) {
        ReportDialog(
            sending = state.isReporting,
            onDismiss = { showReport = false },
            onSend = { reason, note -> viewModel.report(reason, note) { showReport = false } }
        )
    }
}

private val ReportReasons = listOf(
    ProductReportReasonDto.WRONG_PRICE to R.string.product_report_reason_wrong_price,
    ProductReportReasonDto.NOT_SAME_PRODUCT to R.string.product_report_reason_not_same,
    ProductReportReasonDto.OTHER to R.string.product_report_reason_other
)

/** What is wrong, in one tap, and a note only if the reader wants to add one. */
@Composable
private fun ReportDialog(
    sending: Boolean,
    onDismiss: () -> Unit,
    onSend: (ProductReportReasonDto, String) -> Unit
) {
    var reason by rememberSaveable { mutableStateOf<ProductReportReasonDto?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.product_report_error)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                ReportReasons.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == value, onClick = { reason = value })
                    ) {
                        RadioButton(selected = reason == value, onClick = { reason = value })
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 500) note = it },
                    label = { Text(stringResource(R.string.product_report_note)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason != null && !sending,
                onClick = { reason?.let { onSend(it, note) } }
            ) {
                Text(
                    stringResource(
                        if (sending) R.string.product_report_sending else R.string.product_report_send
                    )
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/**
 * Cheapest first, so the answer to "where" is the top row. A price to be
 * checked goes last however low it is: it is most likely for one piece or one
 * kilogram of a bigger pack.
 */
@Composable
private fun ProductDetailsContent(
    product: CanonicalProductDetailsDto,
    watching: Boolean,
    onWatch: (Boolean) -> Unit
) {
    val offers = product.offers.sortedWith(
        compareBy({ it.priceNeedsCheck }, { isCaseOf(it, product) }, { it.effectivePrice })
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
            ProductHeader(product, offers, watching, onWatch)
        }

        item(key = "offers-header") {
            SectionHeader(stringResource(R.string.product_current_offers), trailing = offers.size.toString())
        }
        if (offers.isEmpty()) {
            item(key = "no-offers") {
                NoticeBanner(text = stringResource(R.string.product_no_offers))
            }
        } else {
            itemsIndexed(offers) { index, offer ->
                val cheapest = index == 0 && offers.size > 1 && !offer.priceNeedsCheck &&
                    !isCaseOf(offer, product)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = if (cheapest) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else cardBorder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OfferRow(
                        offer,
                        product = product,
                        cheapest = cheapest,
                        caseOf = product.packageCount.takeIf { isCaseOf(offer, product) }
                    )
                }
            }
        }

        if (product.priceHistory.isNotEmpty()) {
            item(key = "history-header") { SectionHeader(stringResource(R.string.product_price_history)) }
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
        border = cardBorder,
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
private fun OfferRow(
    offer: CanonicalProductOfferDto,
    product: CanonicalProductDetailsDto,
    cheapest: Boolean,
    caseOf: Int? = null
) {
    Row(
        modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        LetterTile(offer.retailerName, modifier = Modifier.padding(end = AppSpacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(offer.retailerName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.product_offer_scope_and_date, offerScope(offer), shortDate(offer.priceDate)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (
                offer.discountedPrice != null &&
                offer.regularPrice != null &&
                offer.discountedPrice < offer.regularPrice
            ) {
                Text(
                    stringResource(R.string.product_offer_sale, money(offer.regularPrice)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            caseOf?.let { single ->
                Text(
                    stringResource(
                        R.string.product_offer_case,
                        offer.packageCount / single,
                        money(offer.effectivePrice * single / offer.packageCount)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (offer.priceNeedsCheck) {
                Text(
                    stringResource(R.string.product_offer_suspicious),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusPill(stringResource(R.string.product_check_price), StatusTone.WARNING)
            }
            if (cheapest) {
                StatusPill(stringResource(R.string.product_best_price), StatusTone.POSITIVE)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                money(offer.effectivePrice),
                style = MaterialTheme.typography.titleLarge,
                color = if (cheapest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            offerUnitPriceLabel(offer, product)?.let {
                Text(
                    it.asString(),
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

/**
 * Ime, pakovanje i barkod, pa najniža, prosečna i najviša cena među lancima
 * (bez cena za proveru i gajbi), i praćenje cene — sve u jednoj kartici.
 */
@Composable
private fun ProductHeader(
    product: CanonicalProductDetailsDto,
    offers: List<CanonicalProductOfferDto>,
    watching: Boolean,
    onWatch: (Boolean) -> Unit
) {
    val comparable = offers.filterNot { it.priceNeedsCheck || isCaseOf(it, product) }
    // Lanci, ne prodavnice: devet METRO objekata je jedan lanac.
    val chains = comparable.map { it.retailerName }.distinct().size
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            product.brand?.let {
                Text(
                    it.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(product.name, style = MaterialTheme.typography.titleLarge)
            productMetadata(product).takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.product_prices_for_date, date(product.requestedDate)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (chains > 1) {
                val lowest = comparable.minBy { it.effectivePrice }
                val highest = comparable.maxBy { it.effectivePrice }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                        .padding(AppSpacing.xs)
                ) {
                    PriceStat(stringResource(R.string.product_stat_lowest), lowest.effectivePrice, lowest.retailerName, highlighted = true, Modifier.weight(1f))
                    PriceStat(
                        stringResource(R.string.product_stat_average),
                        comparable.map { it.effectivePrice }.average(),
                        pluralStringResource(R.plurals.product_chain_count, chains, chains),
                        highlighted = false,
                        Modifier.weight(1f)
                    )
                    PriceStat(stringResource(R.string.product_stat_highest), highest.effectivePrice, highest.retailerName, highlighted = false, Modifier.weight(1f))
                }
            }
            if (bestPrice(product) != null || watching) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.product_watch_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.product_watch_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = watching,
                        onCheckedChange = onWatch,
                        modifier = Modifier.testTag("watch-price")
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceStat(
    label: String,
    price: Double,
    note: String,
    highlighted: Boolean,
    modifier: Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) MaterialTheme.colorScheme.surfaceContainerLowest else androidx.compose.ui.graphics.Color.Transparent,
        shadowElevation = if (highlighted) 1.dp else 0.dp,
        modifier = modifier.fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(AppSpacing.sm)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                money(price),
                style = MaterialTheme.typography.titleSmall,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/**
 * The price per litre, kilo or piece, read from the size of what is sold, so
 * every chain is compared the same way. A chain's own figure is often per
 * piece ("jed. 85,99" for a half-litre bottle) and is shown only when the size
 * is unknown.
 */
internal fun offerUnitPriceLabel(offer: CanonicalProductOfferDto, product: CanonicalProductDetailsDto): UiText? {
    val quantity = product.quantityValue?.takeIf { it > 0 }
    val unit = product.baseUnit
    if (quantity == null || unit == null) {
        return offer.unitPrice?.let { uiText(R.string.product_unit_price_chain, money(it)) }
    }
    val amount = quantity / product.packageCount.coerceAtLeast(1) * offer.packageCount.coerceAtLeast(1)
    return when (unit) {
        "g" -> "${money(offer.effectivePrice * 1000 / amount)}/kg".asUiText()
        "ml" -> "${money(offer.effectivePrice * 1000 / amount)}/l".asUiText()
        "piece" -> if (amount > 1) "${money(offer.effectivePrice / amount)}/kom".asUiText() else null
        else -> offer.unitPrice?.let { uiText(R.string.product_unit_price_chain, money(it)) }
    }
}

/** METRO's case of twenty under one bottle's barcode: priced for all twenty. */
internal fun isCaseOf(offer: CanonicalProductOfferDto, product: CanonicalProductDetailsDto): Boolean =
    offer.packageCount > product.packageCount

@Composable
private fun productMetadata(product: CanonicalProductDetailsDto): String =
    listOfNotNull(
        product.brand,
        product.quantityValue?.let { quantity ->
            product.baseUnit?.let { amountLabel(quantity, it, product.packageCount) }
        },
        product.barcode?.let { stringResource(R.string.product_barcode_meta, it) }
    ).joinToString(" · ")

@Composable
private fun offerScope(offer: CanonicalProductOfferDto): String = when (offer.priceScope) {
    "STORE" -> offer.storeName ?: stringResource(R.string.product_scope_one_store)
    "STORE_FORMAT" -> stringResource(
        R.string.product_scope_format,
        offer.storeFormatName ?: stringResource(R.string.product_scope_format_unknown)
    )
    else -> stringResource(R.string.product_scope_all_stores)
}
