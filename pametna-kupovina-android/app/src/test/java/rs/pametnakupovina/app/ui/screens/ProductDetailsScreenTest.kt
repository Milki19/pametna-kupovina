package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.network.CanonicalProductDetailsDto
import rs.pametnakupovina.app.data.network.CanonicalProductOfferDto

class ProductDetailsScreenTest {

    private fun product(quantity: Double?, unit: String?) = CanonicalProductDetailsDto(
        canonicalProductId = 4813,
        name = "Pivo svetlo Zajecarsko 0,5l RGB",
        quantityValue = quantity,
        baseUnit = unit,
        requestedDate = "2026-09-15"
    )

    private fun offer(price: Double, packageCount: Int = 1, unitPrice: Double? = null) = CanonicalProductOfferDto(
        retailerProductId = 1,
        retailerCode = "MAXI",
        retailerName = "Maxi",
        priceDate = "2026-09-14",
        effectivePrice = price,
        unitPrice = unitPrice,
        priceScope = "STORE_FORMAT",
        packageCount = packageCount
    )

    @Test
    fun `cena po litru iz velicine, ne iz cenovnika lanca`() {
        val bottle = product(500.0, "ml")
        assertEquals("139,98 RSD/l", offerUnitPriceLabel(offer(69.99), bottle))
        // Maxi lists its "price per unit" as the price of one bottle.
        assertEquals("171,98 RSD/l", offerUnitPriceLabel(offer(85.99, unitPrice = 85.99), bottle))
        // METRO's case of twenty.
        assertEquals("168,00 RSD/l", offerUnitPriceLabel(offer(1680.0, packageCount = 20), bottle))
    }

    @Test
    fun `po komadu samo kad pakovanje ima vise komada`() {
        assertEquals("25,00 RSD/kom", offerUnitPriceLabel(offer(250.0), product(10.0, "piece")))
        assertNull(offerUnitPriceLabel(offer(99.99), product(1.0, "piece")))
    }

    @Test
    fun `bez velicine ostaje cena lanca`() {
        assertEquals("jed. 12,50 RSD", offerUnitPriceLabel(offer(99.99, unitPrice = 12.5), product(null, null)))
    }
}
