package rs.pametnakupovina.backend.dataquality;

import java.time.OffsetDateTime;

/** A shopper's report of a wrong price or two products that are not the same. */
public record ProductReportReview(
        long id,
        long canonicalProductId,
        String productName,
        String retailerName,
        String listingName,
        String reason,
        String note,
        String status,
        OffsetDateTime createdAt
) {
}
