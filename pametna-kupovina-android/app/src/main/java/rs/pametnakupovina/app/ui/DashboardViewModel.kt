package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.DraftItemInput
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.HabitDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.sync.SyncScheduler

/**
 * Broj stavki na spisku, bez sinhronizacije — to već radi
 * ShoppingListViewModel kad se otvori Moj spisak — i dodavanje navike na spisak.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    val listItemCount: StateFlow<Int> = repository.draftItems
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Porodice proizvoda koje su već na spisku, za „Na spisku" uz naviku. */
    val familiesOnList: StateFlow<Set<Long>> = repository.draftItems
        .map { items -> items.mapNotNullTo(mutableSetOf()) { it.productFamilyId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Kao „Na spisak" u Akcijama: proizvod, a plan bira najjeftiniju radnju. */
    fun addHabitToList(habit: HabitDto) {
        viewModelScope.launch {
            try {
                repository.addItem(
                    DraftItemInput(
                        name = habit.name,
                        rawInput = habit.name,
                        productFamilyId = habit.productFamilyId,
                        quantity = 1.0,
                        matchingRule = ShoppingItemRuleDto.PRODUCT_FAMILY
                    )
                )
                syncScheduler.enqueue()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Stavka se čuva na telefonu; ako ni to ne uspe, dugme ostaje „Na spisak".
            }
        }
    }
}
