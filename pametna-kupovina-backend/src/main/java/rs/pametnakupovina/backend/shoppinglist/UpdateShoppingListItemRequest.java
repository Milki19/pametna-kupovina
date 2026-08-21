package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record UpdateShoppingListItemRequest(
        String name,
        String rawInput,
        String barcode,
        Long canonicalProductId,
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
                null,
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
                null,
                quantity,
                null,
                null
        );
    }

    public UpdateShoppingListItemRequest(
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
