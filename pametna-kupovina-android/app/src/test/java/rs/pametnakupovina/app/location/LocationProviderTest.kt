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
