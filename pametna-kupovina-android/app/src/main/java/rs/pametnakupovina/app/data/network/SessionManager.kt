package rs.pametnakupovina.app.data.network

import android.os.Build
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore
import rs.pametnakupovina.app.data.preferences.SessionStorage

/**
 * Drži sesiju živom: daje važeći pristupni token, obnavlja ga pre isteka i,
 * ako server sesiju više ne priznaje, otvara novu.
 *
 * Sve ide kroz jedan mutex. Token za obnovu važi jednom, pa bi dva zahteva
 * koja ga istovremeno potroše izgledala serveru kao da ga je neko ukrao.
 */
@Singleton
class SessionManager internal constructor(
    private val sessionApi: SessionApiService,
    private val identityStore: SessionStorage,
    private val clock: () -> Long,
    private val deviceName: () -> String?
) {
    @Inject
    constructor(
        sessionApi: SessionApiService,
        identityStore: ClientIdentityStore
    ) : this(sessionApi, identityStore, System::currentTimeMillis, ::phoneModel)

    private val mutex = Mutex()

    /** Važeći pristupni token; otvara ili obnavlja sesiju kad treba. */
    suspend fun accessToken(): String = mutex.withLock {
        val stored = identityStore.session()

        if (stored?.accessToken != null
            && stored.accessExpiresAtMillis - EXPIRY_MARGIN_MILLIS > clock()
        ) {
            return@withLock stored.accessToken
        }

        renew(stored?.refreshToken)
    }

    /**
     * Server je odbio token koji je upravo poslat. Ako ga u međuvremenu nije
     * zamenio drugi zahtev, sesija se obnavlja; inače važi onaj noviji.
     */
    suspend fun renewAfterRejection(rejectedAccessToken: String): String = mutex.withLock {
        val stored = identityStore.session()

        if (stored?.accessToken != null && stored.accessToken != rejectedAccessToken) {
            return@withLock stored.accessToken
        }

        renew(stored?.refreshToken)
    }

    private suspend fun renew(refreshToken: String?): String {
        if (refreshToken != null) {
            try {
                return save(sessionApi.refreshSession(RefreshSessionRequestDto(refreshToken)))
            } catch (error: HttpException) {
                if (error.code() != 401) throw error
                // Sesija je istekla ili je opozvana; ide se dalje, na novu.
            }
        }

        return try {
            open()
        } catch (error: HttpException) {
            if (error.code() != 401) throw error
            // Ni stari broj telefona više ne otvara nalog: telefon je uklonjen
            // ili je broj već zamenjen za sesiju koja je u međuvremenu pala.
            identityStore.startOver()
            open()
        }
    }

    private suspend fun open(): String = save(
        sessionApi.createSession(
            CreateSessionRequestDto(
                deviceToken = identityStore.getOrCreateToken(),
                deviceName = deviceName()
            )
        )
    )

    private suspend fun save(session: SessionDto): String {
        identityStore.saveSession(
            accessToken = session.accessToken,
            accessExpiresAtMillis = clock() + session.accessExpiresInSeconds * 1000,
            refreshToken = session.refreshToken,
            deviceId = session.deviceId
        )
        return session.accessToken
    }

    private companion object {
        const val EXPIRY_MARGIN_MILLIS = 30_000L
    }
}

/** Proizvođač i model, da se na spisku telefona naloga zna koji je koji. */
private fun phoneModel(): String? = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
    .joinToString(" ")
    .trim()
    .takeIf(String::isNotEmpty)
    ?.take(100)

/** Sesija nije mogla da se otvori jer server nije dostupan. */
class SessionUnavailable(cause: Throwable) : IOException(cause.message, cause)
