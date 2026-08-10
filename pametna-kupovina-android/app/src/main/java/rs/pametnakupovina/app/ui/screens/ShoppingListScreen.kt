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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import rs.pametnakupovina.app.data.DraftItemInput
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.ui.ShoppingListViewModel
import rs.pametnakupovina.app.ui.components.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    onOpenMatching: (Long) -> Unit,
    viewModel: ShoppingListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
            FloatingActionButton(
                onClick = {
                    editedItem = null
                    showItemEditor = true
                }
            ) {
                Text("+")
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
                            editedItem = item
                            showItemEditor = true
                        },
                        onDelete = { viewModel.deleteItem(item) }
                    )
                }
            }
        }
    }

    if (showItemEditor) {
        ItemEditorDialog(
            item = editedItem,
            onDismiss = { showItemEditor = false },
            onSave = { input ->
                val current = editedItem
                if (current == null) {
                    viewModel.addItem(input) { showItemEditor = false }
                } else {
                    viewModel.updateItem(current, input) {
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
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text("Količina: ${formatQuantity(item.quantity)}")
            Text(
                if (item.matchingRule == ShoppingItemRuleDto.EXACT_PRODUCT.name) {
                    "Tačan proizvod"
                } else {
                    "Fleksibilno: ${item.category.orEmpty()}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (item.syncState != "SYNCED") {
                Text(
                    "Čeka sinhronizaciju",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) { Text("Izmeni") }
                TextButton(onClick = onDelete) { Text("Obriši") }
            }
        }
    }
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
                ?: ShoppingItemRuleDto.EXACT_PRODUCT
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

    val parsedQuantity = quantity.replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() &&
        parsedQuantity != null &&
        parsedQuantity > 0 &&
        (rule == ShoppingItemRuleDto.EXACT_PRODUCT || category.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Dodaj stavku" else "Izmeni stavku") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Naziv") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = rule == ShoppingItemRuleDto.EXACT_PRODUCT,
                            onClick = { rule = ShoppingItemRuleDto.EXACT_PRODUCT },
                            label = { Text("Tačan proizvod") }
                        )
                        FilterChip(
                            selected = rule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
                            onClick = {
                                rule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY
                            },
                            label = { Text("Fleksibilna") }
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
                            rawInput = item?.rawInput,
                            barcode = item?.barcode,
                            quantity = requireNotNull(parsedQuantity),
                            matchingRule = rule,
                            category = category,
                            requiredBrand = brand,
                            minPackageQuantity = minPackage
                                .replace(',', '.')
                                .toDoubleOrNull(),
                            maxPackageQuantity = maxPackage
                                .replace(',', '.')
                                .toDoubleOrNull(),
                            requiredBaseUnit = baseUnit
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
                Text("Svaki neprazan red postaće posebna stavka.")
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

private fun formatQuantity(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
