package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;

public record CanonicalProductSearchItem(
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        BigDecimal score
) {
}
