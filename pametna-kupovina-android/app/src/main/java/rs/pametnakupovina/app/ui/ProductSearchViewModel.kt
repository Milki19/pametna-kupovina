package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto

data class ProductSearchUiState(
    val query: String = "",
    val page: Int = 0,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val results: List<CanonicalProductSearchItemDto> = emptyList(),
    val totalElements: Long = 0,
    val hasNext: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ProductSearchViewModel @Inject constructor(
    private val repository: ShoppingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductSearchUiState())
    val uiState: StateFlow<ProductSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        searchJob?.cancel()

        val normalizedQuery = query.trim()
        if (normalizedQuery.length < MIN_QUERY_LENGTH) {
            _uiState.value = ProductSearchUiState(query = normalizedQuery)
            return
        }

        _uiState.value = ProductSearchUiState(
            query = normalizedQuery,
            isSearching = true
        )
        searchJob = launchSearch(
            query = normalizedQuery,
            page = 0,
            append = false,
            debounce = true
        )
    }

    fun retry() {
        val query = _uiState.value.query
        if (query.length < MIN_QUERY_LENGTH) return

        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isSearching = true,
            isLoadingMore = false,
            errorMessage = null
        )
        searchJob = launchSearch(query, page = 0, append = false)
    }

    fun loadNextPage() {
        val current = _uiState.value
        if (
            !current.hasNext ||
            current.isSearching ||
            current.isLoadingMore
        ) {
            return
        }

        searchJob?.cancel()
        _uiState.value = current.copy(
            isLoadingMore = true,
            errorMessage = null
        )
        searchJob = launchSearch(
            query = current.query,
            page = current.page + 1,
            append = true
        )
    }

    fun clear() {
        searchJob?.cancel()
        searchJob = null
        _uiState.value = ProductSearchUiState()
    }

    private fun launchSearch(
        query: String,
        page: Int,
        append: Boolean,
        debounce: Boolean = false
    ): Job = viewModelScope.launch {
        if (debounce) delay(DEBOUNCE_MILLIS)

        try {
            val response = repository.searchProducts(
                query = query,
                page = page,
                limit = PAGE_SIZE
            )
            if (_uiState.value.query != query) return@launch

            _uiState.value = ProductSearchUiState(
                query = query,
                page = response.page,
                results = if (append) {
                    (_uiState.value.results + response.items)
                        .distinctBy {
                            it.productFamilyId ?: it.canonicalProductId
                        }
                } else {
                    response.items
                },
                totalElements = response.totalElements,
                hasNext = response.hasNext
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (_uiState.value.query == query) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    isLoadingMore = false,
                    errorMessage = error.toUserMessage(
                        "Pretraga proizvoda trenutno nije dostupna."
                    )
                )
            }
        }
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEBOUNCE_MILLIS = 300L
        const val PAGE_SIZE = 10
    }
}
