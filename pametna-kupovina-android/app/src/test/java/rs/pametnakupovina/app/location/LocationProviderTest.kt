package rs.pametnakupovina.app.location

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationProviderTest {
    @Test fun refusesStaleAndFutureLocation() = runBlocking {
        assertEquals(true, isRecentLocation(1_000_000_000, 31_000_000_000))
        assertEquals(false, isRecentLocation(1_000_000_000, 32_000_000_000))
        assertEquals(false, isRecentLocation(3_000_000_000, 2_000_000_000))
        assertEquals(null, resolveLocationWithFallback({ 10 }, { 20 }, { it > 30 }))
    }

    @Test
    fun `koristi poslednju lokaciju kada svez zahtev prijavi gresku`() = runBlocking {
        val result = resolveLocationWithFallback(
            freshLocation = { throw IllegalStateException("provider error") },
            lastLocation = { "poslednja lokacija" }
        )

        assertEquals("poslednja lokacija", result)
    }

    @Test
    fun `rezervna lokacija sme da bude starija od svezeg zahteva`() = runBlocking {
        val fiveMinutesNanos = 300_000_000_000L
        val fourMinutesOld = 1_000_000_000L
        val now = fourMinutesOld + 240_000_000_000L

        assertEquals(false, isRecentLocation(fourMinutesOld, now))
        assertEquals(true, isUsableLocation(fourMinutesOld, now, fiveMinutesNanos))
        assertEquals(false, isUsableLocation(fourMinutesOld, now + 120_000_000_000L, fiveMinutesNanos))

        val result = resolveLocationWithFallback(
            freshLocation = { null },
            lastLocation = { 240 },
            isAcceptable = { it <= 30 },
            isAcceptableFallback = { it <= 300 }
        )

        assertEquals(240, result)
    }

    @Test
    fun `rezervna lokacija starija od dozvoljenog se odbija`() = runBlocking {
        val result = resolveLocationWithFallback(
            freshLocation = { null },
            lastLocation = { 600 },
            isAcceptable = { it <= 30 },
            isAcceptableFallback = { it <= 300 }
        )

        assertEquals(null, result)
    }

    @Test
    fun `ne pretvara otkazivanje u fallback`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                resolveLocationWithFallback<String>(
                    freshLocation = { throw CancellationException() },
                    lastLocation = { "ne sme biti pozvana" }
                )
            }
        }
    }
}
