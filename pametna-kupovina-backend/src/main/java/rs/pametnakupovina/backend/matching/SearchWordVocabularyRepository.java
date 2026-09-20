package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The words a shopper's query is allowed to be corrected to: what a shop
 * sells ("mleko", "hleb") and who makes it ("imlek"). Product names are left
 * out on purpose — they carry every misspelling a chain ever published, and
 * correcting one typo into another would be worse than finding nothing.
 */
@Repository
public class SearchWordVocabularyRepository {

    private final JdbcClient jdbcClient;

    public SearchWordVocabularyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<VocabularyWord> findWordsOfLength(
            int minLength,
            int maxLength
    ) {
        if (minLength > maxLength) {
            return List.of();
        }

        return jdbcClient.sql("""
                        SELECT word,
                               MIN(source_rank)::INTEGER AS source_rank
                        FROM (
                            SELECT UNNEST(STRING_TO_ARRAY(
                                       alias.normalized_alias, ' '
                                   )) AS word,
                                   1 AS source_rank
                            FROM app.shopping_intent_alias AS alias
                            JOIN app.shopping_intent AS intent
                              ON intent.id = alias.shopping_intent_id
                             AND intent.active = TRUE
                            UNION ALL
                            SELECT UNNEST(STRING_TO_ARRAY(
                                       alias.normalized_alias, ' '
                                   )),
                                   2
                            FROM app.brand_alias AS alias
                            UNION ALL
                            SELECT UNNEST(STRING_TO_ARRAY(
                                       brand.normalized_name, ' '
                                   )),
                                   2
                            FROM app.brand AS brand
                            WHERE brand.active = TRUE
                        ) AS vocabulary
                        WHERE LENGTH(word) BETWEEN ? AND ?
                          AND word ~ '^[a-z]+$'
                        GROUP BY word
                        """)
                .param(1, minLength)
                .param(2, maxLength)
                .query((resultSet, rowNumber) -> new VocabularyWord(
                        resultSet.getString("word"),
                        resultSet.getInt("source_rank")
                ))
                .list();
    }

    /**
     * @param sourceRank 1 for a word a shopper uses for the thing itself,
     *                   2 for a brand; a tie is decided in that order.
     */
    public record VocabularyWord(String word, int sourceRank) {
    }
}
