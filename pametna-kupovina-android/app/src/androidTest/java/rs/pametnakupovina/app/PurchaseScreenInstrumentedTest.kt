package rs.pametnakupovina.app

import androidx.room.Room
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.ui.PurchaseViewModel
import rs.pametnakupovina.app.ui.screens.PurchaseScreen

class PurchaseScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkUndoAndNoteAreSavedThroughTheScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PametnaKupovinaDatabase::class.java).build()
        try {
            val repository = PurchaseRepository(database, Json { ignoreUnknownKeys = true })
            val result = purchaseTestResult()
            val id = runBlocking { repository.start(result, result.recommendedBalance) }
            val viewModel = PurchaseViewModel(repository)
            compose.setContent {
                MaterialTheme { PurchaseScreen(id, onBack = {}, onOpen = {}, viewModel = viewModel) }
            }
            compose.waitUntil(5000) { viewModel.session.value != null }
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasTestTag("bought-1"))
            compose.onNodeWithTag("bought-1").performClick()
            compose.waitUntil(5000) { viewModel.session.value?.purchasedCount == 1 }
            compose.onNodeWithTag("bought-1").assertIsOn().performClick()
            compose.waitUntil(5000) { viewModel.session.value?.purchasedCount == 0 }
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasText("Detalji"))
            compose.onAllNodesWithText("Detalji")[0].performClick()
            compose.onNodeWithText("Napomena").performTextInput("Uzmi hladan")
            compose.onNodeWithText("Kupljeno (može delimično)").performTextReplacement("2")
            compose.onNodeWithText("Stvarni ukupan iznos, RSD (opciono)").performTextInput("46,00")
            compose.onNodeWithText("Sačuvaj").performClick()
            compose.waitUntil(5000) { viewModel.session.value?.progress?.get(1)?.note == "Uzmi hladan" }
            val saved = runBlocking { repository.observe(id).first()!! }
            assertEquals(2.0, saved.progress[1]!!.boughtPackages,0.0)
            assertEquals("46.00", saved.progress[1]!!.actualLineTotal)
            assertEquals(PurchaseStatus.TO_BUY,saved.progress[1]!!.status)
        } finally { database.close() }
    }
}
