package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;

record CanonicalProductSearchRow(
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        BigDecimal nameSimilarity,
        boolean exactEanMatch
) {
}
