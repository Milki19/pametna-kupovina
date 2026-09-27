package rs.pametnakupovina.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto

class SimilarItemTest {

    @Test
    fun `stavka postaje ista vrsta i kolicina, bilo koji brend`() {
        val item = similarItem("PERUTNINA PTUJ pileća prsa 250 g", 2.0, kind = "pileća prsa")
        assertEquals("pileća prsa", item.category)
        assertEquals(ShoppingItemRuleDto.FLEXIBLE_CATEGORY, item.matchingRule)
        assertEquals(250.0, item.targetQuantity!!, 0.0)
        assertEquals("g", item.requiredBaseUnit)
        assertEquals(2.0, item.quantity, 0.0)
    }

    /** Pravi nazivi iz kataloga (27.09.2026). */
    @Test
    fun `predlog vrste skida brend, oznake i kolicinu`() {
        assertEquals("pileća prsa", similarKind("PERUTNINA PTUJ pileća prsa 250 g", "Perutnina Ptuj"))
        assertEquals("pileća prsa", similarKind("PERUTNINA PTUJ pileća prsa f52 350 g", null))
        assertEquals("dimljena pileća", similarKind("PERUTNINA pp dimljena pileća prsa f140", "Perutnina"))
        assertEquals("mleko uht", similarKind("MLEKARA LESKOVAC mleko uht mizo 1,6% 1 l", "MLEKARA LESKOVAC"))
        assertEquals("mleko", similarKind("MOJA KRAVICA IMLEK mleko 2% 1 l", "Moja Kravica - Imlek"))
        assertEquals("ulje suncokretovo", similarKind("TO ulje suncokretovo prm jestivo rafinisana dijaman 1 l", "TO!"))
        assertEquals("čokolada", similarKind("NELT MARS m & m\"s čokolada 45 g", "NELT - MARS"))
        assertEquals("pileća prsa", similarKind("Pileća prsa,delikates, narezak MK12 100g", null))
        // Sve je brend: ostaje prva prava reč.
        assertEquals("kafa", similarKind("KAFA C  100 g", "Kafa C"))
    }

    @Test
    fun `bez kolicine u nazivu nema ni cilja`() {
        assertNull(similarItem("PERUTNINA PTUJ pileća prsa u omotu dimljena", 1.0).targetQuantity)
    }

    @Test
    fun `prazna vrsta pada na predlog`() {
        assertEquals("pileća prsa", similarItem("PERUTNINA PTUJ pileća prsa 250 g", 1.0, kind = " ").category)
    }
}
