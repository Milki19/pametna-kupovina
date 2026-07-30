package rs.pametnakupovina.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ShoppingListsScreen(
    onListClick: (Long) -> Unit,
    viewModel: ShoppingListsViewModel = viewModel()
) {
    val state = viewModel.uiState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text(
            text = "Liste za kupovinu",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Greška pri povezivanju",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = state.errorMessage)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = viewModel::loadShoppingLists) {
                        Text("Pokušaj ponovo")
                    }
                }
            }

            state.lists.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Nema napravljenih lista.")
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = state.lists,
                        key = { it.id }
                    ) { shoppingList ->
                        ShoppingListCard(
                            shoppingList = shoppingList,
                            onClick = {
                                onListClick(shoppingList.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingListCard(
    shoppingList: ShoppingListSummaryDto,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = shoppingList.name,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (shoppingList.itemCount == 1L) {
                    "1 stavka"
                } else {
                    "${shoppingList.itemCount} stavki"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}