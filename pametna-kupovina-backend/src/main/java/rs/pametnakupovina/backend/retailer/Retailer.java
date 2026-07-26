package rs.pametnakupovina.backend.retailer;

import java.time.OffsetDateTime;

public record Retailer(
        Long id,
        String code,
        String name,
        String datasetUrl,
        OffsetDateTime createdAt
) {
}