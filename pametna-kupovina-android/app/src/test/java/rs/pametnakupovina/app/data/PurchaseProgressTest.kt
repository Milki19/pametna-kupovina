package rs.pametnakupovina.app.data

import org.junit.Assert.*
import org.junit.Test
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.purchase.*
import rs.pametnakupovina.app.text.UserFacingException
import rs.pametnakupovina.app.text.uiText

class PurchaseProgressTest {
    @Test fun partialAndCompletedQuantitiesRemainDistinct() {
        val partial = validatePurchaseProgress(PurchaseItemProgress(boughtPackages = 2.0), 5.0)
        assertEquals(PurchaseStatus.TO_BUY, partial.status)
        val full = validatePurchaseProgress(partial.copy(boughtPackages = 5.0), 5.0)
        assertEquals(PurchaseStatus.PURCHASED, full.status)
        assertEquals(5.0, validatePurchaseProgress(PurchaseItemProgress(status = PurchaseStatus.PURCHASED), 5.0).boughtPackages, 0.0)
        assertEquals(PurchaseStatus.SKIPPED, validatePurchaseProgress(PurchaseItemProgress(status = PurchaseStatus.SKIPPED), 5.0).status)
    }
    @Test fun validatesUserMoneyAndKeepsDecimalPrecision() {
        assertEquals("123.45", validatePurchaseProgress(PurchaseItemProgress(actualLineTotal = "123,45"), 1.0).actualLineTotal)
        for (value in listOf("-1", "NaN", "1.234", "abc")) {
            val error = assertThrows(UserFacingException::class.java) {
                validatePurchaseProgress(PurchaseItemProgress(actualLineTotal = value), 1.0)
            }
            assertEquals(uiText(R.string.purchase_error_price), error.text)
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 2.0)) {
            val error = assertThrows(UserFacingException::class.java) {
                validatePurchaseProgress(PurchaseItemProgress(boughtPackages = value), 1.0)
            }
            assertEquals(uiText(R.string.purchase_error_bought_range), error.text)
        }
    }
    @Test fun refusalsNameTheirReason() {
        val planned = assertThrows(UserFacingException::class.java) {
            validatePurchaseProgress(PurchaseItemProgress(), 0.0)
        }
        assertEquals(uiText(R.string.purchase_error_invalid_planned), planned.text)
        val note = assertThrows(UserFacingException::class.java) {
            validatePurchaseProgress(PurchaseItemProgress(note = "a".repeat(1001)), 1.0)
        }
        assertEquals(uiText(R.string.purchase_error_note_length, 1000), note.text)
    }

    @Test fun onlyFullyBoughtItemsStillOnTheListAreTakenOff() {
        val item = rs.pametnakupovina.app.data.network.RecommendationItemDto(
            itemId = 1, requestedName = "jogurt", requestedQuantity = 1.0,
            matchingRule = rs.pametnakupovina.app.data.network.ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
            matchingStatus = rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto.CONFIRMED,
            resultStatus = rs.pametnakupovina.app.data.network.RecommendationItemStatusDto.AVAILABLE,
            storeId = 1, explanation = "Test"
        )
        val items = (1L..5L).map { item.copy(itemId = it, requestedName = "stavka $it") } +
            item.copy(itemId = 6, requestedName = "bez radnje", storeId = null)
        val session = PurchaseSession(
            id = "s", createdAt = 0, archivedAt = null,
            snapshot = PurchaseSnapshot(
                listId = 10, listName = "Spisak", calculationDate = "2026-11-01",
                scenario = purchaseScenario(items)
            ),
            progress = mapOf(
                1L to PurchaseItemProgress(status = PurchaseStatus.PURCHASED, boughtPackages = 1.0),
                2L to PurchaseItemProgress(status = PurchaseStatus.SKIPPED),
                3L to PurchaseItemProgress(status = PurchaseStatus.NOT_FOUND),
                4L to PurchaseItemProgress(boughtPackages = 0.5),
                // Kupljeno, ali ga je neko u međuvremenu već obrisao sa spiska.
                5L to PurchaseItemProgress(status = PurchaseStatus.PURCHASED, boughtPackages = 1.0),
                6L to PurchaseItemProgress(status = PurchaseStatus.PURCHASED, boughtPackages = 1.0)
            )
        )
        val list = listOf(
            ListEntry(1, "stavka 1"), ListEntry(2, "stavka 2"), ListEntry(3, "stavka 3"),
            ListEntry(4, "stavka 4"), ListEntry(6, "bez radnje"), ListEntry(null, "dodato posle plana")
        )
        val result = session.boughtOnList(list)
        assertEquals(setOf(1L, 6L), result.removable)
        assertEquals(listOf("stavka 2", "stavka 3", "stavka 4", "dodato posle plana"), result.remaining)
    }

    @Test fun boughtAndSkippedLeaveTheActivePart() {
        assertEquals(PurchaseGroup.ACTIVE, PurchaseItemProgress().group())
        assertEquals(PurchaseGroup.ACTIVE, PurchaseItemProgress(status = PurchaseStatus.NOT_FOUND).group())
        assertEquals(PurchaseGroup.BOUGHT, PurchaseItemProgress(status = PurchaseStatus.PURCHASED).group())
        assertEquals(PurchaseGroup.SKIPPED, PurchaseItemProgress(status = PurchaseStatus.SKIPPED).group())
    }

    @Test fun progressSavedByAnOlderAppStillReads() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<PurchaseItemProgress>("""{"status":"SKIPPED","boughtPackages":0.0,"note":""}""")
        assertFalse(old.removedFromList)
    }
}

private fun purchaseScenario(items: List<rs.pametnakupovina.app.data.network.RecommendationItemDto>) =
    rs.pametnakupovina.app.data.network.OptimizationScenarioDto(
        type = rs.pametnakupovina.app.data.network.RecommendationScenarioTypeDto.RECOMMENDED_BALANCE,
        available = true, complete = false, explanation = "Test",
        coveredItems = items.size, unmatchedItems = 0, unavailableItems = 0, stopCount = 1,
        basketCost = 0.0, routeDistanceKm = 0.0, routeDurationSeconds = 0,
        travelCost = 0.0, timeCost = 0.0, stopCost = 0.0,
        routeProvider = "test", distanceMethod = "test", approximateRoute = true,
        items = items, disclaimer = "Test"
    )
