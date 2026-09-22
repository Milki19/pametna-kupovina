package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.RectangleShape
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
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.decimal
import rs.pametnakupovina.app.ui.items
import rs.pametnakupovina.app.ui.plural

@Composable
fun ShoppingListScreen(
    onBack: () -> Unit,
    onOpenMatching: (Long) -> Unit,
    onOpenProduct: (Long) -> Unit,
    viewModel: ShoppingListViewModel = hiltViewModel(),
    productSearchViewModel: ProductSearchViewModel = hiltViewModel()
) {
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
                message = message,
                actionLabel = "U redu",
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
                message = "Obrisano: ${item.name}",
                actionLabel = "Vrati",
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
                title = "Moj spisak",
                onBack = onBack,
                subtitle = when {
                    state.isOffline -> "Bez mreže, izmene čekaju slanje"
                    state.items.isNotEmpty() -> items(state.items.size)
                    else -> null
                }
            )
        },
        bottomBar = {
            if (!state.isInitialLoading) {
                BottomActionBar {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        SecondaryAction(
                            text = "Dodaj stavku",
                            icon = R.drawable.ic_add,
                            onClick = { openEditor(null) },
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryAction(
                            text = "Nalepi spisak",
                            icon = R.drawable.ic_content_paste,
                            onClick = { showPasteDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    PrimaryActionButton(
                        text = if (state.isSyncing) "Šaljem spisak…" else "Izračunaj",
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
                LoadingState("Učitavanje spiska…")
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
            )
        ) {
            state.errorMessage?.let { message ->
                item(key = "error") {
                    NoticeBanner(
                        text = message,
                        tone = StatusTone.ERROR,
                        actionLabel = "Pokušaj ponovo",
                        onAction = viewModel::refresh,
                        modifier = Modifier.padding(bottom = AppSpacing.md)
                    )
                }
            }

            if (state.items.isEmpty()) {
                item(key = "empty") { EmptyList() }
            } else {
                itemsIndexed(state.items, key = { _, item -> item.localId }) { index, item ->
                    DraftItemRow(
                        item = item,
                        isFirst = index == 0,
                        isLast = index == state.items.lastIndex,
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
            title = { Text("Neke stavke nisu poslate") },
            text = {
                Text(
                    "Server nije prihvatio: ${skipped.names.joinToString(", ")}. " +
                        "Razlog piše ispod stavke. Možeš da računaš bez " +
                        plural(skipped.names.size, "nje", "njih", "njih") +
                        " ili da prvo " + plural(skipped.names.size, "izmeniš stavku.", "izmeniš stavke.", "izmeniš stavke.")
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.calculateWithoutSkipped(onOpenMatching) }) {
                    Text("Računaj bez " + plural(skipped.names.size, "nje", "njih", "njih"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSkipped) { Text("Izmeni") }
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
private fun SecondaryAction(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = AppSpacing.md),
        modifier = modifier.heightIn(min = 52.dp)
    ) {
        AppIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(AppSpacing.xs))
        // Uz uvećano pismo „Dodaj stavku" se seklo na „Dodaj": natpis sme u
        // drugi red, dugme naraste.
        Text(text, textAlign = TextAlign.Center)
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
        Text("Spisak je prazan", style = MaterialTheme.typography.titleLarge)
        Text(
            "Nalepi spisak iz poruke ili beleške, ili dodaj stavku po stavku.",
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
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenProduct: (Long) -> Unit
) {
    val corner = 16.dp
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(corner)
        isFirst -> RoundedCornerShape(topStart = corner, topEnd = corner)
        isLast -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
        else -> RectangleShape
    }
    val attention = if (item.syncError != null) {
        "Nije poslato" to StatusTone.ERROR
    } else {
        draftAttention(item.matchingStatus)
    }

    Surface(
        onClick = onEdit,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
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
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    draftRuleLabel(item)?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    attention?.let { (text, tone) ->
                        Spacer(Modifier.size(AppSpacing.xs))
                        StatusPill(text, tone)
                    }
                    item.syncError?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    draftAmountLabel(item),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = AppSpacing.sm)
                )
                item.canonicalProductId?.let { canonicalProductId ->
                    IconButton(onClick = { onOpenProduct(canonicalProductId) }) {
                        AppIcon(R.drawable.ic_local_offer, contentDescription = "Cene za ${item.name}")
                    }
                }
                IconButton(onClick = onDelete) {
                    AppIcon(
                        R.drawable.ic_delete,
                        contentDescription = "Obriši ${item.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!isLast) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = AppSpacing.lg),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
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
internal fun draftRuleLabel(item: DraftItemEntity): String? = when (item.matchingRule) {
    // A pasted line that names one product has no barcode until one is found.
    ShoppingItemRuleDto.EXACT_PRODUCT.name ->
        "Tačan barkod".takeIf { item.canonicalProductId != null || !item.barcode.isNullOrBlank() }
    ShoppingItemRuleDto.PRODUCT_FAMILY.name -> "Isti proizvod, sve varijante"
    else -> {
        val category = item.category?.trim().orEmpty()
        listOfNotNull(
            category
                .takeUnless { it.isEmpty() || it.equals(item.name.trim(), ignoreCase = true) }
                ?.let { "kategorija $it" },
            item.requiredBrand?.takeIf(String::isNotBlank)?.let { "brend $it" }
        ).joinToString(", ")
            .replaceFirstChar { it.uppercaseChar() }
            .ifEmpty { null }
    }
}

private fun draftAttention(status: String): Pair<String, StatusTone>? = when (status) {
    "NEEDS_CONFIRMATION" -> "Treba tvoja potvrda" to StatusTone.WARNING
    "UNMATCHED" -> "Nije pronađeno" to StatusTone.ERROR
    else -> null
}
