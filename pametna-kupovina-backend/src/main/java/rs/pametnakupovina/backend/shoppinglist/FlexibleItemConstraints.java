package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record FlexibleItemConstraints(
        String category,
        String requiredBrand,
        BigDecimal minPackageQuantity,
        BigDecimal maxPackageQuantity,
        String requiredBaseUnit
) {
}
