package rs.pametnakupovina.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.travelDataStore by preferencesDataStore(name = "travel")
private val WALKING = booleanPreferencesKey("walking")

/** Kolima ili peške do prodavnica; pamti se izbor sa Polazne tačke. */
@Singleton
class TravelModeStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val walking: Flow<Boolean> = context.travelDataStore.data.map { it[WALKING] ?: false }

    suspend fun isWalking(): Boolean = walking.first()

    suspend fun setWalking(walking: Boolean) {
        context.travelDataStore.edit { it[WALKING] = walking }
    }
}
