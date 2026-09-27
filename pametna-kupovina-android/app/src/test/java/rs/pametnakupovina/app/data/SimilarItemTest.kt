package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto

class SimilarItemTest {

    private fun kind(name: String, brand: String? = null) = similarItem(name, brand, 1.0).category

    @Test
    fun `brend i oznake otpadaju, ostaju vrsta i kolicina`() {
        val item = similarItem("PERUTNINA PTUJ pileća prsa 250 g", "Perutnina Ptuj", 2.0)
        assertEquals("pileća prsa", item.category)
        assertEquals(ShoppingItemRuleDto.FLEXIBLE_CATEGORY, item.matchingRule)
        assertEquals(250.0, item.targetQuantity!!, 0.0)
        assertEquals("g", item.requiredBaseUnit)
        assertEquals(2.0, item.quantity, 0.0)
    }

    @Test
    fun `brend se skida i kad ga ne znamo`() {
        assertEquals("pileća prsa", kind("PERUTNINA PTUJ pileća prsa f52 350 g"))
        assertEquals("mleko uht", kind("KRAVICA mleko uht moja 2,8%mm tb 1 l", "Kravica"))
        assertEquals("mleko", kind("MOJA KRAVICA IMLEK mleko 2% 1 l", "Moja Kravica - Imlek"))
        assertEquals("pileća prsa", kind("Pileća prsa,delikates, narezak MK12 100g"))
        assertEquals("parizer", kind("Parizer MK10 500g"))
    }

    @Test
    fun `bez kolicine u nazivu nema ni cilja`() {
        val item = similarItem("PERUTNINA PTUJ pileća prsa u omotu dimljena", "Perutnina Ptuj", 1.0)
        assertEquals("pileća prsa", item.category)
        assertNull(item.targetQuantity)
    }
}
