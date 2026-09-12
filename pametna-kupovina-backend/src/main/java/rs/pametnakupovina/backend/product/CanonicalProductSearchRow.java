package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;

record CanonicalProductSearchRow(
        Long productFamilyId,
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        String categoryCode,
        String categoryName,
        int variantCount,
        BigDecimal nameSimilarity,
        boolean exactEanMatch,
        boolean hasUsablePrice
) {
}
