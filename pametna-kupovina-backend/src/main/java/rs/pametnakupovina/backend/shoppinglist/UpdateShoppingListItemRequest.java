package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record UpdateShoppingListItemRequest(
        String name,
        String barcode,
        BigDecimal quantity
) {
}