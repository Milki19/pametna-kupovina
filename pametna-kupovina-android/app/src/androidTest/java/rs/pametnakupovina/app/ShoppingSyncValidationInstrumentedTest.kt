package rs.pametnakupovina.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response
import rs.pametnakupovina.app.data.ItemSyncValidationException
import rs.pametnakupovina.app.data.pushPendingItems
import rs.pametnakupovina.app.data.local.*
import rs.pametnakupovina.app.data.network.*
import rs.pametnakupovina.app.ui.toUserMessage

@RunWith(AndroidJUnit4::class)
class ShoppingSyncValidationInstrumentedTest {
    @Test fun invalidDescriptionDoesNotBlockFollowingItemsOrEraseTheDraft() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            PametnaKupovinaDatabase::class.java).build()
        try {
            val dao = db.draftItemDao()
            for (name in listOf("jogurt", "jogurt nepoznati ukus", "mleko", "kefir")) {
                dao.insert(DraftItemEntity(name=name,category=name,quantity=1.0,matchingRule="FLEXIBLE_CATEGORY"))
            }
            val sent = mutableListOf<String>()
            val api = Proxy.newProxyInstance(ShoppingApiService::class.java.classLoader,
                arrayOf(ShoppingApiService::class.java)) { _, method, args ->
                check(method.name == "addShoppingListItem")
                val request = args!![1] as AddShoppingListItemRequestDto
                if (request.name.contains("nepoznati")) {
                    throw HttpException(Response.error<Any>(400, "invalid description".toResponseBody()))
                }
                sent += request.name
                ShoppingListItemDto(id=sent.size.toLong(),name=request.name,quantity=1.0,
                    matchingRule=ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
                    matchingStatus=ShoppingItemMatchingStatusDto.CONFIRMED,
                    createdAt="2026-09-07",updatedAt="2026-09-07")
            } as ShoppingApiService
            try {
                pushPendingItems(api,dao,42)
                fail("Calculation must not silently omit an invalid row")
            } catch (error: ItemSyncValidationException) {
                assertEquals(listOf("jogurt nepoznati ukus"),error.itemNames)
                assertTrue(error.toUserMessage("offline").contains("jogurt nepoznati ukus"))
                assertFalse(error.toUserMessage("offline").contains("offline"))
            }
            assertEquals(listOf("jogurt","mleko","kefir"),sent)
            assertEquals(4,dao.getAllItems().size)
            assertEquals(3,dao.getAllItems().count { it.syncState == "SYNCED" })
            val rejected = dao.getAllItems().single { it.syncState == "PENDING_CREATE" }
            assertNull(rejected.remoteId)
            assertEquals("Server nije prihvatio ovu stavku.", rejected.syncError)
            // Correcting one row retries only that row, without duplicating prior successes.
            dao.update(rejected.copy(name="jogurt jagoda",category="jogurt jagoda"))
            pushPendingItems(api,dao,42)
            assertEquals(listOf("jogurt","mleko","kefir","jogurt jagoda"),sent)
            assertTrue(dao.getAllItems().all { it.syncState == "SYNCED" })
            assertTrue(dao.getAllItems().all { it.syncError == null })
        } finally { db.close() }
    }

    @Test fun pastedLineIsReadByTheServerAndARefusalKeepsItsReason() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            PametnaKupovinaDatabase::class.java).build()
        try {
            val dao = db.draftItemDao()
            for (line in listOf("Pivo Zaječarsko 0.5", "0 x hleb")) {
                dao.insert(DraftItemEntity(name=line,rawInput=line,category=line,quantity=1.0,
                    matchingRule="FLEXIBLE_CATEGORY",syncState="PENDING_PASTE"))
            }
            val api = Proxy.newProxyInstance(ShoppingApiService::class.java.classLoader,
                arrayOf(ShoppingApiService::class.java)) { _, method, args ->
                check(method.name == "pasteShoppingListItems")
                val request = args!![1] as PasteShoppingListItemsRequestDto
                if (request.text != "Pivo Zaječarsko 0.5") {
                    throw HttpException(Response.error<Any>(400,
                        """{"status":400,"message":"Količina mora biti veća od nule"}"""
                            .toResponseBody("application/json".toMediaType())))
                }
                PasteShoppingListItemsResponseDto(createdCount=1, ignoredBlankLineCount=0, items=listOf(
                    ShoppingListItemDto(id=7,name="Pivo Zaječarsko",rawInput=request.text,quantity=1.0,
                        matchingRule=ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
                        matchingStatus=ShoppingItemMatchingStatusDto.CONFIRMED,
                        flexibleConstraints=FlexibleItemConstraintsDto(category="Pivo",requiredBrand="Zaječarsko",
                            requiredBaseUnit="ml",targetQuantity=500.0),
                        createdAt="2026-09-15",updatedAt="2026-09-15")))
            } as ShoppingApiService
            try {
                pushPendingItems(api,dao,42)
                fail("A refused line must not disappear silently")
            } catch (error: ItemSyncValidationException) {
                assertEquals(listOf("0 x hleb"), error.itemNames)
                assertEquals(42L, error.listId)
            }
            val beer = dao.getAllItems().single { it.remoteId == 7L }
            assertEquals("Zaječarsko", beer.requiredBrand)
            assertEquals(500.0, beer.targetQuantity!!, 0.0)
            assertEquals("SYNCED", beer.syncState)
            val refused = dao.getAllItems().single { it.remoteId == null }
            assertEquals("Količina mora biti veća od nule", refused.syncError)
            assertEquals("PENDING_PASTE", refused.syncState)
        } finally { db.close() }
    }

    @Test fun refreshAroundARefusedRowKeepsTheListInOrder() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            PametnaKupovinaDatabase::class.java).build()
        try {
            val dao = db.draftItemDao()
            dao.insert(DraftItemEntity(remoteId=1,name="ulje",quantity=1.0,matchingRule="FLEXIBLE_CATEGORY",syncState="SYNCED"))
            dao.insert(DraftItemEntity(name="hleb",rawInput="0 x hleb",quantity=1.0,matchingRule="FLEXIBLE_CATEGORY",
                syncState="PENDING_PASTE",syncError="Količina mora biti veća od nule"))
            dao.insert(DraftItemEntity(remoteId=2,name="jaja",quantity=1.0,matchingRule="FLEXIBLE_CATEGORY",syncState="SYNCED"))
            dao.replaceSyncedWithRemote(listOf(
                DraftItemEntity(remoteId=1,name="Ulje",quantity=1.0,matchingRule="FLEXIBLE_CATEGORY",syncState="SYNCED"),
                DraftItemEntity(remoteId=2,name="Jaja",quantity=1.0,matchingRule="FLEXIBLE_CATEGORY",syncState="SYNCED")
            ))
            assertEquals(listOf("Ulje","hleb","Jaja"), dao.getAllItems().map { it.name })
        } finally { db.close() }
    }
}
