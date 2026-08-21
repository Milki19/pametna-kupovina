package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CanonicalProductPricePoint(
        Long retailerProductId,
        String retailerCode,
        String retailerName,
        Long storeId,
        String storeName,
        String storeFormatName,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal discountedPrice,
        BigDecimal effectivePrice,
        String priceScope
) {
}
