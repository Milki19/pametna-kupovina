package rs.pametnakupovina.backend.priceimport;

import java.math.BigDecimal;
import java.time.LocalDate;

record PriceCsvRow(
        String sourceProductKey,
        String categoryCode,
        String categoryName,
        String productName,
        String brand,
        String barcode,
        String unitOfMeasure,
        String retailerFormatName,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal unitPrice,
        BigDecimal discountedPrice,
        LocalDate discountStartDate,
        LocalDate discountEndDate,
        BigDecimal vatRate
) {
}