package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageSizeErrorTest {

    @Test
    fun `bez velicine pakovanja nema greske`() {
        assertNull(packageSizeError("", "", null))
    }

    @Test
    fun `velicina bez jedinice trazi jedinicu`() {
        assertEquals("Izaberi jedinicu iznad: kg, g, l, ml ili kom.", packageSizeError("6", "", null))
    }

    @Test
    fun `od vece od do i nula nisu dozvoljeni`() {
        assertEquals("„Od“ ne može biti veće od „do“.", packageSizeError("2", "1", AmountUnit.L))
        assertEquals("Upiši broj veći od nule.", packageSizeError("0", "", AmountUnit.PIECE))
        assertNull(packageSizeError("0,5", "1,5", AmountUnit.L))
    }
}
