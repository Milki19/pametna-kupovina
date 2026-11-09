package rs.pametnakupovina.backend.shoppinglist;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code id} is the list's number, which only Android up to 2.0.8 still
 * gets ({@link InternalListId}); {@code publicId} is the random id everyone
 * else calls it by.
 */
public record ShoppingListResponse(
        @InternalListId Long id,
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
