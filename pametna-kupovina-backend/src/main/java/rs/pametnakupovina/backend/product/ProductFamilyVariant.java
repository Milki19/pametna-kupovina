package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;

public record ProductFamilyVariant(
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        BigDecimal membershipConfidence,
        boolean reviewed
) {
}
