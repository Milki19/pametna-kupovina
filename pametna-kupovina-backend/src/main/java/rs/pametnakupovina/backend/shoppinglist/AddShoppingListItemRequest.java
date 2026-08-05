package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record AddShoppingListItemRequest(
        String name,
        String rawInput,
        String barcode,
        BigDecimal quantity,
        ShoppingItemRule matchingRule,
        FlexibleItemConstraints flexibleConstraints
) {

    public AddShoppingListItemRequest(
            String name,
            String rawInput,
            String barcode,
            BigDecimal quantity,
            ShoppingItemRule matchingRule
    ) {
        this(
                name,
                rawInput,
                barcode,
                quantity,
                matchingRule,
                null
        );
    }

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
                null,
                null
        );
    }
}
