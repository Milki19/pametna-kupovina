package rs.pametnakupovina.app.ui

import android.content.Context
import android.net.Uri
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
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.NotAFiscalReceipt
import rs.pametnakupovina.app.data.NotAReceiptFile
import rs.pametnakupovina.app.data.ReceiptFileTooLarge
import rs.pametnakupovina.app.data.ReceiptFiles
import rs.pametnakupovina.app.data.ReceiptScanner
import rs.pametnakupovina.app.data.ScanCancelled
import rs.pametnakupovina.app.data.ScannerFailed
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.market.CurrentMarket
import rs.pametnakupovina.app.data.network.CategorySpendingDto
import rs.pametnakupovina.app.data.network.HabitDto
import rs.pametnakupovina.app.data.network.MonthlySpendingDto
import rs.pametnakupovina.app.data.network.ReceiptDto
import rs.pametnakupovina.app.data.network.ShopSpendingDto
import rs.pametnakupovina.app.data.network.WeeklySpendingDto
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.uiText

data class ReceiptUiState(
    val receipts: List<ReceiptDto> = emptyList(),
    val byMonth: List<MonthlySpendingDto> = emptyList(),
    val byShop: List<ShopSpendingDto> = emptyList(),
    val byWeek: List<WeeklySpendingDto> = emptyList(),
    val byCategory: List<CategorySpendingDto> = emptyList(),
    val habits: List<HabitDto> = emptyList(),
    val selectedMonth: LocalDate = LocalDate.now(CurrentMarket.settings.zone).withDayOfMonth(1),
    val scanning: Boolean = false,
    val message: UiText? = null
)

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val scanner: ReceiptScanner,
    private val files: ReceiptFiles
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
                Triple(
                    repository.receipts(),
                    repository.spending(month.toString()),
                    repository.habits()
                )
            }
                .onSuccess { (receipts, spending, habits) ->
                    _uiState.update {
                        it.copy(
                            receipts = receipts,
                            byMonth = spending.byMonth,
                            byShop = spending.byShop,
                            byWeek = spending.byWeek,
                            byCategory = spending.byCategory,
                            habits = habits
                        )
                    }
                }
        }
    }

    /** Meni sme da vrati unazad koliko ima podataka, ali nikad u budućnost. */
    fun changeMonth(monthsDelta: Int) {
        val next = _uiState.value.selectedMonth.plusMonths(monthsDelta.toLong())
        if (next.isAfter(LocalDate.now(CurrentMarket.settings.zone).withDayOfMonth(1))) return
        _uiState.update { it.copy(selectedMonth = next) }
        refresh()
    }

    fun scan(activityContext: Context) {
        if (_uiState.value.scanning) return

        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true, message = null) }

            val message: UiText? = try {
                val receipt = repository.scanReceipt(
                    scanner.verificationUrl(activityContext)
                )
                refresh()
                uiText(R.string.receipt_saved, receipt.shopName, money(receipt.totalAmount))
            } catch (cancelled: ScanCancelled) {
                null
            } catch (notReceipt: NotAFiscalReceipt) {
                uiText(R.string.receipt_not_fiscal)
            } catch (_: ScannerFailed) {
                uiText(R.string.receipt_scan_failed)
            } catch (scanFailed: MlKitException) {
                // Kamera nije uspela da pročita kod. Račun nije ni pokušan da
                // se zavede, pa ne sme da piše da nije zaveden.
                if (scanFailed.errorCode == MlKitException.UNAVAILABLE) {
                    uiText(R.string.receipt_scanner_downloading)
                } else {
                    uiText(R.string.receipt_scan_failed)
                }
            } catch (failure: Exception) {
                // Server kaže zašto (predračun, povraćaj, nečitljiv kod);
                // „nije zaveden" bez razloga ostavlja kupca da nagađa.
                failure.toUserMessage(R.string.receipt_not_saved)
            }

            _uiState.update { it.copy(scanning = false, message = message) }
        }
    }

    /**
     * Digitalni račun (screenshot ili PDF iz aplikacije trgovine): server sam
     * pročita QR kod sa fajla i zavede račun kao da je skeniran.
     */
    fun importFile(uri: Uri) {
        if (_uiState.value.scanning) return

        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true, message = null) }

            val message: UiText = try {
                val receipt = repository.scanReceiptFile(files.read(uri))
                refresh()
                uiText(R.string.receipt_saved, receipt.shopName, money(receipt.totalAmount))
            } catch (_: ReceiptFileTooLarge) {
                uiText(R.string.receipt_file_too_large)
            } catch (_: NotAReceiptFile) {
                uiText(R.string.receipt_file_unreadable)
            } catch (failure: Exception) {
                failure.toUserMessage(R.string.receipt_not_saved)
            }

            _uiState.update { it.copy(scanning = false, message = message) }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
