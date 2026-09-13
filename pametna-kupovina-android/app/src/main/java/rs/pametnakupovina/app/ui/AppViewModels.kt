package rs.pametnakupovina.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import rs.pametnakupovina.app.data.DraftItemInput
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.ItemSyncValidationException
import kotlinx.coroutines.CancellationException
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.network.ProductCandidateDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchResultDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.sync.SyncScheduler

data class ShoppingListUiState(
    val items: List<DraftItemEntity> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null
)

@HiltViewModel
class ShoppingListViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShoppingListUiState())
    val uiState: StateFlow<ShoppingListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.draftItems.collect { items ->
                _uiState.update { it.copy(items = items) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInitialLoading = it.items.isEmpty(),
                    isSyncing = true,
                    errorMessage = null,
                    notice = null
                )
            }
            try {
                repository.synchronizeAndRefresh()
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        isSyncing = false,
                        isOffline = false
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                syncScheduler.enqueue()
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        isSyncing = false,
                        isOffline = error is IOException,
                        errorMessage = if (it.items.isEmpty() || error is ItemSyncValidationException) {
                            error.toUserMessage(
                                "Server trenutno nije dostupan. Možeš ipak napraviti spisak."
                            )
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun addItem(input: DraftItemInput, onSaved: () -> Unit) {
        mutate(onSaved) { repository.addItem(input) }
    }

    fun updateItem(
        item: DraftItemEntity,
        input: DraftItemInput,
        onSaved: () -> Unit
    ) {
        mutate(onSaved) { repository.updateItem(item, input) }
    }

    fun deleteItem(item: DraftItemEntity) {
        mutate { repository.deleteItem(item) }
    }

    fun pasteItems(text: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                val count = repository.pasteItems(text)
                // Name only the lines that actually received a suggested amount.
                val suggested = rs.pametnakupovina.app.data.PastedListParser.parse(text)
                    .map { it.name }
                    .filter { rs.pametnakupovina.app.data.suggestedAmount(it) != null }
                    .distinct()
                onSaved()
                _uiState.update {
                    it.copy(
                        notice = "Dodato: ${items(count)}." + if (suggested.isEmpty()) {
                            ""
                        } else {
                            " Za ${suggested.joinToString(", ")} predložena je ukupna količina, proveri je pre računanja."
                        },
                        errorMessage = null
                    )
                }
                synchronizeSilently()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.toUserMessage("Spisak nije dodat."))
                }
            }
        }
    }

    fun prepareMatching(onReady: (Long) -> Unit) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSyncing = true,
                    errorMessage = null,
                    notice = null
                )
            }
            try {
                val listId = repository.synchronizePending()
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        isOffline = false
                    )
                }
                onReady(listId)
            } catch (error: Exception) {
                syncScheduler.enqueue()
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        isOffline = error is IOException,
                        errorMessage = error.toUserMessage(
                            "Za proveru proizvoda je potrebna veza sa serverom."
                        )
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(errorMessage = null, notice = null) }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /**
     * Undoes a delete by creating the item again through the normal path, so
     * it syncs like any new item even if the delete already reached the server.
     */
    fun restoreItem(item: DraftItemEntity) {
        val rule = ShoppingItemRuleDto.valueOf(item.matchingRule)
        mutate {
            repository.addItem(
                DraftItemInput(
                    name = item.name,
                    rawInput = item.rawInput,
                    // The server writes its match into these same columns, so
                    // keep only what the rule allows, as the editor would.
                    barcode = item.barcode
                        .takeIf { rule == ShoppingItemRuleDto.EXACT_PRODUCT },
                    canonicalProductId = item.canonicalProductId
                        .takeIf { rule == ShoppingItemRuleDto.EXACT_PRODUCT },
                    productFamilyId = item.productFamilyId
                        .takeIf { rule == ShoppingItemRuleDto.PRODUCT_FAMILY },
                    quantity = item.quantity,
                    matchingRule = rule,
                    category = item.category,
                    requiredBrand = item.requiredBrand,
                    minPackageQuantity = item.minPackageQuantity,
                    maxPackageQuantity = item.maxPackageQuantity,
                    requiredBaseUnit = item.requiredBaseUnit,
                    targetQuantity = item.targetQuantity
                )
            )
        }
    }

    private fun mutate(
        onSaved: () -> Unit = {},
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                action()
                onSaved()
                _uiState.update { it.copy(errorMessage = null) }
                synchronizeSilently()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.toUserMessage("Izmena nije sačuvana."))
                }
            }
        }
    }

    private suspend fun synchronizeSilently() {
        syncScheduler.enqueue()
        try {
            repository.synchronizePending()
            _uiState.update { it.copy(isOffline = false, errorMessage = null) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(
                isOffline = error is IOException,
                errorMessage = if (error is IOException) null
                    else error.toUserMessage("Sinhronizacija trenutno nije uspela.")
            ) }
        }
    }

}

