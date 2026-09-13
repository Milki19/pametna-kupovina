package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.local.DraftItemEntity

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
            "Kategorija jogurt, brend Moja kravica",
            draftRuleLabel(flexible("Voćni", category = "jogurt", brand = "Moja kravica"))
        )
    }

    @Test
    fun `porodica i tacan barkod imaju svoje oznake`() {
        val family = DraftItemEntity(name = "Pilos", quantity = 1.0, matchingRule = "PRODUCT_FAMILY")
        assertEquals("Isti proizvod, sve varijante", draftRuleLabel(family))
        assertEquals("Tačan barkod", draftRuleLabel(family.copy(matchingRule = "EXACT_PRODUCT")))
    }

    @Test
    fun `kolicina je ukupna kada postoji cilj`() {
        assertEquals("2 l", draftAmountLabel(flexible("mleko", quantity = 2.0, target = 1000.0, unit = "ml")))
        assertEquals("1,5 kg", draftAmountLabel(flexible("vrat", target = 1500.0, unit = "g")))
        assertEquals("400 g", draftAmountLabel(flexible("sir", target = 400.0, unit = "g")))
        assertEquals("3 kom", draftAmountLabel(flexible("hleb", quantity = 3.0)))
    }
}
