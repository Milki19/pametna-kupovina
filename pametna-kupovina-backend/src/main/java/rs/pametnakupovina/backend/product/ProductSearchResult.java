package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProductSearchResult(
        Long productId,
        String name,
        String brand,
        String barcode,
        String unit,
        String categoryName,
        String retailerCode,
        String retailerName,
        String retailerFormatName,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal discountedPrice,
        BigDecimal unitPrice,
        BigDecimal effectivePrice
) {
}