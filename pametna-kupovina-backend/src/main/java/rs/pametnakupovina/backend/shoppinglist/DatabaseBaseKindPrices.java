package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.matching.KindTaxonomy;
import rs.pametnakupovina.backend.product.CatalogMemo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link BaseKindPrices} from product_type.base_type_id (V155, V161) and the current prices. */
@Repository
public class DatabaseBaseKindPrices implements BaseKindPrices {

    /** Fewer chains than this say nothing about what a kind usually costs. */
    static final int MIN_CHAINS = 3;

    private final JdbcClient jdbcClient;
    private final CatalogMemo memo;
    private final KindTaxonomy taxonomy;

    public DatabaseBaseKindPrices(JdbcClient jdbcClient, CatalogMemo memo, KindTaxonomy taxonomy) {
        this.jdbcClient = jdbcClient;
        this.memo = memo;
        this.taxonomy = taxonomy;
    }

    @Override
    public Map<Long, PlainKind> plainLines(long listId) {
        // A plain line: a kind and nothing more. "brancin" (a word beyond
        // the kind), "riba Frikom" (a brand) or "sveža riba" with a pattern
        // of its own are asked for as written and keep their own offers.
        Map<Long, Set<Long>> typesByItem = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT item.id AS item_id, candidate.product_type_id
                        FROM app.shopping_list_item AS item
                        JOIN app.shopping_intent AS intent
                          ON intent.id = item.shopping_intent_id
                         AND intent.active
                        JOIN app.shopping_intent_product_type AS candidate
                          ON candidate.shopping_intent_id = intent.id
                         AND candidate.enabled_by_default
                        LEFT JOIN app.shopping_intent_alias AS alias
                          ON alias.shopping_intent_id = intent.id
                         AND alias.normalized_alias = item.flexible_category_normalized
                        WHERE item.shopping_list_id = :listId
                          AND item.matching_rule = 'FLEXIBLE_CATEGORY'
                          AND NULLIF(BTRIM(item.required_brand), '') IS NULL
                          AND NULLIF(BTRIM(item.wanted_name_pattern), '') IS NULL
                          AND alias.required_name_pattern IS NULL
                        """)
                .param("listId", listId)
                .query((rs, row) -> typesByItem
                        .computeIfAbsent(rs.getLong("item_id"), id -> new HashSet<>())
                        .add(rs.getLong("product_type_id")))
                .list();
        if (typesByItem.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> grouping = groupingBaseKinds();
        Map<Long, PlainKind> plain = new LinkedHashMap<>();
        typesByItem.forEach((itemId, typeIds) -> {
            Set<Long> bases = new HashSet<>();
            typeIds.forEach(typeId -> bases.add(taxonomy.baseKindOf(typeId)));
            if (bases.size() == 1) {
                long base = bases.iterator().next();
                String name = grouping.get(base);
                if (name != null) {
                    plain.put(itemId, new PlainKind(base, name));
                }
            }
        });
        return plain;
    }

    /** Base kinds that have kinds under them, with their names. */
    private Map<Long, String> groupingBaseKinds() {
        return memo.remember(List.of("groupingBaseKinds"), () -> {
            Map<Long, String> names = new HashMap<>();
            jdbcClient.sql("""
                            SELECT DISTINCT base.id, base.name
                            FROM app.product_type AS kind
                            JOIN app.product_type AS base
                              ON base.id = kind.base_type_id
                            """)
                    .query((rs, row) -> names.put(rs.getLong("id"), rs.getString("name")))
                    .list();
            return Map.copyOf(names);
        });
    }

    @Override
    public Optional<BigDecimal> usualPerThousand(Market market, long baseKindId, String baseUnit) {
        if (!"g".equals(baseUnit) && !"ml".equals(baseUnit)) {
            return Optional.empty();
        }
        // Prices change with an import: kept as a plan's offers are.
        BigDecimal usual = memo.rememberOffers(
                List.of("baseKindUsualPrice", market.id(), baseKindId, baseUnit),
                () -> jdbcClient.sql("""
                                WITH product_price AS (
                                    -- Each product once, however many shops or
                                    -- formats price it; a kilogram or a litre.
                                    SELECT product.retailer_id,
                                           PERCENTILE_CONT(0.5) WITHIN GROUP (
                                               ORDER BY COALESCE(NULLIF(offer.regular_price, 0),
                                                                 offer.discounted_price)
                                                   * CASE WHEN product.quantity_value > 0
                                                          THEN 1000 / product.quantity_value
                                                          ELSE 1 END
                                           ) AS per_thousand
                                    FROM app.product_type AS kind
                                    JOIN app.retailer_product_type AS assignment
                                      ON assignment.product_type_id = kind.id
                                    JOIN app.retailer_product AS product
                                      ON product.id = assignment.retailer_product_id
                                    JOIN app.retailer AS chain
                                      ON chain.id = product.retailer_id
                                     AND chain.market_id = :marketId
                                    JOIN app.current_price_offer AS offer
                                      ON offer.retailer_product_id = product.id
                                    WHERE COALESCE(kind.base_type_id, kind.id) = :baseKindId
                                      AND COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price) > 0
                                      AND app.in_latest_price_list(
                                          product.retailer_id,
                                          offer.scope_key,
                                          offer.price_date,
                                          app.market_today(:marketId)
                                      )
                                      AND (
                                          (product.base_unit = :baseUnit AND product.quantity_value > 0)
                                          OR (:baseUnit = 'g'
                                              AND product.quantity_value IS NULL
                                              AND app.sold_by_the_kilogram(product.id))
                                      )
                                    GROUP BY product.retailer_id, product.id
                                ),
                                chain_price AS (
                                    -- A chain's own median: a chain with sixty
                                    -- kinds of fish does not decide alone.
                                    SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY per_thousand) AS price
                                    FROM product_price
                                    GROUP BY retailer_id
                                )
                                SELECT ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY price)::NUMERIC, 2)
                                FROM chain_price
                                HAVING COUNT(*) >= :minChains
                                """)
                        .param("marketId", market.id())
                        .param("baseKindId", baseKindId)
                        .param("baseUnit", baseUnit)
                        .param("minChains", MIN_CHAINS)
                        .query(BigDecimal.class)
                        .optional()
                        .orElse(BigDecimal.ZERO));
        return usual == null || usual.signum() <= 0 ? Optional.empty() : Optional.of(usual);
    }

    @Override
    public Map<Long, PackAmount> packAmounts(Collection<Long> retailerProductIds) {
        if (retailerProductIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PackAmount> amounts = new HashMap<>();
        jdbcClient.sql("""
                        SELECT product.id,
                               CASE WHEN product.base_unit IN ('g', 'ml') AND product.quantity_value > 0
                                    THEN product.quantity_value
                                    ELSE 1000
                               END AS amount,
                               CASE WHEN product.base_unit IN ('g', 'ml') AND product.quantity_value > 0
                                    THEN product.base_unit
                                    ELSE 'g'
                               END AS base_unit
                        FROM app.retailer_product AS product
                        WHERE product.id IN (:ids)
                          AND ((product.base_unit IN ('g', 'ml') AND product.quantity_value > 0)
                               OR (product.quantity_value IS NULL AND app.sold_by_the_kilogram(product.id)))
                        """)
                .param("ids", new ArrayList<>(retailerProductIds))
                .query((rs, row) -> amounts.put(rs.getLong("id"),
                        new PackAmount(rs.getBigDecimal("amount"), rs.getString("base_unit"))))
                .list();
        return amounts;
    }
}
