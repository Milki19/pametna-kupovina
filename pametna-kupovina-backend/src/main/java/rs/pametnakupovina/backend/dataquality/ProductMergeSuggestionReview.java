package rs.pametnakupovina.backend.dataquality;

import java.math.BigDecimal;

/** Two products of one brand and size that may be one product under two names. */
public record ProductMergeSuggestionReview(
        long id,
        BigDecimal score,
        String brand,
        BigDecimal quantityValue,
        String baseUnit,
        Side left,
        Side right
) {

    public record Side(
            long productFamilyId,
            String name,
            String retailers,
            BigDecimal lowestPrice
    ) {
    }
}
