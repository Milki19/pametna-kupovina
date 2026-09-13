package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

/**
 * A chain that publishes prices without saying where its shops are. It is
 * never part of a route or a recommended plan: it answers "would somewhere
 * else have been cheaper?" while the plan stays something the shopper can
 * actually follow. When the chain publishes several price lists and we cannot
 * tell which one applies, the basket is reported as a range.
 */
public record UnlocatedPriceOption(
        String retailerCode,
        String retailerName,
        int coveredItems,
        int totalItems,
        BigDecimal lowestBasketCost,
        BigDecimal highestBasketCost,
        int priceListCount,
        String caveat
) {
}
