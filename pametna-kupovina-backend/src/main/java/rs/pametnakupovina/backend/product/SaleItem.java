package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A product on sale today, with the chain where it comes off the most.
 *
 * @param otherChainCount   how many more chains have it on sale too
 * @param saleEndDate       the sale's last day, when the chain says
 * @param nearestStoreMeters the nearest shop of that chain; null when the
 *                          shopper's location was not sent
 */
public record SaleItem(
        Long productFamilyId,
        Long canonicalProductId,
        String name,
        String brand,
        BigDecimal quantityValue,
        String baseUnit,
        int packageCount,
        String categoryCode,
        String categoryName,
        String retailerCode,
        String retailerName,
        BigDecimal salePrice,
        BigDecimal regularPrice,
        int discountPercent,
        LocalDate saleEndDate,
        int otherChainCount,
        Double nearestStoreMeters
) {
}
