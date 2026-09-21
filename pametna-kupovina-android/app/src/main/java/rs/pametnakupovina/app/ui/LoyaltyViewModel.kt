package rs.pametnakupovina.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.MlKitException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.BarcodeScanner
import rs.pametnakupovina.app.data.ScanCancelled
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.LoyaltyCardDto

data class LoyaltyUiState(
    val cards: List<LoyaltyCardDto> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val shown: LoyaltyCardDto? = null
)

@HiltViewModel
class LoyaltyViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val scanner: BarcodeScanner
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoyaltyUiState())
    val uiState: StateFlow<LoyaltyUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.loyaltyCards() }
                .onSuccess { cards -> _uiState.update { it.copy(cards = cards) } }
        }
    }

    /** Sa kartice se skenira njen kod; kupac posle samo doda naziv. */
    fun scanCard(activityContext: Context, onScanned: (String, String) -> Unit) {
        if (_uiState.value.busy) return

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }

            val message = try {
                val scanned = scanner.anyBarcode(activityContext)
                onScanned(scanned.value, scanned.format)
                null
            } catch (cancelled: ScanCancelled) {
                null
            } catch (failed: MlKitException) {
                "Skeniranje nije uspelo. Probaj ponovo."
            } catch (failure: Exception) {
                "Skeniranje nije uspelo."
            }

            _uiState.update { it.copy(busy = false, message = message) }
        }
    }

    fun add(name: String, cardNumber: String, barcodeFormat: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }

            val message = try {
                repository.addLoyaltyCard(name, cardNumber, barcodeFormat)
                refresh()
                null
            } catch (failure: Exception) {
                "Kartica nije sačuvana. Proveri broj pa probaj ponovo."
            }

            _uiState.update { it.copy(busy = false, message = message) }
        }
    }

    fun remove(cardId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteLoyaltyCard(cardId) }
                .onSuccess { refresh() }
            _uiState.update { it.copy(shown = null) }
        }
    }

    fun show(card: LoyaltyCardDto?) {
        _uiState.update { it.copy(shown = card) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
