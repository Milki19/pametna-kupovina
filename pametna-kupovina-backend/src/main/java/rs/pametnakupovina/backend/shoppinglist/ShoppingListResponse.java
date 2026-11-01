package rs.pametnakupovina.backend.shoppinglist;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code id} is the list's number, which Android up to 2.0.6 keeps using;
 * {@code publicId} is the random id newer clients call it by.
 */
public record ShoppingListResponse(
        Long id,
        UUID publicId,
        String name,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ShoppingListItemResponse> items
) {

    public ShoppingListResponse(
            Long id,
            String name,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<ShoppingListItemResponse> items
    ) {
        this(id, null, name, createdAt, updatedAt, items);
    }
}
