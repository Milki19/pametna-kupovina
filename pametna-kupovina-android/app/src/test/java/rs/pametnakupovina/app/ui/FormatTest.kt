package rs.pametnakupovina.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `novac ima tacku za hiljade i zarez za pare`() {
        assertEquals("1.086,92 RSD", money(1086.92))
        assertEquals("38,10 RSD", money(38.1))
        assertEquals("0,00 RSD", money(0.0))
        assertEquals("570", wholeDinars(569.74))
        assertEquals("12.346", wholeDinars(12345.5))
    }

    @Test
    fun `decimalni broj ne pise nepotrebne nule`() {
        assertEquals("1,5", decimal(1.5))
        assertEquals("1.000", decimal(1000.0))
        assertEquals("2", decimal(2.0))
    }

    @Test
    fun `udaljenost ispod kilometra je u metrima`() {
        assertEquals("550 m", distance(0.55))
        assertEquals("1,1 km", distance(1.10))
        assertEquals("12,3 km", distance(12.34))
    }

    @Test
    fun `datum je u srpskom obliku`() {
        assertEquals("13.09.2026.", date("2026-09-13"))
        assertEquals("nije datum", date("nije datum"))
        assertEquals("13.09.", shortDate("2026-09-13"))
    }

}
