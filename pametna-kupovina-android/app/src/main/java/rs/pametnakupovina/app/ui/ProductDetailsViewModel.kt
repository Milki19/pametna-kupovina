package rs.pametnakupovina.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto

data class ProductDetailsUiState(
    val isLoading: Boolean = true,
    val product: CanonicalProductDetailsDto? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ProductDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ShoppingRepository
) : ViewModel() {

    val canonicalProductId: Long = requireNotNull(
        savedStateHandle.get<Long>("canonicalProductId")
    )

    private val _uiState = MutableStateFlow(ProductDetailsUiState())
    val uiState: StateFlow<ProductDetailsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = ProductDetailsUiState(isLoading = true)
            _uiState.value = try {
                ProductDetailsUiState(
                    isLoading = false,
                    product = repository.getProductDetails(canonicalProductId)
                )
            } catch (error: Exception) {
                ProductDetailsUiState(
                    isLoading = false,
                    errorMessage = error.toUserMessage(
                        "Detalji proizvoda trenutno nisu dostupni."
                    )
                )
            }
        }
    }
}
