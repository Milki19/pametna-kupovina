package rs.pametnakupovina.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.alerts.PriceWatchStore
import rs.pametnakupovina.app.alerts.WatchedProduct
import rs.pametnakupovina.app.alerts.bestPrice
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.ProductReportReasonDto

data class ProductDetailsUiState(
    val isLoading: Boolean = true,
    val product: CanonicalProductDetailsDto? = null,
    val errorMessage: String? = null,
    val reportMessage: String? = null,
    val isReporting: Boolean = false
)

@HiltViewModel
class ProductDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ShoppingRepository,
    private val priceWatch: PriceWatchStore
) : ViewModel() {

    val canonicalProductId: Long = requireNotNull(
        savedStateHandle.get<Long>("canonicalProductId")
    )

    private val _uiState = MutableStateFlow(ProductDetailsUiState())
    val uiState: StateFlow<ProductDetailsUiState> = _uiState.asStateFlow()

    val watching: StateFlow<Boolean> = priceWatch.watched
        .map { list -> list.any { it.canonicalProductId == canonicalProductId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        load()
    }

    /** Prati se najniža cena koju ekran upravo pokazuje. */
    fun setWatching(watch: Boolean) {
        viewModelScope.launch {
            if (!watch) return@launch priceWatch.unwatch(canonicalProductId)
            val product = _uiState.value.product ?: return@launch
            val offer = bestPrice(product) ?: return@launch
            priceWatch.watch(WatchedProduct(canonicalProductId, product.name, offer.effectivePrice))
        }
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

    /** A wrong price or a listing that is not this product goes to review. */
    fun report(reason: ProductReportReasonDto, note: String, onSent: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isReporting = true, reportMessage = null) }
            val message = try {
                repository.reportProduct(canonicalProductId, reason, note)
                onSent()
                "Hvala, proverićemo."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.toUserMessage("Prijava nije poslata. Pokušaj ponovo.")
            }
            _uiState.update { it.copy(isReporting = false, reportMessage = message) }
        }
    }

    fun clearReportMessage() {
        _uiState.update { it.copy(reportMessage = null) }
    }
}
