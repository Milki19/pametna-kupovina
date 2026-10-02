package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class CanonicalProductSearchRepository {

    private static final RowMapper<CanonicalProductSearchRow>
            SEARCH_ROW_MAPPER = (resultSet, rowNumber) ->
            new CanonicalProductSearchRow(
                    resultSet.getLong("product_family_id"),
                    resultSet.getObject(
                            "canonical_product_id",
                            Long.class
                    ),
                    resultSet.getString("name"),
                    resultSet.getString("brand"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity_value"),
                    resultSet.getString("base_unit"),
                    resultSet.getString("category_code"),
                    resultSet.getString("category_name"),
                    resultSet.getInt("variant_count"),
                    resultSet.getBigDecimal("name_similarity"),
                    resultSet.getBoolean("exact_ean_match"),
                    resultSet.getBoolean("has_usable_price"),
                    resultSet.getObject("product_type_id", Long.class),
                    resultSet.getInt("package_count")
            );

    private static final RowMapper<ProductAvailabilityRow>
            AVAILABILITY_ROW_MAPPER = (resultSet, rowNumber) ->
            new ProductAvailabilityRow(
                    resultSet.getLong("product_family_id"),
                    new ProductRetailerAvailability(
                            resultSet.getString("retailer_code"),
                            resultSet.getString("retailer_name"),
                            resultSet.getObject(
                                    "latest_price_date",
                                    LocalDate.class
                            ),
                            resultSet.getInt("store_count"),
                            resultSet.getInt("format_count"),
                            resultSet.getBigDecimal(
                                    "minimum_effective_price"
                            ),
                            resultSet.getBoolean("price_needs_check"),
                            resultSet.getBigDecimal("sale_regular_price"),
                            resultSet.getObject(
                                    "sale_end_date",
                                    LocalDate.class
                            )
                    )
            );

    private final JdbcClient jdbcClient;
    private final int maxPriceAgeDays;

    public CanonicalProductSearchRepository(JdbcClient jdbcClient,
            @org.springframework.beans.factory.annotation.Value("${shopping.optimization.max-price-age-days:30}") int maxPriceAgeDays) {
        this.jdbcClient = jdbcClient;
        if(maxPriceAgeDays < 0) throw new IllegalArgumentException("Starost cena ne može biti negativna.");
        this.maxPriceAgeDays = maxPriceAgeDays;
    }

    public List<CanonicalProductSearchRow> findCandidates(
            String normalizedQuery,
            String validEan
    ) {
        return jdbcClient.sql("""
                        WITH matching_brands AS MATERIALIZED (
                            SELECT brand_id, normalized_alias
                            FROM app.brand_alias
                            WHERE POSITION(' ' || normalized_alias || ' '
                                IN ' ' || ? || ' ') > 0
                        )
                        SELECT family.id AS product_family_id,
                               representative.id AS canonical_product_id,
                               -- Composed the way Cenoteka writes it (V84).
                               COALESCE(family.composed_name, family.display_name) AS name,
                               brand.display_name AS brand,
                               representative.barcode,
                               family.quantity_value,
                               family.base_unit,
                               category.code AS category_code,
                               category.name AS category_name,
                               family.product_type_id,
                               -- Only rows stating the family's own size: chains
                               -- that describe one barcode differently must not
                               -- turn 45 g into "3 × 15 g".
                               COALESCE((
                                   SELECT MAX(member_product.package_count)
                                   FROM app.retailer_product AS member_product
                                   WHERE member_product.product_family_id = family.id
                                     AND member_product.quantity_value =
                                         family.quantity_value
                               ), 1) AS package_count,
                               EXISTS (
                                   SELECT 1 FROM app.product_retailer_presence AS presence
                                   JOIN app.retailer AS presence_retailer
                                     ON presence_retailer.id = presence.retailer_id
                                   JOIN app.market AS presence_market
                                     ON presence_market.id = presence_retailer.market_id
                                   WHERE presence.product_family_id=family.id
                                     AND presence.current_offer_count > 0
                                     AND presence.minimum_effective_price > 0
                                     -- "Today" is the chain's market's today.
                                     AND presence.latest_price_date BETWEEN
                                         (CURRENT_TIMESTAMP AT TIME ZONE presence_market.time_zone)::date - ?
                                         AND (CURRENT_TIMESTAMP AT TIME ZONE presence_market.time_zone)::date
                               ) AS has_usable_price,
                               GREATEST(
                                   1,
                                   (
                                       SELECT COUNT(*)
                                       FROM app.product_family_member AS member
                                       WHERE member.family_id = family.id
                                   )
                               )::INTEGER AS variant_count,
                               CASE
                                   WHEN ? IS NOT NULL
                                       AND EXISTS (
                                           SELECT 1
                                           FROM app.product_family_member AS member
                                           JOIN app.canonical_product AS canonical
                                             ON canonical.id =
                                                 member.canonical_product_id
                                           WHERE member.family_id = family.id
                                             AND canonical.barcode = ?
                                       )
                                       THEN 1.0000::NUMERIC
                                   ELSE ROUND(
                                       public.similarity(
                                           family.normalized_name,
                                           ?
                                       )::NUMERIC,
                                       4
                                   )
                               END AS name_similarity,
                               (
                                   ? IS NOT NULL
                                   AND EXISTS (
                                       SELECT 1
                                       FROM app.product_family_member AS member
                                       JOIN app.canonical_product AS canonical
                                         ON canonical.id =
                                             member.canonical_product_id
                                       WHERE member.family_id = family.id
                                         AND canonical.barcode = ?
                                   )
                               ) AS exact_ean_match
                        FROM app.product_family AS family
                        LEFT JOIN app.brand AS brand
                          ON brand.id = family.brand_id
                        LEFT JOIN app.product_category AS category
                          ON category.id = family.product_category_id
                        LEFT JOIN LATERAL (
                            SELECT canonical.id,
                                   canonical.barcode
                            FROM app.product_family_member AS member
                            JOIN app.canonical_product AS canonical
                              ON canonical.id = member.canonical_product_id
                            WHERE member.family_id = family.id
                            ORDER BY CASE
                                         WHEN ? IS NOT NULL
                                             AND canonical.barcode = ?
                                             THEN 1
                                         ELSE 2
                                     END,
                                     EXISTS (
                                         SELECT 1
                                         FROM app.retailer_product AS product
                                         JOIN app.current_price_offer AS offer
                                           ON offer.retailer_product_id =
                                               product.id
                                         WHERE product.canonical_product_id =
                                             canonical.id
                                     ) DESC,
                                     canonical.id
                            LIMIT 1
                        ) AS representative ON TRUE
                        WHERE family.review_status <> 'REJECTED'
                          AND (
                              (
                                  ? IS NOT NULL
                                  AND EXISTS (
                                      SELECT 1
                                      FROM app.product_family_member AS member
                                      JOIN app.canonical_product AS canonical
                                        ON canonical.id =
                                            member.canonical_product_id
                                      WHERE member.family_id = family.id
                                        AND canonical.barcode = ?
                                  )
                              )
                              OR family.normalized_name
                                  OPERATOR(public.%) ?
                              OR family.normalized_name LIKE
                                  '%' || ? || '%'
                              OR EXISTS (
                                  SELECT 1 FROM matching_brands AS alias
                                  WHERE alias.brand_id = family.brand_id
                                    AND NOT EXISTS (
                                        SELECT 1 FROM unnest(string_to_array(?, ' ')) AS token(value)
                                        WHERE POSITION(token.value IN family.normalized_name) = 0
                                          AND POSITION(token.value IN alias.normalized_alias) = 0
                                    )
                              )
                          )
                        ORDER BY name_similarity DESC,
                                 family.display_name ASC,
                                 family.id ASC
                        """)
                .param(1, normalizedQuery)
                .param(2, maxPriceAgeDays)
                .param(3, validEan, Types.VARCHAR)
                .param(4, validEan, Types.VARCHAR)
                .param(5, normalizedQuery)
                .param(6, validEan, Types.VARCHAR)
                .param(7, validEan, Types.VARCHAR)
                .param(8, validEan, Types.VARCHAR)
                .param(9, validEan, Types.VARCHAR)
                .param(10, validEan, Types.VARCHAR)
                .param(11, validEan, Types.VARCHAR)
                .param(12, normalizedQuery)
                .param(13, normalizedQuery)
                .param(14, normalizedQuery)
                .query(SEARCH_ROW_MAPPER)
                .list();
    }

    public List<ProductAvailabilityRow> findAvailability(
            List<Long> productFamilyIds
    ) {
        if (productFamilyIds.isEmpty()) {
            return List.of();
        }

        return jdbcClient.sql("""
                        SELECT presence.product_family_id,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               presence.latest_price_date,
                               presence.store_count,
                               presence.format_count,
                               COALESCE(
                                   best.price,
                                   presence.minimum_effective_price
                               ) AS minimum_effective_price,
                               -- Every offer of the chain is far below the
                               -- other chains' price (V72).
                               COALESCE(best.needs_check, FALSE)
                                   AS price_needs_check,
                               -- The chain's lowest price is a sale today:
                               -- what it costs otherwise and until when.
                               best.regular_price AS sale_regular_price,
                               best.discount_end AS sale_end_date
                        FROM app.product_retailer_presence AS presence
                        JOIN app.retailer AS retailer
                          ON retailer.id = presence.retailer_id
                        JOIN app.market AS market
                          ON market.id = retailer.market_id
                        -- A price is compared with what the product costs in
                        -- the chain's own market and currency.
                        LEFT JOIN app.product_family_typical_price
                            AS typical_price
                          ON typical_price.product_family_id =
                              presence.product_family_id
                         AND typical_price.market_id = retailer.market_id
                        -- The chain's lowest price today, one that needs no
                        -- checking first. A sale counts only while it lasts
                        -- (V115). Only a product several chains price has a
                        -- typical price to check against.
                        LEFT JOIN LATERAL (
                            SELECT priced.price,
                                   priced.needs_check,
                                   CASE WHEN priced.sale IS NOT NULL
                                       THEN priced.regular_price END
                                       AS regular_price,
                                   CASE WHEN priced.sale IS NOT NULL
                                       THEN priced.discount_end END
                                       AS discount_end
                            FROM (
                                SELECT app.effective_price(
                                           offer.regular_price,
                                           offer.discounted_price,
                                           offer.discount_start,
                                           offer.discount_end,
                                           (NOW() AT TIME ZONE market.time_zone)::DATE
                                       ) AS price,
                                       app.sale_price(
                                           offer.regular_price,
                                           offer.discounted_price,
                                           offer.discount_start,
                                           offer.discount_end,
                                           (NOW() AT TIME ZONE market.time_zone)::DATE
                                       ) AS sale,
                                       app.price_needs_check(
                                           offer.regular_price,
                                           offer.discounted_price,
                                           typical_price.typical_price
                                       ) AS needs_check,
                                       offer.regular_price,
                                       offer.discount_end
                                FROM app.retailer_product AS product
                                JOIN app.current_price_offer AS offer
                                  ON offer.retailer_product_id = product.id
                                WHERE product.product_family_id =
                                      presence.product_family_id
                                  AND product.retailer_id = presence.retailer_id
                                  AND app.in_latest_price_list(
                                      product.retailer_id,
                                      offer.scope_key,
                                      offer.price_date,
                                      (NOW() AT TIME ZONE market.time_zone)::DATE
                                  )
                                  AND product.package_count =
                                      app.family_base_package_count(product.product_family_id)
                            ) AS priced
                            WHERE priced.price > 0
                            ORDER BY priced.needs_check,
                                     priced.price,
                                     priced.sale IS NULL
                            LIMIT 1
                        ) AS best ON TRUE
                        WHERE presence.product_family_id IN (:familyIds)
                        ORDER BY presence.product_family_id,
                                 retailer.name,
                                 retailer.code
                        """)
                .param("familyIds", productFamilyIds)
                .query(AVAILABILITY_ROW_MAPPER)
                .list();
    }

    public Map<Long, Integer> findProductTypePriorities(
            long shoppingIntentId
    ) {
        return jdbcClient.sql("""
                        SELECT product_type_id, match_priority
                        FROM app.shopping_intent_product_type
                        WHERE shopping_intent_id = ?
                          AND enabled_by_default = TRUE
                        """)
                .param(1, shoppingIntentId)
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getLong("product_type_id"),
                        resultSet.getInt("match_priority")
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }

    /**
     * Koliko je daleko najbliža prodavnica (do 20 km) nekog lanca koji danas
     * ima cenu za proizvod, po proizvodu. Jedan upit: lanci u blizini idu
     * preko prostornog indeksa, proizvodi preko prisustva po lancu.
     * ponytail: blizina lanca, ne tačne prodavnice — plan posle proverava
     * baš tu prodavnicu; dovoljno za redosled pretrage.
     */
    public Map<Long, Double> nearestStoreMeters(
            List<Long> familyIds,
            double latitude,
            double longitude
    ) {
        if (familyIds.isEmpty()) {
            return Map.of();
        }

        return jdbcClient.sql("""
                        WITH here AS (
                            SELECT ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                                       AS location
                        ),
                        nearest_chain AS (
                            SELECT store.retailer_id,
                                   MIN(ST_Distance(store.location, here.location)) AS meters
                            FROM app.store AS store
                            CROSS JOIN here
                            WHERE store.active = TRUE
                              AND store.pricing_eligible = TRUE
                              AND store.location IS NOT NULL
                              AND store.geocoding_status IN ('AUTO_VERIFIED', 'MANUALLY_VERIFIED')
                              AND ST_DWithin(store.location, here.location, 20000)
                            GROUP BY store.retailer_id
                        )
                        SELECT presence.product_family_id,
                               MIN(nearest_chain.meters) AS meters
                        FROM app.product_retailer_presence AS presence
                        JOIN nearest_chain
                          ON nearest_chain.retailer_id = presence.retailer_id
                        WHERE presence.product_family_id
                                  = ANY(STRING_TO_ARRAY(:ids, ',')::BIGINT[])
                          AND presence.current_offer_count > 0
                        GROUP BY presence.product_family_id
                        """)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("ids", familyIds.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")))
                .query((row, number) -> Map.entry(
                        row.getLong("product_family_id"),
                        row.getDouble("meters")
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Which of these products some chain sells on sale today (V117 list). */
    public java.util.Set<Long> findFamiliesOnSale(List<Long> familyIds) {
        if (familyIds.isEmpty()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(jdbcClient.sql("""
                        SELECT DISTINCT sale.product_family_id
                        FROM app.current_sale_list AS sale
                        WHERE sale.product_family_id
                                  = ANY(STRING_TO_ARRAY(:ids, ',')::BIGINT[])
                        """)
                .param("ids", familyIds.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")))
                .query(Long.class)
                .list());
    }

    public java.util.Map<Long, List<String>> findKnownRetailers(List<Long> familyIds) {
        if (familyIds.isEmpty()) return java.util.Map.of();
        return jdbcClient.sql("""
                SELECT DISTINCT p.product_family_id, r.name
                FROM app.retailer_product p JOIN app.retailer r ON r.id=p.retailer_id
                WHERE p.product_family_id IN (:ids)
                ORDER BY p.product_family_id,r.name
                """).param("ids",familyIds)
                .query((rs,n) -> java.util.Map.entry(rs.getLong(1),rs.getString(2))).list().stream()
                .collect(java.util.stream.Collectors.groupingBy(java.util.Map.Entry::getKey,
                        java.util.stream.Collectors.mapping(java.util.Map.Entry::getValue,java.util.stream.Collectors.toList())));
    }

    record ProductAvailabilityRow(
            Long productFamilyId,
            ProductRetailerAvailability availability
    ) {
    }
}
