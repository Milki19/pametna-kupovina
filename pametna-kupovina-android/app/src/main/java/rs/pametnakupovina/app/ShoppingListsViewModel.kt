package rs.pametnakupovina.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class ShoppingListsUiState(
    val isLoading: Boolean = true,
    val lists: List<ShoppingListSummaryDto> = emptyList(),
    val errorMessage: String? = null
)

class ShoppingListsViewModel : ViewModel() {

    var uiState by mutableStateOf(ShoppingListsUiState())
        private set

    init {
        loadShoppingLists()
    }

    fun loadShoppingLists() {
        viewModelScope.launch {
            uiState = uiState.copy(
                isLoading = true,
                errorMessage = null
            )

            uiState = try {
                val lists = ApiClient.shoppingApi.getShoppingLists()

                ShoppingListsUiState(
                    isLoading = false,
                    lists = lists
                )
            } catch (exception: Exception) {
                ShoppingListsUiState(
                    isLoading = false,
                    errorMessage = exception.localizedMessage
                        ?: "Nije moguće povezivanje sa serverom."
                )
            }
        }
    }
}