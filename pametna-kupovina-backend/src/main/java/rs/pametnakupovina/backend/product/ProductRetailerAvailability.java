package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @param saleRegularPrice what the chain's lowest price costs when it is not
 *                         on sale; null when that price is not a sale today
 * @param discountPercent  how much the sale takes off, in whole percent;
 *                         null without a sale
 * @param saleEndDate      the last day of the sale, when the chain says
 */
public record ProductRetailerAvailability(
        String retailerCode,
        String retailerName,
        LocalDate latestPriceDate,
        int storeCount,
        int formatCount,
        BigDecimal minimumEffectivePrice,
        // Every offer of the chain is below half of the product's typical
        // price across chains (V72); the minimum shown is one of them.
        boolean priceNeedsCheck,
        BigDecimal saleRegularPrice,
        Integer discountPercent,
        LocalDate saleEndDate
) {

    public ProductRetailerAvailability(
            String retailerCode,
            String retailerName,
            LocalDate latestPriceDate,
            int storeCount,
            int formatCount,
            BigDecimal minimumEffectivePrice,
            boolean priceNeedsCheck,
            BigDecimal saleRegularPrice,
            LocalDate saleEndDate
    ) {
        this(
                retailerCode,
                retailerName,
                latestPriceDate,
                storeCount,
                formatCount,
                minimumEffectivePrice,
                priceNeedsCheck,
                Discounts.percent(saleRegularPrice, minimumEffectivePrice) == null
                        ? null : saleRegularPrice,
                Discounts.percent(saleRegularPrice, minimumEffectivePrice),
                Discounts.percent(saleRegularPrice, minimumEffectivePrice) == null
                        ? null : saleEndDate
        );
    }
}
