package rs.pametnakupovina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.location.Coordinates
import rs.pametnakupovina.app.location.FusedLocationProvider
import rs.pametnakupovina.app.data.preferences.TravelModeStore
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.asUiText
import rs.pametnakupovina.app.text.uiText

data class LocationUiState(
    val isResolving: Boolean = false,
    val coordinates: Coordinates? = null,
    /** Adresa polazne tačke za prikaz; null dok je nema, pa se vide koordinate. */
    val label: String? = null,
    val message: UiText? = null,
    val isError: Boolean = false,
    /** Greška koju rešavaju podešavanja telefona, a ne druga adresa. */
    val offerSettings: Boolean = false
)

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val locationProvider: FusedLocationProvider,
    private val travelModes: TravelModeStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    val walking: StateFlow<Boolean> = travelModes.walking
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setWalking(walking: Boolean) {
        viewModelScope.launch { travelModes.setWalking(walking) }
    }

    fun resolveCurrentLocation() = resolve(offerSettings = true) {
        Triple(locationProvider.currentLocation(), null, uiText(R.string.location_found_current))
    }

    fun findAddress(query: String) {
        if (query.isBlank()) return
        resolve(offerSettings = false) {
            locationProvider.findAddress(query.trim()).let { (coordinates, label) ->
                Triple(coordinates, label, uiText(R.string.location_found_address, label))
            }
        }
    }

    private fun resolve(
        offerSettings: Boolean,
        lookup: suspend () -> Triple<Coordinates, String?, UiText>
    ) {
        if (_uiState.value.isResolving) return

        viewModelScope.launch {
            _uiState.value = LocationUiState(isResolving = true)
            _uiState.value = try {
                val (coordinates, label, message) = lookup()
                LocationUiState(coordinates = coordinates, label = label, message = message)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                LocationUiState(
                    message = (error as? UserFacingException)?.text
                        ?: error.message?.asUiText()
                        ?: uiText(R.string.location_not_found_type_address),
                    isError = true,
                    offerSettings = offerSettings
                )
            }
            _uiState.value.coordinates?.takeIf { _uiState.value.label == null }?.let { coordinates ->
                val label = locationProvider.addressOf(coordinates) ?: return@launch
                _uiState.update { state -> if (state.coordinates == coordinates) state.copy(label = label) else state }
            }
        }
    }

    fun permissionDenied() {
        _uiState.value = LocationUiState(
            message = uiText(R.string.location_permission_denied),
            isError = true,
            offerSettings = true
        )
    }
}
