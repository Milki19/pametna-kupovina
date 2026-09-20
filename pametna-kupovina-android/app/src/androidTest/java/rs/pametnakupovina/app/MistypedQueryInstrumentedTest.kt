package rs.pametnakupovina.app

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.components.canonicalProductPicker

/**
 * Kad server ispravi pogrešno ukucanu reč, kupac mora da vidi da nije
 * dobio ono što je ukucao — inače izgleda kao da pretraga izmišlja.
 */
class MistypedQueryInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val milk = listOf(
        CanonicalProductSearchItemDto(
            canonicalProductId = 1,
            name = "Alpsko mleko 3,5% 1 l",
            hasUsablePrice = true,
            score = 1.0
        )
    )

    @Test
    fun aCorrectedQueryIsShownAboveTheResults() {
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    canonicalProductPicker(
                        query = "mlkeo",
                        selectedProduct = null,
                        searchState = ProductSearchUiState(
                            query = "mlkeo",
                            correctedQuery = "mleko",
                            results = milk,
                            totalElements = 1
                        ),
                        onQueryChange = {},
                        onSelectProduct = {},
                        onClearSelection = {},
                        onRetry = {},
                        onLoadMore = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("search-corrected")
            .assertIsDisplayed()
            .assertTextContains(
                "Nema rezultata za „mlkeo“. " +
                    "Prikazani su rezultati za „mleko“."
            )
        composeRule.onNodeWithTag("product-result-1").assertIsDisplayed()
    }

    @Test
    fun aQuerySearchedAsTypedSaysNothingExtra() {
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    canonicalProductPicker(
                        query = "mleko",
                        selectedProduct = null,
                        searchState = ProductSearchUiState(
                            query = "mleko",
                            results = milk,
                            totalElements = 1
                        ),
                        onQueryChange = {},
                        onSelectProduct = {},
                        onClearSelection = {},
                        onRetry = {},
                        onLoadMore = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("search-corrected").assertDoesNotExist()
    }
}
