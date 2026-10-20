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

    private fun priced(size: Double, price: Double, name: String = "P") = CanonicalProductSearchItemDto(
        name = name, quantityValue = size, baseUnit = "g", score = 1.0, categoryCode = "BRASNO",
        availability = listOf(
            rs.pametnakupovina.app.data.network.ProductRetailerAvailabilityDto(
                retailerCode = "A", retailerName = "A", latestPriceDate = "2026-10-20", minimumEffectivePrice = price
            )
        )
    )

    /** Izveštaj 20.10.: „2 kg brašna", izabrano 5 kg za 244,99 umesto 2 × 1 kg za oko 106. */
    @Test
    fun `manja pakovanja se nude kad su jeftinija od izabranog velikog`() {
        val twoKilos = RequestedPackages(1.0, 2000.0, "g")
        val big = priced(5000.0, 244.99, "5 kg")
        val kilo = priced(1000.0, 53.0, "1 kg")

        val better = twoKilos.smallerPacks(big, listOf(big, kilo))

        assertEquals("1 kg", better?.product?.name)
        assertEquals(2, better?.packages)
        assertEquals(106.0, better!!.cost, 0.001)
        // Bez tražene količine, ili kad je veliko pakovanje povoljnije, nema predloga.
        assertEquals(null, RequestedPackages(1.0, null, null).smallerPacks(big, listOf(big, kilo)))
        assertEquals(null, twoKilos.smallerPacks(priced(5000.0, 100.0), listOf(kilo)))
    }
}
