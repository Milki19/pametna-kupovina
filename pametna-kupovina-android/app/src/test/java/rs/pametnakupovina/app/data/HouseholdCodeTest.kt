package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseholdCodeTest {
    @Test fun readsBackWhatItWrote() {
        assertEquals("aB-3_x" to "87", parseHouseholdCode(householdCode("aB-3_x", 87)))
        assertEquals(
            "https://pk.example/app/#/domacinstvo/aB-3_x/87",
            householdCode("aB-3_x", 87, "https://pk.example/")
        )
    }

    @Test fun readsTheWebLinkAndTheOldAppCode() {
        assertEquals("aB-3_x" to "87", parseHouseholdCode("https://pk.example/app/#/domacinstvo/aB-3_x/87"))
        assertEquals("aB-3_x" to "87", parseHouseholdCode(" https://pk.example/app/index.html#/domacinstvo/aB-3_x/87\n"))
        assertEquals("aB-3_x" to "87", parseHouseholdCode("pametnakupovina:domacinstvo:aB-3_x:87"))
    }

    @Test fun readsTheRandomListIdOfANewerLink() {
        val publicId = "0e4f8c1a-9b2d-4c3e-8f7a-6b5c4d3e2f1a"
        assertEquals("aB-3_x" to publicId, parseHouseholdCode("https://pk.example/app/#/domacinstvo/aB-3_x/$publicId"))
        assertEquals(
            "aB-3_x" to "12345678-aaaa-bbbb-cccc-1234567890ab",
            parseHouseholdCode("https://pk.example/app/#/domacinstvo/aB-3_x/12345678-aaaa-bbbb-cccc-1234567890ab")
        )
        assertNull(parseHouseholdCode("https://pk.example/app/#/domacinstvo/aB-3_x/0e4f8c1a-9b2d"))
        assertNull(parseHouseholdCode("https://pk.example/app/#/domacinstvo/aB-3_x/99999999999999999999"))
    }

    @Test fun anyOtherCodeIsNotAnInvite() {
        assertNull(parseHouseholdCode("https://suf.purs.gov.rs/v/?vl=abc"))
        assertNull(parseHouseholdCode("8606012345678"))
        assertNull(parseHouseholdCode("pametnakupovina:domacinstvo:kod"))
        assertNull(parseHouseholdCode("pametnakupovina:domacinstvo::87"))
        assertNull(parseHouseholdCode("pametnakupovina:domacinstvo:kod:87:1"))
        assertNull(parseHouseholdCode("https://pk.example/app/#/domacinstvo/kod"))
        assertNull(parseHouseholdCode("https://pk.example/app/#/domacinstvo/kod/87/1"))
        assertNull(parseHouseholdCode("https://pk.example/app/#/spisak"))
    }
}
