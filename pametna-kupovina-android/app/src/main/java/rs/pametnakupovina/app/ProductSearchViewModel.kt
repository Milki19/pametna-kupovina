package rs.pametnakupovina.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ProductSearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<ProductSearchResultDto> = emptyList(),
    val errorMessage: String? = null
)

class ProductSearchViewModel : ViewModel() {

    var uiState by mutableStateOf(ProductSearchUiState())
        private set

    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        searchJob?.cancel()

        uiState = ProductSearchUiState(query = query)

        val normalizedQuery = query.trim()

        if (normalizedQuery.length < 2) {
            return
        }

        searchJob = viewModelScope.launch {
            delay(350)

            uiState = uiState.copy(
                isLoading = true,
                errorMessage = null
            )

            try {
                val products = ApiClient.shoppingApi.searchProducts(
                    query = normalizedQuery,
                    limit = 20
                )

                uiState = uiState.copy(
                    isLoading = false,
                    results = selectBestOfferPerProduct(products)
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                uiState = uiState.copy(
                    isLoading = false,
                    errorMessage = exception.localizedMessage
                        ?: "Pretraga proizvoda nije uspela."
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        uiState = ProductSearchUiState()
    }

    private fun selectBestOfferPerProduct(
        products: List<ProductSearchResultDto>
    ): List<ProductSearchResultDto> {
        return products
            .groupBy { product ->
                product.barcode
                    ?.takeIf { it.isNotBlank() }
                    ?: "product-${product.productId}"
            }
            .values
            .map { offers ->
                offers.minByOrNull { offer ->
                    offer.effectivePrice ?: Double.MAX_VALUE
                } ?: offers.first()
            }
    }
}