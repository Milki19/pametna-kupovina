package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record AddShoppingListItemRequest(
        String name,
        String barcode,
        BigDecimal quantity
) {
}