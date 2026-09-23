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
import rs.pametnakupovina.app.location.FusedLocationProvider

data class LocationUiState(
    val isResolving: Boolean = false,
    val coordinates: Coordinates? = null,
    val message: String? = null,
    val isError: Boolean = false,
    /** Greška koju rešavaju podešavanja telefona, a ne druga adresa. */
    val offerSettings: Boolean = false
)

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val locationProvider: FusedLocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    fun resolveCurrentLocation() = resolve(offerSettings = true) {
        locationProvider.currentLocation() to
            "Lokacija je pronađena. Proveri je i pokreni računanje."
    }

    fun findAddress(query: String) {
        if (query.isBlank()) return
        resolve(offerSettings = false) {
            locationProvider.findAddress(query.trim()).let { (coordinates, label) ->
                coordinates to "Pronađeno: $label"
            }
        }
    }

    private fun resolve(
        offerSettings: Boolean,
        lookup: suspend () -> Pair<Coordinates, String>
    ) {
        if (_uiState.value.isResolving) return

        viewModelScope.launch {
            _uiState.value = LocationUiState(isResolving = true)
            _uiState.value = try {
                val (coordinates, message) = lookup()
                LocationUiState(coordinates = coordinates, message = message)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                LocationUiState(
                    message = error.message
                        ?: "Lokacija nije pronađena. Upiši adresu.",
                    isError = true,
                    offerSettings = offerSettings
                )
            }
        }
    }

    fun permissionDenied() {
        _uiState.value = LocationUiState(
            message = "Dozvola nije odobrena. Možeš je uključiti u podešavanjima aplikacije ili upisati adresu.",
            isError = true,
            offerSettings = true
        )
    }
}
