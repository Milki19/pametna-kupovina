package rs.pametnakupovina.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ean13Test {

    @Test
    fun `ispravan EAN-13 prolazi`() {
        assertTrue(isValidEan13("5901234123457"))
        assertTrue(isValidEan13("8600043004403"))
    }

    @Test
    fun `pogresna kontrolna cifra, slova ili duzina ne prolaze`() {
        assertFalse(isValidEan13("5901234123458"))
        assertFalse(isValidEan13("12345abc"))
        assertFalse(isValidEan13("6108560008584550"))
        assertFalse(isValidEan13(""))
    }
}
