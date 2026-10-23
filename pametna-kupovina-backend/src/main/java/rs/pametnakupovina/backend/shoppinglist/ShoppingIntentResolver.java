package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.SerbianWordForms;

import java.util.List;
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
                               (? = alias.normalized_alias) AS exact_alias,
                               intent.default_base_unit
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
                                resultSet.getBoolean("exact_alias"),
                                resultSet.getString("default_base_unit")
                        ))
                .optional();
    }

    /**
     * The kind the words name in any form a list writes them: "kisele vode"
     * is "kisela voda", "2 kg brašna T-500" flour T-500, "mlevenog mesa"
     * minced meat. A form names the kind exactly, or holds its words with
     * more around them ("feta sir", "mleko 3,2%"); the exact reading wins,
     * then the longest dictionary words.
     */
    public Optional<FormMatch> resolveForms(String value) {
        String normalized = normalizer.normalize(value);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        List<String> candidates = SerbianWordForms.phraseCandidates(normalized);
        return jdbcClient.sql("""
                        SELECT intent.id AS shopping_intent_id,
                               intent.code AS shopping_intent_code,
                               intent.name AS shopping_intent_name,
                               alias.normalized_alias,
                               (candidate.phrase = alias.normalized_alias) AS exact_alias,
                               intent.default_base_unit,
                               candidate.phrase
                        FROM UNNEST(?::text[]) WITH ORDINALITY AS candidate (phrase, position)
                        JOIN app.shopping_intent_alias AS alias
                          ON candidate.phrase = alias.normalized_alias
                          OR POSITION(
                              ' ' || alias.normalized_alias || ' '
                              IN ' ' || candidate.phrase || ' '
                          ) > 0
                        JOIN app.shopping_intent AS intent
                          ON intent.id = alias.shopping_intent_id
                         AND intent.active = TRUE
                        ORDER BY (candidate.phrase = alias.normalized_alias) DESC,
                                 LENGTH(alias.normalized_alias) DESC,
                                 candidate.position,
                                 alias.priority,
                                 alias.id
                        LIMIT 1
                        """)
                .param(1, candidates.toArray(String[]::new))
                .query((resultSet, rowNumber) -> new FormMatch(
                        new ResolvedShoppingIntent(
                                resultSet.getLong("shopping_intent_id"),
                                resultSet.getString("shopping_intent_code"),
                                resultSet.getString("shopping_intent_name"),
                                resultSet.getString("normalized_alias"),
                                resultSet.getBoolean("exact_alias"),
                                resultSet.getString("default_base_unit")
                        ),
                        resultSet.getString("phrase")
                ))
                .optional();
    }

    /**
     * Whether some product of the kind with a price says these words too:
     * there is feta among the cheeses, while "toaletna voda" is no water.
     * Without a price it is no kind to buy from: "krem sir" and "vanilin
     * šećer" stayed without a price as cheese and sugar (proba V140).
     */
    public boolean kindHasProductsMatching(long shoppingIntentId, String pattern) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.shopping_intent_product_type AS allowed
                            JOIN app.retailer_product_type AS assignment
                              ON assignment.product_type_id = allowed.product_type_id
                            JOIN app.retailer_product AS product
                              ON product.id = assignment.retailer_product_id
                            WHERE allowed.shopping_intent_id = ?
                              AND allowed.enabled_by_default = TRUE
                              AND product.normalized_name ~ ?
                              AND EXISTS (
                                  SELECT 1
                                  FROM app.current_price_offer AS offer
                                  WHERE offer.retailer_product_id = product.id
                              )
                        )
                        """)
                .param(1, shoppingIntentId)
                .param(2, pattern)
                .query(Boolean.class)
                .single();
    }

    /**
     * Whether products with a price say all these words: what a list asks
     * for that no dictionary knows ("tortilje", "vlažne maramice") is found
     * by the products' own names.
     */
    public boolean productsMatch(String pattern) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.retailer_product AS product
                            WHERE product.normalized_name ~ ?
                              AND EXISTS (
                                  SELECT 1
                                  FROM app.current_price_offer AS offer
                                  WHERE offer.retailer_product_id = product.id
                              )
                        )
                        """)
                .param(1, pattern)
                .query(Boolean.class)
                .single();
    }

    /**
     * Whether a kind has barely any products with a price while many
     * products' names say the word: "majonez" had one, in one shop, with
     * hundreds of mayonnaises in every chain (provera 23.10.2026). Such a
     * word is found by the names, as a word no dictionary knows.
     */
    public boolean kindIsBarelyTyped(long shoppingIntentId, String namePattern) {
        return jdbcClient.sql("""
                        SELECT (
                                   SELECT COUNT(*)
                                   FROM (
                                       SELECT 1
                                       FROM app.shopping_intent_product_type AS allowed
                                       JOIN app.retailer_product_type AS assignment
                                         ON assignment.product_type_id = allowed.product_type_id
                                       WHERE allowed.shopping_intent_id = ?
                                         AND allowed.enabled_by_default = TRUE
                                         AND EXISTS (
                                             SELECT 1
                                             FROM app.current_price_offer AS offer
                                             WHERE offer.retailer_product_id = assignment.retailer_product_id
                                         )
                                       LIMIT 5
                                   ) AS typed
                               ) < 5
                           AND (
                                   SELECT COUNT(*)
                                   FROM (
                                       SELECT 1
                                       FROM app.retailer_product AS product
                                       WHERE product.normalized_name ~ ?
                                         AND EXISTS (
                                             SELECT 1
                                             FROM app.current_price_offer AS offer
                                             WHERE offer.retailer_product_id = product.id
                                         )
                                       LIMIT 20
                                   ) AS named
                               ) >= 20
                        """)
                .param(1, shoppingIntentId)
                .param(2, namePattern)
                .query(Boolean.class)
                .single();
    }

    public record FormMatch(ResolvedShoppingIntent intent, String phrase) {
    }

    /**
     * Whether these words are a brand some chain sells under ("Zaječarsko",
     * "Rubin"), not a counter of a shop like "povrće" or Lidl's "Brend".
     */
    /**
     * Whether products of the kind with a price carry the brand: "Krem" and
     * "Vanilin" are brands somewhere, but no cheese or sugar is sold under
     * them (provera uživo 23.10.).
     */
    public boolean kindHasProductsOfBrand(long shoppingIntentId, String brand) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.shopping_intent_product_type AS allowed
                            JOIN app.retailer_product_type AS assignment
                              ON assignment.product_type_id = allowed.product_type_id
                            JOIN app.retailer_product AS product
                              ON product.id = assignment.retailer_product_id
                            LEFT JOIN app.canonical_product AS canonical
                              ON canonical.id = product.canonical_product_id
                            WHERE allowed.shopping_intent_id = ?
                              AND allowed.enabled_by_default = TRUE
                              AND app.product_match_brand_key(?) <> ''
                              AND app.product_match_brand_key(?) IN (
                                  app.product_match_brand_key(canonical.brand),
                                  app.product_match_brand_key(product.brand)
                              )
                              AND EXISTS (
                                  SELECT 1
                                  FROM app.current_price_offer AS offer
                                  WHERE offer.retailer_product_id = product.id
                              )
                        )
                        """)
                .param(1, shoppingIntentId)
                .param(2, brand)
                .param(3, brand)
                .query(Boolean.class)
                .single();
    }

    public boolean isKnownBrand(String value) {
        String normalized = normalizer.normalize(value);

        if (normalized.isBlank()) {
            return false;
        }

        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.brand AS brand
                            WHERE brand.active = TRUE
                              AND app.product_match_brand_key(
                                      brand.display_name
                                  ) <> ''
                              AND (
                                  brand.normalized_name = ?
                                  OR EXISTS (
                                      SELECT 1
                                      FROM app.brand_alias AS alias
                                      WHERE alias.brand_id = brand.id
                                        AND alias.normalized_alias = ?
                                  )
                              )
                        )
                        """)
                .param(1, normalized)
                .param(2, normalized)
                .query(Boolean.class)
                .single();
    }

    public record ResolvedShoppingIntent(
            long shoppingIntentId,
            String shoppingIntentCode,
            String shoppingIntentName,
            String normalizedAlias,
            boolean exactAlias,
            String defaultBaseUnit
    ) {
        public ResolvedShoppingIntent(
                long shoppingIntentId,
                String shoppingIntentCode,
                String shoppingIntentName,
                String normalizedAlias,
                boolean exactAlias
        ) {
            this(
                    shoppingIntentId,
                    shoppingIntentCode,
                    shoppingIntentName,
                    normalizedAlias,
                    exactAlias,
                    null
            );
        }
    }
}
