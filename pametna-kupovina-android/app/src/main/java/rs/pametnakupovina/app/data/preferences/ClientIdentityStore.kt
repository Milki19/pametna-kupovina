package rs.pametnakupovina.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.clientIdentityDataStore by preferencesDataStore(
    name = "client_identity"
)

@Singleton
class ClientIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tokenMutex = Mutex()

    val activeListId: Flow<Long?> = context.clientIdentityDataStore.data
        .map { preferences -> preferences[ACTIVE_LIST_ID] }

    suspend fun getOrCreateToken(): String = tokenMutex.withLock {
        val existing = context.clientIdentityDataStore.data
            .first()[CLIENT_TOKEN]

        if (!existing.isNullOrBlank()) {
            return@withLock existing
        }

        val generated = UUID.randomUUID().toString()
        context.clientIdentityDataStore.edit { preferences ->
            preferences[CLIENT_TOKEN] = generated
        }
        generated
    }

    suspend fun getActiveListId(): Long? = activeListId.first()

    suspend fun setActiveListId(listId: Long) {
        context.clientIdentityDataStore.edit { preferences ->
            preferences[ACTIVE_LIST_ID] = listId
        }
    }

    suspend fun clearActiveListId() {
        context.clientIdentityDataStore.edit { preferences ->
            preferences.remove(ACTIVE_LIST_ID)
        }
    }

    private companion object {
        val CLIENT_TOKEN = stringPreferencesKey("client_token")
        val ACTIVE_LIST_ID = longPreferencesKey("active_list_id")
    }
}
