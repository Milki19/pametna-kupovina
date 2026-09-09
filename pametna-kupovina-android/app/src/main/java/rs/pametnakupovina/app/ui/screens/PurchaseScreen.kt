package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.data.network.RecommendationItemDto
import rs.pametnakupovina.app.navigation.*
import rs.pametnakupovina.app.ui.PurchaseViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseScreen(
    sessionId: String? = null,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: PurchaseViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingId by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    var confirmArchive by rememberSaveable(sessionId) { mutableStateOf(false) }
    val observedSession by viewModel.session.collectAsStateWithLifecycle()
    val session = observedSession
    LaunchedEffect(sessionId) { sessionId?.let(viewModel::load) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (sessionId == null) "Moje kupovine" else "U kupovini") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Nazad") } })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("purchase-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (sessionId == null) {
                item { Text("Planovi i napredak su sačuvani na ovom uređaju i rade bez mreže. " +
                    "Brisanje podataka aplikacije briše i ovu istoriju.") }
                if (sessions.isEmpty()) item { Text("Još nema sačuvanih kupovina. Izračunaj korpu i izaberi „Započni kupovinu“.") }
                sessions.forEach { saved ->
                    item(key = saved.id) {
                        Card(onClick = { onOpen(saved.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(saved.listName, style = MaterialTheme.typography.titleMedium)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(saved.createdAt)))
                                Text(if (saved.archivedAt == null) "Aktivna kupovina" else "Arhivirana kupovina")
                                Text("Kupljeno ${saved.purchasedCount}/${saved.itemCount}")
                            }
                        }
                    }
                }
            } else if (session == null) {
                item { Text("Učitavam sačuvanu kupovinu. Ako nije dostupna, vrati se na „Moje kupovine“.") }
            } else {
                val scenario = session.snapshot.scenario
                val archived = session.archivedAt != null
                item {
                    Text(session.snapshot.listName, style = MaterialTheme.typography.headlineSmall)
                    Text("Kupljeno ${session.purchasedCount}/${scenario.items.size} • " +
                        "preostalo za odluku ${scenario.items.size - session.resolvedCount}")
                    LinearProgressIndicator(progress = {
                        session.purchasedCount.toFloat() / scenario.items.size.coerceAtLeast(1)
                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    Text("Plan od ${session.snapshot.calculationDate} • ${scenarioTitle(scenario.type)}")
                    Text("Planirana korpa: ${purchaseMoney(scenario.basketCost)}. Cene se u sačuvanom planu ne osvežavaju.")
                    val recorded = session.progress.values.mapNotNull { it.actualLineTotal?.toBigDecimalOrNull() }
                    if (recorded.isNotEmpty()) Text("Zabeleženi stvarni iznosi: ${recorded.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)} RSD " +
                        "(${recorded.size} stavki; nije nužno cela kupovina)")
                    if (!scenario.complete) Text("Plan nije kompletan — proveri i stavke bez ponude.",
                        color = MaterialTheme.colorScheme.error)
                }
                val stores = scenario.stores.sortedBy { it.stopOrder }
                if (stores.isNotEmpty()) item {
                    Button(onClick = {
                        launchGoogleMapsDirections(context, googleMapsDirectionsUrl(null,
                            stores.map { NavigationPoint(it.latitude, it.longitude) }))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Pregled rute u Google Maps") }
                }
                stores.forEach { store ->
                    val products = scenario.items.filter { it.storeId == store.storeId }
                    item(key = "store-${store.storeId}") {
                        Text("${store.stopOrder}. ${store.retailerName} — ${store.storeName}",
                            style = MaterialTheme.typography.titleLarge)
                        Text(listOfNotNull(store.address, store.city).joinToString(", "))
                        Text("Kupljeno ${products.count { session.progress[it.itemId]?.status == PurchaseStatus.PURCHASED }}/${products.size}")
                        TextButton(onClick = {
                            launchGoogleMapsDirections(context, googleMapsDirectionsUrl(null,
                                listOf(NavigationPoint(store.latitude, store.longitude))))
                        }) { Text("Pogledaj put do prodavnice") }
                    }
                    products.forEach { product ->
                        item(key = product.itemId) {
                            PurchaseItemCard(product, session.progress[product.itemId] ?: PurchaseItemProgress(),
                                archived, { status -> viewModel.status(session.id, product.itemId, status) },
                                { editingId = product.itemId })
                        }
                    }
                }
                val unresolved = scenario.items.filter { it.storeId == null }
                if (unresolved.isNotEmpty()) item {
                    Text("Bez prodavnice / bez ponude", style = MaterialTheme.typography.titleLarge)
                }
                unresolved.forEach { product ->
                    item(key = product.itemId) {
                        PurchaseItemCard(product, session.progress[product.itemId] ?: PurchaseItemProgress(),
                            archived, { status -> viewModel.status(session.id, product.itemId, status) },
                            { editingId = product.itemId })
                    }
                }
                item {
                    Button(onClick = {
                        if (archived) viewModel.archive(session.id, false) else confirmArchive = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (archived) "Ponovo otvori kupovinu" else "Završi i arhiviraj")
                    }
                    Text("Originalni spisak ostaje nepromenjen. Mape zahtevaju mrežu ili unapred preuzetu mapu.")
                }
            }
        }
    }
    if (session != null && confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        title = { Text("Arhiviraj kupovinu?") },
        text = { Text("Kupljeno ${session.purchasedCount}/${session.snapshot.scenario.items.size}. " +
            "Nekupljene stavke neće biti označene kao kupljene. Plan i napomene ostaju u istoriji.") },
        confirmButton = { TextButton(onClick = {
            viewModel.archive(session.id, true); confirmArchive = false
        }) { Text("Arhiviraj") } },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Nazad") } }
    )
    session?.snapshot?.scenario?.items?.firstOrNull { it.itemId == editingId }?.let { product ->
        PurchaseDetailsDialog(product, session.progress[product.itemId] ?: PurchaseItemProgress(),
            onDismiss = { editingId = null },
            onSave = { viewModel.details(session.id, product.itemId, it) { editingId = null } })
    }
}

@Composable
private fun PurchaseItemCard(item: RecommendationItemDto, progress: PurchaseItemProgress,
    archived: Boolean, onStatus: (PurchaseStatus) -> Unit, onDetails: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("purchase-item-${item.itemId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row {
                Checkbox(modifier = Modifier.testTag("bought-${item.itemId}"),
                    checked = progress.status == PurchaseStatus.PURCHASED, enabled = !archived,
                    onCheckedChange = { onStatus(if (it) PurchaseStatus.PURCHASED else PurchaseStatus.TO_BUY) })
                Column(Modifier.weight(1f)) {
                    Text(item.requestedName, style = MaterialTheme.typography.titleMedium)
                    item.productName?.let { Text(it) }
                    itemPriceBreakdown(item)?.let { Text(it) }
                    item.purchaseQuantity?.let { Text(purchaseQuantityDescription(it)) }
                    if (item.storeId == null) Text(item.explanation, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(when (progress.status) {
                PurchaseStatus.TO_BUY -> "Za kupovinu • kupljeno ${progress.boughtPackages}"
                PurchaseStatus.PURCHASED -> "Kupljeno"
                PurchaseStatus.NOT_FOUND -> "Nije pronađeno • prethodno kupljeno ${progress.boughtPackages}"
                PurchaseStatus.SKIPPED -> "Preskočeno • prethodno kupljeno ${progress.boughtPackages}"
            })
            if (progress.note.isNotBlank()) Text("Napomena: ${progress.note}")
            progress.actualLineTotal?.let { Text("Stvarno plaćeno: $it RSD") }
            if (!archived) {
                Row {
                    TextButton(onClick = { onStatus(PurchaseStatus.NOT_FOUND) }) { Text("Nema") }
                    TextButton(onClick = { onStatus(PurchaseStatus.SKIPPED) }) { Text("Preskoči") }
                    TextButton(onClick = onDetails) { Text("Detalji") }
                }
                if (progress.status != PurchaseStatus.TO_BUY || progress.boughtPackages > 0) {
                    TextButton(onClick = { onStatus(PurchaseStatus.TO_BUY) }) { Text("Vrati na spisak") }
                }
            }
        }
    }
}

@Composable
private fun PurchaseDetailsDialog(item: RecommendationItemDto, progress: PurchaseItemProgress,
    onDismiss: () -> Unit, onSave: (PurchaseItemProgress) -> Unit) {
    var bought by rememberSaveable(item.itemId) { mutableStateOf(progress.boughtPackages.toString()) }
    var note by rememberSaveable(item.itemId) { mutableStateOf(progress.note) }
    var price by rememberSaveable(item.itemId) { mutableStateOf(progress.actualLineTotal.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(item.requestedName) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Planirano: ${item.purchaseQuantity?.packages ?: item.requestedQuantity} pakovanja / komada") }
            item { OutlinedTextField(bought, { bought = it }, label = { Text("Kupljeno (može delimično)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
            item { OutlinedTextField(price, { price = it }, label = { Text("Stvarni ukupan iznos, RSD (opciono)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
            item { OutlinedTextField(note, { note = it }, label = { Text("Napomena") }) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                val next = validatePurchaseProgress(progress.copy(
                    status = PurchaseStatus.TO_BUY,
                    boughtPackages = bought.replace(',', '.').toDoubleOrNull() ?: Double.NaN,
                    note = note, actualLineTotal = price),
                    item.purchaseQuantity?.packages ?: item.requestedQuantity)
                onSave(next)
            } catch (e: IllegalArgumentException) { error = e.message }
        }) { Text("Sačuvaj") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Otkaži") } })
}
