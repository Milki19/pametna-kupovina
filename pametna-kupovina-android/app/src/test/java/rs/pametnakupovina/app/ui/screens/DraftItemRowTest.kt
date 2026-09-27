package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        unit: String? = null
    ) = DraftItemEntity(
        name = name,
        quantity = quantity,
        matchingRule = "FLEXIBLE_CATEGORY",
        category = category,
        requiredBrand = brand,
        targetQuantity = target,
        requiredBaseUnit = unit
    )

    @Test
    fun `kategorija ista kao naziv se ne ponavlja`() {
        assertNull(draftRuleLabel(flexible("Mleko", category = "mleko")))
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
        assertEquals("3 kom", draftAmountLabel(flexible("hleb", quantity = 3.0)))
    }
}
