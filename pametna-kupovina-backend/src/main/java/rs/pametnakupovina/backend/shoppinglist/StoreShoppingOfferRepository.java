package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class StoreShoppingOfferRepository {

    private static final RowMapper<StoreItemOffer> ROW_MAPPER =
            (resultSet, rowNumber) -> new StoreItemOffer(
                    resultSet.getLong("store_id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getString("store_format_code"),
                    resultSet.getString("store_format_name"),
                    resultSet.getString("store_name"),
                    resultSet.getString("address"),
                    resultSet.getString("city"),
                    resultSet.getDouble("latitude"),
                    resultSet.getDouble("longitude"),
                    resultSet.getLong("item_id"),
                    resultSet.getString("requested_name"),
                    resultSet.getBigDecimal("requested_quantity"),
                    ShoppingItemRule.valueOf(
                            resultSet.getString("matching_rule")
                    ),
                    ShoppingItemMatchingStatus.valueOf(
                            resultSet.getString("matching_status")
                    ),
                    resultSet.getObject(
                            "retailer_product_id",
                            Long.class
                    ),
                    resultSet.getObject(
                            "canonical_product_id",
                            Long.class
                    ),
                    resultSet.getString("product_name"),
                    resultSet.getString("product_brand"),
                    resultSet.getString("product_barcode"),
                    resultSet.getObject("price_date", LocalDate.class),
                    resultSet.getBigDecimal("regular_price"),
                    resultSet.getBigDecimal("discounted_price"),
                    resultSet.getBigDecimal("effective_price"),
                    resultSet.getBigDecimal("line_total"),
                    resultSet.getString("price_scope")
            );

    private final JdbcClient jdbcClient;

    public StoreShoppingOfferRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<StoreItemOffer> findOffers(
            Long listId,
            List<Long> storeIds,
            LocalDate asOfDate
    ) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }

        return jdbcClient.sql("""
                        SELECT store.id AS store_id,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               format.code AS store_format_code,
                               format.name AS store_format_name,
                               store.name AS store_name,
                               store.address,
                               store.city,
                               ST_Y(store.location::geometry) AS latitude,
                               ST_X(store.location::geometry) AS longitude,
                               item.id AS item_id,
                               item.name AS requested_name,
                               item.quantity AS requested_quantity,
                               item.matching_rule,
                               item.matching_status,
                               offer.retailer_product_id,
                               offer.canonical_product_id,
                               offer.product_name,
                               offer.product_brand,
                               offer.product_barcode,
                               offer.price_date,
                               offer.regular_price,
                               offer.discounted_price,
                               offer.effective_price,
                               ROUND(
                                   offer.effective_price * item.quantity,
                                   2
                               ) AS line_total,
                               offer.price_scope
                        FROM app.store AS store
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        JOIN app.store_format AS format
                          ON format.id = store.store_format_id
                        CROSS JOIN app.shopping_list_item AS item
                        LEFT JOIN LATERAL (
                            SELECT product.id AS retailer_product_id,
                                   product.canonical_product_id,
                                   product.name AS product_name,
                                   COALESCE(
                                       canonical.brand,
                                       product.brand
                                   ) AS product_brand,
                                   product.barcode AS product_barcode,
                                   selected_price.price_date,
                                   selected_price.regular_price,
                                   selected_price.discounted_price,
                                   selected_price.effective_price,
                                   selected_price.price_scope
                            FROM app.retailer_product AS product
                            LEFT JOIN app.canonical_product AS canonical
                              ON canonical.id =
                                  product.canonical_product_id
                            JOIN LATERAL (
                                SELECT priced.price_date,
                                       priced.regular_price,
                                       priced.discounted_price,
                                       priced.effective_price,
                                       priced.price_scope
                                FROM (
                                    SELECT observation.price_date,
                                           observation.regular_price,
                                           observation.discounted_price,
                                           COALESCE(
                                               CASE
                                                   WHEN observation.discounted_price
                                                            IS NOT NULL
                                                    AND (
                                                        observation.discount_start
                                                            IS NULL
                                                        OR observation.discount_start
                                                            <= :asOfDate
                                                    )
                                                    AND (
                                                        observation.discount_end
                                                            IS NULL
                                                        OR observation.discount_end
                                                            >= :asOfDate
                                                    )
                                                   THEN observation.discounted_price
                                               END,
                                               observation.regular_price
                                           ) AS effective_price,
                                           CASE
                                               WHEN observation.store_id = store.id
                                                   THEN 'STORE'
                                               WHEN observation.retailer_format_name
                                                        IS NOT NULL
                                                AND BTRIM(
                                                    observation.retailer_format_name
                                                ) <> ''
                                                   THEN 'STORE_FORMAT'
                                               ELSE 'RETAILER'
                                           END AS price_scope,
                                           CASE
                                               WHEN observation.store_id = store.id
                                                   THEN 1
                                               WHEN observation.store_id IS NULL
                                                AND observation.retailer_format_name
                                                        IS NOT NULL
                                                AND BTRIM(
                                                    observation.retailer_format_name
                                                ) <> ''
                                                AND (
                                                    LOWER(BTRIM(
                                                        observation.retailer_format_name
                                                    )) = LOWER(format.name)
                                                    OR LOWER(BTRIM(
                                                        observation.retailer_format_name
                                                    )) = LOWER(format.code)
                                                )
                                                   THEN 2
                                               ELSE 3
                                           END AS scope_priority,
                                           observation.id
                                    FROM app.price_observation AS observation
                                    WHERE observation.retailer_product_id =
                                            product.id
                                      AND observation.price_date <= :asOfDate
                                      AND (
                                          observation.store_id = store.id
                                          OR (
                                              observation.store_id IS NULL
                                              AND observation.retailer_format_name
                                                    IS NOT NULL
                                              AND BTRIM(
                                                  observation.retailer_format_name
                                              ) <> ''
                                              AND (
                                                  LOWER(BTRIM(
                                                      observation.retailer_format_name
                                                  )) = LOWER(format.name)
                                                  OR LOWER(BTRIM(
                                                      observation.retailer_format_name
                                                  )) = LOWER(format.code)
                                              )
                                          )
                                          OR (
                                              observation.store_id IS NULL
                                              AND NULLIF(BTRIM(
                                                  observation.retailer_format_name
                                              ), '') IS NULL
                                          )
                                      )
                                ) AS priced
                                WHERE priced.effective_price IS NOT NULL
                                ORDER BY priced.scope_priority ASC,
                                         priced.price_date DESC,
                                         priced.id DESC
                                LIMIT 1
                            ) AS selected_price ON TRUE
                            WHERE product.retailer_id = store.retailer_id
                              AND (
                                  (
                                      item.matching_rule = 'EXACT_PRODUCT'
                                      AND item.matching_status IN (
                                          'AUTO_MATCHED',
                                          'CONFIRMED'
                                      )
                                      AND (
                                          product.canonical_product_id =
                                              item.matched_canonical_product_id
                                          OR (
                                              item.barcode IS NOT NULL
                                              AND product.barcode = item.barcode
                                          )
                                      )
                                  )
                                  OR
                                  (
                                      item.matching_rule = 'FLEXIBLE_CATEGORY'
                                      AND (
                                          COALESCE(
                                              canonical.normalized_name,
                                              product.normalized_name,
                                              LOWER(product.name)
                                          ) LIKE '%'
                                              || item.flexible_category_normalized
                                              || '%'
                                          OR public.similarity(
                                              COALESCE(
                                                  canonical.normalized_name,
                                                  product.normalized_name,
                                                  LOWER(product.name)
                                              ),
                                              item.flexible_category_normalized
                                          ) >= 0.3500
                                      )
                                      AND (
                                          item.required_brand IS NULL
                                          OR LOWER(BTRIM(COALESCE(
                                              canonical.brand,
                                              product.brand,
                                              ''
                                          ))) = LOWER(BTRIM(
                                              item.required_brand
                                          ))
                                      )
                                      AND (
                                          item.required_base_unit IS NULL
                                          OR COALESCE(
                                              canonical.base_unit,
                                              product.base_unit
                                          ) = item.required_base_unit
                                      )
                                      AND (
                                          item.min_package_quantity IS NULL
                                          OR COALESCE(
                                              canonical.quantity_value,
                                              product.quantity_value
                                          ) >= item.min_package_quantity
                                      )
                                      AND (
                                          item.max_package_quantity IS NULL
                                          OR COALESCE(
                                              canonical.quantity_value,
                                              product.quantity_value
                                          ) <= item.max_package_quantity
                                      )
                                  )
                              )
                            ORDER BY selected_price.effective_price ASC,
                                     product.id ASC
                            LIMIT 1
                        ) AS offer ON TRUE
                        WHERE item.shopping_list_id = :listId
                          AND store.id IN (:storeIds)
                        ORDER BY store.id ASC,
                                 item.created_at ASC,
                                 item.id ASC
                        """)
                .param("asOfDate", asOfDate)
                .param("listId", listId)
                .param("storeIds", storeIds)
                .query(ROW_MAPPER)
                .list();
    }
}
