package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationScreenTest {

    @Test
    fun `Valjevo koordinate su validne`() {
        assertTrue(coordinatesAreValid(44.2740, 19.8800))
    }

    @Test
    fun `nepotpune i vanopsezne koordinate nisu validne`() {
        assertFalse(coordinatesAreValid(null, 19.8800))
        assertFalse(coordinatesAreValid(91.0, 19.8800))
        assertFalse(coordinatesAreValid(44.2740, -181.0))
    }
}
