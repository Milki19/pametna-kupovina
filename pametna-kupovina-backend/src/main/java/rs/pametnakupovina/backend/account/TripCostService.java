package rs.pametnakupovina.backend.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much an hour on the road and one more shop are worth to a shopper.
 * The plan weighs a cheaper basket against them, so someone who would
 * rather drive across town for a bargain says so here.
 *
 * <p>Amounts are in the account's market currency. A value equal to the
 * market's is not stored, so the account keeps following the market.
 */
@Service
public class TripCostService {

    /**
     * The ceiling is a multiple of the market's own value, so it means the
     * same in every currency; past it the number is a typo, not a wish.
     */
    static final BigDecimal MAX_MULTIPLE = new BigDecimal("25");

    /** Koliko cifara iznos sme da ima pre i posle zareza da bi se uopšte računalo. */
    private static final int MAX_INTEGER_DIGITS = 12;
    private static final int MAX_DECIMALS = 10;

    private final AccountRepository accountRepository;

    public TripCostService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public TripCosts get(DeviceCaller caller) {
        return accountRepository.tripCosts(caller.accountId()).costs();
    }

    /** @param request a missing amount goes back to the market's */
    public TripCosts save(DeviceCaller caller, TripCostRequest request) {
        Stored stored = accountRepository.tripCosts(caller.accountId());
        BigDecimal valuePerHour = own(
                request.valuePerHour(), stored.marketValuePerHour(), stored, "Sat");
        BigDecimal costPerStop = own(
                request.costPerStop(), stored.marketCostPerStop(), stored, "Stajanje");

        accountRepository.saveTripCosts(caller.accountId(), valuePerHour, costPerStop);

        return accountRepository.tripCosts(caller.accountId()).costs();
    }

    private static BigDecimal own(
            BigDecimal requested,
            BigDecimal market,
            Stored stored,
            String what
    ) {
        if (requested == null) {
            return null;
        }
        BigDecimal max = max(market, stored.currencyMinorUnits());
        // Broj kao 1e10000000 je kratak, ali bi se zaokruživao sekundama;
        // veličina se proverava pre ikakvog računanja.
        BigDecimal plain = requested.stripTrailingZeros();
        boolean sane = plain.precision() - plain.scale() <= MAX_INTEGER_DIGITS
                && plain.scale() <= MAX_DECIMALS;
        BigDecimal amount = sane
                ? requested.setScale(stored.currencyMinorUnits(), RoundingMode.HALF_UP)
                : null;
        if (amount == null || amount.signum() < 0 || amount.compareTo(max) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    what + " može da vredi od 0 do " + max.stripTrailingZeros().toPlainString()
                            + " " + stored.currencyCode() + "."
            );
        }
        return amount.compareTo(market) == 0 ? null : amount;
    }

    private static BigDecimal max(BigDecimal market, int minorUnits) {
        return market.max(BigDecimal.ONE)
                .multiply(MAX_MULTIPLE)
                .setScale(minorUnits, RoundingMode.HALF_UP);
    }

    /** The account's own amounts, NULL where it follows the market. */
    public record Stored(
            BigDecimal valuePerHour,
            BigDecimal costPerStop,
            BigDecimal marketValuePerHour,
            BigDecimal marketCostPerStop,
            String currencyCode,
            int currencyMinorUnits
    ) {

        TripCosts costs() {
            return new TripCosts(
                    valuePerHour != null ? valuePerHour : marketValuePerHour,
                    costPerStop != null ? costPerStop : marketCostPerStop,
                    marketValuePerHour,
                    marketCostPerStop,
                    max(marketValuePerHour, currencyMinorUnits),
                    max(marketCostPerStop, currencyMinorUnits),
                    valuePerHour != null || costPerStop != null,
                    currencyCode
            );
        }
    }

    /**
     * @param valuePerHour what the plan uses now
     * @param customized   whether either amount differs from the market's
     * @param currency     ISO 4217, the account's market
     */
    public record TripCosts(
            BigDecimal valuePerHour,
            BigDecimal costPerStop,
            BigDecimal defaultValuePerHour,
            BigDecimal defaultCostPerStop,
            BigDecimal maxValuePerHour,
            BigDecimal maxCostPerStop,
            boolean customized,
            String currency
    ) {
    }

    public record TripCostRequest(BigDecimal valuePerHour, BigDecimal costPerStop) {
    }
}
