package rs.pametnakupovina.backend.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A country the app runs in, with everything that differs between countries:
 * the currency amounts are in, how they are written, the catalogue's
 * language, the clock price lists follow and what a trip to a shop costs.
 *
 * @param locale          BCP 47 tag for writing amounts and dates
 * @param defaultLanguage BCP 47 tag of the catalogue's language
 * @param travelCostPerKm travel costs are in the market's currency
 */
public record Market(
        int id,
        String code,
        String name,
        String currencyCode,
        int currencyMinorUnits,
        String locale,
        String defaultLanguage,
        ZoneId timeZone,
        BigDecimal travelCostPerKm,
        BigDecimal valuePerHour,
        BigDecimal costPerStop
) {

    /** The date where the market is, which is what a price list is dated by. */
    public LocalDate today() {
        return LocalDate.now(timeZone);
    }
}
