package rs.pametnakupovina.backend.shoppinglist;

import rs.pametnakupovina.backend.market.Market;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * What a base kind usually costs in the market (V161): "riba" is fresh and
 * frozen fish, and a line that names only the base kind is judged against
 * what that whole kind costs a kilogram, not against the few offers of the
 * shops around (test 6.11.: "riba 4" was four sea bass at 2.504 RSD/kg in
 * the one-stop plan, because the three fish nearby were all dear).
 */
public interface BaseKindPrices {

    /**
     * The lines of the list that name only a base kind with kinds under it
     * ("riba", "meso"): no brand, no word beyond the kind. Item id → base kind.
     */
    Map<Long, PlainKind> plainLines(long listId);

    /** The usual price of a kilogram or a litre ({@code baseUnit} g or ml) of the base kind; none with too few chains. */
    Optional<BigDecimal> usualPerThousand(Market market, long baseKindId, String baseUnit);

    /**
     * How much one pack of each product holds, in g or ml, where the plan
     * may not know it: a fish sold by the kilogram is 1000 g a price.
     */
    Map<Long, PackAmount> packAmounts(Collection<Long> retailerProductIds);

    /** A base kind and the name shoppers read for it ("Sveža riba"). */
    record PlainKind(long baseKindId, String name) {
    }

    record PackAmount(BigDecimal amount, String baseUnit) {
    }

    /** No base kinds: every line is judged by its own offers only. */
    BaseKindPrices NONE = new BaseKindPrices() {
        @Override
        public Map<Long, PlainKind> plainLines(long listId) {
            return Map.of();
        }

        @Override
        public Optional<BigDecimal> usualPerThousand(Market market, long baseKindId, String baseUnit) {
            return Optional.empty();
        }

        @Override
        public Map<Long, PackAmount> packAmounts(Collection<Long> retailerProductIds) {
            return Map.of();
        }
    };
}
