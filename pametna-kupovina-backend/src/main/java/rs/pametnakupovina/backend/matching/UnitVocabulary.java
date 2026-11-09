package rs.pametnakupovina.backend.matching;

import rs.pametnakupovina.backend.market.Market;

import java.util.List;
import java.util.Optional;

/**
 * The unit words a market's lists are written in (V155), in place of the
 * regular expressions in ShoppingListTextParser and ProductQuantityParser.
 * A word the market itself has wins over the same word for the whole
 * language.
 */
public interface UnitVocabulary {

    /** @param normalizedWord as ProductNameNormalizer writes it */
    Optional<UnitWord> find(Market market, String normalizedWord);

    /** Every word of the market, longest first, so "kilograma" wins over "kg". */
    List<UnitWord> words(Market market);
}
