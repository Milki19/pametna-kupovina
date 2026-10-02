package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.DraftItemInput
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.SaleCategoryDto
import rs.pametnakupovina.app.data.network.SaleItemDto
import rs.pametnakupovina.app.data.network.SaleSortDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.location.Coordinates
import rs.pametnakupovina.app.location.FusedLocationProvider
import rs.pametnakupovina.app.sync.SyncScheduler
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.uiText

data class SalesUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val items: List<SaleItemDto> = emptyList(),
    val categories: List<SaleCategoryDto> = emptyList(),
    val category: String? = null,
    val sort: SaleSortDto = SaleSortDto.DISCOUNT,
    val page: Int = 0,
    val totalElements: Long = 0,
    val hasNext: Boolean = false,
    // Server je znao gde je kupac, pa su na spisku samo lanci u blizini.
    val nearby: Boolean = false,
    val errorMessage: UiText? = null,
    val notice: UiText? = null,
    // Proizvodi koji su već na spisku, odakle god da su dodati.
    val added: Set<Long> = emptySet()
)

@HiltViewModel
class SalesViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val locationProvider: FusedLocationProvider,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(SalesUiState())
    val uiState: StateFlow<SalesUiState> = _uiState.asStateFlow()

    private var near: Coordinates? = null
    private var nearAsked = false
    private var loadJob: Job? = null

    init {
        load()
        // „Na spisku" važi dok je proizvod na spisku, i kad se ekran ponovo otvori.
        viewModelScope.launch {
            repository.draftItems.collect { items ->
                _uiState.update { state ->
                    state.copy(added = items.mapNotNullTo(mutableSetOf()) { it.productFamilyId })
                }
            }
        }
    }

    fun chooseCategory(code: String?) {
        if (code == _uiState.value.category) return
        _uiState.update { it.copy(category = code) }
        load()
    }

    fun chooseSort(sort: SaleSortDto) {
        if (sort == _uiState.value.sort) return
        _uiState.update { it.copy(sort = sort) }
        load()
    }

    fun retry() = load()

    fun loadMore() {
        val current = _uiState.value
        if (!current.hasNext || current.loading || current.loadingMore) return
        _uiState.update { it.copy(loadingMore = true, errorMessage = null) }
        fetch(page = current.page + 1)
    }

    /** Na spisak ide proizvod, ne lanac: plan posle bira najjeftiniju radnju. */
    fun addToList(item: SaleItemDto) {
        viewModelScope.launch {
            try {
                repository.addItem(
                    DraftItemInput(
                        name = item.name,
                        rawInput = item.name,
                        productFamilyId = item.productFamilyId,
                        quantity = 1.0,
                        matchingRule = ShoppingItemRuleDto.PRODUCT_FAMILY
                    )
                )
                syncScheduler.enqueue()
                _uiState.update {
                    it.copy(
                        added = it.added + item.productFamilyId,
                        notice = uiText(R.string.list_notice_added, item.name)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(notice = error.toUserMessage(R.string.list_error_change_not_saved))
                }
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun load() {
        _uiState.update {
            it.copy(loading = true, loadingMore = false, errorMessage = null)
        }
        fetch(page = 0)
    }

    private fun fetch(page: Int) {
        loadJob?.cancel()
        val asked = _uiState.value
        loadJob = viewModelScope.launch {
            try {
                if (!nearAsked) {
                    nearAsked = true
                    near = locationProvider.nearbyLocation()
                }
                val response = repository.sales(
                    category = asked.category,
                    sort = asked.sort,
                    page = page,
                    limit = PAGE_SIZE,
                    near = near
                )
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        loadingMore = false,
                        items = if (page == 0) response.items
                            else (current.items + response.items).distinctBy { it.productFamilyId },
                        // Kategorije stižu samo uz prvu stranu.
                        categories = if (page == 0) response.categories else current.categories,
                        page = response.page,
                        totalElements = response.totalElements,
                        hasNext = response.hasNext,
                        nearby = response.nearbyChecked
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        errorMessage = error.toUserMessage(R.string.sale_load_error)
                    )
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
