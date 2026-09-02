package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingItemMatchResultDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
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

    @Test
    fun `samo neupareni tacan proizvod moze postati fleksibilan`() {
        val unmatched = matchResult(
            rule = ShoppingItemRuleDto.EXACT_PRODUCT,
            status = ShoppingItemMatchingStatusDto.UNMATCHED
        )

        assertTrue(canUseAsFlexible(unmatched))
        assertFalse(
            canUseAsFlexible(
                matchResult(
                    rule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
                    status = ShoppingItemMatchingStatusDto.UNMATCHED
                )
            )
        )
        assertFalse(
            canUseAsFlexible(
                matchResult(
                    rule = ShoppingItemRuleDto.EXACT_PRODUCT,
                    status = ShoppingItemMatchingStatusDto.CONFIRMED
                )
            )
        )
    }

    private fun matchResult(
        rule: ShoppingItemRuleDto,
        status: ShoppingItemMatchingStatusDto
    ) = ShoppingItemMatchResultDto(
        itemId = 1,
        requestedName = "hleb",
        matchingRule = rule,
        matchingStatus = status,
        blocksOptimization = false,
        explanation = "test"
    )
}
