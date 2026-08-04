package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;

public record FuzzyProductCandidate(
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        BigDecimal nameSimilarity
) {
}
