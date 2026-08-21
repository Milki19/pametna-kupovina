package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.location.Coordinates
import rs.pametnakupovina.app.location.LocationProvider

data class LocationUiState(
    val isResolving: Boolean = false,
    val coordinates: Coordinates? = null,
    val message: String? = null,
    val isError: Boolean = false
)

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    fun resolveCurrentLocation() {
        if (_uiState.value.isResolving) return

        viewModelScope.launch {
            _uiState.value = LocationUiState(isResolving = true)
            _uiState.value = try {
                LocationUiState(
                    coordinates = locationProvider.currentLocation(),
                    message = "Lokacija je pronađena. Proveri je i pokreni računanje."
                )
            } catch (error: Exception) {
                LocationUiState(
                    message = error.message
                        ?: "Lokacija nije pronađena. Unesi koordinate ručno.",
                    isError = true
                )
            }
        }
    }

    fun permissionDenied() {
        _uiState.value = LocationUiState(
            message = "Dozvola nije odobrena. Unesi koordinate ručno.",
            isError = true
        )
    }
}
