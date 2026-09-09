package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.data.network.*

@HiltViewModel
class PurchaseViewModel @Inject constructor(private val repository: PurchaseRepository) : ViewModel() {
    private val _sessions = MutableStateFlow<List<rs.pametnakupovina.app.data.local.PurchaseSessionSummary>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private val _session = MutableStateFlow<PurchaseSession?>(null)
    val session = _session.asStateFlow()
    private var observeJob: kotlinx.coroutines.Job? = null
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _createdId = MutableStateFlow<String?>(null)
    val createdId = _createdId.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    init {
        viewModelScope.launch {
            repository.sessions.catch { _message.value = it.message ?: "Sačuvane kupovine nisu dostupne." }
                .collect { _sessions.value = it }
        }
    }

    fun load(id: String) {
        observeJob?.cancel()
        _session.value = null
        observeJob = viewModelScope.launch {
            repository.observe(id).catch { _message.value = it.message ?: "Kupovina nije dostupna." }
                .collect { _session.value = it }
        }
    }

    fun consumeNavigation() { _createdId.value = null }
    fun start(result: ShoppingRecommendationDto, scenario: OptimizationScenarioDto) {
        if (_saving.value || _createdId.value != null) return
        _saving.value = true
        action {
            try { _createdId.value = repository.start(result, scenario) }
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
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            _message.value = null
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _message.value = error.message ?: "Čuvanje nije uspelo. Pokušaj ponovo." }
        }
    }
}
