package rs.pametnakupovina.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.google.android.gms.location.LocationServices
import rs.pametnakupovina.app.data.BarcodeScanner
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.local.PametnaKupovinaDatabase
import rs.pametnakupovina.app.data.network.CanonicalProductSearchPageDto
import rs.pametnakupovina.app.data.network.ShoppingApiService
import rs.pametnakupovina.app.data.preferences.ClientIdentityStore
import rs.pametnakupovina.app.location.FusedLocationProvider
import rs.pametnakupovina.app.ui.ProductSearchViewModel
import rs.pametnakupovina.app.ui.screens.AlternativePickerDialog

class ProductSearchTypingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun spacesSurviveTypingResponsesRetryFilterAndPagination() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PametnaKupovinaDatabase::class.java).build()
        val requests = ConcurrentLinkedQueue<String>()
        val api = Proxy.newProxyInstance(
            ShoppingApiService::class.java.classLoader, arrayOf(ShoppingApiService::class.java)
        ) { _, method, args ->
            check(method.name == "searchProducts") { "Unexpected API call: ${method.name}" }
            val query = args!![0] as String
            val page = args[1] as Int
            requests.add(query)
            CanonicalProductSearchPageDto(query, page, 10, 20, 2, page == 0)
        } as ShoppingApiService
        val viewModel = ProductSearchViewModel(
            ShoppingRepository(api, database.draftItemDao(), ClientIdentityStore(context)),
            BarcodeScanner(),
            FusedLocationProvider(context, LocationServices.getFusedLocationProviderClient(context))
        )
        try {
            compose.setContent {
                val state by viewModel.uiState.collectAsState()
                MaterialTheme {
                    AlternativePickerDialog("Mleko", state, false, null,
                        viewModel::updateQuery, viewModel::retry, viewModel::loadNextPage, {}, { _, _ -> })
                }
            }
            val field = compose.onNodeWithTag("product-search-field")
            fun assertInput(expected: String) {
                field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))
            }
            field.performTextInput("mleko")
            compose.waitUntil(5000) { !viewModel.uiState.value.isSearching }
            field.performTextInput(" ")
            assertInput("mleko ")
            compose.waitUntil(5000) { !viewModel.uiState.value.isSearching }
            assertInput("mleko ")
            field.performTextInput("moja")
            field.performTextInput(" ")
            field.performTextInput("kravica")
            compose.waitUntil(5000) { !viewModel.uiState.value.isSearching }
            assertInput("mleko moja kravica")
            assertEquals("mleko moja kravica", requests.last())

            field.performTextReplacement("  mleko moja kravica  ")
            compose.waitUntil(5000) { !viewModel.uiState.value.isSearching }
            assertInput("  mleko moja kravica  ")
            assertEquals("mleko moja kravica", requests.last())
            compose.runOnIdle { viewModel.retry() }
            compose.waitUntil(5000) { !viewModel.uiState.value.isSearching }
            assertInput("  mleko moja kravica  ")
            compose.runOnIdle { viewModel.includeWithoutPrice(true) }
            compose.waitUntil(5000) { !viewModel.uiState.value.isSearching }
            assertInput("  mleko moja kravica  ")
            compose.runOnIdle { viewModel.loadNextPage() }
            compose.waitUntil(5000) { viewModel.uiState.value.page == 1 }
            assertInput("  mleko moja kravica  ")

            val count = requests.size
            field.performTextReplacement("   ")
            assertInput("   ")
            compose.runOnIdle { viewModel.retry() }
            compose.runOnIdle {
                assertTrue(!viewModel.uiState.value.isSearching)
                assertEquals(count, requests.size)
            }
        } finally {
            compose.runOnIdle { viewModel.clear() }
            database.close()
        }
    }
}
