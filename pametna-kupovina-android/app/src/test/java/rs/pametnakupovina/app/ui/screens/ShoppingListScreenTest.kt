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
}
