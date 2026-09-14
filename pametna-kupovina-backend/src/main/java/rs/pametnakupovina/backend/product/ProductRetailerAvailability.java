package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProductRetailerAvailability(
        String retailerCode,
        String retailerName,
        LocalDate latestPriceDate,
        int storeCount,
        int formatCount,
        BigDecimal minimumEffectivePrice,
        // Every offer of the chain is below half of the product's typical
        // price across chains (V72); the minimum shown is one of them.
        boolean priceNeedsCheck
) {
}
