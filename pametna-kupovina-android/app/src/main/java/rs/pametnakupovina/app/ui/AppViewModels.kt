package rs.pametnakupovina.app.ui

import androidx.annotation.StringRes
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
import rs.pametnakupovina.app.data.network.serverMessage
import rs.pametnakupovina.app.sync.SyncScheduler
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.asUiText
import rs.pametnakupovina.app.text.uiPlural
import rs.pametnakupovina.app.text.uiText

/** Rows the server refused when the shopper asked for a calculation. */
data class SkippedItems(val listId: Long, val names: List<String>)

data class ShoppingListUiState(
    val items: List<DraftItemEntity> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: UiText? = null,
    val notice: UiText? = null,
    val skippedItems: SkippedItems? = null
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
                            error.toUserMessage(R.string.list_error_server_unavailable)
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
                onSaved()
                _uiState.update {
                    it.copy(
                        notice = uiText(R.string.list_notice_added, uiPlural(R.plurals.count_items, count)),
                        errorMessage = null
                    )
                }
                synchronizeSilently()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.toUserMessage(R.string.list_error_paste_failed))
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
            } catch (error: ItemSyncValidationException) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        isOffline = false,
                        skippedItems = SkippedItems(error.listId, error.itemNames)
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                syncScheduler.enqueue()
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        isOffline = error is IOException,
                        errorMessage = error.toUserMessage(
                            R.string.list_error_matching_needs_server
                        )
                    )
                }
            }
        }
    }

    /** The shopper chose to calculate with the rows the server accepted. */
    fun calculateWithoutSkipped(onReady: (Long) -> Unit) {
        val skipped = _uiState.value.skippedItems ?: return
        _uiState.update { it.copy(skippedItems = null) }
        onReady(skipped.listId)
    }

    fun dismissSkipped() {
        _uiState.update { it.copy(skippedItems = null) }
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
                    it.copy(errorMessage = error.toUserMessage(R.string.list_error_change_not_saved))
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
                    else error.toUserMessage(R.string.list_error_sync_failed)
            ) }
        }
    }

}

data class MatchingUiState(
    val isLoading: Boolean = true,
    val isResolving: Boolean = false,
    val result: ShoppingListMatchingDto? = null,
    val errorMessage: UiText? = null
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
                        R.string.match_error_load_failed
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
                    candidate = candidate
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
                            R.string.match_error_confirm_not_saved
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
                            R.string.match_error_flexible_failed
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
    val errorMessage: UiText? = null
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

    /** „Uzmi slično": stavka postaje „bilo koji brend" i plan se računa ponovo. */
    fun useSimilar(listId: Long, itemId: Long, kind: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            try {
                repository.replaceWithSimilar(itemId, kind)
                _uiState.value = RecommendationUiState(isLoading = true)
                _uiState.value = RecommendationUiState(
                    result = repository.getRecommendations(listId, latitude, longitude)
                )
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                _uiState.value = RecommendationUiState(
                    errorMessage = error.toUserMessage(R.string.list_error_replacement_not_saved)
                )
            }
        }
    }

    fun replaceAlternative(listId: Long,itemId: Long,
        product: rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto,
        packages: Double,latitude: Double,longitude: Double,onDone: (UiText?) -> Unit) {
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
                    uiText(R.string.list_error_replacement_saved_no_plan)
                } else {
                    error.toUserMessage(R.string.list_error_replacement_not_saved)
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
                        R.string.list_error_recommendations_unavailable
                    )
                )
            }
        }
    }
}

/**
 * What went wrong, for the shopper. The server's own reason is shown as it is
 * written; everything else comes from string resources, with [fallback] when
 * nothing more specific is known.
 */
internal fun Throwable.toUserMessage(@StringRes fallback: Int): UiText = when (this) {
    is IOException -> uiText(fallback)
    is UserFacingException -> text
    is HttpException -> serverMessage()?.takeIf { code() == 400 || code() == 422 }?.asUiText() ?: when (code()) {
        400 -> uiText(R.string.error_check_input)
        401, 403 -> uiText(R.string.error_list_not_available)
        404 -> uiText(R.string.error_list_or_item_gone)
        in 500..599 -> uiText(R.string.error_server_problem)
        else -> uiText(fallback)
    }
    is IllegalArgumentException -> message?.asUiText() ?: uiText(fallback)
    else -> uiText(fallback)
}
