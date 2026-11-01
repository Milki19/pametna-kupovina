package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.sql.Types;
import java.util.Objects;

/**
 * „Izaberi drugi proizvod“ (V152): a shopper who gives a line another
 * product than the one it had says, better than any test set, which lines
 * are read wrong. Only the line and the two products are kept, with the
 * market; not the account, the device or the rest of the list.
 */
@Component
class ProductChoiceLog {

    private final JdbcClient jdbcClient;
    private final ProductNameNormalizer normalizer;

    ProductChoiceLog(JdbcClient jdbcClient, ProductNameNormalizer normalizer) {
        this.jdbcClient = jdbcClient;
        this.normalizer = normalizer;
    }

    /**
     * Called before the edit is written. Nothing is kept when the edit
     * chooses no product (a typed name, a kind) or the same one again
     * (a new quantity).
     */
    void beforeEdit(long listId, ShoppingListItemResponse before, Long chosenProductId, Long chosenFamilyId) {
        if (before == null || (chosenProductId == null && chosenFamilyId == null)) {
            return;
        }
        if (Objects.equals(before.matchedCanonicalProductId(), chosenProductId)
                && Objects.equals(before.matchedProductFamilyId(), chosenFamilyId)) {
            return;
        }
        String line = before.rawInput() == null || before.rawInput().isBlank() ? before.name() : before.rawInput();
        if (line == null || line.isBlank()) {
            return;
        }
        String text = line.strip();
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }
        String normalized = Objects.requireNonNullElse(normalizer.normalize(text), text);
        if (normalized.length() > 1000) {
            normalized = normalized.substring(0, 1000);
        }

        // Stariji od 180 dana odlaze usput, kao izveštaji o padu.
        jdbcClient.sql("DELETE FROM app.product_choice_change WHERE created_at < NOW() - INTERVAL '180 days'")
                .update();
        jdbcClient.sql("""
                        INSERT INTO app.product_choice_change (
                            market_id, line_text, line_normalized, previous_rule, previous_status,
                            previous_canonical_product_id, previous_product_family_id, previous_shopping_intent_id,
                            previous_score, chosen_canonical_product_id, chosen_product_family_id
                        )
                        SELECT account.market_id, ?, ?, ?, ?,
                               (SELECT id FROM app.canonical_product WHERE id = ?),
                               (SELECT id FROM app.product_family WHERE id = ?),
                               item.shopping_intent_id,
                               ?,
                               (SELECT id FROM app.canonical_product WHERE id = ?),
                               (SELECT id FROM app.product_family WHERE id = ?)
                        FROM app.shopping_list AS list
                        JOIN app.account AS account ON account.id = list.account_id
                        LEFT JOIN app.shopping_list_item AS item
                               ON item.id = ? AND item.shopping_list_id = list.id
                        WHERE list.id = ?
                        """)
                .param(1, text)
                .param(2, normalized)
                .param(3, before.matchingRule() == null ? "?" : before.matchingRule().name())
                .param(4, before.matchingStatus() == null ? "?" : before.matchingStatus().name())
                .param(5, before.matchedCanonicalProductId(), Types.BIGINT)
                .param(6, before.matchedProductFamilyId(), Types.BIGINT)
                .param(7, before.matchingScore(), Types.NUMERIC)
                .param(8, chosenProductId, Types.BIGINT)
                .param(9, chosenFamilyId, Types.BIGINT)
                .param(10, before.id())
                .param(11, listId)
                .update();
    }
}
