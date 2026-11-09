package rs.pametnakupovina.backend.shoppinglist;

import rs.pametnakupovina.backend.market.Market;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a kilometre, an hour and a stop cost the shopper, in the market's
 * currency (V155 contract). The market's own, or the account's where it set
 * them. On foot a kilometre costs nothing; the hour still counts.
 */
public record TripPrices(BigDecimal perKm, BigDecimal perHour, BigDecimal perStop) {

    public TripPrices {
        Objects.requireNonNull(perKm, "perKm");
        Objects.requireNonNull(perHour, "perHour");
        Objects.requireNonNull(perStop, "perStop");
        if (perKm.signum() < 0 || perHour.signum() < 0 || perStop.signum() < 0) {
            throw new IllegalArgumentException("Cene puta ne mogu biti negativne");
        }
    }

    public static TripPrices of(Market market) {
        return new TripPrices(market.travelCostPerKm(), market.valuePerHour(), market.costPerStop());
    }

    /** The same prices for another way of going: on foot the kilometre is free. */
    public TripPrices forMode(TravelMode mode) {
        return mode == TravelMode.WALKING ? new TripPrices(BigDecimal.ZERO, perHour, perStop) : this;
    }
}
