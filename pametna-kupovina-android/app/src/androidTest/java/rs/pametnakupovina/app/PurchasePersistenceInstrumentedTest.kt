package rs.pametnakupovina.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rs.pametnakupovina.app.data.local.*
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.data.network.*
import java.util.UUID

internal fun purchaseTestResult(): ShoppingRecommendationDto {
    val product = RecommendationItemDto(
        itemId=1, requestedName="jogurt", requestedQuantity=1.0,
        matchingRule=ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
        matchingStatus=ShoppingItemMatchingStatusDto.CONFIRMED,
        resultStatus=RecommendationItemStatusDto.AVAILABLE, storeId=1,
        productName="Jogurt 200g", effectivePrice=23.0, lineTotal=115.0,
        explanation="Test", purchaseQuantity=PurchaseQuantityDto(5.0,200.0,"g",1000.0,1000.0,0.0,115.0)
    )
    val scenario = OptimizationScenarioDto(
        type=RecommendationScenarioTypeDto.RECOMMENDED_BALANCE, available=true,complete=true,
        explanation="Test",coveredItems=2,unmatchedItems=0,unavailableItems=0,stopCount=1,
        basketCost=230.0,routeDistanceKm=1.0,routeDurationSeconds=100,travelCost=20.0,
        timeCost=10.0,stopCost=80.0,totalCost=340.0,routeProvider="test",distanceMethod="test",
        approximateRoute=true,
        stores=listOf(RecommendationStoreDto(1,1,"TEST","Test","TEST","Test","Prodavnica",
            "Adresa","Valjevo",44.27,19.88,0.5,50)),
        items=listOf(product,product.copy(itemId=2,requestedName="Drugi jogurt")),disclaimer="Test cene"
    )
    return ShoppingRecommendationDto(
        listId=10,listName="Test kupovina",requestedDate="2026-09-06",candidateStoreCount=1,
        evaluatedSingleStoreScenarios=1,evaluatedTwoStoreCombinations=0,
        assumptions=OptimizationAssumptionsDto(15000,20,30,20.0,400.0,80.0,30.0,"RSD"),
        singleStore=scenario.copy(type=RecommendationScenarioTypeDto.SINGLE_STORE),
        recommendedBalance=scenario,
        lowestPrice=scenario.copy(type=RecommendationScenarioTypeDto.LOWEST_PRICE),
        disclaimer="Test"
    )
}

@RunWith(AndroidJUnit4::class)
class PurchasePersistenceInstrumentedTest {
    @Test fun snapshotAndIndependentProgressSurviveDatabaseReopenWithoutNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "purchase-test-" + UUID.randomUUID() + ".db"
        fun open() = Room.databaseBuilder(context, PametnaKupovinaDatabase::class.java, name).build()
        var db = open()
        try {
            var repository = PurchaseRepository(db, Json { ignoreUnknownKeys = true })
            val result = purchaseTestResult()
            val id = repository.start(result, result.recommendedBalance)
            val first = async {
                repository.update(id,1) { it.copy(boughtPackages=2.0,note="Dve za sada",actualLineTotal="46,00") }
            }
            val second = async {
                repository.update(id,2) { it.copy(status=PurchaseStatus.PURCHASED) }
            }
            first.await(); second.await()
            val encodedBefore = db.purchaseSessionDao().get(id)!!.snapshotJson
            // Editing the draft must not rewrite the saved allocation.
            db.draftItemDao().insert(DraftItemEntity(name="Novi spisak",quantity=9.0,matchingRule="FLEXIBLE_CATEGORY"))
            db.close()
            db = open()
            repository = PurchaseRepository(db, Json { ignoreUnknownKeys = true })
            val restored = repository.observe(id).first()!!
            assertEquals(1, restored.purchasedCount)
            assertEquals(2.0, restored.progress[1]!!.boughtPackages,0.0)
            assertEquals("46.00",restored.progress[1]!!.actualLineTotal)
            assertEquals(result.recommendedBalance,restored.snapshot.scenario)
            assertEquals(encodedBefore,db.purchaseSessionDao().get(id)!!.snapshotJson)
            repository.archive(id,true)
            assertNotNull(repository.observe(id).first()!!.archivedAt)
            repository.archive(id,false)
            repository.update(id,2) { it.copy(status=PurchaseStatus.TO_BUY,boughtPackages=0.0) }
            assertEquals(0,repository.observe(id).first()!!.purchasedCount)
            assertEquals(1,db.draftItemDao().getAllItems().size)
            assertEquals(0,repository.sessions.first().single().purchasedCount)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
