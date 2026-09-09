package rs.pametnakupovina.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
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
            // Correcting one row retries only that row, without duplicating prior successes.
            dao.update(rejected.copy(name="jogurt jagoda",category="jogurt jagoda"))
            pushPendingItems(api,dao,42)
            assertEquals(listOf("jogurt","mleko","kefir","jogurt jagoda"),sent)
            assertTrue(dao.getAllItems().all { it.syncState == "SYNCED" })
        } finally { db.close() }
    }
}
