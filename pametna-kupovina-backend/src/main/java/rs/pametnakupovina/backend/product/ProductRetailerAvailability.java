package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProductRetailerAvailability(
        String retailerCode,
        String retailerName,
        LocalDate latestPriceDate,
        int storeCount,
        int formatCount,
        BigDecimal minimumEffectivePrice
) {
}
