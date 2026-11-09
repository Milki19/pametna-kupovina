package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingListSummaryDto

class ExistingListTest {
    private fun list(id: Long, items: Int) =
        ShoppingListSummaryDto(id, "Moja kupovina", "2026-10-31T10:00:00Z", "2026-11-06T10:00:00Z", items)

    @Test fun aDeviceWithoutListsGetsANewOne() {
        assertNull(existingList(emptyList()))
    }

    @Test fun theListWithItemsWinsOverANewerEmptyOne() {
        // Newest first, as the server sends them: the empty one was made after the data was lost.
        assertEquals(7L, existingList(listOf(list(9, 0), list(7, 12), list(3, 4)))?.id)
    }

    @Test fun onlyEmptyListsGiveTheLatest() {
        assertEquals(9L, existingList(listOf(list(9, 0), list(7, 0)))?.id)
    }
}
