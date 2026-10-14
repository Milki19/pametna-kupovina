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

    @Test
    fun `jedno stanje - samo potvrda zadrzava korak, nepronadjeno ne`() {
        val auto = matchResult(ShoppingItemRuleDto.FLEXIBLE_CATEGORY, ShoppingItemMatchingStatusDto.AUTO_MATCHED)
        val unmatched = matchResult(ShoppingItemRuleDto.EXACT_PRODUCT, ShoppingItemMatchingStatusDto.UNMATCHED)
        val unclear = matchResult(ShoppingItemRuleDto.EXACT_PRODUCT, ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION)

        assertTrue(isFound(auto))
        assertFalse(needsDecision(unmatched))
        assertTrue(notFound(unmatched))
        assertTrue(needsDecision(unclear))
        assertFalse(notFound(unclear))
    }

    @Test
    fun `ista stavka dva puta se prepoznaje bez obzira na kvacice i velika slova`() {
        assertEquals(listOf("mleko", "Šećer"), duplicateNames(listOf("mleko", "hleb", "Mleko ", "Šećer", "secer")))
        assertEquals(emptyList<String>(), duplicateNames(listOf("mleko", "hleb")))
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
