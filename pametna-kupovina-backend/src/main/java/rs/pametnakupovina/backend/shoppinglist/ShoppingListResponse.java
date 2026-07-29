package rs.pametnakupovina.backend.shoppinglist;

import java.time.OffsetDateTime;
import java.util.List;

public record ShoppingListResponse(
        Long id,
        String name,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ShoppingListItemResponse> items
) {
}