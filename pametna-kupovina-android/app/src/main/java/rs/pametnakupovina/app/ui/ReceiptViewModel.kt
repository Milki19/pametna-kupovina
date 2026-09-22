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
import java.time.LocalDate
import rs.pametnakupovina.app.data.NotAFiscalReceipt
import rs.pametnakupovina.app.data.ReceiptScanner
import rs.pametnakupovina.app.data.ScanCancelled
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.MonthlySpendingDto
import rs.pametnakupovina.app.data.network.ReceiptDto
import rs.pametnakupovina.app.data.network.ShopSpendingDto
import rs.pametnakupovina.app.data.network.WeeklySpendingDto

data class ReceiptUiState(
    val receipts: List<ReceiptDto> = emptyList(),
    val byMonth: List<MonthlySpendingDto> = emptyList(),
    val byShop: List<ShopSpendingDto> = emptyList(),
    val byWeek: List<WeeklySpendingDto> = emptyList(),
    val selectedMonth: LocalDate = LocalDate.now().withDayOfMonth(1),
    val scanning: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val scanner: ReceiptScanner
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiptUiState())
    val uiState: StateFlow<ReceiptUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Server je star ili nedostupan: ekran ostaje bez računa, bez crvenog.
     * Kupovine na ovom ekranu rade i bez mreže i ne smeju da ispaštaju.
     */
    fun refresh() {
        val month = _uiState.value.selectedMonth
        viewModelScope.launch {
            runCatching {
                repository.receipts() to repository.spending(month.toString())
            }
                .onSuccess { (receipts, spending) ->
                    _uiState.update {
                        it.copy(
                            receipts = receipts,
                            byMonth = spending.byMonth,
                            byShop = spending.byShop,
                            byWeek = spending.byWeek
                        )
                    }
                }
        }
    }

    /** Meni sme da vrati unazad koliko ima podataka, ali nikad u budućnost. */
    fun changeMonth(monthsDelta: Int) {
        val next = _uiState.value.selectedMonth.plusMonths(monthsDelta.toLong())
        if (next.isAfter(LocalDate.now().withDayOfMonth(1))) return
        _uiState.update { it.copy(selectedMonth = next) }
        refresh()
    }

    fun scan(activityContext: Context) {
        if (_uiState.value.scanning) return

        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true, message = null) }

            val message = try {
                val receipt = repository.scanReceipt(
                    scanner.verificationUrl(activityContext)
                )
                refresh()
                "Zaveden račun: ${receipt.shopName}, ${money(receipt.totalAmount)}."
            } catch (cancelled: ScanCancelled) {
                null
            } catch (notReceipt: NotAFiscalReceipt) {
                "To nije QR sa fiskalnog računa."
            } catch (scanFailed: MlKitException) {
                // Kamera nije uspela da pročita kod. Račun nije ni pokušan da
                // se zavede, pa ne sme da piše da nije zaveden.
                "Skeniranje nije uspelo. Probaj ponovo."
            } catch (failure: Exception) {
                "Račun nije zaveden. Probaj ponovo kasnije."
            }

            _uiState.update { it.copy(scanning = false, message = message) }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
