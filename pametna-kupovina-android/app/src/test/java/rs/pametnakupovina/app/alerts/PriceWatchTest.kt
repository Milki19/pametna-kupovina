package rs.pametnakupovina.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CanonicalProductOfferDto
import rs.pametnakupovina.app.data.network.NearbyStoreDto

class PriceWatchTest {
    private fun offer(
        price: Double,
        needsCheck: Boolean = false,
        packageCount: Int = 1,
        retailer: String = "R",
        storeId: Long? = null
    ) =
        CanonicalProductOfferDto(
            retailerProductId = price.toLong(), retailerCode = retailer, retailerName = retailer,
            storeId = storeId,
            priceDate = "2026-09-23", effectivePrice = price, priceScope = "STORE",
            priceNeedsCheck = needsCheck, packageCount = packageCount
        )

    private fun product(vararg offers: CanonicalProductOfferDto) = CanonicalProductDetailsDto(
        canonicalProductId = 1, name = "Mleko", requestedDate = "2026-09-23", offers = offers.toList()
    )

    // Cena za proveru (verovatno za komad većeg pakovanja) i gajba ne smeju
    // da postanu prag, inače bi alarm javio pad koji ne postoji.
    @Test fun watchesTheCheapestTrustworthySinglePack() {
        assertEquals(
            129.99,
            bestPrice(product(offer(149.99), offer(129.99), offer(19.99, needsCheck = true), offer(9.99, packageCount = 6)))
                ?.effectivePrice
        )
        assertNull(bestPrice(product(offer(19.99, needsCheck = true))))
    }

    // Radnja u drugom gradu ne sme da okine alarm; cena za ceo lanac važi
    // samo ako lanac ima radnju u kraju.
    @Test fun watchesOnlyShopsInTheArea() {
        val nearby = Nearby(listOf(NearbyStoreDto(storeId = 7, retailerCode = "MAXI")))
        val product = product(
            offer(99.99, retailer = "IDEA", storeId = 3),
            offer(119.99, retailer = "LIDL"),
            offer(129.99, retailer = "MAXI", storeId = 7),
            offer(124.99, retailer = "MAXI")
        )
        assertEquals(124.99, bestPrice(product, nearby)?.effectivePrice)
        assertEquals(99.99, bestPrice(product)?.effectivePrice)
        assertNull(bestPrice(product, Nearby(emptyList())))
    }
}
