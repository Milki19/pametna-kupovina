package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto

class PastedListParserTest {

    @Test
    fun `svaki neprazan red postaje stavka`() {
        val result = PastedListParser.parse(
            """
            Mleko

            Hleb
            Jabuke
            """.trimIndent()
        )

        assertEquals(listOf("Mleko", "Hleb", "Jabuke"), result.map { it.name })
    }

    @Test
    fun `zarez sa razmakom deli stavke kao na serveru`() {
        val result = PastedListParser.parse("10 jaja, mleko 2, 2 mleka\nмлеко 1л\nMleko 2,8%\nmleko, 2l")

        assertEquals(
            listOf("10 jaja", "mleko 2", "2 mleka", "млеко 1л", "Mleko 2,8%", "mleko, 2l"),
            result.map { it.rawInput }
        )
    }

    @Test
    fun `prepoznaje kolicinu pre i posle naziva`() {
        val result = PastedListParser.parse(
            """
            2x mleko
            jabuke x3
            """.trimIndent()
        )

        assertEquals(2.0, result[0].quantity, 0.0)
        assertEquals("mleko", result[0].name)
        assertEquals(3.0, result[1].quantity, 0.0)
        assertEquals("jabuke", result[1].name)
    }

    @Test
    fun `podrzava decimalni zarez i uklanja oznaku liste`() {
        val result = PastedListParser.parse("• 1,5x paradajz")

        assertEquals(1.5, result.single().quantity, 0.0)
        assertEquals("paradajz", result.single().name)
    }

    @Test
    fun `nalepljena stavka se cuva kao fleksibilna kategorija`() {
        val input = PastedListParser.parse("2x hleb")
            .single()
            .toFlexibleDraftInput()

        assertEquals(ShoppingItemRuleDto.FLEXIBLE_CATEGORY, input.matchingRule)
        assertEquals("hleb", input.category)
        assertEquals(2.0, input.quantity, 0.0)
    }
}
