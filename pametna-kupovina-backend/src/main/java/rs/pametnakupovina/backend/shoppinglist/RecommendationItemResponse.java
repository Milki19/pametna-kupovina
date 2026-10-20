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
        String explanation,
        PurchaseQuantity purchaseQuantity,
        // Set when the product is the nearest the shop has, not what the line
        // asks for: "feta sir" priced by a young cheese (V140).
        String substituteNote
) {
    public RecommendationItemResponse(
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
        String explanation,
        PurchaseQuantity purchaseQuantity
    ) {
        this(itemId, requestedName, requestedQuantity, matchingRule, matchingStatus, resultStatus, storeId, retailerCode, retailerName, canonicalProductId, retailerProductId, productName, productBrand, productBarcode, priceDate, effectivePrice, lineTotal, priceScope, explanation, purchaseQuantity, null);
    }

    public RecommendationItemResponse(
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
        this(itemId, requestedName, requestedQuantity, matchingRule, matchingStatus, resultStatus, storeId, retailerCode, retailerName, canonicalProductId, retailerProductId, productName, productBrand, productBarcode, priceDate, effectivePrice, lineTotal, priceScope, explanation, null);
    }

}
