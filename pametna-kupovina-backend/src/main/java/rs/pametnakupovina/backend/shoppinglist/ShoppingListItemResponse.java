package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShoppingListItemResponse(
        Long id,
        String name,
        String barcode,
        BigDecimal quantity,
        OffsetDateTime createdAt
) {
}