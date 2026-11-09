package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.shoppinglist.WantedWords;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The lines kept before V159 look for their words as the start of a word:
 * "kinoa" was {@code \mkin} and found Kinder Cards (šesti test, 6.11.2026).
 * Each gets the expression a new line gets, whole words in their forms
 * ({@link WantedWords#pattern}), and its hint. A kind's line keeps the
 * reading it was given: the words beside the kind, or, where nothing was
 * beside it, the narrower word itself. The column grows to 2000 characters
 * first: a word with its endings is longer than the start of it was.
 */
public class V159__read_the_kept_lines_by_whole_words extends BaseJavaMigration {

    private record Row(long id, String pattern, String hint) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        try (PreparedStatement widen = context.getConnection().prepareStatement(
                "ALTER TABLE app.shopping_list_item ALTER COLUMN wanted_name_pattern TYPE VARCHAR(2000)")) {
            widen.execute();
        }
        ProductNameNormalizer normalizer = new ProductNameNormalizer();
        List<Row> changed = new ArrayList<>();
        try (PreparedStatement select = context.getConnection().prepareStatement("""
                SELECT id, flexible_category, flexible_category_normalized,
                       shopping_intent_id, wanted_name_pattern
                FROM app.shopping_list_item
                WHERE wanted_name_pattern IS NOT NULL
                  AND matching_rule = 'FLEXIBLE_CATEGORY'
                  AND flexible_category IS NOT NULL
                """);
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String category = rows.getString(2);
                String alias = rows.getString(3);
                boolean ofAKind = rows.getObject(4) != null;
                String old = rows.getString(5);
                String pattern = ofAKind ? WantedWords.pattern(category, alias, normalizer) : null;
                if (pattern == null) {
                    pattern = WantedWords.pattern(category, null, normalizer);
                }
                if (pattern != null && !Objects.equals(pattern, old)) {
                    changed.add(new Row(rows.getLong(1), pattern, WantedWords.hint(pattern)));
                }
            }
        }
        try (PreparedStatement update = context.getConnection().prepareStatement("""
                UPDATE app.shopping_list_item
                SET wanted_name_pattern = ?, wanted_name_hint = ?
                WHERE id = ?
                """)) {
            for (Row row : changed) {
                update.setString(1, row.pattern());
                update.setString(2, row.hint());
                update.setLong(3, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
