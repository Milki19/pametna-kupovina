package rs.pametnakupovina.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.local.DraftItemEntity
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto

class ShoppingListScreenTest {

    private val existingItem = DraftItemEntity(
        localId = 1,
        remoteId = 10,
        name = "Stari proizvod",
        barcode = "8601234567899",
        canonicalProductId = 15,
        quantity = 1.0,
        matchingRule = ShoppingItemRuleDto.EXACT_PRODUCT.name
    )

    private val selectedProduct = CanonicalProductSearchItemDto(
        canonicalProductId = 20,
        name = "VODA DONAT 1/1-PALANACKI-300",
        barcode = "3838600041300",
        score = 1.0
    )

    @Test
    fun `izabrani canonical proizvod salje njegov barkod`() {
        assertEquals(
            "3838600041300",
            resolveDraftBarcode(
                item = existingItem,
                enteredName = selectedProduct.name,
                rule = ShoppingItemRuleDto.EXACT_PRODUCT,
                selectedProduct = selectedProduct
            )
        )
    }

    @Test
    fun `rucna promena naziva uklanja stari barkod`() {
        assertNull(
            resolveDraftBarcode(
                item = existingItem,
                enteredName = "Drugi proizvod",
                rule = ShoppingItemRuleDto.EXACT_PRODUCT,
                selectedProduct = null
            )
        )
    }

    @Test
    fun `izmena kolicine uz isti naziv zadrzava postojeci barkod`() {
        assertEquals(
            "8601234567899",
            resolveDraftBarcode(
                item = existingItem,
                enteredName = "  Stari proizvod  ",
                rule = ShoppingItemRuleDto.EXACT_PRODUCT,
                selectedProduct = null
            )
        )
    }

    @Test
    fun `fleksibilna stavka nikada ne salje barkod`() {
        assertNull(
            resolveDraftBarcode(
                item = existingItem,
                enteredName = selectedProduct.name,
                rule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
                selectedProduct = selectedProduct
            )
        )
    }

    @Test
    fun `izabrani proizvod salje canonical id i bez barkoda`() {
        val withoutBarcode = selectedProduct.copy(barcode = null)

        assertEquals(
            20L,
            resolveDraftCanonicalProductId(
                item = null,
                enteredName = withoutBarcode.name,
                rule = ShoppingItemRuleDto.EXACT_PRODUCT,
                selectedProduct = withoutBarcode
            )
        )
    }

    @Test
    fun `rucna promena naziva uklanja stari canonical id`() {
        assertNull(
            resolveDraftCanonicalProductId(
                item = existingItem,
                enteredName = "Drugi proizvod",
                rule = ShoppingItemRuleDto.EXACT_PRODUCT,
                selectedProduct = null
            )
        )
    }

    @Test
    fun `izmena kolicine zadrzava postojeci canonical id`() {
        assertEquals(
            15L,
            resolveDraftCanonicalProductId(
                item = existingItem,
                enteredName = " Stari proizvod ",
                rule = ShoppingItemRuleDto.EXACT_PRODUCT,
                selectedProduct = null
            )
        )
    }

    @Test
    fun `izabrana porodica salje product family id`() {
        val family = selectedProduct.copy(productFamilyId = 77)

        assertEquals(
            77L,
            resolveDraftProductFamilyId(
                item = null,
                enteredName = family.name,
                rule = ShoppingItemRuleDto.PRODUCT_FAMILY,
                selectedProduct = family
            )
        )
        assertNull(
            resolveDraftCanonicalProductId(
                item = null,
                enteredName = family.name,
                rule = ShoppingItemRuleDto.PRODUCT_FAMILY,
                selectedProduct = family
            )
        )
        assertNull(
            resolveDraftBarcode(
                item = null,
                enteredName = family.name,
                rule = ShoppingItemRuleDto.PRODUCT_FAMILY,
                selectedProduct = family
            )
        )
    }

    private fun flexible(id: Long, name: String, quantity: Double = 1.0, target: Double? = null, unit: String? = null, brand: String? = null) =
        DraftItemEntity(
            localId = id, remoteId = id * 10, name = name, quantity = quantity,
            matchingRule = ShoppingItemRuleDto.FLEXIBLE_CATEGORY.name, category = name,
            requiredBrand = brand, targetQuantity = target, requiredBaseUnit = unit,
            // Server je za svaku izabrao drugi proizvod; to ne smeta spajanju.
            canonicalProductId = 100 + id, syncState = "SYNCED"
        )

    @Test
    fun `duplikati bez kolicine se spajaju u prvu stavku sa zbirom komada`() {
        val merged = mergedItem(listOf(flexible(1, "mleko", 2.0), flexible(2, "Mleko ", 1.0)))!!
        assertEquals(1L, merged.localId)
        assertEquals("mleko", merged.name)
        assertEquals(3.0, merged.quantity, 0.0)
        assertNull(merged.targetQuantity)
    }

    @Test
    fun `duplikati sa kolicinom u istoj jedinici sabiraju ukupnu kolicinu`() {
        val merged = mergedItem(listOf(flexible(1, "mleko", 2.0, 1000.0, "ml"), flexible(2, "mleko", 1.0, 500.0, "ml")))!!
        assertEquals(1.0, merged.quantity, 0.0)
        assertEquals(2500.0, merged.targetQuantity!!, 0.0)
        assertEquals("ml", merged.requiredBaseUnit)
    }

    @Test
    fun `duplikati koji se razlikuju po jedinici, brendu ili proizvodu se ne spajaju`() {
        assertNull(mergedItem(listOf(flexible(1, "mleko", target = 1000.0, unit = "ml"), flexible(2, "mleko"))))
        assertNull(mergedItem(listOf(flexible(1, "sir", target = 400.0, unit = "g"), flexible(2, "sir", target = 2.0, unit = "piece"))))
        assertNull(mergedItem(listOf(flexible(1, "mleko", brand = "Imlek"), flexible(2, "mleko", brand = "Moja kravica"))))
        assertNull(mergedItem(listOf(existingItem, existingItem.copy(localId = 2, canonicalProductId = 16))))
        assertNull(mergedItem(listOf(flexible(1, "mleko"), flexible(2, "mleko").copy(syncState = "PENDING_PASTE"))))
        assertNull(mergedItem(listOf(flexible(1, "pivo", 600.0), flexible(2, "pivo", 500.0))))
    }

    @Test
    fun `isti tacan proizvod dva puta sabira pakovanja`() {
        val merged = mergedItem(listOf(existingItem, existingItem.copy(localId = 2, quantity = 2.0)))!!
        assertEquals(3.0, merged.quantity, 0.0)
        assertEquals(15L, merged.canonicalProductId)
    }
}
