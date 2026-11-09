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
        // The rule lives in app.piece_grams (V156), which the plan's query
        // reads too, so a piece weighs the same here and there.
        return jdbcClient.sql("SELECT app.piece_grams(CAST(:marketId AS INTEGER), CAST(:typeId AS BIGINT), CAST(:intentId AS BIGINT), CAST(:word AS TEXT))")
                .param("marketId", market.id())
                .param("typeId", productTypeId)
                .param("intentId", shoppingIntentId)
                .param("word", normalizedWord)
                .query(BigDecimal.class)
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
