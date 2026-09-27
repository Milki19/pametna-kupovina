package rs.pametnakupovina.app.data.market

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.marketDataStore by preferencesDataStore(name = "market")

/**
 * Remembers the account's market between starts, so amounts are written in
 * the right currency before the server has answered.
 */
@Singleton
class MarketStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json
) {
    suspend fun restore() {
        val saved = context.marketDataStore.data.first()[MARKET] ?: return
        runCatching { json.decodeFromString<MarketSettings>(saved) }
            .onSuccess { CurrentMarket.settings = it }
    }

    suspend fun remember(settings: MarketSettings) {
        CurrentMarket.settings = settings
        context.marketDataStore.edit { preferences ->
            preferences[MARKET] = json.encodeToString(settings)
        }
    }

    private companion object {
        val MARKET = stringPreferencesKey("market")
    }
}
