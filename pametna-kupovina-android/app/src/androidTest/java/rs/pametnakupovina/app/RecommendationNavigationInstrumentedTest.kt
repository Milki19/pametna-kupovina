package rs.pametnakupovina.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import rs.pametnakupovina.app.ui.screens.RouteNavigationCard

class RecommendationNavigationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun multipleStoresShowOneRouteAction() {
        var clicks = 0

        composeRule.setContent {
            MaterialTheme {
                RouteNavigationCard(
                    stopCount = 2,
                    onClick = { clicks++ }
                )
            }
        }

        composeRule.onNodeWithText("Navigacija kroz 2 prodavnice")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("open-google-maps")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, clicks)
        }
    }
}
