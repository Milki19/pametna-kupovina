package rs.pametnakupovina.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.purchase.PurchaseGroup
import rs.pametnakupovina.app.data.purchase.boughtOnList
import rs.pametnakupovina.app.data.purchase.group
import rs.pametnakupovina.app.ui.components.LocalBottomBarInset
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import rs.pametnakupovina.app.ui.components.Meter
import rs.pametnakupovina.app.ui.components.enter
import rs.pametnakupovina.app.ui.components.rememberEntrance
import rs.pametnakupovina.app.ui.duration
import rs.pametnakupovina.app.ui.theme.LocalAppColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.local.PurchaseSessionSummary
import rs.pametnakupovina.app.data.network.RecommendationItemDto
import rs.pametnakupovina.app.data.network.RecommendationStoreDto
import rs.pametnakupovina.app.data.purchase.PurchaseItemProgress
import rs.pametnakupovina.app.data.purchase.PurchaseSession
import rs.pametnakupovina.app.data.purchase.PurchaseStatus
import rs.pametnakupovina.app.data.purchase.validatePurchaseProgress
import rs.pametnakupovina.app.location.Coordinates
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.navigation.googleMapsDirectionsUrl
import rs.pametnakupovina.app.navigation.launchGoogleMapsDirections
import rs.pametnakupovina.app.data.network.MonthlySpendingDto
import rs.pametnakupovina.app.data.network.ReceiptDto
import rs.pametnakupovina.app.ui.ReceiptViewModel
import rs.pametnakupovina.app.ui.PurchaseViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.TonalActionButton
import rs.pametnakupovina.app.ui.components.listBottomPadding
import rs.pametnakupovina.app.ui.currency
import rs.pametnakupovina.app.ui.wholeAmount
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.GlassCard
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.SectionHeader
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.date
import rs.pametnakupovina.app.ui.dateTime
import rs.pametnakupovina.app.ui.instantDateTime
import rs.pametnakupovina.app.ui.decimal
import rs.pametnakupovina.app.ui.money

@Composable
fun PurchaseScreen(
    sessionId: String? = null,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenCards: () -> Unit = {},
    onStartNewList: () -> Unit = {},
    viewModel: PurchaseViewModel = hiltViewModel(),
    // Zajednički sa početnim ekranom, pa skenirani račun odmah menja troškove.
    receiptViewModel: ReceiptViewModel? = null
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { sessionId?.let(viewModel::load) }

    if (sessionId == null) {
        PurchaseHistory(sessions, message, onOpen, receiptViewModel ?: hiltViewModel())
    } else {
        PurchaseInProgress(session, message, onBack, onOpenCards, onStartNewList, viewModel, receiptViewModel)
    }
}

