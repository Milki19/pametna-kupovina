package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.util.List;

public record CanonicalProductSearchItem(
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
        List<ProductRetailerAvailability> availability,
        BigDecimal score,
        boolean hasUsablePrice,
        List<String> knownRetailers,
        int packageCount
) {

    public CanonicalProductSearchItem(
            Long canonicalProductId,
            String name,
            String brand,
            String barcode,
            BigDecimal quantityValue,
            String baseUnit,
            BigDecimal score
    ) {
        this(
                null,
                canonicalProductId,
                name,
                brand,
                barcode,
                quantityValue,
                baseUnit,
                null,
                null,
                1,
                List.of(),
                score,
                false,
                List.of(),
                1
        );
    }
}
