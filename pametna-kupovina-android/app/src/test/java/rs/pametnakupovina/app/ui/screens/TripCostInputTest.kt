package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripCostInputTest {

    /** „1.200“ bi se pročitalo kao 1,2, pa polje ne razdvaja hiljade. */
    @Test
    fun `iznos u polju nema tačku za hiljade i čita se nazad isto`() {
        assertEquals("1200", amountInput(1200.0))
        assertEquals("650,5", amountInput(650.5))
        assertEquals(1200.0, parseAmount("1200")!!, 0.0)
        assertEquals(650.5, parseAmount(" 650,5 ")!!, 0.0)
        assertNull(parseAmount("pet"))
    }
}
