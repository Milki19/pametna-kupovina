package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;

public record ProductSearchCandidate(
        Long productFamilyId,
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        BigDecimal nameSimilarity,
        int packageCount
) {
}
