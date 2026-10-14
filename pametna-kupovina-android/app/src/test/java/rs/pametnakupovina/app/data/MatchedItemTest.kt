package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingItemMatchResultDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto

class MatchedItemTest {
    private fun item(
        id: Long,
        status: ShoppingItemMatchingStatusDto,
        rule: ShoppingItemRuleDto = ShoppingItemRuleDto.EXACT_PRODUCT
    ) = ShoppingItemMatchResultDto(
        itemId = id,
        requestedName = "Stavka $id",
        matchingRule = rule,
        matchingStatus = status,
        blocksOptimization = rule == ShoppingItemRuleDto.EXACT_PRODUCT &&
            status == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION,
        explanation = ""
    )

    private val before = ShoppingListMatchingDto(
        listId = 7,
        totalItems = 3,
        automaticallyMatchedItems = 1,
        confirmedItems = 0,
        itemsNeedingConfirmation = 1,
        unmatchedItems = 1,
        flexibleItems = 0,
        readyForOptimization = false,
        blockingItemIds = listOf(2),
        items = listOf(
            item(1, ShoppingItemMatchingStatusDto.AUTO_MATCHED),
            item(2, ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION),
            item(3, ShoppingItemMatchingStatusDto.UNMATCHED)
        )
    )

    @Test fun aConfirmedItemTakesItsPlaceAndUnblocksTheList() {
        val after = before.withItem(item(2, ShoppingItemMatchingStatusDto.CONFIRMED))

        assertEquals(listOf(1L, 2L, 3L), after.items.map { it.itemId })
        assertEquals(ShoppingItemMatchingStatusDto.CONFIRMED, after.items[1].matchingStatus)
        assertEquals(1, after.confirmedItems)
        assertEquals(0, after.itemsNeedingConfirmation)
        assertEquals(1, after.automaticallyMatchedItems)
        assertEquals(1, after.unmatchedItems)
        assertTrue(after.readyForOptimization)
        assertTrue(after.blockingItemIds.isEmpty())
    }

    @Test fun anItemLeftToTheAppCountsAsFlexible() {
        val after = before.withItem(
            item(3, ShoppingItemMatchingStatusDto.AUTO_MATCHED, ShoppingItemRuleDto.FLEXIBLE_CATEGORY)
        )

        assertEquals(1, after.flexibleItems)
        assertEquals(0, after.unmatchedItems)
        assertEquals(2, after.automaticallyMatchedItems)
        assertFalse(after.readyForOptimization)
        assertEquals(listOf(2L), after.blockingItemIds)
    }
}
