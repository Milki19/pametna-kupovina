package rs.pametnakupovina.app.data

import org.junit.Assert.*
import org.junit.Test
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.uiText

class PurchaseProgressTest {
    @Test fun partialAndCompletedQuantitiesRemainDistinct() {
        val partial = validatePurchaseProgress(PurchaseItemProgress(boughtPackages = 2.0), 5.0)
        assertEquals(PurchaseStatus.TO_BUY, partial.status)
        val full = validatePurchaseProgress(partial.copy(boughtPackages = 5.0), 5.0)
        assertEquals(PurchaseStatus.PURCHASED, full.status)
        assertEquals(5.0, validatePurchaseProgress(PurchaseItemProgress(status = PurchaseStatus.PURCHASED), 5.0).boughtPackages, 0.0)
        assertEquals(PurchaseStatus.SKIPPED, validatePurchaseProgress(PurchaseItemProgress(status = PurchaseStatus.SKIPPED), 5.0).status)
    }
    @Test fun validatesUserMoneyAndKeepsDecimalPrecision() {
        assertEquals("123.45", validatePurchaseProgress(PurchaseItemProgress(actualLineTotal = "123,45"), 1.0).actualLineTotal)
        for (value in listOf("-1", "NaN", "1.234", "abc")) {
            val error = assertThrows(UserFacingException::class.java) {
                validatePurchaseProgress(PurchaseItemProgress(actualLineTotal = value), 1.0)
            }
            assertEquals(uiText(R.string.purchase_error_price), error.text)
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 2.0)) {
            val error = assertThrows(UserFacingException::class.java) {
                validatePurchaseProgress(PurchaseItemProgress(boughtPackages = value), 1.0)
            }
            assertEquals(uiText(R.string.purchase_error_bought_range), error.text)
        }
    }
    @Test fun refusalsNameTheirReason() {
        val planned = assertThrows(UserFacingException::class.java) {
            validatePurchaseProgress(PurchaseItemProgress(), 0.0)
        }
        assertEquals(uiText(R.string.purchase_error_invalid_planned), planned.text)
        val note = assertThrows(UserFacingException::class.java) {
            validatePurchaseProgress(PurchaseItemProgress(note = "a".repeat(1001)), 1.0)
        }
        assertEquals(uiText(R.string.purchase_error_note_length, 1000), note.text)
    }
}
