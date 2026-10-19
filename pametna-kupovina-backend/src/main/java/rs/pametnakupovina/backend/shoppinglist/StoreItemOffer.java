package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StoreItemOffer(
        Long storeId,
        String retailerCode,
        String retailerName,
        String storeFormatCode,
        String storeFormatName,
        String storeName,
        String address,
        String city,
        double latitude,
        double longitude,
        Long itemId,
        String requestedName,
        BigDecimal requestedQuantity,
        ShoppingItemRule matchingRule,
        ShoppingItemMatchingStatus matchingStatus,
        Long retailerProductId,
        Long canonicalProductId,
        String productName,
        String productBrand,
        String productBarcode,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal discountedPrice,
        BigDecimal effectivePrice,
        BigDecimal lineTotal,
        String priceScope,
        PurchaseQuantity purchaseQuantity,
        // The usual kind and size for the item; false for a 35 g sachet of
        // ketchup chosen only because the shop has nothing better.
        boolean usualChoice
) {
    public StoreItemOffer(
        Long storeId,
        String retailerCode,
        String retailerName,
        String storeFormatCode,
        String storeFormatName,
        String storeName,
        String address,
        String city,
        double latitude,
        double longitude,
        Long itemId,
        String requestedName,
        BigDecimal requestedQuantity,
        ShoppingItemRule matchingRule,
        ShoppingItemMatchingStatus matchingStatus,
        Long retailerProductId,
        Long canonicalProductId,
        String productName,
        String productBrand,
        String productBarcode,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal discountedPrice,
        BigDecimal effectivePrice,
        BigDecimal lineTotal,
        String priceScope
    ) {
        this(storeId, retailerCode, retailerName, storeFormatCode, storeFormatName, storeName, address, city, latitude, longitude, itemId, requestedName, requestedQuantity, matchingRule, matchingStatus, retailerProductId, canonicalProductId, productName, productBrand, productBarcode, priceDate, regularPrice, discountedPrice, effectivePrice, lineTotal, priceScope, null, true);
    }

    public StoreItemOffer(
        Long storeId,
        String retailerCode,
        String retailerName,
        String storeFormatCode,
        String storeFormatName,
        String storeName,
        String address,
        String city,
        double latitude,
        double longitude,
        Long itemId,
        String requestedName,
        BigDecimal requestedQuantity,
        ShoppingItemRule matchingRule,
        ShoppingItemMatchingStatus matchingStatus,
        Long retailerProductId,
        Long canonicalProductId,
        String productName,
        String productBrand,
        String productBarcode,
        LocalDate priceDate,
        BigDecimal regularPrice,
        BigDecimal discountedPrice,
        BigDecimal effectivePrice,
        BigDecimal lineTotal,
        String priceScope,
        PurchaseQuantity purchaseQuantity
    ) {
        this(storeId, retailerCode, retailerName, storeFormatCode, storeFormatName, storeName, address, city, latitude, longitude, itemId, requestedName, requestedQuantity, matchingRule, matchingStatus, retailerProductId, canonicalProductId, productName, productBrand, productBarcode, priceDate, regularPrice, discountedPrice, effectivePrice, lineTotal, priceScope, purchaseQuantity, true);
    }

    public boolean available() {
        return retailerProductId != null
                && effectivePrice != null
                && lineTotal != null;
    }
}
