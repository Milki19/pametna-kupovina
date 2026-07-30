package rs.pametnakupovina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ShoppingListDetailScreen(
    listId: Long,
    onBack: () -> Unit,
    onShowBestPrices: () -> Unit,
    viewModel: ShoppingListDetailViewModel = viewModel()
) {
    val state = viewModel.uiState
    var showAddItemDialog by rememberSaveable {
        mutableStateOf(false)
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(listId) {
        viewModel.loadShoppingList(listId)
    }

    if (showAddItemDialog) {
        AddShoppingListItemDialog(
            isSaving = state.isAddingItem,
            errorMessage = state.addItemError,
            onDismiss = {
                if (!state.isAddingItem) {
                    showAddItemDialog = false
                    viewModel.clearAddItemError()
                }
            },
            onConfirm = { name, barcode, quantity ->
                viewModel.addItem(
                    listId = listId,
                    name = name,
                    barcode = barcode,
                    quantity = quantity,
                    onSuccess = {
                        showAddItemDialog = false
                    }
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("← Nazad")
        }

        when {
            state.isLoading -> {
                LoadingContent()
            }

            state.errorMessage != null -> {
                ErrorContent(
                    message = state.errorMessage,
                    onRetry = {
                        viewModel.retry(listId)
                    }
                )
            }

            state.shoppingList != null -> {
                ShoppingListContent(
                    shoppingList = state.shoppingList,
                    onAddItem = {
                        viewModel.clearAddItemError()
                        showAddItemDialog = true
                    },
                    onShowBestPrices = onShowBestPrices
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ShoppingListContent(
    shoppingList: ShoppingListDetailsDto,
    onAddItem: () -> Unit,
    onShowBestPrices: () -> Unit
) {
    Text(
        text = shoppingList.name,
        style = MaterialTheme.typography.headlineMedium
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = if (shoppingList.items.size == 1) {
            "1 stavka"
        } else {
            "${shoppingList.items.size} stavki"
        },
        style = MaterialTheme.typography.bodyMedium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onAddItem,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Dodaj artikal")
    }
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedButton(
        onClick = onShowBestPrices,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Pronađi najbolje cene")
    }

    Spacer(modifier = Modifier.height(16.dp))

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = shoppingList.items,
            key = { it.id }
        ) { item ->
            ShoppingListItemCard(item)
        }
    }
}

@Composable
private fun ShoppingListItemCard(
    item: ShoppingListItemDto
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Količina: ${formatQuantity(item.quantity)}",
                style = MaterialTheme.typography.bodyMedium
            )

            item.barcode
                ?.takeIf { it.isNotBlank() }
                ?.let { barcode ->
                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Barkod: $barcode",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
        }
    }
}

@Composable
private fun AddShoppingListItemDialog(
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        barcode: String?,
        quantity: Double
    ) -> Unit
) {
    var name by rememberSaveable {
        mutableStateOf("")
    }

    var barcode by rememberSaveable {
        mutableStateOf("")
    }

    var quantityText by rememberSaveable {
        mutableStateOf("1")
    }

    val quantity = quantityText
        .replace(',', '.')
        .toDoubleOrNull()

    val canSave =
        name.isNotBlank() &&
                quantity != null &&
                quantity > 0 &&
                !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Dodaj artikal")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                    },
                    label = {
                        Text("Naziv artikla")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = barcode,
                    onValueChange = {
                        barcode = it
                    },
                    label = {
                        Text("Barkod — opciono")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = quantityText,
                    onValueChange = {
                        quantityText = it
                    },
                    label = {
                        Text("Količina")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onConfirm(
                        name,
                        barcode.takeIf { it.isNotBlank() },
                        quantity ?: return@TextButton
                    )
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Dodaj")
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text("Otkaži")
            }
        }
    )
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Greška pri učitavanju",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = message)

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onRetry) {
            Text("Pokušaj ponovo")
        }
    }
}

private fun formatQuantity(quantity: Double): String {
    val wholeNumber = quantity.toLong()

    return if (quantity == wholeNumber.toDouble()) {
        wholeNumber.toString()
    } else {
        quantity.toString()
    }
}