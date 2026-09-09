package rs.pametnakupovina.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoogleMapsDirectionsTest {
    @Test fun savedPlanUsesMapsOriginAndNeverStartsNavigation() {
        val url = googleMapsDirectionsUrl(null, listOf(NavigationPoint(44.28, 19.9)))
        assertEquals(false, url.contains("origin="))
        assertEquals(false, url.contains("dir_action"))
    }

    private val origin = NavigationPoint(44.274, 19.88)

    @Test
    fun `jedna prodavnica postaje destinacija bez waypoint-a`() {
        val url = googleMapsDirectionsUrl(
            origin = origin,
            orderedStops = listOf(NavigationPoint(44.28, 19.9))
        )

        assertEquals(
            "https://www.google.com/maps/dir/?api=1" +
                "&origin=44.274,19.88" +
                "&destination=44.28,19.9" +
                "&travelmode=driving",
            url
        )
    }

    @Test
    fun `vise prodavnica cuva redosled stajanja`() {
        val url = googleMapsDirectionsUrl(
            origin = origin,
            orderedStops = listOf(
                NavigationPoint(44.28, 19.9),
                NavigationPoint(44.3, 19.92)
            )
        )

        assertEquals(
            "https://www.google.com/maps/dir/?api=1" +
                "&origin=44.274,19.88" +
                "&destination=44.3,19.92" +
                "&waypoints=44.28,19.9" +
                "&travelmode=driving",
            url
        )
    }

    @Test
    fun `ruta bez prodavnice nije dozvoljena`() {
        assertThrows(IllegalArgumentException::class.java) {
            googleMapsDirectionsUrl(origin, emptyList())
        }
    }
}
