package rs.pametnakupovina.app.data

import rs.pametnakupovina.app.data.network.ShoppingItemMatchResultDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto

/**
 * A decision in step 1 changes one item, so only that item is matched again
 * and put in place of the old one; the counts follow from the items.
 */
internal fun ShoppingListMatchingDto.withItem(
    result: ShoppingItemMatchResultDto
): ShoppingListMatchingDto {
    val items = items.map { if (it.itemId == result.itemId) result else it }
    fun count(status: ShoppingItemMatchingStatusDto) = items.count { it.matchingStatus == status }
    val blocking = items.filter { it.blocksOptimization }.map { it.itemId }
    return copy(
        totalItems = items.size,
        automaticallyMatchedItems = count(ShoppingItemMatchingStatusDto.AUTO_MATCHED),
        confirmedItems = count(ShoppingItemMatchingStatusDto.CONFIRMED),
        itemsNeedingConfirmation = count(ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION),
        unmatchedItems = count(ShoppingItemMatchingStatusDto.UNMATCHED),
        flexibleItems = items.count { it.matchingRule == ShoppingItemRuleDto.FLEXIBLE_CATEGORY },
        readyForOptimization = blocking.isEmpty(),
        blockingItemIds = blocking,
        items = items
    )
}
