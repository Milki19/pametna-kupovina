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
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun ShoppingListDetailScreen(
    listId: Long,
    onBack: () -> Unit,
    onShowBestPrices: () -> Unit,
    viewModel: ShoppingListDetailViewModel = viewModel(),
    searchViewModel: ProductSearchViewModel = viewModel()
) {
    val state = viewModel.uiState
    val searchState = searchViewModel.uiState

    var showAddItemDialog by rememberSaveable {
        mutableStateOf(false)
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(listId) {
        viewModel.loadShoppingList(listId)
    }

    if (showAddItemDialog) {
        AddShoppingListItemDialog(
            searchState = searchState,
            isSaving = state.isAddingItem,
            errorMessage = state.addItemError,
            onQueryChange = searchViewModel::updateQuery,
            onDismiss = {
                if (!state.isAddingItem) {
                    showAddItemDialog = false
                    viewModel.clearAddItemError()
                    searchViewModel.clearSearch()
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
                        searchViewModel.clearSearch()
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
                        searchViewModel.clearSearch()
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
    searchState: ProductSearchUiState,
    isSaving: Boolean,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        barcode: String?,
        quantity: Double
    ) -> Unit
) {
    var selectedProduct by remember {
        mutableStateOf<ProductSearchResultDto?>(null)
    }

    var quantityText by rememberSaveable {
        mutableStateOf("1")
    }

    val quantity = quantityText
        .replace(',', '.')
        .toDoubleOrNull()

    val itemName = selectedProduct?.name
        ?: searchState.query.trim()

    val canSave =
        itemName.isNotBlank() &&
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = { query ->
                        selectedProduct = null
                        onQueryChange(query)
                    },
                    label = {
                        Text("Naziv ili barkod")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                selectedProduct?.let { product ->
                    SelectedProductCard(
                        product = product,
                        onChangeProduct = {
                            selectedProduct = null
                        }
                    )
                } ?: ProductSearchResults(
                    searchState = searchState,
                    onProductSelected = { product ->
                        selectedProduct = product
                    }
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
                        itemName,
                        selectedProduct?.barcode,
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
                    Text(
                        if (selectedProduct != null) {
                            "Dodaj proizvod"
                        } else {
                            "Dodaj kao opšti artikal"
                        }
                    )
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
private fun ProductSearchResults(
    searchState: ProductSearchUiState,
    onProductSelected: (ProductSearchResultDto) -> Unit
) {
    when {
        searchState.query.trim().length < 2 -> {
            Text(
                text = "Unesi najmanje 2 karaktera za pretragu.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        searchState.isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp
                )
            }
        }

        searchState.errorMessage != null -> {
            Text(
                text = searchState.errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        searchState.results.isEmpty() -> {
            Text(
                text = "Nema rezultata. Artikal možeš dodati kao opšti.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 230.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = searchState.results,
                    key = { product ->
                        product.barcode
                            ?.takeIf { it.isNotBlank() }
                            ?: product.productId
                    }
                ) { product ->
                    ProductSearchResultCard(
                        product = product,
                        onClick = {
                            onProductSelected(product)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductSearchResultCard(
    product: ProductSearchResultDto,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleSmall
            )

            product.brand
                ?.takeIf { it.isNotBlank() }
                ?.let { brand ->
                    Text(
                        text = "Brend: $brand",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

            product.categoryName
                ?.takeIf { it.isNotBlank() }
                ?.let { category ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

            if (
                product.retailerName != null &&
                product.effectivePrice != null
            ) {
                Text(
                    text = "${product.retailerName}: " +
                            formatCatalogPrice(product.effectivePrice),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SelectedProductCard(
    product: ProductSearchResultDto,
    onChangeProduct: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "Izabran proizvod",
                style = MaterialTheme.typography.labelMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = product.name,
                style = MaterialTheme.typography.titleSmall
            )

            product.barcode?.let {
                Text(
                    text = "Barkod: $it",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            TextButton(
                onClick = onChangeProduct,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Promeni proizvod")
            }
        }
    }
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

private fun formatCatalogPrice(value: Double): String {
    return BigDecimal
        .valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString() + " RSD"
}