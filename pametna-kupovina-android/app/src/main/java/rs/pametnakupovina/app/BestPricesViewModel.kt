package rs.pametnakupovina.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class BestPricesUiState(
    val isLoading: Boolean = false,
    val result: BestPricesDto? = null,
    val errorMessage: String? = null
)

class BestPricesViewModel : ViewModel() {

    var uiState by mutableStateOf(BestPricesUiState())
        private set

    private var loadedListId: Long? = null

    fun loadBestPrices(
        listId: Long,
        forceReload: Boolean = false
    ) {
        if (
            !forceReload &&
            loadedListId == listId &&
            uiState.result != null
        ) {
            return
        }

        loadedListId = listId

        viewModelScope.launch {
            uiState = BestPricesUiState(
                isLoading = true
            )

            uiState = try {
                BestPricesUiState(
                    result = ApiClient.shoppingApi.getBestPrices(listId)
                )
            } catch (exception: Exception) {
                BestPricesUiState(
                    errorMessage = exception.localizedMessage
                        ?: "Nije moguće izračunati najbolje cene."
                )
            }
        }
    }

    fun retry(listId: Long) {
        loadBestPrices(
            listId = listId,
            forceReload = true
        )
    }
}