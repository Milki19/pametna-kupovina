package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
