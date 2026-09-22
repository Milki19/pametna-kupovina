package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.ShoppingRepository

data class DashboardUiState(val listItemCount: Int = 0)

/**
 * Samo broj stavki na spisku, bez sinhronizacije — to već radi
 * ShoppingListViewModel kad se otvori Moj spisak.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: ShoppingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.draftItems.collect { items ->
                _uiState.update { it.copy(listItemCount = items.size) }
            }
        }
    }
}
