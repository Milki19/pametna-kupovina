package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.product.CatalogMemo;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** {@link PieceMassCatalog} from app.piece_mass, remembered until the catalogue changes. */
@Component
public class DatabasePieceMassCatalog implements PieceMassCatalog {

    private final JdbcClient jdbcClient;
    private final CatalogMemo memo;

    public DatabasePieceMassCatalog(JdbcClient jdbcClient, CatalogMemo memo) {
        this.jdbcClient = jdbcClient;
        this.memo = memo;
    }

    @Override
    public Optional<BigDecimal> gramsPerPiece(
            Market market, Long productTypeId, Long shoppingIntentId, String normalizedWord
    ) {
        List<Object> key = Arrays.asList("pieceMass", market.id(), market.defaultLanguage(),
                productTypeId, shoppingIntentId, normalizedWord);
        return memo.remember(key, () -> read(market, productTypeId, shoppingIntentId, normalizedWord));
    }

    private Optional<BigDecimal> read(
            Market market, Long productTypeId, Long shoppingIntentId, String normalizedWord
    ) {
        if (productTypeId == null && shoppingIntentId == null
                && (normalizedWord == null || normalizedWord.isBlank())) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                        SELECT grams
                        FROM app.piece_mass
                        WHERE (market_id = :marketId OR market_id IS NULL)
                          AND (product_type_id = :typeId
                               OR shopping_intent_id = :intentId
                               OR (normalized_word = :word AND language = :language))
                        ORDER BY CASE WHEN product_type_id IS NOT NULL THEN 0
                                      WHEN shopping_intent_id IS NOT NULL THEN 1
                                      ELSE 2 END,
                                 (market_id IS NULL),
                                 id
                        LIMIT 1
                        """)
                .param("marketId", market.id())
                .param("typeId", productTypeId)
                .param("intentId", shoppingIntentId)
                .param("word", normalizedWord)
                .param("language", market.defaultLanguage())
                .query(BigDecimal.class)
                .optional();
    }
}
