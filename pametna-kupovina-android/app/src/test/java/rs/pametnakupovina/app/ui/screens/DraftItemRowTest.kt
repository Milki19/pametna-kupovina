package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.text.uiText

class DraftItemRowTest {

    private fun flexible(
        name: String,
        category: String? = name,
        brand: String? = null,
        quantity: Double = 1.0,
        target: Double? = null,
        unit: String? = null,
        pieceGrams: Double? = null
    ) = DraftItemEntity(
        name = name,
        quantity = quantity,
        matchingRule = "FLEXIBLE_CATEGORY",
        category = category,
        requiredBrand = brand,
        targetQuantity = target,
        requiredBaseUnit = unit,
        pieceGrams = pieceGrams
    )

    @Test
    fun `komadi voca kazu i koliko otprilike teze`() {
        val lemons = flexible("limun", target = 3.0, unit = "piece", pieceGrams = 100.0)
        assertEquals("3 kom", draftAmountLabel(lemons))
        assertEquals("≈0,3 kg", draftPiecesWeight(lemons))
        // Dva puta po dva avokada: 4 × 200 g.
        assertEquals("≈0,8 kg", draftPiecesWeight(flexible("avokado", quantity = 2.0, target = 2.0, unit = "piece", pieceGrams = 200.0)))
        assertEquals("≈0,12 kg", draftPiecesWeight(flexible("kajsije", target = 3.0, unit = "piece", pieceGrams = 40.0)))
    }

    @Test
    fun `bez mase komada ili za kilograme nema napomene`() {
        assertNull(draftPiecesWeight(flexible("jaja", target = 10.0, unit = "piece")))
        assertNull(draftPiecesWeight(flexible("limun", target = 2000.0, unit = "g", pieceGrams = 100.0)))
        assertNull(draftPiecesWeight(flexible("limun", pieceGrams = 100.0)))
    }

    @Test
    fun `kategorija ista kao naziv se ne ponavlja`() {
        assertNull(draftRuleLabel(flexible("Mleko", category = "mleko")))
    }

    @Test
    fun `ista vrsta u drugom padezu ili bez znakova se ne ponavlja`() {
        assertNull(draftRuleLabel(flexible("mlevenog mesa", category = "mlevenog meso")))
        assertNull(draftRuleLabel(flexible("mleka 2,8%", category = "mleko 2 8")))
        assertNull(draftRuleLabel(flexible("glavice luka", category = "glavice luk")))
    }

    @Test
    fun `drugacija kategorija i brend se navode`() {
        assertEquals(
            uiText(R.string.list_rule_category_brand, "jogurt", "Moja kravica"),
            draftRuleLabel(flexible("Voćni", category = "jogurt", brand = "Moja kravica"))
        )
    }

    @Test
    fun `samo kategorija ili samo brend`() {
        assertEquals(
            uiText(R.string.list_rule_category, "jogurt"),
            draftRuleLabel(flexible("Voćni", category = " jogurt "))
        )
        assertEquals(
            uiText(R.string.list_rule_brand, "Moja kravica"),
            draftRuleLabel(flexible("Mleko", brand = "Moja kravica"))
        )
    }

    @Test
    fun `porodica i tacan barkod imaju svoje oznake`() {
        val family = DraftItemEntity(name = "Pilos", quantity = 1.0, matchingRule = "PRODUCT_FAMILY")
        assertEquals(uiText(R.string.list_rule_same_product), draftRuleLabel(family))
        assertEquals(
            uiText(R.string.list_rule_exact_barcode),
            draftRuleLabel(family.copy(matchingRule = "EXACT_PRODUCT", canonicalProductId = 42))
        )
        // "Plastični tanjiri" pasted as a product search is not a chosen barcode.
        assertNull(draftRuleLabel(family.copy(name = "Plastični tanjiri", matchingRule = "EXACT_PRODUCT")))
    }

    @Test
    fun `kolicina je ukupna kada postoji cilj`() {
        assertEquals("2 l", draftAmountLabel(flexible("mleko", quantity = 2.0, target = 1000.0, unit = "ml")))
        assertEquals("1,5 kg", draftAmountLabel(flexible("vrat", target = 1500.0, unit = "g")))
        assertEquals("400 g", draftAmountLabel(flexible("sir", target = 400.0, unit = "g")))
        assertEquals("3", draftAmountLabel(flexible("hleb", quantity = 3.0)))
    }

    @Test
    fun `neprepoznata nalepljena stavka je oznacena dok se ne izabere`() {
        val pasted = flexible("xyz grickalica").copy(remoteId = 7)
        val ids = setOf(7L)

        assertTrue(pastedUnknown(pasted, ids))
        assertFalse(pastedUnknown(pasted.copy(matchingStatus = "CONFIRMED"), ids))
        assertFalse(pastedUnknown(pasted.copy(syncError = "Naziv mora imati bar jedno slovo."), ids))
        assertFalse(pastedUnknown(pasted, emptySet()))
        assertFalse(pastedUnknown(pasted.copy(remoteId = null), ids))
    }
}
