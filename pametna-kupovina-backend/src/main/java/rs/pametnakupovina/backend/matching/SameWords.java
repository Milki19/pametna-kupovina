package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.product.CatalogMemo;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What product names write for a word of a market's lists (app.same_word,
 * V168): "puter" is "puter" or "maslac" in a Serbian shop. A market's own
 * rows for a word replace the rows of its language, so another market adds
 * its words without code. Remembered until the catalogue changes.
 */
@Component
public class SameWords {

    private final JdbcClient jdbcClient;
    private final CatalogMemo memo;

    public SameWords(JdbcClient jdbcClient, CatalogMemo memo) {
        this.jdbcClient = jdbcClient;
        this.memo = memo;
    }

    /**
     * @return by the word as the list writes it, the expression of the words
     * a product name says it with, each in its forms
     * ({@link WholeWords#anyFormOf}): "puter" is "puter" or "maslac"
     */
    public Map<String, String> of(Market market) {
        return memo.remember(List.of("sameWords", market.id(), market.defaultLanguage()), () -> read(market));
    }

    private record Row(String word, String nameWord, boolean ofTheMarket) {
    }

    private Map<String, String> read(Market market) {
        List<Row> rows = jdbcClient.sql("""
                        SELECT normalized_word, name_word, market_id IS NOT NULL AS of_the_market
                        FROM app.same_word
                        WHERE active
                          AND language = :language
                          AND (market_id = :marketId OR market_id IS NULL)
                        ORDER BY normalized_word, priority, id
                        """)
                .param("language", market.defaultLanguage())
                .param("marketId", market.id())
                .query((rs, row) -> new Row(
                        rs.getString("normalized_word"),
                        rs.getString("name_word"),
                        rs.getBoolean("of_the_market")))
                .list();
        Set<String> marketWords = rows.stream()
                .filter(Row::ofTheMarket)
                .map(Row::word)
                .collect(Collectors.toSet());
        Map<String, Set<String>> nameWords = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.ofTheMarket() || !marketWords.contains(row.word())) {
                nameWords.computeIfAbsent(row.word(), word -> new LinkedHashSet<>()).add(row.nameWord());
            }
        }
        Map<String, String> expressions = new LinkedHashMap<>();
        nameWords.forEach((word, said) -> expressions.put(word, WholeWords.anyFormOf(List.copyOf(said))));
        return Map.copyOf(expressions);
    }
}
