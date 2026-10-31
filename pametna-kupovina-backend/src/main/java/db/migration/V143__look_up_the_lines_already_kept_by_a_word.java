package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import rs.pametnakupovina.backend.shoppinglist.WantedWords;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The lines kept before V142 get the word their expression is looked up by,
 * worked out the way a new line's is ({@link WantedWords#hint}).
 */
public class V143__look_up_the_lines_already_kept_by_a_word extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Map<Long, String> hints = new LinkedHashMap<>();
        try (PreparedStatement select = context.getConnection().prepareStatement("""
                SELECT id, wanted_name_pattern
                FROM app.shopping_list_item
                WHERE wanted_name_pattern IS NOT NULL
                  AND wanted_name_hint IS NULL
                """);
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String hint = WantedWords.hint(rows.getString(2));
                if (hint != null) {
                    hints.put(rows.getLong(1), hint);
                }
            }
        }
        try (PreparedStatement update = context.getConnection().prepareStatement(
                "UPDATE app.shopping_list_item SET wanted_name_hint = ? WHERE id = ?")) {
            for (Map.Entry<Long, String> hint : hints.entrySet()) {
                update.setString(1, hint.getValue());
                update.setLong(2, hint.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
