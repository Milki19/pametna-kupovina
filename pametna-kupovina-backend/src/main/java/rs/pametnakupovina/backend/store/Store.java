package rs.pametnakupovina.backend.store;

import java.time.OffsetDateTime;

public record Store(
        Long id,
        Long retailerId,
        String retailerCode,
        String retailerName,
        Long storeFormatId,
        String storeFormatCode,
        String storeFormatName,
        String externalCode,
        String name,
        String address,
        String city,
        Double latitude,
        Double longitude,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

