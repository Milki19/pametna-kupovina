package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Za nedeljni izveštaj (V152): koje linije kupci najčešće prebace na drugi
 * proizvod, šta je aplikacija bila našla i šta su izabrali. Linija koja se
 * ovde ponavlja ide u test skup, pa u rečnik vrsta.
 */
@RestController
public class ProductChoiceChangeController {

    private final JdbcClient jdbcClient;

    public ProductChoiceChangeController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/api/v1/imports/quality/choice-changes")
    public List<ChoiceChange> changes(@RequestParam(defaultValue = "7") int days) {
        return jdbcClient.sql("""
                        SELECT change.line_normalized,
                               (ARRAY_AGG(change.line_text ORDER BY change.created_at DESC))[1] AS line,
                               market.code AS market,
                               change.previous_status,
                               COALESCE(previous.name, previous_family.display_name, intent.name) AS previous,
                               COALESCE(chosen.name, chosen_family.display_name) AS chosen,
                               COUNT(*) AS times,
                               MAX(change.created_at) AS last_seen
                        FROM app.product_choice_change AS change
                        LEFT JOIN app.market AS market ON market.id = change.market_id
                        LEFT JOIN app.canonical_product AS previous ON previous.id = change.previous_canonical_product_id
                        LEFT JOIN app.product_family AS previous_family ON previous_family.id = change.previous_product_family_id
                        LEFT JOIN app.shopping_intent AS intent ON intent.id = change.previous_shopping_intent_id
                        LEFT JOIN app.canonical_product AS chosen ON chosen.id = change.chosen_canonical_product_id
                        LEFT JOIN app.product_family AS chosen_family ON chosen_family.id = change.chosen_product_family_id
                        WHERE change.created_at >= NOW() - MAKE_INTERVAL(days => ?)
                        GROUP BY 1, 3, 4, 5, 6
                        ORDER BY times DESC, last_seen DESC
                        LIMIT 200
                        """)
                .param(Math.max(1, Math.min(days, 180)))
                .query((row, n) -> new ChoiceChange(
                        row.getString("line"),
                        row.getString("market"),
                        row.getString("previous_status"),
                        row.getString("previous"),
                        row.getString("chosen"),
                        row.getInt("times")
                ))
                .list();
    }

    /** {@code previousStatus}: AUTO_MATCHED or CONFIRMED is a wrong find, the rest a line step 1 could not settle. */
    public record ChoiceChange(String line, String market, String previousStatus, String previous, String chosen, int times) {
    }
}
