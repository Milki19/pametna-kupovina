package rs.pametnakupovina.app.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.data.network.*
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.uiText

@HiltViewModel
class PurchaseViewModel @Inject constructor(
    private val repository: PurchaseRepository,
    private val missingReporter: MissingProductReporter
) : ViewModel() {
    private val _sessions = MutableStateFlow<List<rs.pametnakupovina.app.data.local.PurchaseSessionSummary>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private val _session = MutableStateFlow<PurchaseSession?>(null)
    val session = _session.asStateFlow()
    private var observeJob: kotlinx.coroutines.Job? = null
    private val _message = MutableStateFlow<UiText?>(null)
    val message = _message.asStateFlow()
    private val _createdId = MutableStateFlow<String?>(null)
    val createdId = _createdId.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _activePurchase = MutableStateFlow<PurchaseSession?>(null)
    val activePurchase = _activePurchase.asStateFlow()
    private val _activeLoaded = MutableStateFlow(false)
    val activeLoaded = _activeLoaded.asStateFlow()
    private var activeJob: kotlinx.coroutines.Job? = null

    fun watchActive(listId: Long) {
        activeJob?.cancel()
        _activePurchase.value = null
        _activeLoaded.value = false
        activeJob = viewModelScope.launch {
            repository.observeActive(listId)
                .catch { _message.value = it.userText(R.string.purchase_error_active_unavailable) }
                .collect { _activePurchase.value = it; _activeLoaded.value = true }
        }
    }

    init {
        viewModelScope.launch {
            repository.sessions.catch { _message.value = it.userText(R.string.purchase_error_sessions_unavailable) }
                .collect { _sessions.value = it }
        }
    }

    fun load(id: String) {
        observeJob?.cancel()
        _session.value = null
        observeJob = viewModelScope.launch {
            repository.observe(id).catch { _message.value = it.userText(R.string.purchase_error_session_unavailable) }
                .collect { _session.value = it }
        }
    }

    fun consumeNavigation() { _createdId.value = null }
    fun start(result: ShoppingRecommendationDto, scenario: OptimizationScenarioDto, createNew: Boolean = false) {
        if (_saving.value || _createdId.value != null) return
        _saving.value = true
        action {
            try { _createdId.value = repository.start(result, scenario, createNew) }
            finally { _saving.value = false }
        }
    }

    fun status(id: String, itemId: Long, status: PurchaseStatus) = action {
        repository.update(id, itemId) { it.copy(status = status, boughtPackages = if (status == PurchaseStatus.TO_BUY) 0.0 else it.boughtPackages) }
    }
    fun details(id: String, itemId: Long, progress: PurchaseItemProgress, onSaved: () -> Unit = {}) = action {
        repository.update(id, itemId) { progress }
        onSaved()
    }
    fun archive(id: String, archived: Boolean) = action { repository.archive(id, archived) }

    /** Jednim dodirom: „nema u prodavnici" ide serveru, a stavka pamti da je javljeno. */
    fun reportMissing(id: String, item: RecommendationItemDto) {
        val canonicalProductId = item.canonicalProductId ?: return
        val retailerProductId = item.retailerProductId ?: return
        val storeId = item.storeId ?: return
        viewModelScope.launch {
            _message.value = null
            try {
                missingReporter.report(canonicalProductId, retailerProductId, storeId)
                repository.update(id, item.itemId) { it.copy(reportedMissing = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _message.value = error.userText(R.string.purchase_report_missing_failed)
            }
        }
    }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            _message.value = null
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _message.value = error.userText(R.string.purchase_error_save_failed) }
        }
    }

    /** The refusal the shopper can act on, or [fallback] for anything else. */
    private fun Throwable.userText(@StringRes fallback: Int): UiText =
        (this as? UserFacingException)?.text ?: uiText(fallback)
}
