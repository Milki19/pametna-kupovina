package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record FlexibleItemConstraints(
        String category,
        String requiredBrand,
        BigDecimal minPackageQuantity,
        BigDecimal maxPackageQuantity,
        String requiredBaseUnit,
        BigDecimal targetQuantity
) {
    public FlexibleItemConstraints(String category, String requiredBrand,
            BigDecimal minPackageQuantity, BigDecimal maxPackageQuantity, String requiredBaseUnit) {
        this(category, requiredBrand, minPackageQuantity, maxPackageQuantity, requiredBaseUnit, null);
    }
}
