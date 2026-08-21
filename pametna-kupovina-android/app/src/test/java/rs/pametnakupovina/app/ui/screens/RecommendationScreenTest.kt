package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.pametnakupovina.app.data.network.RecommendationItemDto
import rs.pametnakupovina.app.data.network.RecommendationItemStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto

class RecommendationScreenTest {

    @Test
    fun `prikazuje kolicinu puta jedinicnu cenu`() {
        val item = RecommendationItemDto(
            itemId = 1,
            requestedName = "AQUA VIVA 5L PET",
            requestedQuantity = 6.0,
            matchingRule = ShoppingItemRuleDto.EXACT_PRODUCT,
            matchingStatus = ShoppingItemMatchingStatusDto.CONFIRMED,
            resultStatus = RecommendationItemStatusDto.AVAILABLE,
            effectivePrice = 170.5,
            lineTotal = 1023.0,
            explanation = "Dostupno"
        )

        assertEquals("6 × 170.50 RSD", itemPriceBreakdown(item))
    }
}
