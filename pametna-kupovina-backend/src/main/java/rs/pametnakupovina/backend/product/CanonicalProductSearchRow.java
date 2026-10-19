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
        boolean hasUsablePrice,
        Long productTypeId,
        int packageCount,
        int chainCount
) {
    CanonicalProductSearchRow(
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
            boolean hasUsablePrice,
            Long productTypeId,
            int packageCount
    ) {
        this(productFamilyId, canonicalProductId, name, brand, barcode, quantityValue, baseUnit,
                categoryCode, categoryName, variantCount, nameSimilarity, exactEanMatch,
                hasUsablePrice, productTypeId, packageCount, hasUsablePrice ? 1 : 0);
    }
}
