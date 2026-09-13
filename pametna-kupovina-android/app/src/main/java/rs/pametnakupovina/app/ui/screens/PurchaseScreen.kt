package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
import rs.pametnakupovina.app.navigation.NavigationPoint
import rs.pametnakupovina.app.navigation.googleMapsDirectionsUrl
import rs.pametnakupovina.app.navigation.launchGoogleMapsDirections
import rs.pametnakupovina.app.ui.PurchaseViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.SectionHeader
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.counted
import rs.pametnakupovina.app.ui.date
import rs.pametnakupovina.app.ui.dateTime
import rs.pametnakupovina.app.ui.decimal
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.plural

@Composable
fun PurchaseScreen(
    sessionId: String? = null,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: PurchaseViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { sessionId?.let(viewModel::load) }

    if (sessionId == null) {
        PurchaseHistory(sessions, message, onBack, onOpen)
    } else {
        PurchaseInProgress(session, message, onBack, viewModel)
    }
}

@Composable
private fun PurchaseHistory(
    sessions: List<PurchaseSessionSummary>,
    message: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    val (active, finished) = sessions.partition { it.archivedAt == null }
    Scaffold(topBar = { AppTopBar(title = "Kupovine", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("purchase-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = AppSpacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            message?.let {
                item(key = "message") { NoticeBanner(text = it, tone = StatusTone.ERROR) }
            }
            if (sessions.isEmpty()) {
                item(key = "empty") { EmptyPurchases() }
            }
            if (active.isNotEmpty()) {
                item(key = "active-header") {
                    SectionHeader("U toku", trailing = active.size.toString())
                }
                item(key = "active") { SessionGroup(active, onOpen) }
            }
            if (finished.isNotEmpty()) {
                item(key = "finished-header") {
                    SectionHeader("Završene", trailing = finished.size.toString())
                }
                item(key = "finished") { SessionGroup(finished, onOpen) }
            }
            item(key = "storage-note") {
                Text(
                    "Planovi i napredak su sačuvani na ovom telefonu i rade bez mreže. " +
                        "Brisanje podataka aplikacije briše i ovu istoriju.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
        Text("Još nema sačuvanih kupovina", style = MaterialTheme.typography.titleLarge)
        Text(
            "Izračunaj plan i izaberi „Započni kupovinu po ovom planu“.",
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
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            sessions.forEachIndexed { index, saved ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(saved.id) }
                        .padding(start = AppSpacing.lg, end = AppSpacing.sm, top = AppSpacing.md, bottom = AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Text(dateTime(saved.createdAt), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${saved.listName} · kupljeno ${saved.purchasedCount} od ${saved.itemCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = {
                                saved.purchasedCount.toFloat() / saved.itemCount.coerceAtLeast(1)
                            },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppSpacing.xs)
                        )
                    }
                    AppIcon(
                        R.drawable.ic_chevron_right,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = AppSpacing.sm)
                    )
                }
                if (index < sessions.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    message: String?,
    onBack: () -> Unit,
    viewModel: PurchaseViewModel
) {
    val context = LocalContext.current
    var editingId by rememberSaveable(session?.id) { mutableStateOf<Long?>(null) }
    var confirmArchive by rememberSaveable(session?.id) { mutableStateOf(false) }
    val scenario = session?.snapshot?.scenario
    val stores = scenario?.stores?.sortedBy { it.stopOrder }.orEmpty()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "U kupovini",
                subtitle = session?.let {
                    "Plan od ${date(it.snapshot.calculationDate)} · ${scenarioTitle(it.snapshot.scenario.type)}"
                },
                onBack = onBack,
                actions = {
                    if (stores.isNotEmpty()) {
                        IconButton(onClick = {
                            launchGoogleMapsDirections(
                                context,
                                googleMapsDirectionsUrl(
                                    null,
                                    stores.map { NavigationPoint(it.latitude, it.longitude) }
                                )
                            )
                        }) {
                            AppIcon(R.drawable.ic_directions, contentDescription = "Pregled rute u Google Maps")
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
                        text = message,
                        tone = StatusTone.ERROR,
                        modifier = Modifier.padding(AppSpacing.lg)
                    )
                } else {
                    LoadingState("Učitavam sačuvanu kupovinu…")
                }
            }
            return@Scaffold
        }

        val archived = session.archivedAt != null
        val onStatus: (Long, PurchaseStatus) -> Unit = { itemId, status ->
            viewModel.status(session.id, itemId, status)
        }
        val unresolved = scenario.items.filter { it.storeId == null }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("purchase-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = AppSpacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            item(key = "progress") { PurchaseProgressHeader(session) }

            message?.let {
                item(key = "message") { NoticeBanner(text = it, tone = StatusTone.ERROR) }
            }
            if (archived) {
                item(key = "archived") {
                    NoticeBanner(
                        text = "Ova kupovina je završena. Kućice su zaključane dok je ponovo ne otvoriš."
                    )
                }
            }
            if (!scenario.complete) {
                item(key = "incomplete") {
                    NoticeBanner(
                        text = "Plan nije potpun. Stavke bez ponude su na dnu.",
                        tone = StatusTone.WARNING
                    )
                }
            }

            stores.forEach { store ->
                val products = scenario.items.filter { it.storeId == store.storeId }
                item(key = "store-${store.storeId}") {
                    PurchaseStoreSection(
                        store = store,
                        products = products,
                        session = session,
                        archived = archived,
                        onStatus = onStatus,
                        onDetails = { editingId = it },
                        onRoute = {
                            launchGoogleMapsDirections(
                                context,
                                googleMapsDirectionsUrl(
                                    null,
                                    listOf(NavigationPoint(store.latitude, store.longitude))
                                )
                            )
                        }
                    )
                }
            }

            if (unresolved.isNotEmpty()) {
                item(key = "unresolved") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        SectionHeader("Bez prodavnice", trailing = unresolved.size.toString())
                        ItemGroup(unresolved, session, archived, onStatus) { editingId = it }
                    }
                }
            }

            item(key = "finish") {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            if (archived) viewModel.archive(session.id, false) else confirmArchive = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text(if (archived) "Ponovo otvori kupovinu" else "Završi kupovinu")
                    }
                    Text(
                        "Originalni spisak ostaje nepromenjen. Mape traže mrežu ili unapred preuzetu mapu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (session != null && confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Završi kupovinu?") },
            text = {
                Text(
                    "Kupljeno ${session.purchasedCount} od ${session.snapshot.scenario.items.size}. " +
                        "Nekupljene stavke neće biti označene kao kupljene. Plan i napomene ostaju u istoriji."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.archive(session.id, true)
                    confirmArchive = false
                }) { Text("Završi") }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) { Text("Nazad") }
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

@Composable
private fun PurchaseProgressHeader(session: PurchaseSession) {
    val scenario = session.snapshot.scenario
    val total = scenario.items.size
    val recorded = session.progress.values.mapNotNull { it.actualLineTotal?.toBigDecimalOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "Kupljeno ${session.purchasedCount} od $total",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                "još ${total - session.resolvedCount} za odluku",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { session.purchasedCount.toFloat() / total.coerceAtLeast(1) },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
        Text(
            "Planirana korpa ${money(scenario.basketCost)}. Cene u sačuvanom planu se ne osvežavaju.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (recorded.isNotEmpty()) {
            Text(
                "Upisano plaćeno: ${money(recorded.fold(BigDecimal.ZERO, BigDecimal::add).toDouble())} " +
                    "za ${counted(recorded.size, "stavku", "stavke", "stavki")}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
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
    onRoute: () -> Unit
) {
    val bought = products.count { session.progress[it.itemId]?.status == PurchaseStatus.PURCHASED }
    Surface(
        shape = MaterialTheme.shapes.medium,
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                StopBadge(store.stopOrder)
                Column(modifier = Modifier.weight(1f)) {
                    Text(store.retailerName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        storeAddress(store),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("$bought/${products.size}", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRoute) {
                    AppIcon(
                        R.drawable.ic_directions,
                        contentDescription = "Put do prodavnice ${store.retailerName}",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ItemRows(products, session, archived, onStatus, onDetails)
        }
    }
}

@Composable
private fun ItemGroup(
    items: List<RecommendationItemDto>,
    session: PurchaseSession,
    archived: Boolean,
    onStatus: (Long, PurchaseStatus) -> Unit,
    onDetails: (Long) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { ItemRows(items, session, archived, onStatus, onDetails) }
    }
}

@Composable
private fun ItemRows(
    items: List<RecommendationItemDto>,
    session: PurchaseSession,
    archived: Boolean,
    onStatus: (Long, PurchaseStatus) -> Unit,
    onDetails: (Long) -> Unit
) {
    items.forEachIndexed { index, product ->
        PurchaseItemRow(
            item = product,
            progress = session.progress[product.itemId] ?: PurchaseItemProgress(),
            archived = archived,
            onStatus = { onStatus(product.itemId, it) },
            onDetails = { onDetails(product.itemId) }
        )
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
    onDetails: () -> Unit
) {
    val purchased = progress.status == PurchaseStatus.PURCHASED
    var menuOpen by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("purchase-item-${item.itemId}")
            .padding(start = AppSpacing.xs, end = AppSpacing.xs, top = AppSpacing.sm, bottom = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = purchased,
            enabled = !archived,
            onCheckedChange = { onStatus(if (it) PurchaseStatus.PURCHASED else PurchaseStatus.TO_BUY) },
            modifier = Modifier.testTag("bought-${item.itemId}")
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                item.requestedName,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (purchased) TextDecoration.LineThrough else null,
                color = if (purchased) muted else MaterialTheme.colorScheme.onSurface
            )
            item.productName
                ?.takeUnless { it.equals(item.requestedName, ignoreCase = true) }
                ?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
            itemQuantityLine(item)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            purchaseStatus(progress, item)?.let { (text, tone) -> StatusPill(text, tone) }
            if (progress.note.isNotBlank()) {
                Text("Napomena: ${progress.note}", style = MaterialTheme.typography.bodySmall)
            }
            progress.actualLineTotal?.toDoubleOrNull()?.let {
                Text("Plaćeno ${money(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (item.storeId == null) {
                Text(
                    item.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        item.lineTotal?.let {
            Text(
                money(it),
                style = MaterialTheme.typography.titleMedium,
                color = if (purchased) muted else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = AppSpacing.sm)
            )
        }
        if (!archived) {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("item-menu-${item.itemId}")
                ) {
                    AppIcon(R.drawable.ic_more_vert, contentDescription = "Još za ${item.requestedName}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Nema u prodavnici") },
                        onClick = {
                            menuOpen = false
                            onStatus(PurchaseStatus.NOT_FOUND)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Preskoči") },
                        onClick = {
                            menuOpen = false
                            onStatus(PurchaseStatus.SKIPPED)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Detalji") },
                        onClick = {
                            menuOpen = false
                            onDetails()
                        }
                    )
                    if (progress.status != PurchaseStatus.TO_BUY || progress.boughtPackages > 0) {
                        DropdownMenuItem(
                            text = { Text("Vrati na spisak") },
                            onClick = {
                                menuOpen = false
                                onStatus(PurchaseStatus.TO_BUY)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun purchaseStatus(
    progress: PurchaseItemProgress,
    item: RecommendationItemDto
): Pair<String, StatusTone>? {
    val planned = item.purchaseQuantity?.packages ?: item.requestedQuantity
    val partial = progress.boughtPackages.takeIf { it > 0 }
        ?.let { ", kupljeno ${decimal(it)} od ${decimal(planned)}" }
        .orEmpty()
    return when (progress.status) {
        PurchaseStatus.NOT_FOUND -> "Nema u prodavnici$partial" to StatusTone.WARNING
        PurchaseStatus.SKIPPED -> "Preskočeno$partial" to StatusTone.NEUTRAL
        PurchaseStatus.TO_BUY -> partial.takeIf { it.isNotEmpty() }
            ?.let { "Delimično$it" to StatusTone.NEUTRAL }
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
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.requestedName) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                item {
                    Text(
                        "Planirano: ${decimal(planned)} " +
                            if (planned % 1.0 == 0.0) {
                                plural(planned.toInt(), "pakovanje", "pakovanja", "pakovanja")
                            } else {
                                "pakovanja"
                            },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    OutlinedTextField(
                        value = bought,
                        onValueChange = { bought = it },
                        label = { Text("Kupljeno (može delimično)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("Stvarni ukupan iznos, RSD (opciono)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Napomena") }
                    )
                }
                error?.let {
                    item { Text(it, color = MaterialTheme.colorScheme.error) }
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
                    error = e.message
                }
            }) { Text("Sačuvaj") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Otkaži") } }
    )
}
