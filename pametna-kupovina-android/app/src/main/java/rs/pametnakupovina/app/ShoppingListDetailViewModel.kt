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
    val isItemActionInProgress: Boolean = false,
    val shoppingList: ShoppingListDetailsDto? = null,
    val errorMessage: String? = null,
    val addItemError: String? = null,
    val itemActionError: String? = null
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
                    isLoading = false,
                    shoppingList = ApiClient.shoppingApi
                        .getShoppingList(listId)
                )
            } catch (exception: Exception) {
                ShoppingListDetailUiState(
                    isLoading = false,
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
                val addedItem =
                    ApiClient.shoppingApi.addShoppingListItem(
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

    fun updateItem(
        listId: Long,
        item: ShoppingListItemDto,
        quantity: Double,
        onSuccess: () -> Unit
    ) {
        val currentList = uiState.shoppingList ?: return

        viewModelScope.launch {
            uiState = uiState.copy(
                isItemActionInProgress = true,
                itemActionError = null
            )

            try {
                val updatedItem =
                    ApiClient.shoppingApi.updateShoppingListItem(
                        listId = listId,
                        itemId = item.id,
                        request = UpdateShoppingListItemRequest(
                            name = item.name,
                            barcode = item.barcode,
                            quantity = quantity
                        )
                    )

                val updatedItems = currentList.items.map { existingItem ->
                    if (existingItem.id == updatedItem.id) {
                        updatedItem
                    } else {
                        existingItem
                    }
                }

                uiState = uiState.copy(
                    isItemActionInProgress = false,
                    shoppingList = currentList.copy(
                        items = updatedItems
                    )
                )

                onSuccess()
            } catch (exception: Exception) {
                uiState = uiState.copy(
                    isItemActionInProgress = false,
                    itemActionError = exception.localizedMessage
                        ?: "Artikal nije izmenjen."
                )
            }
        }
    }

    fun deleteItem(
        listId: Long,
        item: ShoppingListItemDto,
        onSuccess: () -> Unit
    ) {
        val currentList = uiState.shoppingList ?: return

        viewModelScope.launch {
            uiState = uiState.copy(
                isItemActionInProgress = true,
                itemActionError = null
            )

            try {
                val response =
                    ApiClient.shoppingApi.deleteShoppingListItem(
                        listId = listId,
                        itemId = item.id
                    )

                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Server je vratio status ${response.code()}."
                    )
                }

                val remainingItems = currentList.items.filterNot {
                    it.id == item.id
                }

                uiState = uiState.copy(
                    isItemActionInProgress = false,
                    shoppingList = currentList.copy(
                        items = remainingItems
                    )
                )

                onSuccess()
            } catch (exception: Exception) {
                uiState = uiState.copy(
                    isItemActionInProgress = false,
                    itemActionError = exception.localizedMessage
                        ?: "Artikal nije obrisan."
                )
            }
        }
    }

    fun clearAddItemError() {
        uiState = uiState.copy(
            addItemError = null
        )
    }

    fun clearItemActionError() {
        uiState = uiState.copy(
            itemActionError = null
        )
    }

    fun retry(listId: Long) {
        loadShoppingList(
            listId = listId,
            forceReload = true
        )
    }
}