package rs.pametnakupovina.backend.shoppinglist;

import java.time.OffsetDateTime;

public record ShoppingListSummary(
        Long id,
        String name,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int itemCount
) {
}