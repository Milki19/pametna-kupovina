package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.product.CatalogMemo;

import java.util.List;
import java.util.Optional;

/** {@link UnitVocabulary} from app.unit_word, remembered until the catalogue changes. */
@Component
public class DatabaseUnitVocabulary implements UnitVocabulary {

    private final JdbcClient jdbcClient;
    private final CatalogMemo memo;

    public DatabaseUnitVocabulary(JdbcClient jdbcClient, CatalogMemo memo) {
        this.jdbcClient = jdbcClient;
        this.memo = memo;
    }

    @Override
    public Optional<UnitWord> find(Market market, String normalizedWord) {
        if (normalizedWord == null || normalizedWord.isBlank()) {
            return Optional.empty();
        }
        return words(market).stream()
                .filter(word -> word.word().equals(normalizedWord))
                .findFirst();
    }

    @Override
    public List<UnitWord> words(Market market) {
        return memo.remember(List.of("unitWords", market.id(), market.defaultLanguage()), () -> read(market));
    }

    private List<UnitWord> read(Market market) {
        return jdbcClient.sql("""
                        SELECT DISTINCT ON (normalized_word)
                               normalized_word, role, base_unit, factor, inside_pack
                        FROM app.unit_word
                        WHERE active
                          AND language = :language
                          AND (market_id = :marketId OR market_id IS NULL)
                        ORDER BY normalized_word, (market_id IS NULL), priority, id
                        """)
                .param("language", market.defaultLanguage())
                .param("marketId", market.id())
                .query((rs, row) -> new UnitWord(
                        rs.getString("normalized_word"),
                        UnitWord.Role.valueOf(rs.getString("role")),
                        rs.getString("base_unit") == null ? null : BaseUnit.fromDatabase(rs.getString("base_unit")),
                        rs.getBigDecimal("factor"),
                        rs.getBoolean("inside_pack")))
                .list()
                .stream()
                .sorted((a, b) -> Integer.compare(b.word().length(), a.word().length()))
                .toList();
    }
}
