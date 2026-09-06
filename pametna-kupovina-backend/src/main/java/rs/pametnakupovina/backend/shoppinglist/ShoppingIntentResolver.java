package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.util.Optional;

@Component
public class ShoppingIntentResolver {

    private final JdbcClient jdbcClient;
    private final ProductNameNormalizer normalizer;

    public ShoppingIntentResolver(
            JdbcClient jdbcClient,
            ProductNameNormalizer normalizer
    ) {
        this.jdbcClient = jdbcClient;
        this.normalizer = normalizer;
    }

    public Optional<ResolvedShoppingIntent> resolve(String value) {
        String normalized = normalizer.normalize(value);

        if (normalized.isBlank()) {
            return Optional.empty();
        }

        return jdbcClient.sql("""
                        SELECT intent.id AS shopping_intent_id,
                               intent.code AS shopping_intent_code,
                               intent.name AS shopping_intent_name,
                               alias.normalized_alias,
                               (? = alias.normalized_alias) AS exact_alias
                        FROM app.shopping_intent_alias AS alias
                        JOIN app.shopping_intent AS intent
                          ON intent.id = alias.shopping_intent_id
                         AND intent.active = TRUE
                        WHERE ? = alias.normalized_alias
                           OR POSITION(
                               ' ' || alias.normalized_alias || ' '
                               IN ' ' || ? || ' '
                           ) > 0
                        ORDER BY CASE
                                     WHEN ? = alias.normalized_alias THEN 0
                                     ELSE 1
                                 END,
                                 LENGTH(alias.normalized_alias) DESC,
                                 alias.priority,
                                 alias.id
                        LIMIT 1
                        """)
                .param(1, normalized)
                .param(2, normalized)
                .param(3, normalized)
                .param(4, normalized)
                .query((resultSet, rowNumber) ->
                        new ResolvedShoppingIntent(
                                resultSet.getLong("shopping_intent_id"),
                                resultSet.getString("shopping_intent_code"),
                                resultSet.getString("shopping_intent_name"),
                                resultSet.getString("normalized_alias"),
                                resultSet.getBoolean("exact_alias")
                        ))
                .optional();
    }

    public record ResolvedShoppingIntent(
            long shoppingIntentId,
            String shoppingIntentCode,
            String shoppingIntentName,
            String normalizedAlias,
            boolean exactAlias
    ) {
    }
}
