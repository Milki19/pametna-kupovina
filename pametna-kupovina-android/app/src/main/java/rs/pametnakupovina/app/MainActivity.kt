package rs.pametnakupovina.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import rs.pametnakupovina.app.ui.theme.PametnaKupovinaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PametnaKupovinaTheme {
                var selectedListId by rememberSaveable {
                    mutableStateOf<Long?>(null)
                }

                var showBestPrices by rememberSaveable {
                    mutableStateOf(false)
                }

                val listsViewModel: ShoppingListsViewModel = viewModel()
                val listId = selectedListId

                when {
                    listId == null -> {
                        ShoppingListsScreen(
                            onListClick = { selectedId ->
                                selectedListId = selectedId
                                showBestPrices = false
                            },
                            viewModel = listsViewModel
                        )
                    }

                    showBestPrices -> {
                        BestPricesScreen(
                            listId = listId,
                            onBack = {
                                showBestPrices = false
                            }
                        )
                    }

                    else -> {
                        ShoppingListDetailScreen(
                            listId = listId,
                            onBack = {
                                selectedListId = null
                                showBestPrices = false
                                listsViewModel.loadShoppingLists()
                            },
                            onShowBestPrices = {
                                showBestPrices = true
                            }
                        )
                    }
                }
            }
        }
    }
}