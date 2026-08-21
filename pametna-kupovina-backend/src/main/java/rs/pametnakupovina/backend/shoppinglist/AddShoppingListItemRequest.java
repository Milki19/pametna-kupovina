package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record AddShoppingListItemRequest(
        String name,
        String rawInput,
        String barcode,
        Long canonicalProductId,
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
                null,
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
                null,
                quantity,
                null,
                null
        );
    }

    public AddShoppingListItemRequest(
            String name,
            String rawInput,
            String barcode,
            BigDecimal quantity,
            ShoppingItemRule matchingRule,
            FlexibleItemConstraints flexibleConstraints
    ) {
        this(
                name,
                rawInput,
                barcode,
                null,
                quantity,
                matchingRule,
                flexibleConstraints
        );
    }
}
