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

                val listsViewModel: ShoppingListsViewModel = viewModel()

                selectedListId?.let { listId ->
                    ShoppingListDetailScreen(
                        listId = listId,
                        onBack = {
                            selectedListId = null
                            listsViewModel.loadShoppingLists()
                        }
                    )
                } ?: ShoppingListsScreen(
                    onListClick = { listId ->
                        selectedListId = listId
                    },
                    viewModel = listsViewModel
                )
            }
        }
    }
}