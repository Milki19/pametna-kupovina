package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.uiText

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
        val error = assertThrows(UserFacingException::class.java) { pivo(null).validated() }
        assertEquals(uiText(R.string.list_error_package_unit_required), error.text)
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

    private fun hleb(quantity: Double, name: String = "hleb") = DraftItemInput(
        name = name,
        quantity = quantity,
        matchingRule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
        category = name
    )

    @Test
    fun `kolicina mora biti od jedne stotine do 999`() {
        listOf(0.0, -5.0, 0.009, 999.01, 999999.0).forEach { quantity ->
            val error = assertThrows(UserFacingException::class.java) { hleb(quantity).validated() }
            assertEquals(uiText(R.string.list_error_quantity_range), error.text)
        }
        assertEquals(0.01, hleb(0.01).validated().quantity, 0.0)
        assertEquals(999.0, hleb(999.0).validated().quantity, 0.0)
    }

    @Test
    fun `naziv koji se kuca ima najvise 80 znakova, a proizvod iz kataloga svoj`() {
        val error = assertThrows(UserFacingException::class.java) { hleb(1.0, "a".repeat(81)).validated() }
        assertEquals(uiText(R.string.list_error_name_too_long), error.text)
        hleb(1.0, "a".repeat(80)).validated()
        DraftItemInput(
            name = "Proizvod iz kataloga sa dugim nazivom ".repeat(3),
            canonicalProductId = 42L,
            quantity = 1.0,
            matchingRule = ShoppingItemRuleDto.EXACT_PRODUCT
        ).validated()
    }

    @Test
    fun `naziv bez ijednog slova se ne cuva`() {
        listOf("%%%###", "!!!", "123").forEach { name ->
            val error = assertThrows(UserFacingException::class.java) { hleb(1.0, name).validated() }
            assertEquals(uiText(R.string.list_error_name_no_letter), error.text)
        }
        hleb(1.0, "7up").validated()
        hleb(1.0, "Čaj").validated()
    }
}
