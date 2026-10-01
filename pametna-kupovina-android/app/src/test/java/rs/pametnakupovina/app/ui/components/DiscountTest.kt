package rs.pametnakupovina.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
