package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseholdCodeTest {
    @Test fun readsBackWhatItWrote() {
        assertEquals("aB-3_x" to 87L, parseHouseholdCode(householdCode("aB-3_x", 87)))
    }

    @Test fun anyOtherCodeIsNotAnInvite() {
        assertNull(parseHouseholdCode("https://suf.purs.gov.rs/v/?vl=abc"))
        assertNull(parseHouseholdCode("8606012345678"))
        assertNull(parseHouseholdCode("pametnakupovina:domacinstvo:kod"))
        assertNull(parseHouseholdCode("pametnakupovina:domacinstvo::87"))
        assertNull(parseHouseholdCode("pametnakupovina:domacinstvo:kod:87:1"))
    }
}
