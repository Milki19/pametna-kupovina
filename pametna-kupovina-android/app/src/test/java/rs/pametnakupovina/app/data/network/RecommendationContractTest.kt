package rs.pametnakupovina.app.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecommendationContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `dekodira tri recommendation scenarija i neuparenu stavku`() {
        val payload = """
            {
              "listId": 7,
              "listName": "Moja kupovina",
              "requestedDate": "2026-08-05",
              "candidateStoreCount": 2,
              "evaluatedSingleStoreScenarios": 2,
              "evaluatedTwoStoreCombinations": 1,
              "assumptions": {
                "candidateRadiusMeters": 10000,
                "maxCandidateStores": 20,
                "maxPriceAgeDays": 14,
                "costPerKm": 20.0,
                "valuePerHour": 300.0,
                "costPerStop": 50.0,
                "straightLineAverageSpeedKmh": 30.0,
                "currency": "RSD"
              },
              "singleStore": ${scenario("SINGLE_STORE")},
              "recommendedBalance": ${scenario("RECOMMENDED_BALANCE")},
              "lowestPrice": ${scenario("LOWEST_PRICE")},
              "disclaimer": "Zalihe i cena na kasi nisu garantovane."
            }
        """.trimIndent()

        val result = json.decodeFromString<ShoppingRecommendationDto>(payload)

        assertEquals(RecommendationScenarioTypeDto.SINGLE_STORE, result.singleStore.type)
        assertEquals(
            RecommendationScenarioTypeDto.RECOMMENDED_BALANCE,
            result.recommendedBalance.type
        )
        assertEquals(RecommendationScenarioTypeDto.LOWEST_PRICE, result.lowestPrice.type)
        assertEquals(14, result.assumptions.maxPriceAgeDays)
        assertFalse(result.recommendedBalance.complete)
        assertEquals(
            RecommendationItemStatusDto.UNMATCHED,
            result.recommendedBalance.items.single().resultStatus
        )
    }

    private fun scenario(type: String): String = """
        {
          "type": "$type",
          "available": true,
          "complete": false,
          "explanation": "Primer",
          "coveredItems": 0,
          "unmatchedItems": 1,
          "unavailableItems": 0,
          "stopCount": 0,
          "basketCost": 0.0,
          "routeDistanceKm": 0.0,
          "routeDurationSeconds": 0,
          "travelCost": 0.0,
          "timeCost": 0.0,
          "stopCost": 0.0,
          "totalCost": 0.0,
          "savingsComparedWithSingleStore": 0.0,
          "routeProvider": "straight-line",
          "distanceMethod": "HAVERSINE",
          "approximateRoute": true,
          "priceSources": [],
          "dataAsOf": "2026-08-05",
          "stores": [],
          "items": [{
            "itemId": 1,
            "requestedName": "mleko",
            "requestedQuantity": 1.0,
            "matchingRule": "EXACT_PRODUCT",
            "matchingStatus": "UNMATCHED",
            "resultStatus": "UNMATCHED",
            "explanation": "Nije pronađeno uparivanje."
          }],
          "disclaimer": "Zalihe i cena na kasi nisu garantovane."
        }
    """.trimIndent()
}
