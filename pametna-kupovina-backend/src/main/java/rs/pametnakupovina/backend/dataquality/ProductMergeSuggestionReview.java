package rs.pametnakupovina.backend.dataquality;

import java.math.BigDecimal;

/**
 * Two products of one brand and size that may be one product under two names.
 * score is shared letters; meaningScore is how close the names are in meaning
 * (V135), null without the model.
 */
public record ProductMergeSuggestionReview(
        long id,
        BigDecimal score,
        BigDecimal meaningScore,
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
            BigDecimal lowestPrice,
            String currency
    ) {
    }
}
