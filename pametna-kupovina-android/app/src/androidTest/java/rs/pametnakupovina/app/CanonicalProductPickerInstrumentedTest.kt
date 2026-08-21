package rs.pametnakupovina.app

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.components.canonicalProductPicker

class CanonicalProductPickerInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun multipleResultsAllowNoBarcodeSelectionAndPagination() {
        var selectedId: Long? = null
        var loadMoreRequested = false
        val products = listOf(
            CanonicalProductSearchItemDto(
                canonicalProductId = 1,
                name = "Donat sa barkodom",
                barcode = "3838600041300",
                score = 1.0
            ),
            CanonicalProductSearchItemDto(
                canonicalProductId = 2,
                name = "Proizvod bez barkoda",
                score = 0.8
            )
        )

        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    canonicalProductPicker(
                        query = "proizvod",
                        selectedProduct = null,
                        searchState = ProductSearchUiState(
                            query = "proizvod",
                            results = products,
                            totalElements = 12,
                            hasNext = true
                        ),
                        onQueryChange = {},
                        onSelectProduct = { selectedId = it.canonicalProductId },
                        onClearSelection = {},
                        onRetry = {},
                        onLoadMore = { loadMoreRequested = true }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("product-result-1").assertIsDisplayed()
        composeRule.onNodeWithTag("product-result-2").assertIsDisplayed()
        composeRule.onNodeWithTag("product-choose-2")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("product-load-more")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(2L, selectedId)
            assertTrue(loadMoreRequested)
        }
    }
}
