package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto

class RequestedPackagesTest {

    private fun product(size: Double?, unit: String?, inPack: Int = 1) =
        CanonicalProductSearchItemDto(name = "P", quantityValue = size, baseUnit = unit, score = 1.0, packageCount = inPack)

    @Test
    fun `broj sa spiska ostaje kad se izabere proizvod`() {
        assertEquals(3.0, RequestedPackages(3.0, null, null).packagesOf(product(1000.0, "g")), 0.0)
    }

    @Test
    fun `ukupna kolicina postaje broj pakovanja`() {
        val kilo = RequestedPackages(1.0, 1000.0, "g")
        assertEquals(2.0, kilo.packagesOf(product(500.0, "g")), 0.0)
        assertEquals(1.0, kilo.packagesOf(product(900.0, "g")), 0.0)
        // Druga jedinica: ostaje broj sa spiska.
        assertEquals(1.0, kilo.packagesOf(product(1000.0, "ml")), 0.0)
    }

    @Test
    fun `sest piva u paketu od sest je jedan paket`() {
        assertEquals(1.0, RequestedPackages(6.0, null, null).packagesOf(product(3000.0, "ml", 6)), 0.0)
        assertEquals(4.0, RequestedPackages(4.0, null, null).packagesOf(product(3000.0, "ml", 6)), 0.0)
    }
}
