package rs.pametnakupovina.app

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase
import rs.pametnakupovina.app.data.network.ShoppingApiService
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InfrastructureSmokeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var retrofit: Retrofit
    @Inject lateinit var api: ShoppingApiService
    @Inject lateinit var database: PametnaKupovinaDatabase
    @Inject lateinit var identityStore: ClientIdentityStore

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun hiltRetrofitRoomAndDataStoreAreReady() = runBlocking {
        assertEquals(BuildConfig.BACKEND_BASE_URL, retrofit.baseUrl().toString())
        if (!retrofit.baseUrl().isHttps) {
            assertTrue(
                NetworkSecurityPolicy.getInstance()
                    .isCleartextTrafficPermitted(retrofit.baseUrl().host)
            )
        }
        assertNotNull(api)
        assertNotNull(database.draftItemDao().getAllItems())

        val firstToken = identityStore.getOrCreateToken()
        val secondToken = identityStore.getOrCreateToken()
        assertEquals(firstToken, secondToken)
    }
}
