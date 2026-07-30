package rs.pametnakupovina.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class LocationOptimizationUiState(
    val isLoading: Boolean = false,
    val result: LocationOptimizationDto? = null,
    val errorMessage: String? = null
)

class LocationOptimizationViewModel : ViewModel() {

    var uiState by mutableStateOf(LocationOptimizationUiState())
        private set

    fun loadOptimization(
        listId: Long,
        latitude: Double,
        longitude: Double,
        costPerKm: Double
    ) {
        viewModelScope.launch {
            uiState = LocationOptimizationUiState(
                isLoading = true
            )

            uiState = try {
                LocationOptimizationUiState(
                    result = ApiClient.shoppingApi.getLocationOptimization(
                        listId = listId,
                        latitude = latitude,
                        longitude = longitude,
                        costPerKm = costPerKm
                    )
                )
            } catch (exception: Exception) {
                LocationOptimizationUiState(
                    errorMessage = exception.localizedMessage
                        ?: "Lokacijska optimizacija nije uspela."
                )
            }
        }
    }
}