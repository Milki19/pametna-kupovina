package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto

class MatchingScreenTest {

    @Test
    fun `povezano obuhvata automatske i potvrdjene stavke`() {
        val result = ShoppingListMatchingDto(
            listId = 1,
            totalItems = 5,
            automaticallyMatchedItems = 1,
            confirmedItems = 4,
            itemsNeedingConfirmation = 0,
            unmatchedItems = 0,
            flexibleItems = 0,
            readyForOptimization = true
        )

        assertEquals(5, connectedItems(result))
    }
}
