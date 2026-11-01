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
import rs.pametnakupovina.app.data.network.SaleRetailerDto
import rs.pametnakupovina.app.data.preferences.SaleRadius
import rs.pametnakupovina.app.data.preferences.SaleRadiusStore
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
    // Server je znao gde je kupac, pa su na spisku samo lanci u krugu.
    val nearby: Boolean = false,
    // Mesto je poznato (telefon ili polazna tačka plana), pa kupac bira krug.
    val located: Boolean = false,
    val radius: SaleRadius = SaleRadius.DEFAULT,
    // Bez lokacije telefona krug se meri od polazne tačke plana; ovo je njena adresa.
    val fromOrigin: Boolean = false,
    val originLabel: String? = null,
    // Lanci sa akcijama u krugu, sa brojem proizvoda; stižu uz prvu stranu.
    val retailers: List<SaleRetailerDto> = emptyList(),
    val retailer: String? = null,
    val retailerName: String? = null,
    val errorMessage: UiText? = null,
    val notice: UiText? = null,
    // Proizvodi koji su već na spisku, odakle god da su dodati.
    val added: Set<Long> = emptySet()
)

@HiltViewModel
class SalesViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val locationProvider: FusedLocationProvider,
    private val syncScheduler: SyncScheduler,
    private val radiusStore: SaleRadiusStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SalesUiState())
    val uiState: StateFlow<SalesUiState> = _uiState.asStateFlow()

    // Lokacija telefona se traži jednom; polazna tačka plana je rezerva.
    private var phoneNear: Coordinates? = null
    private var phoneAsked = false
    private var origin: Coordinates? = null
    private var originLabel: String? = null
    private var loadJob: Job? = null
    private var unlocatedChains: Set<String>? = null

    private suspend fun chainsWithoutAddresses(): Set<String> =
        unlocatedChains ?: try {
            repository.chainsWithoutAddresses().also { unlocatedChains = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptySet()
        }

    init {
        viewModelScope.launch {
            val radius = try {
                radiusStore.radius()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                SaleRadius.DEFAULT
            }
            _uiState.update { it.copy(radius = radius) }
            load()
        }
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

    fun chooseRadius(radius: SaleRadius) {
        if (radius == _uiState.value.radius) return
        _uiState.update {
            it.copy(
                radius = radius,
                // „Najbliže" bez kruga nema smisla, pa ni čip.
                sort = if (radius == SaleRadius.ALL && it.sort == SaleSortDto.NEAREST) SaleSortDto.DISCOUNT else it.sort
            )
        }
        viewModelScope.launch {
            try {
                radiusStore.choose(radius)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Izbor važi i kad ne može da se zapamti.
            }
        }
        load()
    }

    fun chooseRetailer(retailer: SaleRetailerDto?) {
        if (retailer?.code == _uiState.value.retailer) return
        _uiState.update { it.copy(retailer = retailer?.code, retailerName = retailer?.name) }
        load()
    }

    /**
     * Polazna tačka iz plana, kad telefon ne da lokaciju. Zaokružuje se na
     * ~1 km, kao lokacija telefona: za krug je to dovoljno.
     */
    fun useOrigin(latitude: Double?, longitude: Double?, label: String?) {
        val next = if (latitude != null && longitude != null) {
            Coordinates(roughly(latitude), roughly(longitude))
        } else {
            null
        }
        if (next == origin && label == originLabel) return
        origin = next
        originLabel = label
        // Dok se lokacija telefona još traži, fetch će sam uzeti polaznu tačku.
        if (phoneAsked && phoneNear == null) load()
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
                // Lanac bez adresa prodavnica nikad nije u planu: to se kaže odmah.
                val withoutAddresses = item.retailerCode in chainsWithoutAddresses()
                _uiState.update {
                    it.copy(
                        added = it.added + item.productFamilyId,
                        notice = if (withoutAddresses) {
                            uiText(R.string.sale_added_chain_without_addresses, item.name, item.retailerName)
                        } else {
                            uiText(R.string.list_notice_added, item.name)
                        }
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
                if (!phoneAsked) {
                    phoneNear = locationProvider.nearbyLocation()
                    phoneAsked = true
                }
                val near = phoneNear ?: origin
                val fromOrigin = phoneNear == null && origin != null
                val response = repository.sales(
                    category = asked.category,
                    sort = asked.sort,
                    page = page,
                    limit = PAGE_SIZE,
                    near = near.takeUnless { asked.radius == SaleRadius.ALL },
                    retailer = asked.retailer,
                    radiusMeters = asked.radius.meters
                )
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        loadingMore = false,
                        items = if (page == 0) response.items
                            else (current.items + response.items).distinctBy { it.productFamilyId },
                        // Kategorije stižu samo uz prvu stranu.
                        categories = if (page == 0) response.categories else current.categories,
                        retailers = if (page == 0) response.retailers else current.retailers,
                        page = response.page,
                        totalElements = response.totalElements,
                        hasNext = response.hasNext,
                        nearby = response.nearbyChecked,
                        located = near != null,
                        fromOrigin = fromOrigin,
                        originLabel = originLabel.takeIf { fromOrigin }
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

        fun roughly(degrees: Double) = Math.round(degrees * 100) / 100.0
    }
}
