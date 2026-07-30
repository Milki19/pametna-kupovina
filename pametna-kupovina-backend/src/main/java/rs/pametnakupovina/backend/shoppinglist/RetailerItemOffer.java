package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RetailerItemOffer(
        Long itemId,
        String requestedName,
        String barcode,
        BigDecimal quantity,
        boolean available,
        Long productId,
        String productName,
        LocalDate priceDate,
        BigDecimal effectivePrice,
        BigDecimal lineTotal
) {
}