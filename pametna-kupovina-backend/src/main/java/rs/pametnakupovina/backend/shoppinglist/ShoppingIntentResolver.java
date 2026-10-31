package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.SerbianWordForms;
import rs.pametnakupovina.backend.product.CatalogMemo;

import java.util.List;
import java.util.Optional;

@Component
public class ShoppingIntentResolver {

    private final JdbcClient jdbcClient;
    private final ProductNameNormalizer normalizer;
    // A word's kind and what the names say change only with the catalogue.
    private final CatalogMemo memo;

    public ShoppingIntentResolver(
            JdbcClient jdbcClient,
            ProductNameNormalizer normalizer,
            CatalogMemo memo
    ) {
        this.jdbcClient = jdbcClient;
        this.normalizer = normalizer;
        this.memo = memo;
    }

    public Optional<ResolvedShoppingIntent> resolve(String value) {
        return memo.remember(
                List.of("resolve", String.valueOf(value)),
                () -> readResolve(value)
        );
    }

    private Optional<ResolvedShoppingIntent> readResolve(String value) {
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
        return memo.remember(
                List.of("resolveForms", String.valueOf(value)),
                () -> readResolveForms(value)
        );
    }

    private Optional<FormMatch> readResolveForms(String value) {
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
        return memo.remember(
                List.of("kindHasProductsMatching", String.valueOf(shoppingIntentId), String.valueOf(pattern)),
                () -> readKindHasProductsMatching(shoppingIntentId, pattern)
        );
    }

    private boolean readKindHasProductsMatching(long shoppingIntentId, String pattern) {
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
        return memo.remember(
                List.of("productsMatch", String.valueOf(pattern)),
                () -> readProductsMatch(pattern)
        );
    }

    private boolean readProductsMatch(String pattern) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.retailer_product AS product
                            WHERE """ + byHint(WantedWords.hint(pattern)) + """
                              product.normalized_name ~ ?
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
        return memo.remember(
                List.of("kindIsBarelyTyped", String.valueOf(shoppingIntentId), String.valueOf(namePattern)),
                () -> readKindIsBarelyTyped(shoppingIntentId, namePattern)
        );
    }

    private boolean readKindIsBarelyTyped(long shoppingIntentId, String namePattern) {
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
                                       WHERE """ + byHint(WantedWords.hint(namePattern)) + """
                                         product.normalized_name ~ ?
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

    /**
     * A word the expression's names all say, which the trigram index finds
     * (V142): the expression alone, with its look-arounds, read every name.
     * Single quotes cannot occur in it: the words are letters and digits.
     */
    private static String byHint(String hint) {
        // Spaces around: a text block drops the ones at its line ends.
        return hint == null || hint.indexOf('\'') >= 0
                ? " "
                : " product.normalized_name ~ '" + hint + "' AND ";
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
        return memo.remember(
                List.of("kindHasProductsOfBrand", String.valueOf(shoppingIntentId), String.valueOf(brand)),
                () -> readKindHasProductsOfBrand(shoppingIntentId, brand)
        );
    }

    private boolean readKindHasProductsOfBrand(long shoppingIntentId, String brand) {
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
        return memo.remember(
                List.of("isKnownBrand", String.valueOf(value)),
                () -> readIsKnownBrand(value)
        );
    }

    private boolean readIsKnownBrand(String value) {
        String normalized = normalizer.normalize(value);

        if (normalized.isBlank()) {
            return false;
        }

        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.brand AS brand
                            -- The brands of that name first, by their indexes:
                            -- asked with OR, the key was worked out for each
                            -- of 10,500 brands (60 ms a word, profil 31.10.).
                            WHERE brand.id IN (
                                      SELECT named.id
                                      FROM app.brand AS named
                                      WHERE named.normalized_name = ?
                                      UNION
                                      SELECT alias.brand_id
                                      FROM app.brand_alias AS alias
                                      WHERE alias.normalized_alias = ?
                                  )
                              AND brand.active = TRUE
                              AND app.product_match_brand_key(
                                      brand.display_name
                                  ) <> ''
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
