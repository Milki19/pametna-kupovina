package rs.pametnakupovina.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CanonicalProductOfferDto

class PriceWatchTest {
    private fun offer(price: Double, needsCheck: Boolean = false, packageCount: Int = 1) =
        CanonicalProductOfferDto(
            retailerProductId = price.toLong(), retailerCode = "R", retailerName = "R",
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
}
