package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import rs.pametnakupovina.app.data.ShoppingRepository

/**
 * Samo broj stavki na spisku, bez sinhronizacije — to već radi
 * ShoppingListViewModel kad se otvori Moj spisak.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: ShoppingRepository
) : ViewModel() {

    val listItemCount: StateFlow<Int> = repository.draftItems
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}
