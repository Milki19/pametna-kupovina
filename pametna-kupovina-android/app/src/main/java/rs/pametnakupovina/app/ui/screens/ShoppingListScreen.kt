package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.ui.ProductSearchViewModel
import rs.pametnakupovina.app.ui.ShoppingListViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.BottomActionBar
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.TonalActionButton
import rs.pametnakupovina.app.ui.components.cardBorder
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.ui.decimal

@Composable
fun ShoppingListScreen(
    onOpenMatching: (Long) -> Unit,
    onOpenProduct: (Long) -> Unit,
    viewModel: ShoppingListViewModel = hiltViewModel(),
    productSearchViewModel: ProductSearchViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val productSearchState by productSearchViewModel.uiState
        .collectAsStateWithLifecycle()
    var editedItem by remember { mutableStateOf<DraftItemEntity?>(null) }
    var showItemEditor by rememberSaveable { mutableStateOf(false) }
    var showPasteDialog by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A snackbar rather than a card above the list, so reporting what was
    // pasted never pushes the buttons out from under the reader's thumb.
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbar.showSnackbar(
                message = message.resolve(resources),
                actionLabel = resources.getString(R.string.list_snackbar_ok),
                duration = SnackbarDuration.Long
            )
            viewModel.clearNotice()
        }
    }

    fun openEditor(item: DraftItemEntity?) {
        productSearchViewModel.clear()
        if (item != null && item.matchingRule != ShoppingItemRuleDto.FLEXIBLE_CATEGORY.name) {
            productSearchViewModel.updateQuery(item.name)
        }
        editedItem = item
        showItemEditor = true
    }

    fun delete(item: DraftItemEntity) {
        viewModel.deleteItem(item)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = resources.getString(R.string.list_snackbar_deleted, item.name),
                actionLabel = resources.getString(R.string.list_snackbar_undo),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreItem(item)
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.list_title),
                subtitle = when {
                    state.isOffline -> stringResource(R.string.list_subtitle_offline)
                    state.items.isNotEmpty() ->
                        pluralStringResource(R.plurals.count_items, state.items.size, state.items.size)
                    else -> null
                }
            )
        },
        bottomBar = {
            if (!state.isInitialLoading) {
                BottomActionBar {
                    PrimaryActionButton(
                        text = stringResource(
                            if (state.isSyncing) R.string.list_action_sending else R.string.list_action_calculate
                        ),
                        enabled = state.items.isNotEmpty() && !state.isSyncing,
                        onClick = { viewModel.prepareMatching(onOpenMatching) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (state.isInitialLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LoadingState(stringResource(R.string.list_loading))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = AppSpacing.lg,
                vertical = AppSpacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    TonalActionButton(
                        text = stringResource(R.string.list_add_item),
                        icon = R.drawable.ic_add,
                        primary = true,
                        onClick = { openEditor(null) },
                        modifier = Modifier.weight(1f)
                    )
                    TonalActionButton(
                        text = stringResource(R.string.list_paste_list),
                        icon = R.drawable.ic_content_paste,
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            state.errorMessage?.let { message ->
                item(key = "error") {
                    NoticeBanner(
                        text = message.asString(),
                        tone = StatusTone.ERROR,
                        actionLabel = stringResource(R.string.common_retry),
                        onAction = viewModel::refresh
                    )
                }
            }

            if (state.items.isEmpty()) {
                item(key = "empty") { EmptyList() }
            } else {
                item(key = "header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = AppSpacing.sm)
                    ) {
                        Text(stringResource(R.string.list_items_header), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(AppSpacing.sm))
                        StatusPill(state.items.size.toString())
                    }
                }
                items(state.items, key = { it.localId }) { item ->
                    DraftItemRow(
                        item = item,
                        onEdit = { openEditor(item) },
                        onDelete = { delete(item) },
                        onOpenProduct = onOpenProduct
                    )
                }
            }
        }
    }

    if (showItemEditor) {
        ItemEditorDialog(
            item = editedItem,
            productSearchState = productSearchState,
            onSearchQueryChange = productSearchViewModel::updateQuery,
            onClearProductSearch = productSearchViewModel::clear,
            onRetryProductSearch = productSearchViewModel::retry,
            onLoadMoreProducts = productSearchViewModel::loadNextPage,
            onIncludeWithoutPrice = productSearchViewModel::includeWithoutPrice,
            onScanBarcode = { productSearchViewModel.scanBarcode(context) },
            onDismiss = {
                productSearchViewModel.clear()
                showItemEditor = false
            },
            onSave = { input ->
                val current = editedItem
                val close = {
                    productSearchViewModel.clear()
                    showItemEditor = false
                }
                if (current == null) {
                    viewModel.addItem(input, close)
                } else {
                    viewModel.updateItem(current, input, close)
                }
            }
        )
    }

    state.skippedItems?.let { skipped ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSkipped,
            title = { Text(stringResource(R.string.list_skipped_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.list_skipped_text,
                        skipped.names.size,
                        skipped.names.joinToString(", ")
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.calculateWithoutSkipped(onOpenMatching) }) {
                    Text(pluralStringResource(R.plurals.list_skipped_calculate_without, skipped.names.size))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSkipped) { Text(stringResource(R.string.list_skipped_edit)) }
            }
        )
    }

    if (showPasteDialog) {
        PasteItemsDialog(
            onDismiss = { showPasteDialog = false },
            onSave = { text ->
                viewModel.pasteItems(text) { showPasteDialog = false }
            }
        )
    }
}

@Composable
private fun EmptyList() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        AppIcon(
            R.drawable.ic_content_paste,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(stringResource(R.string.list_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.list_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * What to buy and how much on one line. Anything else appears only when it
 * matters: a non-default way of choosing, or a status that needs the reader,
 * so twenty rows of "recognised" no longer bury the one that is not.
 */
@Composable
private fun DraftItemRow(
    item: DraftItemEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenProduct: (Long) -> Unit
) {
    val attention = if (item.syncError != null) {
        stringResource(R.string.list_status_not_sent) to StatusTone.ERROR
    } else {
        draftAttention(item.matchingStatus)?.let { (text, tone) -> stringResource(text) to tone }
    }

    Surface(
        onClick = onEdit,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.padding(
                    start = AppSpacing.lg,
                    end = AppSpacing.xs,
                    top = AppSpacing.md,
                    bottom = AppSpacing.md
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    attention?.let { (text, tone) ->
                        StatusPill(text, tone)
                        Spacer(Modifier.size(AppSpacing.xs))
                    }
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    draftRuleLabel(item)?.let { label ->
                        Text(
                            label.asString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item.syncError?.let { reason ->
                        Text(
                            // Blank when the server gave no reason of its own.
                            reason.ifBlank { stringResource(R.string.list_sync_error_rejected) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = AppSpacing.xs)
                ) {
                    Text(
                        draftAmountLabel(item),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
                    )
                }
                item.canonicalProductId?.let { canonicalProductId ->
                    IconButton(onClick = { onOpenProduct(canonicalProductId) }) {
                        AppIcon(R.drawable.ic_local_offer, contentDescription = stringResource(R.string.list_cd_prices_for, item.name))
                    }
                }
                IconButton(onClick = onDelete) {
                    AppIcon(
                        R.drawable.ic_delete,
                        contentDescription = stringResource(R.string.list_cd_delete, item.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

internal fun draftAmountLabel(item: DraftItemEntity): String =
    item.targetQuantity?.let { amountLabel(it * item.quantity, item.requiredBaseUnit) }
        ?: "${decimal(item.quantity)} kom"

/**
 * Says how an item is chosen only when that differs from the default of
 * taking the best offer, which is what most of a pasted list is.
 */
internal fun draftRuleLabel(item: DraftItemEntity): UiText? = when (item.matchingRule) {
    // A pasted line that names one product has no barcode until one is found.
    ShoppingItemRuleDto.EXACT_PRODUCT.name ->
        uiText(R.string.list_rule_exact_barcode)
            .takeIf { item.canonicalProductId != null || !item.barcode.isNullOrBlank() }
    ShoppingItemRuleDto.PRODUCT_FAMILY.name -> uiText(R.string.list_rule_same_product)
    else -> {
        val category = item.category?.trim().orEmpty()
            .takeUnless { it.isEmpty() || it.equals(item.name.trim(), ignoreCase = true) }
        val brand = item.requiredBrand?.takeIf(String::isNotBlank)
        when {
            category != null && brand != null -> uiText(R.string.list_rule_category_brand, category, brand)
            category != null -> uiText(R.string.list_rule_category, category)
            brand != null -> uiText(R.string.list_rule_brand, brand)
            else -> null
        }
    }
}

private fun draftAttention(status: String): Pair<Int, StatusTone>? = when (status) {
    "NEEDS_CONFIRMATION" -> R.string.list_status_needs_confirmation to StatusTone.WARNING
    "UNMATCHED" -> R.string.list_status_not_found to StatusTone.ERROR
    else -> null
}
