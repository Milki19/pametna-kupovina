package rs.pametnakupovina.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import rs.pametnakupovina.app.data.network.OptimizationScenarioDto
import rs.pametnakupovina.app.data.network.UnlocatedPriceOptionDto
import rs.pametnakupovina.app.ui.screens.UnlocatedOptionsSection

/**
 * A chain without addresses must not read like an ordinary offer: the shopper
 * has to see that nobody can promise the product at that price in their shop.
 */
class UnlocatedChainWarningInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chainWithoutAddressesIsMarkedAsUnconfirmed() {
        composeRule.setContent {
            MaterialTheme {
                UnlocatedOptionsSection(
                    options = listOf(
                        UnlocatedPriceOptionDto(
                            retailerCode = "UNIVEREXPORT",
                            retailerName = "Univerexport",
                            coveredItems = 3,
                            totalItems = 3,
                            lowestBasketCost = 344.97,
                            highestBasketCost = 402.10,
                            priceListCount = 3,
                            caveat = "Ovaj lanac ne objavljuje adrese objekata."
                        )
                    ),
                    selected = scenario()
                )
            }
        }

        composeRule.onNodeWithText("Ne znamo u kojoj prodavnici važi ova cena")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Nepotvrđena prodavnica").assertIsDisplayed()
        composeRule.onNodeWithText("Univerexport").assertIsDisplayed()
    }

    private fun scenario(): OptimizationScenarioDto =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            """
            {"type": "RECOMMENDED_BALANCE", "available": true, "complete": true,
             "explanation": "Plan", "coveredItems": 3, "unmatchedItems": 0,
             "unavailableItems": 0, "stopCount": 1, "basketCost": 278.09,
             "routeDistanceKm": 1.0, "routeDurationSeconds": 120, "travelCost": 20.0,
             "timeCost": 20.0, "stopCost": 80.0, "totalCost": 398.09,
             "routeProvider": "straight-line", "distanceMethod": "HAVERSINE",
             "approximateRoute": true, "stores": [], "items": [], "disclaimer": "-"}
            """
        )
}
