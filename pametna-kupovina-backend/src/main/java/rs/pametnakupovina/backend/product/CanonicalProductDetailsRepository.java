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
                    resultSet.getString("base_unit")
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
                    resultSet.getString("price_scope")
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
                               name,
                               brand,
                               barcode,
                               quantity_value,
                               base_unit
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
                                                    IS NOT NULL
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
                                       observation.regular_price
                                   ) AS effective_price,
                                   observation.unit_price,
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
                                                observation.id DESC
                                   ) AS rank_number
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
                            WHERE retailer_product.canonical_product_id =
                                  :productId
                              AND observation.price_date <= :asOfDate
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
                               price_scope
                        FROM ranked
                        WHERE rank_number = 1
                          AND effective_price IS NOT NULL
                        ORDER BY effective_price ASC,
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
                                                IS NOT NULL
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
                                   observation.regular_price
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
                        WHERE retailer_product.canonical_product_id =
                              :productId
                          AND observation.price_date <= :asOfDate
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
            String baseUnit
    ) {
    }
}
