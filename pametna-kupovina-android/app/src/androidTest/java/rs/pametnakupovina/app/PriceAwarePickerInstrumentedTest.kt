package rs.pametnakupovina.app

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.components.canonicalProductPicker
import rs.pametnakupovina.app.ui.screens.AlternativePickerDialog

class PriceAwarePickerInstrumentedTest {
    @get:Rule val compose=createComposeRule()

    @Test fun replacementEditsOnlyDraftAndPreservesSavedPurchase() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            context, rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase::class.java
        ).build()
        try {
            val api = java.lang.reflect.Proxy.newProxyInstance(
                rs.pametnakupovina.app.data.network.ShoppingApiService::class.java.classLoader,
                arrayOf(rs.pametnakupovina.app.data.network.ShoppingApiService::class.java)
            ) { _, _, _ -> error("Local replacement must not call the network") }
                as rs.pametnakupovina.app.data.network.ShoppingApiService
            val dao = database.draftItemDao()
            val repository = rs.pametnakupovina.app.data.ShoppingRepository(
                api, dao, rs.pametnakupovina.app.data.preferences.ClientIdentityStore(context)
            )
            dao.insert(rs.pametnakupovina.app.data.local.DraftItemEntity(
                remoteId=99, name="Staro mleko", rawInput="Pilos mleko", quantity=2.0,
                matchingRule="FLEXIBLE_CATEGORY", matchingStatus="CONFIRMED", category="mleko",
                targetQuantity=1000.0, requiredBaseUnit="ml", syncState="SYNCED"
            ))
            val purchases = rs.pametnakupovina.app.data.purchase.PurchaseRepository(database, kotlinx.serialization.json.Json)
            val recommendation = purchaseTestResult()
            val purchaseId = purchases.start(recommendation, recommendation.recommendedBalance)
            purchases.update(purchaseId, 1) { it.copy(status=rs.pametnakupovina.app.data.purchase.PurchaseStatus.PURCHASED) }
            val savedBefore = database.purchaseSessionDao().get(purchaseId)
            val unavailable = CanonicalProductSearchItemDto(productFamilyId=456, name="Novo mleko", score=0.5)
            assertTrue(runCatching { repository.replaceWithAlternative(99, unavailable, 3.0) }.isFailure)
            assertEquals("Staro mleko", dao.findByRemoteId(99)!!.name)
            repository.replaceWithAlternative(99, unavailable.copy(hasUsablePrice=true), 3.0)
            val changed = dao.findByRemoteId(99)!!
            assertEquals("Novo mleko", changed.name)
            assertEquals("Pilos mleko", repository.alternativeQuery(99))
            assertEquals(456L, changed.productFamilyId)
            assertEquals(3.0, changed.quantity, 0.0)
            assertEquals("PRODUCT_FAMILY", changed.matchingRule)
            assertEquals("PENDING_UPDATE", changed.syncState)
            assertNull(changed.targetQuantity)
            assertNull(changed.requiredBaseUnit)
            assertEquals(savedBefore, database.purchaseSessionDao().get(purchaseId))
        } finally {
            database.close()
        }
    }

    @Test fun noPriceShowsWarningAndRequiresExplicitConfirmation() {
        val product=CanonicalProductSearchItemDto(productFamilyId=1,name="Mleko",brand="Pilos",score=0.38,knownRetailers=listOf("Lidl"))
        var selected=false
        var include=false
        compose.setContent { MaterialTheme { LazyColumn(Modifier.testTag("picker")) {
            canonicalProductPicker("Pilos",null,ProductSearchUiState(query="Pilos",results=listOf(product)),
                {},{ selected=true },{},{},{},{ include=it })
        } } }
        compose.onNodeWithText("Nemamo aktuelnu cenu").assertExists()
        compose.onNodeWithText("Zabeležen kod: Lidl").assertExists()
        compose.onNodeWithText("Poklapanje: 38%").assertDoesNotExist()
        compose.onNodeWithTag("include-without-price").performClick()
        compose.runOnIdle { assertTrue(include) }
        compose.onNodeWithTag("picker").performScrollToNode(hasTestTag("product-choose-1"))
        compose.onNodeWithTag("product-choose-1").performClick()
        compose.runOnIdle { assertFalse(selected) }
        compose.onNodeWithTag("confirm-without-price").performClick()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test fun choosingAlternativeDoesNotReplaceUntilConfirmed() {
        val product=CanonicalProductSearchItemDto(productFamilyId=1,name="Drugo mleko 1l",brand="Pilos",score=0.5,hasUsablePrice=true)
        var saved=false
        compose.setContent { MaterialTheme {
            AlternativePickerDialog("Staro mleko",ProductSearchUiState(query="Pilos mleko",results=listOf(product)),false,null,
                {},{},{},{},{ chosen,amount -> assertEquals(product,chosen); assertEquals(1.0,amount,0.0); saved=true })
        } }
        compose.onNodeWithTag("alternatives-list").performScrollToNode(hasTestTag("product-choose-1"))
        compose.onNodeWithTag("product-choose-1").performClick()
        compose.runOnIdle { assertFalse(saved) }
        compose.onNodeWithTag("confirm-alternative").performClick()
        compose.runOnIdle { assertTrue(saved) }
    }
}
