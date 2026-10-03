package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FiscalVerificationUrlTest {

    private val printed = "https://suf.purs.gov.rs/v/?vl=A1NERDJOUkU3U0REMk5SRTdU"

    @Test
    fun printedReceiptPassesUnchanged() {
        assertEquals(printed, fiscalVerificationUrl(printed))
    }

    @Test
    fun digitalReceiptVariantsBecomeTheSameAddress() {
        val code = "/v/?vl=A1NERDJOUkU3U0REMk5SRTdU"
        listOf(
            "http://suf.purs.gov.rs$code",
            "HTTPS://SUF.PURS.GOV.RS$code",
            "https://suf.purs.gov.rs:443$code",
            "  https://suf.purs.gov.rs$code\n",
            "﻿https://suf.purs.gov.rs$code"
        ).forEach { scanned ->
            assertEquals(scanned, printed, fiscalVerificationUrl(scanned))
        }
    }

    @Test
    fun otherCodesAreNotReceipts() {
        assertNull(fiscalVerificationUrl("8600939001234"))
        assertNull(fiscalVerificationUrl("https://example.com/?vl=abc"))
        assertNull(fiscalVerificationUrl("https://suf.purs.gov.rs.example.com/v/?vl=abc"))
    }
}
