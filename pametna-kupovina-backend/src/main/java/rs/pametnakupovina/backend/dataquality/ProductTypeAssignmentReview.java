package rs.pametnakupovina.backend.dataquality;

import java.math.BigDecimal;

/** A product that has a type, automatically or after a review. */
public record ProductTypeAssignmentReview(
        long retailerProductId,
        String retailerName,
        String productName,
        String categoryName,
        String assignmentSource,
        BigDecimal confidence,
        boolean reviewed
) {
}
