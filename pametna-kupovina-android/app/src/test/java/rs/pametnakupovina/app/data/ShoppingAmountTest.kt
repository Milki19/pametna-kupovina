package rs.pametnakupovina.app.data

import org.junit.Assert.*
import org.junit.Test

class ShoppingAmountTest {
    @Test fun parsesMassVolumeAndCountWithoutChangingCategory() {
        assertEquals(RequestedShoppingAmount("jogurt", ShoppingAmount(1000.0, "g")), parseShoppingAmount("jogurt 1 kg"))
        assertEquals(RequestedShoppingAmount("mleko", ShoppingAmount(1500.0, "ml")), parseShoppingAmount("1,5 l mleko"))
        assertEquals(RequestedShoppingAmount("jaja", ShoppingAmount(10.0, "piece")), parseShoppingAmount("jaja 10 kom"))
        assertNull(parseShoppingAmount("jogurt"))
        assertNull(parseShoppingAmount("jogurt 0 kg"))
    }
    @Test fun visibleDefaultsAreOnlyForExactGenericNames() {
        assertEquals(ShoppingAmount(1000.0, "g"), suggestedAmount("Jogurt"))
        assertNull(suggestedAmount("voćni jogurt"))
        assertNull(suggestedAmount("čokoladno mleko"))
        val input = PastedListParser.parse("2x jogurt").single().toFlexibleDraftInput().validated()
        assertEquals(1000.0, input.targetQuantity!!, 0.0)
        assertEquals(2.0, input.quantity, 0.0)
        assertEquals("2 kg", amountLabel(input.targetQuantity * input.quantity, input.requiredBaseUnit))
    }
    @Test fun explicitQuantityOverridesSuggestion() {
        val input = PastedListParser.parse("jogurt 400g").single().toFlexibleDraftInput()
        assertEquals("jogurt", input.category)
        assertEquals(400.0, input.targetQuantity!!, 0.0)
    }
}
