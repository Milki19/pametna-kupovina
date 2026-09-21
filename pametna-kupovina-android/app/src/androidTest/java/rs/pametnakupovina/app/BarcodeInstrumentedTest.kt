package rs.pametnakupovina.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.pametnakupovina.app.ui.components.renderBarcode

/**
 * Kod mora da proradi iz prve, na kasi, pred redom ljudi. Zato se ovde gleda
 * da li je zaista nacrtan i da li je crno-belo — čitač gleda kontrast.
 */
class BarcodeInstrumentedTest {

    /**
     * Prava kartica ume da nosi broj koji ne staje u oblik u kom je
     * odštampana: 16 cifara nije EAN-13. Prazan ekran pred kasirkom je
     * najgori ishod, pa se tada crta CODE-128.
     */
    @Test
    fun aNumberTooLongForItsFormatStillGetsACode() {
        val bitmap = renderBarcode("6108560008584550", "EAN_13")

        assertNotNull("kartica sa 16 cifara je ostala bez koda", bitmap)
    }

    @Test
    fun aThirteenDigitNumberIsDrawnInBlackAndWhite() {
        val bitmap = renderBarcode("5901234123457", "EAN_13")!!

        assertTrue(bitmap.width > 100)
        var black = 0
        var white = 0
        for (x in 0 until bitmap.width) {
            when (bitmap.getPixel(x, bitmap.height / 2)) {
                android.graphics.Color.BLACK -> black++
                android.graphics.Color.WHITE -> white++
            }
        }
        assertTrue("nema crnih linija", black > 0)
        assertTrue("nema belih razmaka", white > 0)
        assertEquals("kod mora biti samo crno-beo", bitmap.width, black + white)
    }

    @Test
    fun code128TakesWhatEanWouldRefuse() {
        assertNotNull(renderBarcode("ABC-123/45", "CODE_128"))
    }

    /** Bolje ništa nego kod koji kasa ne pročita. */
    @Test
    fun aNumberThatDoesNotFitTheFormatDrawsNothing() {
        // Prekratak za EAN-13, ali CODE-128 ga prima — bolje nego ništa.
        assertNotNull(renderBarcode("12345", "EAN_13"))
        assertNull(renderBarcode("6108560008584550", "IZMISLJEN_OBLIK"))
        assertNull(renderBarcode("", "CODE_128"))
    }
}
