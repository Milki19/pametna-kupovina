package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record AddShoppingListItemRequest(
        String name,
        String rawInput,
        String barcode,
        BigDecimal quantity,
        ShoppingItemRule matchingRule
) {

    public AddShoppingListItemRequest(
            String name,
            String barcode,
            BigDecimal quantity
    ) {
        this(
                name,
                null,
                barcode,
                quantity,
                null
        );
    }
}
