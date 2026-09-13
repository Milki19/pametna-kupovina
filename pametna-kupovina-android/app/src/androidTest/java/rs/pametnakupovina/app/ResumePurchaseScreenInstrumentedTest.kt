package rs.pametnakupovina.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.ui.screens.RecommendationContent

class ResumePurchaseScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resumeKeepsExistingSessionAndNewRequiresConfirmation() {
        val result = purchaseTestResult()
        val active = PurchaseSession("saved-id",1,null,
            PurchaseSnapshot(listId=result.listId,listName=result.listName,
                calculationDate=result.requestedDate,scenario=result.recommendedBalance),
            mapOf(1L to PurchaseItemProgress(PurchaseStatus.PURCHASED,5.0)))
        var resumed: String? = null
        var newCount = 0
        compose.setContent {
            MaterialTheme {
                RecommendationContent(result,44.27 to 19.88,false,null,active,true,
                    onResume={ resumed=it }, onStart={ scenario, createNew ->
                        assertEquals(result.recommendedBalance,scenario)
                        assertTrue(createNew)
                        newCount++
                    })
            }
        }
        compose.onNodeWithTag("recommendation-list").performScrollToNode(hasTestTag("resume-previous-purchase"))
        compose.onNodeWithText("Nastavi prethodnu kupovinu").performClick()
        compose.runOnIdle { assertEquals("saved-id",resumed); assertEquals(0,newCount) }
        compose.onNodeWithTag("start-or-resume-purchase").performClick()
        compose.onNodeWithTag("confirm-new-purchase").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0,newCount) }
        compose.onNodeWithText("Otkaži").performClick()
        compose.onNodeWithTag("start-or-resume-purchase").performClick()
        compose.onNodeWithTag("confirm-new-purchase").performClick()
        compose.runOnIdle { assertEquals(1,newCount) }
    }

    @Test fun withoutActiveSessionStartsSelectedPlan() {
        val result = purchaseTestResult()
        var started = false
        compose.setContent {
            MaterialTheme {
                RecommendationContent(result,44.27 to 19.88,false,null,null,true,
                    onResume={ fail("No session to resume") }, onStart={ scenario, createNew ->
                        assertEquals(result.recommendedBalance,scenario)
                        assertFalse(createNew)
                        started=true
                    })
            }
        }
        compose.onNodeWithText("Započni kupovinu po ovom planu").performClick()
        compose.runOnIdle { assertTrue(started) }
    }
}