@Composable
private fun PurchaseHistory(
    sessions: List<PurchaseSessionSummary>,
    message: UiText?,
    onOpen: (String) -> Unit,
    receiptViewModel: ReceiptViewModel = hiltViewModel()
) {
    val (active, finished) = sessions.partition { it.archivedAt == null }
    val receipts by receiptViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickReceiptFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(receiptViewModel::importFile)
    }
    Scaffold(containerColor = Color.Transparent, topBar = { AppTopBar(title = stringResource(R.string.purchase_history_title)) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .testTag("purchase-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = listBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            message?.let {
                item(key = "message") { NoticeBanner(text = it.asString(), tone = StatusTone.ERROR) }
            }
            receipts.message?.let {
                item(key = "receipt-message") {
                    NoticeBanner(
                        text = it.asString(),
                        tone = StatusTone.NEUTRAL,
                        actionLabel = stringResource(R.string.purchase_ok),
                        onAction = receiptViewModel::dismissMessage
                    )
                }
            }
            item(key = "scan") {
                SpendingCard(
                    byMonth = receipts.byMonth,
                    receiptCount = receipts.receipts.size,
                    scanning = receipts.scanning,
                    onScan = { receiptViewModel.scan(context) },
                    onImportFile = { pickReceiptFile.launch(RECEIPT_FILE_TYPES) }
                )
            }
            if (receipts.receipts.isNotEmpty()) {
                item(key = "receipts-header") {
                    SectionHeader(stringResource(R.string.purchase_receipts_header), trailing = receipts.receipts.size.toString())
                }
                item(key = "receipts") { ReceiptGroup(receipts.receipts) }
            }
            if (sessions.isEmpty()) {
                item(key = "empty") { EmptyPurchases() }
            }
            if (active.isNotEmpty()) {
                item(key = "active-header") {
                    SectionHeader(stringResource(R.string.purchase_active_header), trailing = active.size.toString())
                }
                item(key = "active") { SessionGroup(active, onOpen) }
            }
            if (finished.isNotEmpty()) {
                item(key = "finished-header") {
                    SectionHeader(stringResource(R.string.purchase_finished_header), trailing = finished.size.toString())
                }
                item(key = "finished") { SessionGroup(finished, onOpen) }
            }
            item(key = "storage-note") {
                Text(
                    stringResource(R.string.purchase_storage_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Potrošnja i dugme za skeniranje stoje zajedno: broj bez načina da mu se
 * doda sledeći račun je samo broj.
 */
@Composable
private fun SpendingCard(
    byMonth: List<MonthlySpendingDto>,
    receiptCount: Int,
    scanning: Boolean,
    onScan: () -> Unit,
    onImportFile: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            val thisMonth = byMonth.firstOrNull()

            if (thisMonth == null) {
                Text(stringResource(R.string.purchase_spending_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.purchase_spending_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    monthName(thisMonth.month),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    money(thisMonth.spent),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    pluralStringResource(R.plurals.purchase_receipt_count, receiptCount, receiptCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TonalActionButton(
                text = stringResource(if (scanning) R.string.purchase_scanning else R.string.purchase_scan_receipt),
                icon = R.drawable.ic_camera,
                primary = true,
                enabled = !scanning,
                onClick = onScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scan-receipt")
            )
            // Digitalni račun iz aplikacije trgovine je na istom telefonu,
            // pa ga kamera ne može uhvatiti: učitava se screenshot ili PDF.
            TonalActionButton(
                text = stringResource(R.string.purchase_import_receipt),
                icon = R.drawable.ic_image,
                enabled = !scanning,
                onClick = onImportFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("import-receipt")
            )
        }
    }
}

@Composable
private fun ReceiptGroup(receipts: List<ReceiptDto>) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            receipts.forEachIndexed { index, receipt ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = AppSpacing.lg)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.lg)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            receipt.shopName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            instantDateTime(receipt.issuedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        money(receipt.totalAmount),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

/** „2026-09-01" postaje „septembar 2026.". */
@Composable
private fun monthName(isoMonth: String): String {
    val month = try {
        java.time.LocalDate.parse(isoMonth)
    } catch (invalid: Exception) {
        null
    }
    return month?.let { rs.pametnakupovina.app.ui.monthName(it) } ?: isoMonth
}

@Composable
private fun EmptyPurchases() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = AppSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        AppIcon(
            R.drawable.ic_history,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(stringResource(R.string.purchase_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.purchase_empty_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Every saved list is called the same thing, so the date leads and the
 * progress bar shows at a glance which one was left half done.
 */
@Composable
private fun SessionGroup(
    sessions: List<PurchaseSessionSummary>,
    onOpen: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sessions.forEach { saved ->
            GlassCard(onClick = { onOpen(saved.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(AppSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dateTime(saved.createdAt),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f)
                        )
                        AppIcon(
                            R.drawable.ic_chevron_right,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        stringResource(
                            R.string.purchase_session_progress,
                            saved.listName,
                            saved.purchasedCount,
                            saved.itemCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Meter(saved.purchasedCount.toFloat() / saved.itemCount.coerceAtLeast(1))
                }
            }
        }
    }
}

/**
 * The screen held in one hand in an aisle: a big box to tick, what to take
 * off the shelf, and its price. Everything rarer waits in the row's menu.
 */
@Composable
private fun PurchaseInProgress(
    session: PurchaseSession?,
    message: UiText?,
    onBack: () -> Unit,
    onOpenCards: () -> Unit,
    onStartNewList: () -> Unit,
    viewModel: PurchaseViewModel,
    receiptViewModel: ReceiptViewModel?
) {
    val context = LocalContext.current
    val receipts = receiptViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    var editingId by rememberSaveable(session?.id) { mutableStateOf<Long?>(null) }
    var confirmArchive by rememberSaveable(session?.id) { mutableStateOf(false) }
    var confirmNewList by rememberSaveable(session?.id) { mutableStateOf(false) }
    var confirmRemoveId by rememberSaveable(session?.id) { mutableStateOf<Long?>(null) }
    val listEntries by viewModel.listEntries.collectAsStateWithLifecycle()
    val onList = listEntries?.let { entries -> session?.boughtOnList(entries) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = context.resources
    // Račun zaveden dok je kupovina otvorena: ponudi da se kupljeno skloni sa spiska.
    // Brojač se pamti, pa okretanje ekrana ne pita ponovo za isti račun.
    val savedReceipts = receipts?.savedCount ?: 0
    var seenReceipts by rememberSaveable(session?.id) { mutableStateOf(savedReceipts) }
    var offerRemoveBought by rememberSaveable(session?.id) { mutableStateOf(false) }
    LaunchedEffect(savedReceipts) {
        if (savedReceipts > seenReceipts) {
            seenReceipts = savedReceipts
            offerRemoveBought = true
        }
    }
    val removeBought: (Set<Long>) -> Unit = { ids ->
        viewModel.removeBought(ids) { count ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(resources.getQuantityString(R.plurals.purchase_removed_bought, count, count))
            }
        }
    }
    val scenario = session?.snapshot?.scenario
    val stores = scenario?.stores?.sortedBy { it.stopOrder }.orEmpty()
    val entrance = rememberEntrance(session?.id)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalBottomBarInset.current)) },
        topBar = {
            AppTopBar(
                title = stringResource(R.string.purchase_in_progress_title),
                subtitle = session?.let {
                    stringResource(
                        R.string.purchase_plan_subtitle,
                        date(it.snapshot.calculationDate),
                        scenarioTitle(it.snapshot.scenario.type)
                    )
                },
                onBack = onBack,
                actions = {
                    if (stores.isNotEmpty()) {
                        IconButton(onClick = {
                            launchGoogleMapsDirections(
                                context,
                                googleMapsDirectionsUrl(
                                    null,
                                    stores.map { Coordinates(it.latitude, it.longitude) },
                                    walking = session?.snapshot?.walking == true
                                )
                            )
                        }) {
                            AppIcon(R.drawable.ic_directions, contentDescription = stringResource(R.string.purchase_route_overview))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (session == null || scenario == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (message != null) {
                    NoticeBanner(
                        text = message.asString(),
                        tone = StatusTone.ERROR,
                        modifier = Modifier.padding(AppSpacing.lg)
                    )
                } else {
                    LoadingState(stringResource(R.string.purchase_loading_session))
                }
            }
            return@Scaffold
        }

        val archived = session.archivedAt != null
        // Kupljeno i preskočeno odlaze dole u sklopljen odeljak, pa red nestaje
        // ispod prsta; „Poništi" ga vraća tačno kakav je bio.
        val onStatus: (Long, PurchaseStatus) -> Unit = { itemId, status ->
            val previous = session.progress[itemId] ?: PurchaseItemProgress()
            viewModel.status(session.id, itemId, status)
            val moved = PurchaseItemProgress(status = status).group()
            val name = scenario.items.firstOrNull { it.itemId == itemId }?.requestedName
            if (name != null && moved != PurchaseGroup.ACTIVE && previous.group() != moved) {
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(
                        message = resources.getString(
                            if (moved == PurchaseGroup.BOUGHT) R.string.purchase_moved_bought else R.string.purchase_moved_skipped,
                            name
                        ),
                        actionLabel = resources.getString(R.string.purchase_undo),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(session.id, itemId, previous)
                }
            }
        }
        val onRemoveFromList: (Long) -> Unit = { confirmRemoveId = it }
        val onReportMissing: (RecommendationItemDto) -> Unit = { viewModel.reportMissing(session.id, it) }
        val unresolved = scenario.items.filter { it.storeId == null }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .testTag("purchase-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = listBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            item(key = "progress") { PurchaseProgressHeader(session, onOpenCards, Modifier.enter(entrance)) }

            if (receipts != null && receiptViewModel != null) {
                val receiptNotice = if (receipts.scanning) uiText(R.string.purchase_logging_receipt) else receipts.message
                receiptNotice?.let { text ->
                    item(key = "receipt-notice") {
                        NoticeBanner(
                            text = text.asString(),
                            actionLabel = stringResource(R.string.purchase_ok).takeUnless { receipts.scanning },
                            onAction = receiptViewModel::dismissMessage.takeUnless { receipts.scanning }
                        )
                    }
                }
            }

            message?.let {
                item(key = "message") { NoticeBanner(text = it.asString(), tone = StatusTone.ERROR) }
            }
            if (archived) {
                item(key = "archived") {
                    // Posle kupovine glavno dugme sklanja samo kupljeno; ostalo
                    // (preskočeno, nema u prodavnici) čeka sledeću kupovinu.
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        NoticeBanner(text = stringResource(R.string.purchase_archived_notice))
                        if (onList != null && onList.removable.isNotEmpty()) {
                            TonalActionButton(
                                text = stringResource(R.string.purchase_remove_bought_count, onList.removable.size),
                                icon = R.drawable.ic_check_circle,
                                primary = true,
                                onClick = { removeBought(onList.removable) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("purchase-remove-bought")
                            )
                            Text(
                                remainingText(onList.remaining),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!listEntries.isNullOrEmpty()) {
                            TonalActionButton(
                                text = stringResource(R.string.purchase_clear_whole_list),
                                icon = R.drawable.ic_delete,
                                onClick = { confirmNewList = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("purchase-start-new-list")
                            )
                        }
                    }
                }
            }
            if (!scenario.complete) {
                item(key = "incomplete") {
                    NoticeBanner(
                        text = stringResource(R.string.purchase_incomplete_notice),
                        tone = StatusTone.WARNING
                    )
                }
            }

            stores.forEachIndexed { index, store ->
                val products = scenario.items.filter { it.storeId == store.storeId }
                item(key = "store-${store.storeId}") {
                    PurchaseStoreSection(
                        modifier = Modifier.enter(entrance, index + 1),
                        store = store,
                        products = products,
                        session = session,
                        archived = archived,
                        onStatus = onStatus,
                        onDetails = { editingId = it },
                        onRemoveFromList = onRemoveFromList,
                        onReportMissing = onReportMissing,
                        onRoute = {
                            launchGoogleMapsDirections(
                                context,
                                googleMapsDirectionsUrl(
                                    null,
                                    listOf(Coordinates(store.latitude, store.longitude)),
                                    walking = session.snapshot.walking
                                )
                            )
                        }
                    )
                }
            }

            if (unresolved.isNotEmpty()) {
                item(key = "unresolved") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        SectionHeader(stringResource(R.string.purchase_no_store_header), trailing = unresolved.size.toString())
                        ItemGroup(unresolved, session, archived, onStatus, onRemoveFromList) { editingId = it }
                    }
                }
            }

            item(key = "finish") {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    // Troškovi se pišu sa računa, ne iz čekiranja; zato se
                    // račun skenira odavde, i pre i posle završetka.
                    if (receipts != null && receiptViewModel != null) {
                        TonalActionButton(
                            text = stringResource(
                                if (receipts.scanning) R.string.purchase_logging_receipt else R.string.purchase_scan_receipt
                            ),
                            icon = R.drawable.ic_camera,
                            primary = true,
                            enabled = !receipts.scanning,
                            onClick = { receiptViewModel.scan(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("purchase-scan-receipt")
                        )
                    }
                    TonalActionButton(
                        text = stringResource(if (archived) R.string.purchase_reopen else R.string.purchase_finish),
                        icon = R.drawable.ic_check_circle,
                        onClick = {
                            if (archived) viewModel.archive(session.id, false) else confirmArchive = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.purchase_footer_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmNewList) {
        AlertDialog(
            onDismissRequest = { confirmNewList = false },
            title = { Text(stringResource(R.string.list_clear_all_title)) },
            text = { Text(stringResource(R.string.purchase_start_new_list_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNewList = false
                        viewModel.startNewList(onStartNewList)
                    },
                    modifier = Modifier.testTag("purchase-start-new-list-confirm")
                ) { Text(stringResource(R.string.purchase_start_new_list)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewList = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    session?.snapshot?.scenario?.items?.firstOrNull { it.itemId == confirmRemoveId }?.let { product ->
        AlertDialog(
            onDismissRequest = { confirmRemoveId = null },
            title = { Text(stringResource(R.string.purchase_remove_from_list_title, product.requestedName)) },
            text = { Text(stringResource(R.string.purchase_remove_from_list_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveId = null
                        viewModel.removeFromList(session.id, product.itemId)
                    },
                    modifier = Modifier.testTag("purchase-remove-from-list-confirm")
                ) { Text(stringResource(R.string.purchase_remove_from_list)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveId = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // Posle zavedenog računa, samo ako ima šta da se skloni.
    if (offerRemoveBought && onList != null) {
        if (onList.removable.isEmpty()) {
            LaunchedEffect(Unit) { offerRemoveBought = false }
        } else {
            AlertDialog(
                onDismissRequest = { offerRemoveBought = false },
                title = { Text(stringResource(R.string.purchase_receipt_saved_title)) },
                text = {
                    Text(
                        stringResource(R.string.purchase_remove_bought_question, onList.removable.size) +
                            "\n\n" + remainingText(onList.remaining)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            offerRemoveBought = false
                            removeBought(onList.removable)
                        },
                        modifier = Modifier.testTag("receipt-remove-bought")
                    ) { Text(stringResource(R.string.purchase_remove_bought)) }
                },
                dismissButton = {
                    TextButton(onClick = { offerRemoveBought = false }) { Text(stringResource(R.string.purchase_keep_all)) }
                }
            )
        }
    }

    if (session != null && confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(stringResource(R.string.purchase_finish_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.purchase_finish_confirm_text,
                        session.purchasedCount,
                        session.shoppableCount
                    )
                )
            },
            confirmButton = {
                if (receiptViewModel != null) {
                    TextButton(
                        onClick = {
                            viewModel.archive(session.id, true)
                            confirmArchive = false
                            receiptViewModel.scan(context)
                        },
                        modifier = Modifier.testTag("finish-and-scan")
                    ) { Text(stringResource(R.string.purchase_finish_and_scan)) }
                } else {
                    TextButton(onClick = {
                        viewModel.archive(session.id, true)
                        confirmArchive = false
                    }) { Text(stringResource(R.string.purchase_finish_short)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (receiptViewModel != null) viewModel.archive(session.id, true)
                    confirmArchive = false
                }) {
                    Text(
                        stringResource(
                            if (receiptViewModel != null) R.string.purchase_just_finish else R.string.purchase_back
                        )
                    )
                }
            }
        )
    }

    session?.snapshot?.scenario?.items?.firstOrNull { it.itemId == editingId }?.let { product ->
        PurchaseDetailsDialog(
            item = product,
            progress = session.progress[product.itemId] ?: PurchaseItemProgress(),
            onDismiss = { editingId = null },
            onSave = { viewModel.details(session.id, product.itemId, it) { editingId = null } }
        )
    }
}

/** „Ostaje na spisku 3: jaja, hleb, sir" ili „Spisak ostaje prazan". Najviše tri naziva. */
@Composable
private fun remainingText(remaining: List<String>): String = when {
    remaining.isEmpty() -> stringResource(R.string.purchase_list_left_empty)
    remaining.size <= 3 -> stringResource(R.string.purchase_list_left, remaining.size, remaining.joinToString(", "))
    else -> stringResource(R.string.purchase_list_left, remaining.size, remaining.take(3).joinToString(", ") + ", …")
}

@Composable
private fun PurchaseProgressHeader(session: PurchaseSession, onOpenCards: () -> Unit, modifier: Modifier = Modifier) {
    val scenario = session.snapshot.scenario
    val total = session.shoppableCount
    val recorded = session.progress.values.mapNotNull { it.actualLineTotal?.toBigDecimalOrNull() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProgressRing(done = session.purchasedCount, total = total)
            Text(
                stringResource(R.string.purchase_bought_of, session.purchasedCount, total),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.purchase_planned_value, "${wholeAmount(scenario.basketCost)} ${currency()}"),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                textAlign = TextAlign.Center
            )
            Text(
                (total - session.resolvedCount).let { left ->
                    if (left > 0) stringResource(R.string.purchase_remaining, left)
                    else stringResource(R.string.purchase_prices_frozen)
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                textAlign = TextAlign.Center
            )
            if (recorded.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.purchase_paid_for_items,
                        recorded.size,
                        recorded.size,
                        money(recorded.fold(BigDecimal.ZERO, BigDecimal::add).toDouble())
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            // Na kasi: kartica lanca je jedan dodir daleko, kao na nacrtu.
            Surface(
                onClick = onOpenCards,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    AppIcon(R.drawable.ic_card, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.purchase_loyalty_cards_link),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    AppIcon(R.drawable.ic_chevron_right, contentDescription = null, tint = muted)
                }
            }
        }
    }
}

/** Krug napretka sa „x/y" u sredini; puni se animirano, a pun menja boju. */
@Composable
private fun ProgressRing(done: Int, total: Int) {
    val fraction by animateFloatAsState(
        targetValue = done.toFloat() / total.coerceAtLeast(1),
        animationSpec = tween(700, easing = EaseOutCubic),
        label = "ring"
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color by animateColorAsState(
        if (total > 0 && done == total) LocalAppColors.current.save else MaterialTheme.colorScheme.primary,
        label = "ringColor"
    )
    Box(
        modifier = Modifier
            .size(96.dp)
            .progressSemantics(done.toFloat() / total.coerceAtLeast(1)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
            drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = stroke)
        }
        // Krug je fiksne veličine, pa broj u njemu ne raste sa pismom telefona.
        BasicText(
            "$done/$total",
            style = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 20.sp),
            modifier = Modifier.padding(horizontal = 18.dp)
        )
    }
}

@Composable
private fun PurchaseStoreSection(
    store: RecommendationStoreDto,
    products: List<RecommendationItemDto>,
    session: PurchaseSession,
    archived: Boolean,
    onStatus: (Long, PurchaseStatus) -> Unit,
    onDetails: (Long) -> Unit,
    onRemoveFromList: (Long) -> Unit,
    onReportMissing: (RecommendationItemDto) -> Unit,
    onRoute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bought = products.count { session.progress[it.itemId]?.status == PurchaseStatus.PURCHASED }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(
            modifier = Modifier.padding(start = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            StopBadge(store.stopOrder)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    store.retailerName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    listOf(
                        storeAddress(store),
                        stringResource(R.string.purchase_about_time, duration(store.durationFromPreviousSeconds))
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("$bought/${products.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onRoute) {
                AppIcon(
                    R.drawable.ic_directions,
                    contentDescription = stringResource(R.string.purchase_route_to_store, store.retailerName),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                GroupedItemRows("store-${store.storeId}", products, session, archived, onStatus, onDetails, onRemoveFromList, onReportMissing)
            }
        }
    }
}

@Composable
private fun ItemGroup(
    items: List<RecommendationItemDto>,
    session: PurchaseSession,
    archived: Boolean,
    onStatus: (Long, PurchaseStatus) -> Unit,
    onRemoveFromList: (Long) -> Unit,
    onDetails: (Long) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { GroupedItemRows("unresolved", items, session, archived, onStatus, onDetails, onRemoveFromList) }
    }
}

/**
 * Gore ono što još treba uzeti; kupljeno i preskočeno idu na dno, u
 * sklopljene odeljke, da se u radnji ne traži po već rešenim stavkama.
 */
@Composable
private fun GroupedItemRows(
    groupKey: String,
    items: List<RecommendationItemDto>,
    session: PurchaseSession,
    archived: Boolean,
    onStatus: (Long, PurchaseStatus) -> Unit,
    onDetails: (Long) -> Unit,
    onRemoveFromList: (Long) -> Unit,
    onReportMissing: ((RecommendationItemDto) -> Unit)? = null
) {
    val groups = items.groupBy { (session.progress[it.itemId] ?: PurchaseItemProgress()).group() }
    val active = groups[PurchaseGroup.ACTIVE].orEmpty()
    ItemRows(active, session, archived, onStatus, onDetails, onRemoveFromList, onReportMissing)
    var above = active.isNotEmpty()
    for ((group, label) in listOf(
        PurchaseGroup.BOUGHT to R.string.purchase_group_bought,
        PurchaseGroup.SKIPPED to R.string.purchase_group_skipped
    )) {
        val rows = groups[group].orEmpty()
        if (rows.isNotEmpty()) key(group) {
            var open by rememberSaveable(groupKey, group.name) { mutableStateOf(false) }
            if (above) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            above = true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = open, role = Role.Button, onValueChange = { open = it })
                    .testTag("group-${group.name.lowercase()}-$groupKey")
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)
            ) {
                Text(
                    stringResource(label, rows.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                AppIcon(
                    if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (open) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                ItemRows(rows, session, archived, onStatus, onDetails, onRemoveFromList, onReportMissing)
            }
        }
    }
}

@Composable
private fun ItemRows(
    items: List<RecommendationItemDto>,
    session: PurchaseSession,
    archived: Boolean,
    onStatus: (Long, PurchaseStatus) -> Unit,
    onDetails: (Long) -> Unit,
    onRemoveFromList: (Long) -> Unit,
    onReportMissing: ((RecommendationItemDto) -> Unit)? = null
) {
    items.forEachIndexed { index, product ->
        // Ključ drži stanje prevlačenja uz stavku kad ona pređe u drugi odeljak.
        key(product.itemId) {
            PurchaseItemRow(
                item = product,
                progress = session.progress[product.itemId] ?: PurchaseItemProgress(),
                archived = archived,
                onStatus = { onStatus(product.itemId, it) },
                onDetails = { onDetails(product.itemId) },
                onRemoveFromList = { onRemoveFromList(product.itemId) },
                onReportMissing = onReportMissing?.let { report -> { report(product) } }
            )
        }
        if (index < items.lastIndex) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun PurchaseItemRow(
    item: RecommendationItemDto,
    progress: PurchaseItemProgress,
    archived: Boolean,
    onStatus: (PurchaseStatus) -> Unit,
    onDetails: () -> Unit,
    onRemoveFromList: () -> Unit,
    onReportMissing: (() -> Unit)? = null
) {
    val purchased = progress.status == PurchaseStatus.PURCHASED
    var menuOpen by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Sa krupnim slovima cena ide ispod naziva, da naziv ne ostane u uskoj koloni.
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    val press = remember { MutableInteractionSource() }
    val skipLabel = stringResource(R.string.purchase_skip)
    val boughtLabel = stringResource(R.string.purchase_mark_bought)

    // Prevuci desno = kupljeno, levo = preskoči; isto i čitaču ekrana kao radnje reda.
    SwipeRow(
        enabled = !archived,
        onSwipeRight = { if (!purchased) onStatus(PurchaseStatus.PURCHASED) },
        onSwipeLeft = { if (progress.status != PurchaseStatus.SKIPPED) onStatus(PurchaseStatus.SKIPPED) },
        rightLabel = boughtLabel,
        leftLabel = skipLabel,
        modifier = Modifier.testTag("purchase-item-${item.itemId}")
    ) {
        // Ceo red je kućica: dodir bilo gde na stavku je kupljeno, ne samo na kvadrat levo.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = purchased,
                    interactionSource = press,
                    indication = LocalIndication.current,
                    enabled = !archived,
                    role = Role.Checkbox,
                    onValueChange = { onStatus(if (it) PurchaseStatus.PURCHASED else PurchaseStatus.TO_BUY) }
                )
                .semantics {
                    if (!archived) {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(skipLabel) { onStatus(PurchaseStatus.SKIPPED); true }
                                .takeIf { progress.status != PurchaseStatus.SKIPPED }
                        )
                    }
                }
                .testTag("bought-${item.itemId}")
                .padding(start = AppSpacing.xs, end = AppSpacing.xs, top = AppSpacing.sm, bottom = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BoughtCheck(checked = purchased, interactionSource = press)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                StrikeText(item.requestedName, struck = purchased)
                item.productName
                    ?.takeUnless { it.equals(item.requestedName, ignoreCase = true) }
                    ?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                itemQuantityLine(item)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                item.substituteNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                purchaseStatus(progress, item)?.let { (text, tone) -> StatusPill(text, tone) }
                // Cenovnik ne zna šta je na polici; kad više kupaca javi, plan
                // tu ponudu u toj radnji preskače.
                if (
                    progress.status == PurchaseStatus.NOT_FOUND && onReportMissing != null &&
                    item.canonicalProductId != null && item.retailerProductId != null && item.storeId != null
                ) {
                    if (progress.reportedMissing) {
                        Text(stringResource(R.string.purchase_reported_missing), style = MaterialTheme.typography.bodySmall, color = muted)
                    } else if (!archived) {
                        TextButton(
                            onClick = onReportMissing,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.testTag("report-missing-${item.itemId}")
                        ) {
                            Text(stringResource(R.string.purchase_report_missing))
                        }
                    }
                }
                if (progress.note.isNotBlank()) {
                    Text(stringResource(R.string.purchase_note_value, progress.note), style = MaterialTheme.typography.bodySmall)
                }
                progress.actualLineTotal?.toDoubleOrNull()?.let {
                    Text(stringResource(R.string.purchase_paid_value, money(it)), style = MaterialTheme.typography.bodySmall)
                }
                if (largeFont) {
                    item.lineTotal?.let {
                        Text(
                            money(it),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (purchased) muted else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (item.storeId == null) {
                    Text(
                        item.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (!largeFont) {
                item.lineTotal?.let {
                    Text(
                        money(it),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (purchased) muted else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = AppSpacing.sm)
                    )
                }
            }
            if (!archived) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("item-menu-${item.itemId}")
                    ) {
                        AppIcon(R.drawable.ic_more_vert, contentDescription = stringResource(R.string.purchase_more_for_item, item.requestedName))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // Meni je preko sadržaja, pa ne sme da bude providan.
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.purchase_not_in_store)) },
                            onClick = {
                                menuOpen = false
                                onStatus(PurchaseStatus.NOT_FOUND)
                            }
                        )
                        if (progress.status != PurchaseStatus.SKIPPED) {
                            DropdownMenuItem(
                                text = { Text(skipLabel) },
                                onClick = {
                                    menuOpen = false
                                    onStatus(PurchaseStatus.SKIPPED)
                                },
                                modifier = Modifier.testTag("skip-${item.itemId}")
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.purchase_details)) },
                            onClick = {
                                menuOpen = false
                                onDetails()
                            }
                        )
                        if (progress.status != PurchaseStatus.TO_BUY || progress.boughtPackages > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.purchase_back_to_list)) },
                                onClick = {
                                    menuOpen = false
                                    onStatus(PurchaseStatus.TO_BUY)
                                }
                            )
                        }
                        // „Ne treba više uopšte": preskočeno ostaje na spisku za sledeći put, ovo ne.
                        if (!progress.removedFromList) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.purchase_remove_from_list)) },
                                onClick = {
                                    menuOpen = false
                                    onRemoveFromList()
                                },
                                modifier = Modifier.testTag("remove-from-list-${item.itemId}")
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Red koji se prevlači: desno otkriva zeleno „Kupljeno", levo „Preskoči".
 * Pušten preko trećine širine izvrši radnju i vrati se na mesto; red zatim
 * pređe u svoj odeljak. Samo vodoravni pokret, pa lista i dalje skroluje.
 */
@Composable
private fun SwipeRow(
    enabled: Boolean,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    rightLabel: String,
    leftLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val x = offset.value
                            val threshold = width / 3f
                            when {
                                x > threshold -> right()
                                x < -threshold -> left()
                            }
                            scope.launch { offset.animateTo(0f, tween(200)) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(200)) } },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + amount).coerceIn(-width.toFloat(), width.toFloat())) }
                        }
                    )
                }
            )
    ) {
        val x = offset.value
        if (x != 0f) {
            val toRight = x > 0
            val strip = with(LocalDensity.current) { kotlin.math.abs(x).toDp() }
            // Boji se samo otkriveni deo, jer red iznad nema svoju pozadinu.
            Box(Modifier.matchParentSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm, if (toRight) Alignment.Start else Alignment.End),
                    modifier = Modifier
                        .align(if (toRight) Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(strip)
                        .clipToBounds()
                        .background(if (toRight) scheme.primaryContainer else scheme.surfaceContainerHighest)
                        .padding(horizontal = AppSpacing.lg)
                ) {
                    AppIcon(
                        if (toRight) R.drawable.ic_check else R.drawable.ic_close,
                        contentDescription = null,
                        tint = if (toRight) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                    )
                    Text(
                        if (toRight) rightLabel else leftLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (toRight) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/** Naziv koji se, kad je kupljeno, precrta linijom s leva na desno i izbledi. */
@Composable
private fun StrikeText(text: String, struck: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (struck) 1f else 0f,
        animationSpec = tween(350, easing = EaseOutCubic),
        label = "strike"
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val color = MaterialTheme.colorScheme.onSurface
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        onTextLayout = { layout = it },
        modifier = Modifier
            .graphicsLayer { alpha = 1f - 0.5f * progress }
            .drawWithContent {
                drawContent()
                val lines = layout ?: return@drawWithContent
                if (progress > 0f) {
                    // Svaki red teksta dobija svoju liniju.
                    for (line in 0 until lines.lineCount) {
                        val left = lines.getLineLeft(line)
                        val y = (lines.getLineTop(line) + lines.getLineBottom(line)) / 2f + 1.dp.toPx()
                        drawLine(
                            color,
                            Offset(left, y),
                            Offset(left + (lines.getLineRight(line) - left) * progress, y),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
    )
}

/**
 * Kućica iz nacrta: zaobljen kvadrat koji se popuni, a kvačica se iscrta.
 * Dodiruje se ceo red, pa je meta za ruku koja gura kolica šira od 48 dp.
 */
@Composable
private fun BoughtCheck(
    checked: Boolean,
    interactionSource: MutableInteractionSource
) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (checked) scheme.primary else Color.Transparent, label = "checkFill")
    val edge by animateColorAsState(if (checked) scheme.primary else scheme.outline, label = "checkEdge")
    val drawn by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(300, delayMillis = if (checked) 80 else 0),
        label = "checkMark"
    )
    // Dodir ide na ceo red; kućica samo pokazuje pritisak i stanje.
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "checkPress"
    )
    val markColor = scheme.onPrimary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp)
    ) {
        Canvas(
            Modifier
                .size(28.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            val corner = CornerRadius(9.dp.toPx())
            drawRoundRect(fill, cornerRadius = corner)
            val border = 2.dp.toPx()
            drawRoundRect(
                edge,
                topLeft = Offset(border / 2, border / 2),
                size = Size(size.width - border, size.height - border),
                cornerRadius = corner,
                style = Stroke(border)
            )
            if (drawn > 0f) {
                val mark = Path().apply {
                    moveTo(size.width * 0.27f, size.height * 0.52f)
                    lineTo(size.width * 0.43f, size.height * 0.68f)
                    lineTo(size.width * 0.74f, size.height * 0.34f)
                }
                val shown = Path()
                PathMeasure().apply {
                    setPath(mark, false)
                    getSegment(0f, length * drawn, shown)
                }
                drawPath(shown, markColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun purchaseStatus(
    progress: PurchaseItemProgress,
    item: RecommendationItemDto
): Pair<String, StatusTone>? {
    val planned = item.purchaseQuantity?.packages ?: item.requestedQuantity
    val bought = progress.boughtPackages.takeIf { it > 0 }?.let { decimal(it) }
    return when (progress.status) {
        PurchaseStatus.NOT_FOUND -> {
            val text = if (bought == null) {
                stringResource(R.string.purchase_not_in_store)
            } else {
                stringResource(R.string.purchase_status_not_found_partial, bought, decimal(planned))
            }
            text to StatusTone.WARNING
        }
        PurchaseStatus.SKIPPED -> {
            val text = if (progress.removedFromList) {
                stringResource(R.string.purchase_status_removed_from_list)
            } else if (bought == null) {
                stringResource(R.string.purchase_status_skipped)
            } else {
                stringResource(R.string.purchase_status_skipped_partial, bought, decimal(planned))
            }
            text to StatusTone.NEUTRAL
        }
        PurchaseStatus.TO_BUY -> bought?.let {
            stringResource(R.string.purchase_status_partial, it, decimal(planned)) to StatusTone.NEUTRAL
        }
        PurchaseStatus.PURCHASED -> null
    }
}

@Composable
private fun PurchaseDetailsDialog(
    item: RecommendationItemDto,
    progress: PurchaseItemProgress,
    onDismiss: () -> Unit,
    onSave: (PurchaseItemProgress) -> Unit
) {
    val planned = item.purchaseQuantity?.packages ?: item.requestedQuantity
    var bought by rememberSaveable(item.itemId) { mutableStateOf(formatQuantity(progress.boughtPackages).replace('.', ',')) }
    var note by rememberSaveable(item.itemId) { mutableStateOf(progress.note) }
    var price by rememberSaveable(item.itemId) { mutableStateOf(progress.actualLineTotal.orEmpty()) }
    var error by remember { mutableStateOf<UiText?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.requestedName) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                item {
                    Text(
                        if (planned % 1.0 == 0.0) {
                            pluralStringResource(R.plurals.purchase_planned_packages, planned.toInt(), decimal(planned))
                        } else {
                            stringResource(R.string.purchase_planned_packages_fraction, decimal(planned))
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    OutlinedTextField(
                        value = bought,
                        onValueChange = { bought = it },
                        label = { Text(stringResource(R.string.purchase_bought_field)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text(stringResource(R.string.purchase_actual_total_field, currency())) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.purchase_note_field)) }
                    )
                }
                error?.let {
                    item { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val next = validatePurchaseProgress(
                        progress.copy(
                            status = PurchaseStatus.TO_BUY,
                            boughtPackages = bought.trim().replace(',', '.')
                                .toDoubleOrNull() ?: Double.NaN,
                            note = note,
                            actualLineTotal = price
                        ),
                        planned
                    )
                    onSave(next)
                } catch (e: IllegalArgumentException) {
                    error = (e as? UserFacingException)?.text ?: uiText(R.string.error_check_input)
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** Screenshot ili PDF digitalnog računa. */
internal val RECEIPT_FILE_TYPES = arrayOf("image/*", "application/pdf")
