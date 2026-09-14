package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto

class DraftItemInputTest {

    private fun pivo(unit: String?) = DraftItemInput(
        name = "pivo",
        quantity = 1.0,
        matchingRule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
        category = "pivo",
        minPackageQuantity = 6.0,
        requiredBaseUnit = unit
    )

    @Test
    fun `pakovanje bez jedinice se ne cuva`() {
        val error = assertThrows(IllegalArgumentException::class.java) { pivo(null).validated() }
        assertEquals("Za veličinu pakovanja izaberi jedinicu: kg, g, l, ml ili kom.", error.message)
    }

    @Test
    fun `pakovanje od sest komada je broj komada`() {
        assertEquals("piece", pivo("kom").validated().requiredBaseUnit)
    }

    @Test
    fun `nalepljen red cuva kolicinu koju je napisao, bez predloga`() {
        val input = PastedListParser.parse("mleko").single().toFlexibleDraftInput()
        assertEquals(null, input.targetQuantity)
        val cevapi = PastedListParser.parse("Ćevapi 3kg").single().toFlexibleDraftInput()
        assertEquals(3000.0, cevapi.targetQuantity!!, 0.0)
    }
}
