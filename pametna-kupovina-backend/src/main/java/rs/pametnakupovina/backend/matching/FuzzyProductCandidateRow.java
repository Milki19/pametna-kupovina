package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;

record FuzzyProductCandidateRow(
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        BigDecimal nameSimilarity
) {
}
