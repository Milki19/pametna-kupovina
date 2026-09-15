package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class CanonicalProductDetailsRepository {

    private static final RowMapper<CanonicalProductSummary> PRODUCT_MAPPER =
            (resultSet, rowNumber) -> new CanonicalProductSummary(
                    resultSet.getLong("canonical_product_id"),
                    resultSet.getString("name"),
                    resultSet.getString("brand"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity_value"),
                    resultSet.getString("base_unit"),
                    resultSet.getInt("package_count")
            );

    private static final RowMapper<CanonicalProductOffer> OFFER_MAPPER =
            (resultSet, rowNumber) -> new CanonicalProductOffer(
                    resultSet.getLong("retailer_product_id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getObject("store_id", Long.class),
                    resultSet.getString("store_name"),
                    resultSet.getString("store_format_code"),
                    resultSet.getString("store_format_name"),
                    resultSet.getObject("price_date", LocalDate.class),
                    resultSet.getBigDecimal("regular_price"),
                    resultSet.getBigDecimal("discounted_price"),
                    resultSet.getBigDecimal("effective_price"),
                    resultSet.getBigDecimal("unit_price"),
                    resultSet.getString("price_scope"),
                    resultSet.getBoolean("price_needs_check"),
                    resultSet.getInt("package_count")
            );

    private static final RowMapper<CanonicalProductPricePoint> HISTORY_MAPPER =
            (resultSet, rowNumber) -> new CanonicalProductPricePoint(
                    resultSet.getLong("retailer_product_id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getObject("store_id", Long.class),
                    resultSet.getString("store_name"),
                    resultSet.getString("store_format_name"),
                    resultSet.getObject("price_date", LocalDate.class),
                    resultSet.getBigDecimal("regular_price"),
                    resultSet.getBigDecimal("discounted_price"),
                    resultSet.getBigDecimal("effective_price"),
                    resultSet.getString("price_scope")
            );

    private final JdbcClient jdbcClient;

    public CanonicalProductDetailsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CanonicalProductSummary> findProduct(Long productId) {
        return jdbcClient.sql("""
                        SELECT id AS canonical_product_id,
                               -- Without METRO's suffix and stock codes (V81).
                               app.clean_product_name(name) AS name,
                               brand,
                               barcode,
                               quantity_value,
                               base_unit,
                               COALESCE((
                                   SELECT MAX(product.package_count)
                                   FROM app.retailer_product AS product
                                   WHERE product.canonical_product_id =
                                         canonical_product.id
                                     AND product.quantity_value =
                                         canonical_product.quantity_value
                               ), 1) AS package_count
                        FROM app.canonical_product
                        WHERE id = ?
                        """)
                .param(1, productId)
                .query(PRODUCT_MAPPER)
                .optional();
    }

    public List<CanonicalProductOffer> findLatestOffers(
            Long productId,
            LocalDate asOfDate
    ) {
        return jdbcClient.sql("""
                        WITH ranked AS (
                            SELECT retailer_product.id AS retailer_product_id,
                                   retailer_product.package_count,
                                   retailer.code AS retailer_code,
                                   retailer.name AS retailer_name,
                                   observation.store_id,
                                   store.name AS store_name,
                                   store_format.code AS store_format_code,
                                   COALESCE(
                                       store_format.name,
                                       NULLIF(BTRIM(
                                           observation.retailer_format_name
                                       ), '')
                                   ) AS store_format_name,
                                   observation.price_date,
                                   observation.regular_price,
                                   observation.discounted_price,
                                   COALESCE(
                                       CASE
                                           WHEN observation.discounted_price
                                                    > 0
                                            AND (
                                                observation.discount_start IS NULL
                                                OR observation.discount_start <= :asOfDate
                                            )
                                            AND (
                                                observation.discount_end IS NULL
                                                OR observation.discount_end >= :asOfDate
                                            )
                                           THEN observation.discounted_price
                                       END,
                                       CASE
                                           WHEN observation.regular_price > 0
                                               THEN observation.regular_price
                                       END
                                   ) AS effective_price,
                                   observation.unit_price,
                                   app.price_needs_check(
                                       observation.regular_price,
                                       observation.discounted_price,
                                       typical_price.typical_price
                                   ) AS price_needs_check,
                                   CASE
                                       WHEN observation.store_id IS NOT NULL
                                           THEN 'STORE'
                                       WHEN NULLIF(BTRIM(
                                           observation.retailer_format_name
                                       ), '') IS NOT NULL
                                           THEN 'STORE_FORMAT'
                                       ELSE 'RETAILER'
                                   END AS price_scope,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY retailer_product.retailer_id,
                                                    observation.store_id,
                                                    COALESCE(
                                                        NULLIF(BTRIM(
                                                            observation.retailer_format_name
                                                        ), ''),
                                                        ''
                                                    )
                                       ORDER BY observation.price_date DESC,
                                                observation.source_priority ASC,
                                                observation.id DESC
                                   ) AS rank_number
                            FROM app.retailer_product AS retailer_product
                            JOIN app.retailer AS retailer
                              ON retailer.id = retailer_product.retailer_id
                            JOIN (
                                SELECT current_offer.id,
                                       current_offer.retailer_product_id,
                                       current_offer.retailer_format_name,
                                       current_offer.store_id,
                                       current_offer.price_date,
                                       current_offer.regular_price,
                                       current_offer.unit_price,
                                       current_offer.discounted_price,
                                       current_offer.discount_start,
                                       current_offer.discount_end,
                                       0 AS source_priority
                                FROM app.current_price_offer AS current_offer
                                UNION ALL
                                SELECT history.id,
                                       history.retailer_product_id,
                                       history.retailer_format_name,
                                       history.store_id,
                                       history.price_date,
                                       history.regular_price,
                                       history.unit_price,
                                       history.discounted_price,
                                       history.discount_start,
                                       history.discount_end,
                                       1 AS source_priority
                                FROM app.price_observation AS history
                                WHERE NOT EXISTS (
                                    SELECT 1
                                    FROM app.current_price_offer
                                        AS available_current
                                    WHERE available_current.retailer_product_id =
                                          history.retailer_product_id
                                      AND available_current.price_date <=
                                          :asOfDate
                                      AND available_current.scope_key = CASE
                                          WHEN history.store_id IS NOT NULL
                                              THEN 'STORE:' ||
                                                   history.store_id::TEXT
                                          WHEN NULLIF(BTRIM(
                                              history.retailer_format_name
                                          ), '') IS NOT NULL
                                              THEN 'STORE_FORMAT:' ||
                                                   LOWER(BTRIM(
                                                       history.retailer_format_name
                                                   ))
                                          ELSE 'RETAILER'
                                      END
                                )
                            ) AS observation
                              ON observation.retailer_product_id =
                                  retailer_product.id
                            LEFT JOIN app.product_family_typical_price
                                AS typical_price
                              ON typical_price.product_family_id =
                                  retailer_product.product_family_id
                            LEFT JOIN app.store AS store
                              ON store.id = observation.store_id
                            LEFT JOIN app.store_format AS store_format
                              ON store_format.retailer_id =
                                  retailer_product.retailer_id
                             AND (
                                 (
                                     store.store_format_id IS NOT NULL
                                     AND store_format.id = store.store_format_id
                                 )
                                 OR (
                                     store.store_format_id IS NULL
                                     AND (
                                         LOWER(store_format.code) = LOWER(BTRIM(
                                             observation.retailer_format_name
                                         ))
                                         OR LOWER(store_format.name) = LOWER(BTRIM(
                                             observation.retailer_format_name
                                         ))
                                     )
                                 )
                             )
                            -- A merged product sells under several
                            -- barcodes; every chain carrying one counts.
                            WHERE (
                                retailer_product.canonical_product_id =
                                    :productId
                                OR retailer_product.product_family_id IN (
                                    SELECT member.family_id
                                    FROM app.product_family_member AS member
                                    WHERE member.canonical_product_id =
                                          :productId
                                )
                            )
                              AND observation.price_date <= :asOfDate
                              -- A chain's price for a product it no longer
                              -- lists is history, not an offer (V75).
                              AND app.in_latest_price_list(
                                  retailer_product.retailer_id,
                                  CASE
                                      WHEN observation.store_id IS NOT NULL
                                          THEN 'STORE:' || observation.store_id::TEXT
                                      WHEN NULLIF(BTRIM(observation.retailer_format_name), '') IS NOT NULL
                                          THEN 'STORE_FORMAT:' || LOWER(BTRIM(observation.retailer_format_name))
                                      ELSE 'RETAILER'
                                  END,
                                  observation.price_date,
                                  :asOfDate
                              )
                        )
                        SELECT retailer_product_id,
                               retailer_code,
                               retailer_name,
                               store_id,
                               store_name,
                               store_format_code,
                               store_format_name,
                               price_date,
                               regular_price,
                               discounted_price,
                               effective_price,
                               unit_price,
                               price_scope,
                               price_needs_check,
                               package_count
                        FROM ranked
                        WHERE rank_number = 1
                          AND effective_price > 0
                        -- A price to be checked is never the cheapest.
                        ORDER BY price_needs_check ASC,
                                 effective_price ASC,
                                 retailer_name ASC,
                                 price_scope ASC
                        """)
                .param("productId", productId)
                .param("asOfDate", asOfDate)
                .query(OFFER_MAPPER)
                .list();
    }

    public List<CanonicalProductPricePoint> findPriceHistory(
            Long productId,
            LocalDate asOfDate,
            int limit
    ) {
        return jdbcClient.sql("""
                        SELECT retailer_product.id AS retailer_product_id,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               observation.store_id,
                               store.name AS store_name,
                               COALESCE(
                                   store_format.name,
                                   NULLIF(BTRIM(
                                       observation.retailer_format_name
                                   ), '')
                               ) AS store_format_name,
                               observation.price_date,
                               observation.regular_price,
                               observation.discounted_price,
                               COALESCE(
                                   CASE
                                       WHEN observation.discounted_price
                                                > 0
                                        AND (
                                            observation.discount_start IS NULL
                                            OR observation.discount_start <=
                                                observation.price_date
                                        )
                                        AND (
                                            observation.discount_end IS NULL
                                            OR observation.discount_end >=
                                                observation.price_date
                                        )
                                       THEN observation.discounted_price
                                   END,
                                   CASE
                                       WHEN observation.regular_price > 0
                                           THEN observation.regular_price
                                   END
                               ) AS effective_price,
                               CASE
                                   WHEN observation.store_id IS NOT NULL
                                       THEN 'STORE'
                                   WHEN NULLIF(BTRIM(
                                       observation.retailer_format_name
                                   ), '') IS NOT NULL
                                       THEN 'STORE_FORMAT'
                                   ELSE 'RETAILER'
                               END AS price_scope
                        FROM app.retailer_product AS retailer_product
                        JOIN app.retailer AS retailer
                          ON retailer.id = retailer_product.retailer_id
                        JOIN app.price_observation AS observation
                          ON observation.retailer_product_id =
                              retailer_product.id
                        LEFT JOIN app.store AS store
                          ON store.id = observation.store_id
                        LEFT JOIN app.store_format AS store_format
                          ON store_format.retailer_id =
                              retailer_product.retailer_id
                         AND (
                             (
                                 store.store_format_id IS NOT NULL
                                 AND store_format.id = store.store_format_id
                             )
                             OR (
                                 store.store_format_id IS NULL
                                 AND (
                                     LOWER(store_format.code) = LOWER(BTRIM(
                                         observation.retailer_format_name
                                     ))
                                     OR LOWER(store_format.name) = LOWER(BTRIM(
                                         observation.retailer_format_name
                                     ))
                                 )
                             )
                         )
                        WHERE (
                            retailer_product.canonical_product_id = :productId
                            OR retailer_product.product_family_id IN (
                                SELECT member.family_id
                                FROM app.product_family_member AS member
                                WHERE member.canonical_product_id = :productId
                            )
                        )
                          AND observation.price_date <= :asOfDate
                          AND (
                              observation.discounted_price > 0
                              OR observation.regular_price > 0
                          )
                        ORDER BY observation.price_date DESC,
                                 observation.id DESC
                        LIMIT :historyLimit
                        """)
                .param("productId", productId)
                .param("asOfDate", asOfDate)
                .param("historyLimit", limit)
                .query(HISTORY_MAPPER)
                .list();
    }

    record CanonicalProductSummary(
            Long id,
            String name,
            String brand,
            String barcode,
            java.math.BigDecimal quantityValue,
            String baseUnit,
            int packageCount
    ) {

        CanonicalProductSummary(
                Long id,
                String name,
                String brand,
                String barcode,
                java.math.BigDecimal quantityValue,
                String baseUnit
        ) {
            this(id, name, brand, barcode, quantityValue, baseUnit, 1);
        }
    }
}
