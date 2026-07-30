package rs.pametnakupovina.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class ShoppingListDetailUiState(
    val isLoading: Boolean = false,
    val isAddingItem: Boolean = false,
    val shoppingList: ShoppingListDetailsDto? = null,
    val errorMessage: String? = null,
    val addItemError: String? = null
)

class ShoppingListDetailViewModel : ViewModel() {

    var uiState by mutableStateOf(ShoppingListDetailUiState())
        private set

    private var loadedListId: Long? = null

    fun loadShoppingList(
        listId: Long,
        forceReload: Boolean = false
    ) {
        if (
            !forceReload &&
            loadedListId == listId &&
            uiState.shoppingList != null
        ) {
            return
        }

        loadedListId = listId

        viewModelScope.launch {
            uiState = ShoppingListDetailUiState(
                isLoading = true
            )

            uiState = try {
                ShoppingListDetailUiState(
                    shoppingList = ApiClient.shoppingApi.getShoppingList(listId)
                )
            } catch (exception: Exception) {
                ShoppingListDetailUiState(
                    errorMessage = exception.localizedMessage
                        ?: "Nije moguće učitati listu."
                )
            }
        }
    }

    fun addItem(
        listId: Long,
        name: String,
        barcode: String?,
        quantity: Double,
        onSuccess: () -> Unit
    ) {
        val currentList = uiState.shoppingList ?: return

        viewModelScope.launch {
            uiState = uiState.copy(
                isAddingItem = true,
                addItemError = null
            )

            try {
                val addedItem = ApiClient.shoppingApi.addShoppingListItem(
                    listId = listId,
                    request = CreateShoppingListItemRequest(
                        name = name.trim(),
                        barcode = barcode
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                        quantity = quantity
                    )
                )

                uiState = uiState.copy(
                    isAddingItem = false,
                    shoppingList = currentList.copy(
                        items = currentList.items + addedItem
                    )
                )

                onSuccess()
            } catch (exception: Exception) {
                uiState = uiState.copy(
                    isAddingItem = false,
                    addItemError = exception.localizedMessage
                        ?: "Artikal nije dodat."
                )
            }
        }
    }

    fun clearAddItemError() {
        uiState = uiState.copy(addItemError = null)
    }

    fun retry(listId: Long) {
        loadShoppingList(
            listId = listId,
            forceReload = true
        )
    }
}