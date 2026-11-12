package rs.pametnakupovina.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import rs.pametnakupovina.app.ui.components.enter
import rs.pametnakupovina.app.ui.components.listBottomPadding
import rs.pametnakupovina.app.ui.components.pressScale
import rs.pametnakupovina.app.ui.components.rememberEntrance
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
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.IconButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.PastedListParser
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.MAX_ITEM_QUANTITY
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.local.SyncState
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.ui.ProductSearchViewModel
import rs.pametnakupovina.app.ui.ShoppingListViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.GlassCard
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.TitleAction
import rs.pametnakupovina.app.ui.components.LocalBottomBarInset
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.ui.decimal
import androidx.compose.ui.platform.LocalDensity

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
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var duplicatesExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Zajednički spisak: ono što je ukućanin dodao vidi se po povratku.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshOnReturn()
        onPauseOrDispose { }
    }

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

    // Stavka se prvo skupi i klizne levo, pa se tek onda briše.
    var removingId by remember { mutableStateOf<Long?>(null) }
    // Stavke viđene do sada; one koje stignu kasnije (nova, vraćena) ulaze sa iskakanjem.
    val seen = remember { mutableSetOf<Long>() }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(state.items, state.isInitialLoading) {
        if (!state.isInitialLoading) {
            seen += state.items.map { it.localId }
            settled = true
        }
    }
    val entrance = rememberEntrance(state.isInitialLoading)
    val actionBottom = listBottomPadding() - AppSpacing.sm
    // Dugme raste sa slovima, pa se njegova visina meri umesto da se pretpostavlja.
    var actionHeight by remember { mutableStateOf(60.dp) }
    val density = LocalDensity.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.list_title),
                subtitle = when {
                    state.isOffline -> stringResource(R.string.list_subtitle_offline)
                    state.items.isNotEmpty() ->
                        pluralStringResource(R.plurals.count_items, state.items.size, state.items.size)
                    else -> null
                },
                actions = {
                    TitleAction(
                        text = stringResource(R.string.list_paste_list),
                        icon = R.drawable.ic_content_paste,
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.testTag("paste-list")
                    )
                }
            )
        },
        snackbarHost = {
            // Iznad dugmeta „Izračunaj", koje stoji iznad donje trake.
            SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalBottomBarInset.current + actionHeight))
        }
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.lg,
                    end = AppSpacing.lg,
                    top = AppSpacing.md,
                    // Spisak skroluje ispod dugmeta i donje trake.
                    bottom = actionBottom + actionHeight + AppSpacing.lg
                )
            ) {
                item(key = "add") {
                    AddItemRow(
                        onClick = { openEditor(null) },
                        modifier = Modifier
                            .enter(entrance)
                            .padding(bottom = AppSpacing.lg)
                    )
                }

                state.errorMessage?.let { message ->
                    item(key = "error") {
                        NoticeBanner(
                            text = message.asString(),
                            tone = StatusTone.ERROR,
                            actionLabel = stringResource(R.string.common_retry),
                            onAction = viewModel::refresh,
                            modifier = Modifier.padding(bottom = AppSpacing.lg)
                        )
                    }
                }

                if (state.items.isEmpty()) {
                    item(key = "empty") { EmptyList(Modifier.enter(entrance, 1)) }
                }
                // Ista stavka više puta: jedna niska traka sa „Spoji sve“, grupe tek na dodir
                // (šesti test, stavka 13), da stavke ostanu na prvom ekranu.
                val duplicates = duplicateGroups(state.items)
                if (duplicates.isNotEmpty()) {
                    item(key = "duplicates") {
                        DuplicateStrip(
                            groups = duplicates,
                            expanded = duplicatesExpanded,
                            onToggle = { duplicatesExpanded = !duplicatesExpanded },
                            onMergeAll = { viewModel.mergeAllDuplicates(mergeAllGroups(duplicates)) },
                            onMerge = { group -> group.merged?.let { viewModel.mergeDuplicates(group.items, it) } },
                            modifier = Modifier.padding(bottom = AppSpacing.lg)
                        )
                    }
                }
                // Upravo nalepljeno, a neprepoznato: na vrhu, dok se ne izabere proizvod.
                val unknown = { item: DraftItemEntity -> pastedUnknown(item, state.pastedUnknownIds) }
                val ordered = state.items.filter(unknown) + state.items.filterNot(unknown)
                itemsIndexed(ordered, key = { _, item -> item.localId }) { index, item ->
                    val isNew = remember { settled && item.localId !in seen }
                    val visibility = remember { MutableTransitionState(!isNew) }
                    visibility.targetState = removingId != item.localId
                    if (removingId == item.localId && visibility.isIdle && !visibility.currentState) {
                        LaunchedEffect(Unit) {
                            removingId = null
                            delete(item)
                        }
                    }
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = expandVertically() + fadeIn() +
                            scaleIn(spring(dampingRatio = 0.55f, stiffness = 320f), initialScale = 0.92f),
                        exit = slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(220)) +
                            shrinkVertically(tween(300, delayMillis = 50)),
                        modifier = Modifier
                            .animateItem()
                            .enter(entrance, index + 1)
                    ) {
                        DraftItemRow(
                            item = item,
                            notRecognized = unknown(item),
                            onEdit = { openEditor(item) },
                            onQuantity = { viewModel.changeQuantity(item, it) },
                            onDelete = { removingId = item.localId },
                            onOpenProduct = onOpenProduct,
                            onOpenFamily = { familyId -> viewModel.openFamilyPrices(familyId, onOpenProduct) },
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                }
                if (state.items.isNotEmpty()) {
                    item(key = "clear-all") {
                        TextButton(
                            onClick = { confirmClear = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clear-list")
                        ) {
                            AppIcon(R.drawable.ic_delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                stringResource(R.string.list_clear_all),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = AppSpacing.sm)
                            )
                        }
                    }
                }
            }

            // Jedino glavno dugme na ekranu, uvek na istom mestu iznad donje trake.
            PrimaryActionButton(
                text = stringResource(
                    if (state.isSyncing) R.string.list_action_sending else R.string.list_action_calculate
                ),
                enabled = state.items.isNotEmpty() && !state.isSyncing,
                onClick = { viewModel.prepareMatching(onOpenMatching) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = actionBottom)
                    .onSizeChanged { actionHeight = with(density) { it.height.toDp() } }
                    .testTag("calculate")
            )
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
            onOnlyOnSale = productSearchViewModel::onlyOnSale,
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.list_clear_all_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.list_clear_all_text,
                        pluralStringResource(R.plurals.count_items, state.items.size, state.items.size)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearList()
                    },
                    modifier = Modifier.testTag("clear-list-confirm")
                ) { Text(stringResource(R.string.list_clear_all_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showPasteDialog) {
        PasteItemsDialog(
            existingCount = state.items.size,
            onDismiss = { showPasteDialog = false },
            onSave = { text, replace ->
                viewModel.pasteItems(text, replace) { showPasteDialog = false }
            }
        )
    }
}

/**
 * Duplikati u jednoj niskoj traci: „N duplikata“ i „Spoji sve (N)“. Dodir na
 * traku otvara grupe, svaku sa svojim „Spoji“, ili sa razlogom zašto se ne
 * spaja (drugi proizvod, brend ili jedinica).
 */
@Composable
private fun DuplicateStrip(
    groups: List<DuplicateGroup>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMergeAll: () -> Unit,
    onMerge: (DuplicateGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    val mergeable = mergeAllGroups(groups)
    val toggleLabel = stringResource(if (expanded) R.string.list_duplicates_hide else R.string.list_duplicates_show)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .testTag("duplicates")
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.padding(start = AppSpacing.xs, end = AppSpacing.xs, top = AppSpacing.xs, bottom = AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
                        .padding(horizontal = AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    AppIcon(R.drawable.ic_warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        pluralStringResource(R.plurals.list_duplicates_count, groups.size, groups.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    AppIcon(
                        if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (mergeable.isNotEmpty()) {
                    Button(
                        onClick = onMergeAll,
                        contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                        modifier = Modifier.testTag("merge-all")
                    ) {
                        Text(stringResource(R.string.list_duplicate_merge_all, mergeable.size))
                    }
                }
            }
            if (expanded) {
                HorizontalDivider(
                    color = LocalContentColor.current.copy(alpha = 0.22f),
                    modifier = Modifier.padding(horizontal = AppSpacing.md)
                )
                Column(modifier = Modifier.padding(start = AppSpacing.md, end = AppSpacing.xs, bottom = AppSpacing.xs)) {
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier.heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = AppSpacing.xs)) {
                                Text(
                                    stringResource(R.string.list_duplicate_times, group.items.first().name, group.items.size),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (group.merged == null) {
                                    Text(
                                        stringResource(R.string.list_duplicate_cannot_merge),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (group.merged != null) {
                                TextButton(onClick = { onMerge(group) }) {
                                    Text(
                                        stringResource(R.string.list_duplicate_merge),
                                        color = LocalContentColor.current,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Red sa isprekidanom ivicom: ovde se dodaje nova stavka. */
@Composable
private fun AddItemRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                    )
                )
            }
            .heightIn(min = 56.dp)
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)
            .testTag("add-item"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        AppIcon(R.drawable.ic_add, contentDescription = null, tint = color)
        Text(stringResource(R.string.list_add_item), style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun EmptyList(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(
                stringResource(R.string.list_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.list_empty_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Naziv, pravilo i status levo, količina desno. Dodir na karticu otvara
 * izmenu. Kad je količina 1, minus postaje kanta i briše stavku.
 */
@Composable
private fun DraftItemRow(
    item: DraftItemEntity,
    notRecognized: Boolean,
    onEdit: () -> Unit,
    onQuantity: (Double) -> Unit,
    onDelete: () -> Unit,
    onOpenProduct: (Long) -> Unit,
    onOpenFamily: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val attention = if (item.syncError != null) {
        stringResource(R.string.list_status_not_sent) to StatusTone.ERROR
    } else {
        if (notRecognized) {
            stringResource(R.string.list_status_not_recognized) to StatusTone.WARNING
        } else {
            draftAttention(item.matchingStatus)?.let { (text, tone) -> stringResource(text) to tone }
        }
    }
    val openPrices: (() -> Unit)? = item.canonicalProductId?.let { id -> { onOpenProduct(id) } }
        ?: item.productFamilyId
            ?.takeIf { item.matchingRule == ShoppingItemRuleDto.PRODUCT_FAMILY.name }
            ?.let { familyId -> { onOpenFamily(familyId) } }

    val texts: @Composable (Modifier) -> Unit = { textModifier ->
        Column(modifier = textModifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            draftPiecesWeight(item)?.let { weight ->
                Text(
                    weight,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PastedListParser.noteOf(item.rawInput)?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            draftRuleLabel(item)?.let { label ->
                Text(
                    label.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item.syncError?.let { reason ->
                Text(
                    // Blank when the server gave no reason of its own.
                    reason.ifBlank { stringResource(R.string.list_sync_error_rejected) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            attention?.let { (text, tone) ->
                Spacer(Modifier.size(1.dp))
                StatusPill(text, tone)
            }
        }
    }
    val controls: @Composable () -> Unit = {
        openPrices?.let { open ->
            IconButton(onClick = open) {
                AppIcon(
                    R.drawable.ic_local_offer,
                    contentDescription = stringResource(R.string.list_cd_prices_for, item.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        QuantityStepper(
            label = draftAmountLabel(item),
            name = item.name,
            canDecrease = item.quantity > 1.0,
            onLess = { onQuantity((item.quantity - 1.0).coerceAtLeast(1.0)) },
            onMore = { onQuantity(item.quantity + 1.0) },
            onDelete = onDelete
        )
    }

    // Sa krupnim pismom količina ide ispod naziva, da naziv ne ostane u uskoj koloni.
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    GlassCard(onClick = onEdit, modifier = modifier.fillMaxWidth()) {
        if (largeFont) {
            Column(
                modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.md, top = AppSpacing.md, bottom = AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                texts(Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) { controls() }
            }
        } else {
            Row(
                modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.md, top = AppSpacing.md, bottom = AppSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                texts(Modifier.weight(1f))
                Spacer(Modifier.width(AppSpacing.sm))
                controls()
            }
        }
    }
}

/** − količina +; broj kratko poskoči kad se promeni. */
@Composable
private fun QuantityStepper(
    label: String,
    name: String,
    canDecrease: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit
) {
    val bump = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(label) }
    LaunchedEffect(label) {
        if (shown != label) {
            shown = label
            bump.animateTo(1.35f, tween(140))
            bump.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
        }
    }
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canDecrease) {
            StepButton(R.drawable.ic_remove, stringResource(R.string.list_cd_less), onLess)
        } else {
            StepButton(
                R.drawable.ic_delete,
                stringResource(R.string.list_cd_delete, name),
                onDelete,
                tint = MaterialTheme.colorScheme.error
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = 28.dp)
                .padding(horizontal = 2.dp)
                .graphicsLayer {
                    scaleX = bump.value
                    scaleY = bump.value
                }
        )
        StepButton(R.drawable.ic_add, stringResource(R.string.list_cd_more), onMore)
    }
}

@Composable
private fun StepButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(source)
            .size(44.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * „≈0,3 kg" uz komade voća i povrća („limun 3" su 3 komada, ne 3 kg), kao
 * napomena „3 kom ≈ 0,3 kg" u planu (šesti test, V156). Inače null.
 */
internal fun draftPiecesWeight(item: DraftItemEntity): String? {
    val pieces = item.targetQuantity?.takeIf { item.requiredBaseUnit == "piece" } ?: return null
    val grams = item.pieceGrams?.takeIf { it > 0 } ?: return null
    val kilograms = maxOf(0.01, Math.round(pieces * item.quantity * grams / 10) / 100.0)
    return "≈${decimal(kilograms)} kg"
}

/** Ukupna količina kad je upisana („2 kg"), inače broj komada („1 kom"), kao na webu. */
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
            .takeUnless { it.isEmpty() || sameKindWords(it, item.name) }
        val brand = item.requiredBrand?.takeIf(String::isNotBlank)
        when {
            category != null && brand != null -> uiText(R.string.list_rule_category_brand, category, brand)
            category != null -> uiText(R.string.list_rule_category, category)
            brand != null -> uiText(R.string.list_rule_brand, brand)
            else -> null
        }
    }
}

/**
 * „mlevenog mesa“ i „mleveno meso“, „mleka 2,8%“ i „mleko 2 8“: iste reči u
 * drugom padežu ili bez znakova nisu nova kategorija (peti test, 1.11.2026).
 */
internal fun sameKindWords(first: String, second: String): Boolean {
    fun words(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("đ", "dj").replace("Đ", "dj")
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotEmpty)
    val a = words(first)
    val b = words(second)
    if (a.size != b.size) return false
    return a.zip(b).all { (word, other) ->
        when {
            word == other -> true
            word.any(Char::isDigit) || other.any(Char::isDigit) -> false
            word.length < 3 || other.length < 3 -> false
            else -> {
                val keep = maxOf(3, minOf(word.length, other.length) - 2)
                word.take(keep) == other.take(keep)
            }
        }
    }
}

/**
 * Stavka iz upravo nalepljenog spiska za koju ni provera servera nije našla
 * ništa. Server proverava nalepljeno pre odgovora, pa „med“ i „senf“ nisu tu.
 */
internal fun pastedUnknown(item: DraftItemEntity, unknownRemoteIds: Set<Long>): Boolean =
    item.remoteId != null && item.remoteId in unknownRemoteIds &&
        item.matchingStatus == "UNMATCHED" && item.syncError == null

private fun draftAttention(status: String): Pair<Int, StatusTone>? = when (status) {
    "NEEDS_CONFIRMATION" -> R.string.list_status_needs_confirmation to StatusTone.WARNING
    "UNMATCHED" -> R.string.list_status_not_found to StatusTone.ERROR
    else -> null
}

/**
 * Stavke koje su na spisku više puta, bez obzira na velika slova, razmake i
 * kvačice; [merged] je prva stavka sa zbirom, ili null kad se ne mogu sabrati.
 */
internal data class DuplicateGroup(val items: List<DraftItemEntity>, val merged: DraftItemEntity?)

// Dok server ne pročita nalepljen red, ne zna se da li je to ista stavka, pa se ćuti.
internal fun duplicateGroups(items: List<DraftItemEntity>): List<DuplicateGroup> =
    items.groupBy { sameName(it.name) }.values
        .filter { group -> group.size > 1 && group.none { it.syncState == SyncState.PENDING_PASTE.name } }
        .map { DuplicateGroup(it, mergedItem(it)) }

/** Grupe za „Spoji sve“: samo one koje se mogu sabrati. */
internal fun mergeAllGroups(groups: List<DuplicateGroup>): List<DuplicateGroup> =
    groups.filter { it.merged != null }

/**
 * Prva stavka ostaje (mesto, pravilo, proizvod) i dobija zbir količina.
 * Null kad zbir nema smisla: drugo pravilo, proizvod ili brend, količina u
 * različitim jedinicama („mleko 1 l“ i „mleko“ od 1 kom), ili red iz
 * nalepljenog spiska koji server još nije pročitao.
 */
internal fun mergedItem(group: List<DraftItemEntity>): DraftItemEntity? {
    val keep = group.first()
    fun same(pick: (DraftItemEntity) -> String?) = group.all { sameName(pick(it).orEmpty()) == sameName(pick(keep).orEmpty()) }
    // Server upisuje svoj pogodak i u „bilo koji“, pa se proizvod poredi samo kad ga pravilo traži.
    val fits = group.all { it.matchingRule == keep.matchingRule && it.syncState != SyncState.PENDING_PASTE.name } &&
        (keep.matchingRule != ShoppingItemRuleDto.EXACT_PRODUCT.name ||
            (group.all { it.canonicalProductId == keep.canonicalProductId } && same { it.barcode })) &&
        (keep.matchingRule != ShoppingItemRuleDto.PRODUCT_FAMILY.name ||
            group.all { it.productFamilyId == keep.productFamilyId }) &&
        same { it.requiredBrand } && same { it.category }
    if (!fits) return null
    if (group.all { it.targetQuantity == null }) {
        val quantity = Math.round(group.sumOf { it.quantity } * 100) / 100.0
        return keep.copy(quantity = quantity).takeIf { quantity <= MAX_ITEM_QUANTITY }
    }
    if (group.any { it.targetQuantity == null || it.requiredBaseUnit != keep.requiredBaseUnit }) return null
    // Isto kao na redu spiska: ukupno je ciljna količina puta broj.
    val target = Math.round(group.sumOf { it.targetQuantity!! * it.quantity } * 1000) / 1000.0
    val limit = if (keep.requiredBaseUnit == "piece") MAX_ITEM_QUANTITY else MAX_ITEM_QUANTITY * 1000
    return keep.copy(quantity = 1.0, targetQuantity = target).takeIf { target <= limit }
}

private fun sameName(name: String): String =
    java.text.Normalizer.normalize(name.replace("đ", "dj").replace("Đ", "Dj"), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
