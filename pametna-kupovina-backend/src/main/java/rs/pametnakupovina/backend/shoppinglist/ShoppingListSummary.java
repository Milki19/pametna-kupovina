package rs.pametnakupovina.backend.shoppinglist;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Like {@link ShoppingListResponse}: the number for old apps, the random id for the rest. */
public record ShoppingListSummary(
        @InternalListId Long id,
        UUID publicId,
        String name,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int itemCount
) {
}
