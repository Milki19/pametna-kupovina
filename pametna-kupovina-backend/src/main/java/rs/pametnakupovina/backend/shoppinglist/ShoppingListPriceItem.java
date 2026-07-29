package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ShoppingListPriceItem(
        Long itemId,
        String requestedName,
        String barcode,
        BigDecimal quantity,
        boolean matched,
        Long productId,
        String productName,
        String retailerCode,
        String retailerName,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal discountedPrice,
        BigDecimal effectivePrice,
        BigDecimal lineTotal
) {
}