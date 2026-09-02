package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.util.List;

public record ProductFamilyDetailsResponse(
        Long productFamilyId,
        String name,
        String brand,
        String categoryCode,
        String categoryName,
        BigDecimal quantityValue,
        String baseUnit,
        boolean reviewRequired,
        List<ProductFamilyVariant> variants,
        List<ProductRetailerAvailability> availability
) {
}
