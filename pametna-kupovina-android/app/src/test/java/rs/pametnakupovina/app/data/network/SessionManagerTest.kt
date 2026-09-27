package rs.pametnakupovina.app.data.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import rs.pametnakupovina.app.data.preferences.SessionStorage
import rs.pametnakupovina.app.data.preferences.StoredSession

class SessionManagerTest {

    private var now = 1_000_000L
    private val storage = FakeStorage()
    private val api = FakeSessionApi()
    private val manager = SessionManager(api, storage, { now }, { "Test telefon" })

    @Test
    fun `stari broj telefona se jednom menja za sesiju`() = runBlocking {
        storage.clientToken = "broj-iz-1.8"

        assertEquals("pka_1", manager.accessToken())
        assertEquals("pka_1", manager.accessToken())

        assertEquals(listOf("open:broj-iz-1.8"), api.calls)
        assertEquals(7L, storage.deviceId)
    }

    @Test
    fun `pristupni token se obnavlja pre isteka`() = runBlocking {
        manager.accessToken()
        now += 15 * 60 * 1000L - 10_000

        assertEquals("pka_2", manager.accessToken())
        assertEquals("refresh:pkr_1", api.calls.last())
    }

    @Test
    fun `odbijen token se obnavlja samo jednom za vise zahteva`() = runBlocking {
        val rejected = manager.accessToken()

        val renewed = (1..5).map { async { manager.renewAfterRejection(rejected) } }.awaitAll()

        assertEquals(setOf("pka_2"), renewed.toSet())
        assertEquals(1, api.calls.count { it.startsWith("refresh") })
    }

    @Test
    fun `istekla sesija se otvara ponovo istim brojem`() = runBlocking {
        storage.clientToken = "broj"
        manager.accessToken()
        api.refreshRejected = true
        now += 60 * 60 * 1000L

        manager.accessToken()

        assertEquals(listOf("open:broj", "refresh:pkr_1", "open:broj"), api.calls)
    }

    @Test
    fun `uklonjen telefon pocinje kao nov`() = runBlocking {
        storage.clientToken = "uklonjen"
        storage.activeListId = 5L
        manager.accessToken()
        api.refreshRejected = true
        api.rejectedTokens += "uklonjen"
        now += 60 * 60 * 1000L

        manager.accessToken()

        assertEquals("open:novi-broj", api.calls.last())
        assertNull(storage.activeListId)
    }

    private class FakeStorage : SessionStorage {
        var clientToken: String? = null
        var session: StoredSession? = null
        var deviceId: Long? = null
        var activeListId: Long? = null

        override suspend fun getOrCreateToken(): String =
            clientToken ?: "novi-broj".also { clientToken = it }

        override suspend fun session(): StoredSession? = session

        override suspend fun saveSession(
            accessToken: String,
            accessExpiresAtMillis: Long,
            refreshToken: String,
            deviceId: Long
        ) {
            session = StoredSession(accessToken, accessExpiresAtMillis, refreshToken)
            this.deviceId = deviceId
        }

        override suspend fun startOver() {
            clientToken = null
            session = null
            deviceId = null
            activeListId = null
        }
    }

    private class FakeSessionApi : SessionApiService {
        val calls = mutableListOf<String>()
        var refreshRejected = false
        val rejectedTokens = mutableSetOf<String>()
        private var issued = 0

        override suspend fun createSession(request: CreateSessionRequestDto): SessionDto {
            calls += "open:${request.deviceToken}"
            if (request.deviceToken in rejectedTokens) throw unauthorized()
            return next()
        }

        override suspend fun refreshSession(request: RefreshSessionRequestDto): SessionDto {
            calls += "refresh:${request.refreshToken}"
            if (refreshRejected) throw unauthorized()
            return next()
        }

        private fun next(): SessionDto {
            issued++
            return SessionDto("pka_$issued", 15 * 60, "pkr_$issued", 7L)
        }

        private fun unauthorized() = HttpException(
            Response.error<Any>(401, "{}".toResponseBody())
        )
    }
}
