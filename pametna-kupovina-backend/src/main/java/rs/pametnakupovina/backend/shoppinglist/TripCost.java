package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one cost of a plan, for every way of going (V155 contract):
 * {@code total = basket + km × perKm + hours × perHour + stops × perStop}.
 * Every plan is chosen by it; how a plan is shown (on foot: the basket and
 * the minutes) is a separate matter of the response.
 */
public record TripCost(
        BigDecimal basket,
        BigDecimal travel,
        BigDecimal time,
        BigDecimal stops,
        BigDecimal total
) {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    public static TripCost of(
            BigDecimal basket,
            double distanceKm,
            long durationSeconds,
            int stopCount,
            TripPrices prices
    ) {
        BigDecimal travel = money(BigDecimal.valueOf(distanceKm).multiply(prices.perKm()));
        BigDecimal time = money(BigDecimal.valueOf(durationSeconds)
                .divide(SECONDS_PER_HOUR, 8, RoundingMode.HALF_UP)
                .multiply(prices.perHour()));
        BigDecimal stops = money(prices.perStop().multiply(BigDecimal.valueOf(stopCount)));
        BigDecimal total = money(basket.add(travel).add(time).add(stops));
        return new TripCost(basket, travel, time, stops, total);
    }

    /** What the trip adds to the basket. */
    public BigDecimal tripOnly() {
        return money(travel.add(time).add(stops));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
