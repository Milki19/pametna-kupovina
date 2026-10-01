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
        String priceScope,
        // Below half of what the product typically costs across chains
        // (V72), most likely the price of one piece or one kilogram.
        boolean priceNeedsCheck,
        // A case of the product, like METRO's twenty bottles under one
        // bottle's barcode; the price is for all of them (V76, V77).
        int packageCount,
        // The last day of the sale the effective price is; null when the
        // price is not a sale or the chain does not say (V115).
        LocalDate saleEndDate
) {

    public CanonicalProductOffer(
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
            String priceScope,
            boolean priceNeedsCheck,
            int packageCount
    ) {
        this(retailerProductId, retailerCode, retailerName, storeId, storeName,
                storeFormatCode, storeFormatName, priceDate, regularPrice,
                discountedPrice, effectivePrice, unitPrice, priceScope,
                priceNeedsCheck, packageCount, null);
    }

    public CanonicalProductOffer(
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
            String priceScope,
            boolean priceNeedsCheck
    ) {
        this(retailerProductId, retailerCode, retailerName, storeId, storeName,
                storeFormatCode, storeFormatName, priceDate, regularPrice,
                discountedPrice, effectivePrice, unitPrice, priceScope,
                priceNeedsCheck, 1);
    }

    public CanonicalProductOffer(
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
        this(
                retailerProductId,
                retailerCode,
                retailerName,
                storeId,
                storeName,
                storeFormatCode,
                storeFormatName,
                priceDate,
                regularPrice,
                discountedPrice,
                effectivePrice,
                unitPrice,
                priceScope,
                false,
                1
        );
    }
}
