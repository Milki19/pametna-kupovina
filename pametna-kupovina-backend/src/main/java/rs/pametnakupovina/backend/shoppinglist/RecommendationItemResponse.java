package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecommendationItemResponse(
        Long itemId,
        String requestedName,
        BigDecimal requestedQuantity,
        ShoppingItemRule matchingRule,
        ShoppingItemMatchingStatus matchingStatus,
        RecommendationItemStatus resultStatus,
        Long storeId,
        String retailerCode,
        String retailerName,
        Long canonicalProductId,
        Long retailerProductId,
        String productName,
        String productBrand,
        String productBarcode,
        LocalDate priceDate,
        BigDecimal effectivePrice,
        BigDecimal lineTotal,
        String priceScope,
        String explanation
) {
}
