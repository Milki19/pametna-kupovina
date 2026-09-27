package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.R

class PackageSizeErrorTest {

    @Test
    fun `bez velicine pakovanja nema greske`() {
        assertNull(packageSizeError("", "", null))
    }

    @Test
    fun `velicina bez jedinice trazi jedinicu`() {
        assertEquals(R.string.list_package_error_unit, packageSizeError("6", "", null))
    }

    @Test
    fun `od vece od do i nula nisu dozvoljeni`() {
        assertEquals(R.string.list_package_error_order, packageSizeError("2", "1", AmountUnit.L))
        assertEquals(R.string.list_package_error_positive, packageSizeError("0", "", AmountUnit.PIECE))
        assertNull(packageSizeError("0,5", "1,5", AmountUnit.L))
    }
}
