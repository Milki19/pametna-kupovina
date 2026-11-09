package rs.pametnakupovina.backend.matching;

import rs.pametnakupovina.backend.market.Market;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * What one piece weighs (app.piece_mass, V155): a lemon about 100 g, an
 * avocado about 200 g. The most precise key wins: the kind of product, then
 * the word of the list, then the word alone; the market's own before the
 * language's.
 */
public interface PieceMassCatalog {

    /**
     * @param productTypeId    null where the line has no kind
     * @param shoppingIntentId null where the list word is not in the dictionary
     * @param normalizedWord   the line's name, as ProductNameNormalizer writes it
     */
    Optional<BigDecimal> gramsPerPiece(
            Market market, Long productTypeId, Long shoppingIntentId, String normalizedWord);
}
