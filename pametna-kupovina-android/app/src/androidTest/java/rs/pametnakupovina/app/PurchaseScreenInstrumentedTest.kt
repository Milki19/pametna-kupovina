package rs.pametnakupovina.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
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
            val viewModel = PurchaseViewModel(repository, MissingProductReporter { _, _, _ -> }, ShoppingListClearer {}, fakeList().first)
            compose.setContent {
                MaterialTheme { PurchaseScreen(id, onBack = {}, onOpen = {}, viewModel = viewModel) }
            }
            compose.waitUntil(5000) { viewModel.session.value != null }
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasTestTag("bought-1"))
            compose.onNodeWithTag("bought-1").performClick()
            compose.waitUntil(5000) { viewModel.session.value?.purchasedCount == 1 }
            // Kupljeno se sklanja u sklopljen odeljak na dnu radnje.
            compose.onAllNodesWithTag("bought-1").assertCountEquals(0)
            compose.onNodeWithTag("group-bought-store-1").performClick()
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasTestTag("bought-1"))
            compose.onNodeWithTag("bought-1").assertIsOn().performClick()
            compose.waitUntil(5000) { viewModel.session.value?.purchasedCount == 0 }
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasTestTag("item-menu-1"))
            compose.onNodeWithTag("item-menu-1").performClick()
            compose.onAllNodesWithText(text(R.string.purchase_details))[0].performClick()
            compose.onNodeWithText(text(R.string.purchase_note_field)).performTextInput("Uzmi hladan")
            compose.onNodeWithText(text(R.string.purchase_bought_field)).performTextReplacement("2")
            compose.onNodeWithText(text(R.string.purchase_actual_total_field, "RSD")).performTextInput("46,00")
            compose.onNodeWithText(text(R.string.common_save)).performClick()
            compose.waitUntil(5000) { viewModel.session.value?.progress?.get(1)?.note == "Uzmi hladan" }
            val saved = runBlocking { repository.observe(id).first()!! }
            assertEquals(2.0, saved.progress[1]!!.boughtPackages,0.0)
            assertEquals("46.00", saved.progress[1]!!.actualLineTotal)
            assertEquals(PurchaseStatus.TO_BUY,saved.progress[1]!!.status)
        } finally { database.close() }
    }

    @Test fun swipeLeftSkipsAndUndoBringsTheItemBack() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PametnaKupovinaDatabase::class.java).build()
        try {
            val repository = PurchaseRepository(database, Json { ignoreUnknownKeys = true })
            val result = purchaseTestResult()
            val id = runBlocking { repository.start(result, result.recommendedBalance) }
            val viewModel = PurchaseViewModel(repository, MissingProductReporter { _, _, _ -> }, ShoppingListClearer {}, fakeList().first)
            compose.setContent {
                MaterialTheme { PurchaseScreen(id, onBack = {}, onOpen = {}, viewModel = viewModel) }
            }
            compose.waitUntil(5000) { viewModel.session.value != null }
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasTestTag("purchase-item-2"))
            compose.onNodeWithTag("purchase-item-2").performTouchInput { swipeLeft() }
            compose.waitUntil(5000) { viewModel.session.value?.progress?.get(2)?.status == PurchaseStatus.SKIPPED }
            compose.onNodeWithText(text(R.string.purchase_group_skipped, 1)).assertExists()
            compose.onNodeWithText(text(R.string.purchase_undo)).performClick()
            compose.waitUntil(5000) { viewModel.session.value?.progress?.get(2)?.status == PurchaseStatus.TO_BUY }

            compose.onNodeWithTag("purchase-item-1").performTouchInput { swipeRight() }
            compose.waitUntil(5000) { viewModel.session.value?.purchasedCount == 1 }
        } finally { database.close() }
    }

    @Test fun deleteFromListLeavesTheTripAndTheList() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PametnaKupovinaDatabase::class.java).build()
        try {
            val repository = PurchaseRepository(database, Json { ignoreUnknownKeys = true })
            val result = purchaseTestResult()
            val id = runBlocking { repository.start(result, result.recommendedBalance) }
            val (list, removed) = fakeList()
            val viewModel = PurchaseViewModel(repository, MissingProductReporter { _, _, _ -> }, ShoppingListClearer {}, list)
            compose.setContent {
                MaterialTheme { PurchaseScreen(id, onBack = {}, onOpen = {}, viewModel = viewModel) }
            }
            compose.waitUntil(5000) { viewModel.session.value != null }
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasTestTag("item-menu-2"))
            compose.onNodeWithTag("item-menu-2").performClick()
            compose.onNodeWithTag("remove-from-list-2").performClick()
            compose.onNodeWithTag("purchase-remove-from-list-confirm").performClick()
            compose.waitUntil(5000) { viewModel.session.value?.progress?.get(2)?.removedFromList == true }
            assertEquals(setOf(2L), removed.value)
            assertEquals(PurchaseStatus.SKIPPED, viewModel.session.value!!.progress[2]!!.status)
        } finally { database.close() }
    }

    @Test fun finishedTripTakesOnlyTheBoughtOffTheList() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PametnaKupovinaDatabase::class.java).build()
        try {
            val repository = PurchaseRepository(database, Json { ignoreUnknownKeys = true })
            val result = purchaseTestResult()
            val id = runBlocking {
                repository.start(result, result.recommendedBalance).also {
                    repository.update(it, 1) { p -> p.copy(status = PurchaseStatus.PURCHASED) }
                    repository.archive(it, true)
                }
            }
            val (list, removed) = fakeList()
            val viewModel = PurchaseViewModel(repository, MissingProductReporter { _, _, _ -> }, ShoppingListClearer {}, list)
            compose.setContent {
                MaterialTheme { PurchaseScreen(id, onBack = {}, onOpen = {}, viewModel = viewModel) }
            }
            compose.waitUntil(5000) { viewModel.session.value != null }
            compose.onNodeWithTag("purchase-list").performScrollToNode(hasTestTag("purchase-remove-bought"))
            compose.onNodeWithTag("purchase-remove-bought").assertTextContains(text(R.string.purchase_remove_bought_count, 1)).performClick()
            compose.waitUntil(5000) { removed.value == setOf(1L) }
        } finally { database.close() }
    }
}

/**
 * Glavni spisak sa obe test stavke (1 i 2) i hleb dodat posle plana;
 * brisanje ga menja kao pravi spisak i pamti šta je obrisano.
 */
private fun fakeList(): Pair<ShoppingListItems, MutableStateFlow<Set<Long>>> {
    val removed = MutableStateFlow<Set<Long>>(emptySet())
    val entries = MutableStateFlow(listOf(ListEntry(1, "jogurt"), ListEntry(2, "Drugi jogurt"), ListEntry(null, "hleb")))
    val list = ShoppingListItems(
        entries = { entries },
        remove = { ids ->
            removed.value = removed.value + ids
            entries.value = entries.value.filter { it.itemId !in ids }
        }
    )
    return list to removed
}

/** A text as the app shows it on this device, so the test runs in any language. */
private fun text(id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
