package rs.pametnakupovina.app.ui.screens

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.pametnakupovina.app.data.network.PurchaseQuantityDto
import rs.pametnakupovina.app.data.network.RecommendationItemDto
import rs.pametnakupovina.app.data.network.RecommendationItemStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.RecommendationScenarioTypeDto
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto

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

    private fun store(id: Long) = """
        {"stopOrder": 1, "storeId": $id, "retailerCode": "LIDL", "retailerName": "Lidl",
         "storeFormatCode": null, "storeFormatName": null, "storeName": "Lidl $id",
         "address": null, "city": null, "latitude": 44.8, "longitude": 20.5,
         "distanceFromPreviousKm": 1.0, "durationFromPreviousSeconds": 120}
    """

    private fun scenario(type: String, basket: Double, vararg stores: Long) = """
        {"type": "$type", "available": true, "complete": true, "explanation": "Plan",
         "coveredItems": 18, "unmatchedItems": 0, "unavailableItems": 0,
         "stopCount": ${stores.size}, "basketCost": $basket, "routeDistanceKm": 3.0,
         "routeDurationSeconds": 600, "travelCost": 60.0, "timeCost": 60.0, "stopCost": 80.0,
         "totalCost": ${basket + 200}, "routeProvider": "straight-line",
         "distanceMethod": "HAVERSINE", "approximateRoute": true,
         "stores": [${stores.joinToString(",") { store(it) }}], "items": [], "disclaimer": "-"}
    """

    private fun recommendation(single: String, best: String, lowest: String) =
        Json { ignoreUnknownKeys = true }.decodeFromString<ShoppingRecommendationDto>("""
            {"listId": 1, "listName": "Slava", "requestedDate": "2026-09-15",
             "candidateStoreCount": 20, "evaluatedSingleStoreScenarios": 20,
             "evaluatedTwoStoreCombinations": 190,
             "assumptions": {"candidateRadiusMeters": 15000, "maxCandidateStores": 20,
               "maxPriceAgeDays": 30, "costPerKm": 20.0, "valuePerHour": 400.0,
               "costPerStop": 80.0, "straightLineAverageSpeedKmh": 30.0, "currency": "RSD"},
             "singleStore": $single, "recommendedBalance": $best, "lowestPrice": $lowest,
             "disclaimer": "-"}
        """)

    @Test
    fun `isti plan se ne nudi dva puta`() {
        // The slava list: the best overall plan is also the cheapest basket.
        val slava = recommendation(
            scenario("SINGLE_STORE", 18454.92, 722),
            scenario("RECOMMENDED_BALANCE", 13032.68, 86, 383),
            scenario("LOWEST_PRICE", 13032.68, 383, 86)
        )
        assertEquals(
            listOf(RecommendationScenarioTypeDto.SINGLE_STORE, RecommendationScenarioTypeDto.RECOMMENDED_BALANCE),
            distinctScenarios(slava).map { it.type }
        )

        val threePlans = recommendation(
            scenario("SINGLE_STORE", 18454.92, 722),
            scenario("RECOMMENDED_BALANCE", 13100.00, 86, 383),
            scenario("LOWEST_PRICE", 13032.68, 86, 402)
        )
        assertEquals(3, distinctScenarios(threePlans).size)

        // A short list: the cheapest basket is the one store, a nearer store
        // is the best overall plan.
        val shortList = recommendation(
            scenario("SINGLE_STORE", 3250.00, 722),
            scenario("RECOMMENDED_BALANCE", 3259.98, 650),
            scenario("LOWEST_PRICE", 3250.00, 722)
        )
        assertEquals(
            listOf(RecommendationScenarioTypeDto.SINGLE_STORE, RecommendationScenarioTypeDto.RECOMMENDED_BALANCE),
            distinctScenarios(shortList).map { it.type }
        )

        val oneStoreIsBest = recommendation(
            scenario("SINGLE_STORE", 9000.00, 722),
            scenario("RECOMMENDED_BALANCE", 9000.00, 722),
            scenario("LOWEST_PRICE", 8900.00, 722, 86)
        )
        assertEquals(
            listOf(RecommendationScenarioTypeDto.RECOMMENDED_BALANCE, RecommendationScenarioTypeDto.LOWEST_PRICE),
            distinctScenarios(oneStoreIsBest).map { it.type }
        )
    }
}
