package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CanonicalProductOffer(
        Long retailerProductId,
        String retailerCode,
        String retailerName,
        Long storeId,
        String storeName,
        String storeFormatCode,
        String storeFormatName,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal discountedPrice,
        BigDecimal effectivePrice,
        BigDecimal unitPrice,
        String priceScope
) {
}
