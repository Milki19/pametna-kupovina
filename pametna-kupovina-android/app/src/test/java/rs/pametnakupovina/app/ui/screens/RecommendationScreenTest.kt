package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.pametnakupovina.app.data.network.PurchaseQuantityDto
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

        assertEquals("6 × 170,50 RSD", itemPriceBreakdown(item))
    }

    private fun planned(requested: Double, packages: Double, unit: String) = RecommendationItemDto(
        itemId = 2,
        requestedName = "secer",
        requestedQuantity = requested,
        matchingRule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
        matchingStatus = ShoppingItemMatchingStatusDto.CONFIRMED,
        resultStatus = RecommendationItemStatusDto.AVAILABLE,
        explanation = "Dostupno",
        purchaseQuantity = PurchaseQuantityDto(packages = packages, packageSize = 500.0, baseUnit = unit)
    )

    @Test
    fun `mnogo malih pakovanja samo kad ih bira aplikacija`() {
        // "secer 25kg" as fifty half-kilo bags.
        assertTrue(manySmallPacks(planned(requested = 1.0, packages = 50.0, unit = "g")))
        // Twenty-four cans asked for by the piece.
        assertFalse(manySmallPacks(planned(requested = 1.0, packages = 24.0, unit = "piece")))
        // Twelve packs the shopper wrote themselves.
        assertFalse(manySmallPacks(planned(requested = 12.0, packages = 12.0, unit = "ml")))
        assertFalse(manySmallPacks(planned(requested = 1.0, packages = 4.0, unit = "g")))
    }
}
