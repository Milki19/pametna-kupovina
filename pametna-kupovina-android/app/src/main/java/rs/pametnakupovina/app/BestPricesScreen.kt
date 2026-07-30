package rs.pametnakupovina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun BestPricesScreen(
    listId: Long,
    onBack: () -> Unit,
    viewModel: BestPricesViewModel = viewModel()
) {
    val state = viewModel.uiState

    BackHandler(onBack = onBack)

    LaunchedEffect(listId) {
        viewModel.loadBestPrices(listId)
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
            Text("← Nazad na listu")
        }

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Greška pri izračunavanju",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(state.errorMessage)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.retry(listId)
                        }
                    ) {
                        Text("Pokušaj ponovo")
                    }
                }
            }

            state.result != null -> {
                BestPricesContent(state.result)
            }
        }
    }
}

@Composable
private fun ColumnScope.BestPricesContent(
    result: BestPricesDto
) {
    Text(
        text = "Najbolje cene",
        style = MaterialTheme.typography.headlineMedium
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = result.listName,
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (result.unmatchedItems > 0) {
                    "Ukupno za pronađene: ${formatMoney(result.totalPrice)}"
                } else {
                    "Ukupno: ${formatMoney(result.totalPrice)}"
                },
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("Pronađeno artikala: ${result.matchedItems}")

            Text(
                text = "Bez pronađene cene: ${result.unmatchedItems}",
                color = if (result.unmatchedItems > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = result.items,
            key = { it.itemId }
        ) { item ->
            BestPriceItemCard(item)
        }
    }
}

@Composable
private fun BestPriceItemCard(
    item: BestPriceItemDto
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = item.requestedName,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (item.matched) {
                item.productName?.let {
                    Text(
                        text = "Pronađeno: $it",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                item.retailerName?.let {
                    Text(
                        text = "Prodavnica: $it",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                item.effectivePrice?.let {
                    Text(
                        text = "Cena: ${formatMoney(it)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "Količina: ${formatBestPriceQuantity(item.quantity)}",
                    style = MaterialTheme.typography.bodyMedium
                )

                item.lineTotal?.let {
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Ukupno: ${formatMoney(it)}",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            } else {
                Text(
                    text = "Cena nije pronađena",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun formatMoney(value: Double): String {
    return BigDecimal
        .valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString() + " RSD"
}

private fun formatBestPriceQuantity(quantity: Double): String {
    val wholeNumber = quantity.toLong()

    return if (quantity == wholeNumber.toDouble()) {
        wholeNumber.toString()
    } else {
        quantity.toString()
    }
}