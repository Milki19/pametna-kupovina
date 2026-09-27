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

/**
 * Sesija sa servera. Pristupni token važi nekoliko minuta; token za obnovu
 * se menja pri svakoj upotrebi. Datoteka je izuzeta iz rezervnih kopija
 * (res/xml/backup_rules.xml), pa sesija ne prelazi na drugi telefon.
 */
data class StoredSession(
    val accessToken: String?,
    val accessExpiresAtMillis: Long,
    val refreshToken: String
)

/** Ono što sesija treba od telefona; odvojeno da bi se proverilo bez Androida. */
interface SessionStorage {
    suspend fun getOrCreateToken(): String
    suspend fun session(): StoredSession?
    suspend fun saveSession(
        accessToken: String,
        accessExpiresAtMillis: Long,
        refreshToken: String,
        deviceId: Long
    )
    suspend fun startOver()
}

@Singleton
class ClientIdentityStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SessionStorage {
    private val tokenMutex = Mutex()

    val activeListId: Flow<Long?> = context.clientIdentityDataStore.data
        .map { preferences -> preferences[ACTIVE_LIST_ID] }

    /**
     * Slučajan broj telefona. Do verzije 1.8 išao je uz svaki zahtev; sada
     * samo jednom, da se zameni za sesiju, posle čega ga server više ne prima.
     */
    override suspend fun getOrCreateToken(): String = tokenMutex.withLock {
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

    /** Broj ovog telefona na nalogu; ništa ne otvara, pa sme da se pokaže. */
    val deviceId: Flow<Long?> = context.clientIdentityDataStore.data
        .map { preferences -> preferences[DEVICE_ID] }

    override suspend fun session(): StoredSession? {
        val preferences = context.clientIdentityDataStore.data.first()
        val refreshToken = preferences[REFRESH_TOKEN] ?: return null

        return StoredSession(
            accessToken = preferences[ACCESS_TOKEN],
            accessExpiresAtMillis = preferences[ACCESS_EXPIRES_AT] ?: 0L,
            refreshToken = refreshToken
        )
    }

    override suspend fun saveSession(
        accessToken: String,
        accessExpiresAtMillis: Long,
        refreshToken: String,
        deviceId: Long
    ) {
        context.clientIdentityDataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = accessToken
            preferences[ACCESS_EXPIRES_AT] = accessExpiresAtMillis
            preferences[REFRESH_TOKEN] = refreshToken
            preferences[DEVICE_ID] = deviceId
        }
    }

    /**
     * Server više ne priznaje ni sesiju ni stari broj ovog telefona (uklonjen
     * je sa naloga ili dugo nije korišćen). Telefon počinje kao nov: novi
     * broj, bez sesije i bez spiska koji je pripadao starom nalogu.
     */
    override suspend fun startOver() {
        tokenMutex.withLock {
            context.clientIdentityDataStore.edit { preferences ->
                preferences.remove(CLIENT_TOKEN)
                preferences.remove(ACCESS_TOKEN)
                preferences.remove(ACCESS_EXPIRES_AT)
                preferences.remove(REFRESH_TOKEN)
                preferences.remove(DEVICE_ID)
                preferences.remove(ACTIVE_LIST_ID)
            }
        }
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
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val ACCESS_EXPIRES_AT = longPreferencesKey("access_expires_at")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val DEVICE_ID = longPreferencesKey("device_id")
    }
}
