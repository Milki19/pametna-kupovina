package rs.pametnakupovina.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.network.ProductRetailerAvailabilityDto

class DiscountTest {

    @Test
    fun `popust je ceo procenat kao na serveru`() {
        assertEquals(25, discountPercent(266.99, 199.99))
        assertEquals(50, discountPercent(100.0, 50.0))
    }

    @Test
    fun `bez nize cene nema akcije`() {
        assertNull(discountPercent(null, 10.0))
        assertNull(discountPercent(10.0, 10.0))
        assertNull(discountPercent(10.0, 12.0))
        // 0,2 % je zaokruživanje, a „−0 %" bi izgledalo kao akcija.
        assertNull(discountPercent(500.0, 499.0))
    }

    @Test
    fun `boja zavisi od velicine popusta`() {
        assertEquals(DiscountTier.SMALL, discountTier(5))
        assertEquals(DiscountTier.SMALL, discountTier(19))
        assertEquals(DiscountTier.MEDIUM, discountTier(20))
        assertEquals(DiscountTier.MEDIUM, discountTier(39))
        assertEquals(DiscountTier.LARGE, discountTier(40))
    }

    private fun chain(name: String, price: Double, discount: Int? = null) =
        ProductRetailerAvailabilityDto(
            retailerCode = name,
            retailerName = name,
            latestPriceDate = "2026-10-02",
            minimumEffectivePrice = price,
            discountPercent = discount
        )

    @Test
    fun `lanac na akciji je uvek medju tri prikazana`() {
        val offers = listOf(chain("A", 100.0), chain("B", 110.0), chain("C", 120.0), chain("D", 130.0, 20))
        assertEquals(listOf("A", "B", "D"), shortList(offers).map { it.retailerCode })
    }

    @Test
    fun `bez akcije ili sa akcijom medju prvima ostaju tri najjeftinija`() {
        val plain = listOf(chain("A", 100.0), chain("B", 110.0), chain("C", 120.0), chain("D", 130.0))
        assertEquals(listOf("A", "B", "C"), shortList(plain).map { it.retailerCode })
        val early = listOf(chain("A", 100.0, 10), chain("B", 110.0), chain("C", 120.0), chain("D", 130.0, 20))
        assertEquals(listOf("A", "B", "C"), shortList(early).map { it.retailerCode })
    }
}