data class MatchingUiState(
    val isLoading: Boolean = true,
    val isResolving: Boolean = false,
    val result: ShoppingListMatchingDto? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class MatchingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ShoppingRepository
) : ViewModel() {
    val listId: Long = requireNotNull(
        savedStateHandle.get<Long>("listId")
    )

    private val _uiState = MutableStateFlow(MatchingUiState())
    val uiState: StateFlow<MatchingUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = MatchingUiState(isLoading = true)
            _uiState.value = try {
                MatchingUiState(
                    isLoading = false,
                    result = repository.matchItems()
                )
            } catch (error: Exception) {
                MatchingUiState(
                    isLoading = false,
                    errorMessage = error.toUserMessage(
                        "Provera proizvoda nije uspela."
                    )
                )
            }
        }
    }

    fun confirm(
        item: ShoppingItemMatchResultDto,
        candidate: ProductCandidateDto?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true, errorMessage = null) }
            try {
                repository.resolveMatch(
                    listId = _uiState.value.result?.listId ?: listId,
                    itemId = item.itemId,
                    candidateId = candidate?.canonicalProductId
                )
                val refreshed = repository.matchItems()
                _uiState.value = MatchingUiState(
                    isLoading = false,
                    result = refreshed
                )
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isResolving = false,
                        errorMessage = error.toUserMessage(
                            "Potvrda nije sačuvana."
                        )
                    )
                }
            }
        }
    }

    fun useAsFlexible(item: ShoppingItemMatchResultDto) {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true, errorMessage = null) }
            try {
                val refreshed = repository.convertToFlexible(
                    itemId = item.itemId,
                    category = item.requestedName
                )
                _uiState.value = MatchingUiState(
                    isLoading = false,
                    result = refreshed
                )
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isResolving = false,
                        errorMessage = error.toUserMessage(
                            "Stavka nije pretvorena u fleksibilnu."
                        )
                    )
                }
            }
        }
    }
}

data class RecommendationUiState(
    val isLoading: Boolean = false,
    val result: ShoppingRecommendationDto? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class CalculationSessionViewModel @Inject constructor() : ViewModel() {
    private val _location = MutableStateFlow<Pair<Double, Double>?>(null)
    val location: StateFlow<Pair<Double, Double>?> = _location.asStateFlow()

    fun useLocation(latitude: Double, longitude: Double) {
        _location.value = latitude to longitude
    }
}

@HiltViewModel
class RecommendationViewModel @Inject constructor(
    private val repository: ShoppingRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecommendationUiState())
    val uiState: StateFlow<RecommendationUiState> = _uiState.asStateFlow()

    suspend fun alternativeQuery(itemId: Long) = repository.alternativeQuery(itemId)

    fun replaceAlternative(listId: Long,itemId: Long,
        product: rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto,
        packages: Double,latitude: Double,longitude: Double,onDone: (String?) -> Unit) {
        viewModelScope.launch {
            var savedLocally = false
            try {
                repository.replaceWithAlternative(itemId,product,packages)
                savedLocally = true
                // The displayed allocation no longer describes the edited list.
                _uiState.value = RecommendationUiState(isLoading = true)
                val result=repository.getRecommendations(listId,latitude,longitude)
                _uiState.value=RecommendationUiState(result=result)
                onDone(null)
            } catch(error: kotlinx.coroutines.CancellationException) { throw error }
            catch(error: Exception) {
                val message = if (savedLocally) {
                    "Zamena je sačuvana na ovom uređaju, ali novi plan nije izračunat. Proveri vezu i ponovi računanje."
                } else {
                    error.toUserMessage("Zamena nije sačuvana. Pokušaj ponovo.")
                }
                if (savedLocally) _uiState.value = RecommendationUiState(errorMessage = message)
                onDone(message)
            }
        }
    }

    fun load(listId: Long, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            _uiState.value = RecommendationUiState(isLoading = true)
            _uiState.value = try {
                RecommendationUiState(
                    isLoading = false,
                    result = repository.getRecommendations(
                        listId = listId,
                        latitude = latitude,
                        longitude = longitude
                    )
                )
            } catch (error: Exception) {
                RecommendationUiState(
                    isLoading = false,
                    errorMessage = error.toUserMessage(
                        "Preporuke trenutno nisu dostupne."
                    )
                )
            }
        }
    }
}

internal fun Throwable.toUserMessage(fallback: String): String = when (this) {
    is IOException -> fallback
    is HttpException -> when (code()) {
        400 -> "Proveri unesene podatke i pokušaj ponovo."
        401, 403 -> "Ovaj spisak više nije dostupan na serveru."
        404 -> "Traženi spisak ili stavka više ne postoji."
        in 500..599 -> "Server trenutno ima problem. Pokušaj ponovo."
        else -> fallback
    }
    is IllegalArgumentException -> message ?: fallback
    else -> fallback
}
