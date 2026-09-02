package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import rs.pametnakupovina.app.data.DraftItemInput
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.ProductSearchViewModel
import rs.pametnakupovina.app.ui.ShoppingListViewModel
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.components.canonicalProductPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pametna kupovina")
                        Text(
                            text = if (state.isOffline) {
                                "Offline draft — biće sinhronizovan"
                            } else if (state.isSyncing) {
                                "Sinhronizacija…"
                            } else {
                                "Aktivni spisak"
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    productSearchViewModel.clear()
                    editedItem = null
                    showItemEditor = true
                }
            ) {
                Text("+ Dodaj stavku")
            }
        }
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Unesi stavke pojedinačno ili nalepi ceo spisak.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            state.errorMessage?.let { message ->
                item {
                    MessageCard(
                        message = message,
                        isError = true,
                        actionLabel = "Pokušaj ponovo",
                        onAction = viewModel::refresh
                    )
                }
            }

            state.notice?.let { message ->
                item {
                    MessageCard(
                        message = message,
                        isError = false,
                        actionLabel = "U redu",
                        onAction = viewModel::clearMessage
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Nalepi spisak")
                    }
                    Button(
                        enabled = state.items.isNotEmpty() && !state.isSyncing,
                        onClick = {
                            viewModel.prepareMatching(onOpenMatching)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Izračunaj")
                    }
                }
            }

            if (state.items.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                "Spisak je prazan",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Dodaj prvu stavku pomoću dugmeta +.")
                        }
                    }
                }
            } else {
                items(state.items, key = { it.localId }) { item ->
                    DraftItemCard(
                        item = item,
                        onEdit = {
                            productSearchViewModel.clear()
                            if (
                                item.matchingRule !=
                                ShoppingItemRuleDto.FLEXIBLE_CATEGORY.name
                            ) {
                                productSearchViewModel.updateQuery(item.name)
                            }
                            editedItem = item
                            showItemEditor = true
                        },
                        onDelete = { viewModel.deleteItem(item) },
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
            onDismiss = {
                productSearchViewModel.clear()
                showItemEditor = false
            },
            onSave = { input ->
                val current = editedItem
                if (current == null) {
                    viewModel.addItem(input) {
                        productSearchViewModel.clear()
                        showItemEditor = false
                    }
                } else {
                    viewModel.updateItem(current, input) {
                        productSearchViewModel.clear()
                        showItemEditor = false
                    }
                }
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
private fun DraftItemCard(
    item: DraftItemEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenProduct: (Long) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text("Količina: ${formatQuantity(item.quantity)}")
            Text(
                when (item.matchingRule) {
                    ShoppingItemRuleDto.EXACT_PRODUCT.name ->
                        "Tačan barkod"
                    ShoppingItemRuleDto.PRODUCT_FAMILY.name ->
                        "Isti proizvod • sve poznate varijante"
                    else -> "Fleksibilno: ${item.category.orEmpty()}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = matchingStatusText(item.matchingStatus),
                    tone = matchingStatusTone(item.matchingStatus)
                )
                if (item.syncState != "SYNCED") {
                    StatusPill("Čeka sinhronizaciju", StatusTone.WARNING)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                item.canonicalProductId?.let { canonicalProductId ->
                    TextButton(
                        onClick = { onOpenProduct(canonicalProductId) }
                    ) { Text("Cene") }
                }
                TextButton(onClick = onEdit) { Text("Izmeni") }
                TextButton(onClick = onDelete) { Text("Obriši") }
            }
        }
    }
}

private fun matchingStatusText(status: String): String = when (status) {
    "AUTO_MATCHED" -> "Automatski povezano"
    "NEEDS_CONFIRMATION" -> "Potrebna potvrda"
    "CONFIRMED" -> "Potvrđeno"
    "UNMATCHED" -> "Neupareno"
    else -> "Čeka proveru"
}

private fun matchingStatusTone(status: String): StatusTone = when (status) {
    "AUTO_MATCHED", "CONFIRMED" -> StatusTone.POSITIVE
    "NEEDS_CONFIRMATION", "PENDING" -> StatusTone.WARNING
    "UNMATCHED" -> StatusTone.ERROR
    else -> StatusTone.NEUTRAL
}

@Composable
private fun MessageCard(
    message: String,
    isError: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = message,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ItemEditorDialog(
    item: DraftItemEntity?,
    productSearchState: ProductSearchUiState,
    onSearchQueryChange: (String) -> Unit,
    onClearProductSearch: () -> Unit,
    onRetryProductSearch: () -> Unit,
    onLoadMoreProducts: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (DraftItemInput) -> Unit
) {
    var name by rememberSaveable(item?.localId) {
        mutableStateOf(item?.name.orEmpty())
    }
    var quantity by rememberSaveable(item?.localId) {
        mutableStateOf(item?.quantity?.let(::formatQuantity) ?: "1")
    }
    var rule by rememberSaveable(item?.localId) {
        mutableStateOf(
            item?.matchingRule
                ?.let(ShoppingItemRuleDto::valueOf)
                ?: ShoppingItemRuleDto.PRODUCT_FAMILY
        )
    }
    var category by rememberSaveable(item?.localId) {
        mutableStateOf(item?.category.orEmpty())
    }
    var brand by rememberSaveable(item?.localId) {
        mutableStateOf(item?.requiredBrand.orEmpty())
    }
    var minPackage by rememberSaveable(item?.localId) {
        mutableStateOf(item?.minPackageQuantity?.let(::formatQuantity).orEmpty())
    }
    var maxPackage by rememberSaveable(item?.localId) {
        mutableStateOf(item?.maxPackageQuantity?.let(::formatQuantity).orEmpty())
    }
    var baseUnit by rememberSaveable(item?.localId) {
        mutableStateOf(item?.requiredBaseUnit.orEmpty())
    }
    var selectedProduct by remember(item?.localId) {
        mutableStateOf<CanonicalProductSearchItemDto?>(null)
    }
    var selectedProductRawInput by remember(item?.localId) {
        mutableStateOf<String?>(null)
    }

    val parsedQuantity = quantity.replace(',', '.').toDoubleOrNull()
    val exactSelection = resolveDraftCanonicalProductId(
        item = item,
        enteredName = name,
        rule = rule,
        selectedProduct = selectedProduct
    )
    val familySelection = resolveDraftProductFamilyId(
        item = item,
        enteredName = name,
        rule = rule,
        selectedProduct = selectedProduct
    )
    val valid = name.isNotBlank() &&
        parsedQuantity != null &&
        parsedQuantity > 0 &&
        when (rule) {
            ShoppingItemRuleDto.EXACT_PRODUCT -> exactSelection != null
            ShoppingItemRuleDto.PRODUCT_FAMILY -> familySelection != null
            ShoppingItemRuleDto.FLEXIBLE_CATEGORY -> category.isNotBlank()
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Dodaj stavku" else "Izmeni stavku") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rule != ShoppingItemRuleDto.FLEXIBLE_CATEGORY) {
                    canonicalProductPicker(
                        query = name,
                        selectedProduct = selectedProduct,
                        searchState = productSearchState,
                        onQueryChange = { value ->
                            name = value
                            selectedProduct = null
                            selectedProductRawInput = null
                            onSearchQueryChange(value)
                        },
                        onSelectProduct = { product ->
                            selectedProductRawInput = name.trim()
                            selectedProduct = product
                            name = product.name
                            if (
                                rule == ShoppingItemRuleDto.EXACT_PRODUCT &&
                                product.canonicalProductId == null
                            ) {
                                rule = ShoppingItemRuleDto.PRODUCT_FAMILY
                            }
                            onClearProductSearch()
                        },
                        onClearSelection = {
                            selectedProduct = null
                            selectedProductRawInput = null
                            onSearchQueryChange(name)
                        },
                        onRetry = onRetryProductSearch,
                        onLoadMore = onLoadMoreProducts
                    )
                    item(key = "product-mode-help") {
                        Text(
                            if (rule == ShoppingItemRuleDto.PRODUCT_FAMILY) {
                                "Isti proizvod obuhvata sve poznate barkod varijante i ponude prikazanih trgovaca."
                            } else {
                                "Tačan barkod zaključava stavku na izabranu GTIN varijantu."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    item(key = "flexible-item-name") {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { value ->
                                if (
                                    category.isBlank() ||
                                    category.trim() == name.trim()
                                ) {
                                    category = value
                                }
                                name = value
                            },
                            label = { Text("Naziv stavke") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Količina") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = rule ==
                                    ShoppingItemRuleDto.PRODUCT_FAMILY,
                                onClick = {
                                    rule = ShoppingItemRuleDto.PRODUCT_FAMILY
                                    selectedProduct = null
                                    selectedProductRawInput = null
                                    onSearchQueryChange(name)
                                },
                                label = { Text("Isti proizvod") }
                            )
                            FilterChip(
                                selected = rule ==
                                    ShoppingItemRuleDto.EXACT_PRODUCT,
                                onClick = {
                                    rule = ShoppingItemRuleDto.EXACT_PRODUCT
                                    selectedProduct = null
                                    selectedProductRawInput = null
                                    onSearchQueryChange(name)
                                },
                                label = { Text("Tačan barkod") }
                            )
                        }
                        FilterChip(
                            selected = rule ==
                                ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
                            onClick = {
                                rule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY
                                if (category.isBlank()) {
                                    category = name.trim()
                                }
                                selectedProduct = null
                                selectedProductRawInput = null
                                onClearProductSearch()
                            },
                            label = { Text("Fleksibilna kategorija") }
                        )
                    }
                }
                if (rule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY) {
                    item {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Kategorija*") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = brand,
                            onValueChange = { brand = it },
                            label = { Text("Obavezan brend (opciono)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = minPackage,
                                onValueChange = { minPackage = it },
                                label = { Text("Min. pakovanje") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maxPackage,
                                onValueChange = { maxPackage = it },
                                label = { Text("Maks. pakovanje") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = baseUnit,
                            onValueChange = { baseUnit = it },
                            label = { Text("Jedinica: g, ml ili piece") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        DraftItemInput(
                            name = name,
                            rawInput = when {
                                selectedProduct != null ->
                                    selectedProductRawInput
                                item != null &&
                                    item.name.trim() == name.trim() ->
                                    item.rawInput
                                else -> name.trim()
                            },
                            barcode = resolveDraftBarcode(
                                item = item,
                                enteredName = name,
                                rule = rule,
                                selectedProduct = selectedProduct
                            ),
                            canonicalProductId = exactSelection,
                            productFamilyId = familySelection,
                            quantity = requireNotNull(parsedQuantity),
                            matchingRule = rule,
                            category = category.takeIf {
                                rule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY
                            },
                            requiredBrand = brand.takeIf {
                                rule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY
                            },
                            minPackageQuantity = minPackage
                                .replace(',', '.')
                                .toDoubleOrNull()
                                ?.takeIf {
                                    rule ==
                                        ShoppingItemRuleDto.FLEXIBLE_CATEGORY
                                },
                            maxPackageQuantity = maxPackage
                                .replace(',', '.')
                                .toDoubleOrNull()
                                ?.takeIf {
                                    rule ==
                                        ShoppingItemRuleDto.FLEXIBLE_CATEGORY
                                },
                            requiredBaseUnit = baseUnit.takeIf {
                                rule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY
                            }
                        )
                    )
                }
            ) { Text("Sačuvaj") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Otkaži") }
        }
    )
}

internal fun resolveDraftBarcode(
    item: DraftItemEntity?,
    enteredName: String,
    rule: ShoppingItemRuleDto,
    selectedProduct: CanonicalProductSearchItemDto?
): String? = when {
    rule != ShoppingItemRuleDto.EXACT_PRODUCT -> null
    selectedProduct != null -> selectedProduct.barcode
    item != null &&
        item.matchingRule == ShoppingItemRuleDto.EXACT_PRODUCT.name &&
        item.name.trim() == enteredName.trim() -> item.barcode
    else -> null
}

internal fun resolveDraftCanonicalProductId(
    item: DraftItemEntity?,
    enteredName: String,
    rule: ShoppingItemRuleDto,
    selectedProduct: CanonicalProductSearchItemDto?
): Long? = when {
    rule != ShoppingItemRuleDto.EXACT_PRODUCT -> null
    selectedProduct != null -> selectedProduct.canonicalProductId
    item != null &&
        item.matchingRule == ShoppingItemRuleDto.EXACT_PRODUCT.name &&
        item.name.trim() == enteredName.trim() -> item.canonicalProductId
    else -> null
}

internal fun resolveDraftProductFamilyId(
    item: DraftItemEntity?,
    enteredName: String,
    rule: ShoppingItemRuleDto,
    selectedProduct: CanonicalProductSearchItemDto?
): Long? = when {
    rule != ShoppingItemRuleDto.PRODUCT_FAMILY -> null
    selectedProduct != null -> selectedProduct.productFamilyId
    item != null &&
        item.matchingRule == ShoppingItemRuleDto.PRODUCT_FAMILY.name &&
        item.name.trim() == enteredName.trim() -> item.productFamilyId
    else -> null
}

@Composable
private fun PasteItemsDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nalepi spisak") },
        text = {
            Column {
                Text(
                    "Svaki neprazan red postaće fleksibilna stavka, pa aplikacija može da izabere najpovoljniji odgovarajući proizvod. Za tačan brend ili pakovanje koristi Dodaj stavku."
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 7,
                    placeholder = { Text("2x mleko\nHleb\nJabuke x3") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onSave(text) }
            ) { Text("Dodaj sve") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Otkaži") }
        }
    )
}

internal fun formatQuantity(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
