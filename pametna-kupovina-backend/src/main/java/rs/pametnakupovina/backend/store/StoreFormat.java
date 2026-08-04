package rs.pametnakupovina.backend.store;

import java.time.OffsetDateTime;

public record StoreFormat(
        Long id,
        Long retailerId,
        String retailerCode,
        String code,
        String name,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

