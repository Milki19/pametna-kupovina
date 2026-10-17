package rs.pametnakupovina.backend.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.NameMeaning;

import java.util.List;
import java.util.StringJoiner;

/**
 * Scores every open "Isti proizvodi?" pair by what the two names mean
 * (V135), next to the shared-letters score, so the owner sees likely pairs
 * first. Vectors are kept per family and made again only for a new name or
 * model, so a catalog refresh only multiplies stored vectors.
 */
@Component
public class MergeSuggestionMeaningScorer {

    private static final Logger log = LoggerFactory.getLogger(MergeSuggestionMeaningScorer.class);
    private static final int BATCH = 64;

    private final JdbcClient jdbcClient;
    private final NameMeaning meaning;

    public MergeSuggestionMeaningScorer(JdbcClient jdbcClient, NameMeaning meaning) {
        this.jdbcClient = jdbcClient;
        this.meaning = meaning;
    }

    /** Pairs that are already open when the app starts get their score without waiting for an import. */
    @EventListener(ApplicationReadyEvent.class)
    public void scoreOpenSuggestionsOnStart() {
        if (!meaning.available()) {
            return;
        }
        Thread.ofVirtual().name("merge-meaning").start(() -> {
            try {
                scoreSuggestions();
            } catch (RuntimeException exception) {
                log.warn("Bodovanje predloga po značenju nije uspelo", exception);
            }
        });
    }

    public void scoreSuggestions() {
        if (!meaning.available()) {
            return;
        }
        String model = meaning.modelCode();
        List<FamilyName> missing = jdbcClient.sql("""
                        SELECT family.id, family.display_name
                        FROM app.product_family AS family
                        WHERE family.id IN (
                            SELECT left_family_id FROM app.product_merge_suggestion
                            UNION
                            SELECT right_family_id FROM app.product_merge_suggestion
                        )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM app.product_family_meaning AS stored
                              WHERE stored.product_family_id = family.id
                                AND stored.model_code = ?
                                AND stored.name_text = family.display_name
                          )
                        ORDER BY family.id
                        """)
                .param(1, model)
                .query((resultSet, rowNumber) -> new FamilyName(
                        resultSet.getLong("id"),
                        resultSet.getString("display_name")
                ))
                .list();
        for (int start = 0; start < missing.size(); start += BATCH) {
            List<FamilyName> batch = missing.subList(start, Math.min(missing.size(), start + BATCH));
            List<float[]> vectors;
            try {
                vectors = meaning.vectors(batch.stream().map(FamilyName::name).toList());
            } catch (RuntimeException exception) {
                // An import must not fail for a score; the pairs keep the letters score.
                log.warn("Model za značenje naziva nije uspeo; preskačem bodovanje", exception);
                return;
            }
            for (int index = 0; index < batch.size(); index++) {
                store(batch.get(index), model, vectors.get(index));
            }
        }
        int scored = jdbcClient.sql("""
                        UPDATE app.product_merge_suggestion AS suggestion
                        SET meaning_score = ROUND(LEAST(1, GREATEST(-1, (
                                SELECT SUM(l * r)
                                FROM UNNEST(left_meaning.vector, right_meaning.vector) AS pair(l, r)
                            )))::NUMERIC, 4)
                        FROM app.product_family_meaning AS left_meaning,
                             app.product_family_meaning AS right_meaning
                        WHERE left_meaning.product_family_id = suggestion.left_family_id
                          AND right_meaning.product_family_id = suggestion.right_family_id
                          AND left_meaning.model_code = ?
                          AND right_meaning.model_code = ?
                        """)
                .param(1, model)
                .param(2, model)
                .update();
        if (!missing.isEmpty()) {
            log.info("Značenje naziva: {} novih porodica, {} predloga bodovano", missing.size(), scored);
        }
    }

    private void store(FamilyName family, String model, float[] vector) {
        StringJoiner array = new StringJoiner(",", "{", "}");
        for (float value : vector) {
            array.add(Float.toString(value));
        }
        jdbcClient.sql("""
                        INSERT INTO app.product_family_meaning (product_family_id, model_code, name_text, vector)
                        VALUES (?, ?, ?, ?::REAL[])
                        ON CONFLICT (product_family_id) DO UPDATE SET
                            model_code = EXCLUDED.model_code,
                            name_text = EXCLUDED.name_text,
                            vector = EXCLUDED.vector,
                            updated_at = NOW()
                        """)
                .param(1, family.id())
                .param(2, model)
                .param(3, family.name())
                .param(4, array.toString())
                .update();
    }

    private record FamilyName(long id, String name) {
    }
}
