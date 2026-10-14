package rs.pametnakupovina.backend.retailer;

import java.time.OffsetDateTime;

/**
 * @param storeLocationsKnown whether the chain says where its shops are; a
 *                            chain that does not never enters a plan
 */
public record Retailer(
        Long id,
        String code,
        String name,
        String datasetUrl,
        OffsetDateTime createdAt,
        boolean storeLocationsKnown
) {
}
