package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record UpdateShoppingListItemRequest(
        String name,
        String rawInput,
        String barcode,
        BigDecimal quantity,
        ShoppingItemRule matchingRule,
        FlexibleItemConstraints flexibleConstraints
) {

    public UpdateShoppingListItemRequest(
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

    public UpdateShoppingListItemRequest(
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
