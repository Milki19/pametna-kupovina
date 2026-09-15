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
                    resultSet.getString("price_scope"),
                    new PurchaseQuantity(resultSet.getBigDecimal("packages"),
                            resultSet.getBigDecimal("package_size"), resultSet.getString("package_unit"),
                            resultSet.getBigDecimal("target_amount"), resultSet.getBigDecimal("supplied_amount"),
                            resultSet.getBigDecimal("extra_amount"), resultSet.getBigDecimal("unit_price"))
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
        return findOffers(listId, storeIds, asOfDate, false);
    }

    private List<StoreItemOffer> findOffers(
            Long listId,
            List<Long> storeIds,
            LocalDate asOfDate,
            boolean includeUnlocated
    ) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }

        return jdbcClient.sql("""
                        -- Which products can answer each item in each chain, worked
                        -- out once per chain instead of once per store: twenty
                        -- stores of a few chains asked the same question twenty
                        -- times, 5.7 s of a 6.5 s query. An item with an intent
                        -- starts from the products of its types. The offer below
                        -- still checks every condition.
                        WITH candidate AS MATERIALIZED (
                            SELECT item.id AS item_id,
                                   product.id AS retailer_product_id
                            FROM (
                                SELECT DISTINCT requested_store.retailer_id
                                FROM app.store AS requested_store
                                WHERE requested_store.id IN (:storeIds)
                            ) AS chain
                            CROSS JOIN app.shopping_list_item AS item
                        LEFT JOIN LATERAL (
                            SELECT intent.id AS shopping_intent_id,
                                   intent.default_min_package_quantity,
                                   intent.default_max_package_quantity,
                                   intent.default_base_unit,
                                   alias.required_name_pattern
                            FROM app.shopping_intent AS intent
                            LEFT JOIN app.shopping_intent_alias AS alias
                              ON alias.shopping_intent_id = intent.id
                             AND alias.normalized_alias = item.flexible_category_normalized
                            WHERE item.matching_rule =
                                  'FLEXIBLE_CATEGORY'
                              AND intent.id = item.shopping_intent_id
                              AND intent.active = TRUE
                        ) AS requested_intent ON TRUE
                            JOIN app.shopping_intent_product_type AS candidate_type
                              ON candidate_type.shopping_intent_id =
                                 requested_intent.shopping_intent_id
                             AND candidate_type.enabled_by_default = TRUE
                            JOIN app.retailer_product_type AS candidate_assignment
                              ON candidate_assignment.product_type_id =
                                 candidate_type.product_type_id
                            JOIN app.retailer_product AS product
                              ON product.id = candidate_assignment.retailer_product_id
                             AND product.retailer_id = chain.retailer_id
                            LEFT JOIN app.canonical_product AS canonical
                              ON canonical.id = product.canonical_product_id
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN counted.by_piece
                                            THEN product.package_count::NUMERIC
                                            ELSE product.quantity_value
                                       END AS size,
                                       CASE WHEN counted.by_piece
                                            THEN 'piece'
                                            ELSE product.base_unit
                                       END AS unit,
                                       -- One bottle or can, for the usual size.
                                       CASE WHEN counted.by_piece
                                            THEN product.quantity_value
                                                 / product.package_count
                                            ELSE product.quantity_value
                                       END AS one_size,
                                       product.base_unit AS one_unit,
                                       counted.by_piece
                                FROM (
                                    SELECT COALESCE(
                                               item.required_base_unit = 'piece'
                                               AND product.base_unit IN ('g', 'ml')
                                               AND requested_intent.default_base_unit
                                                   IS DISTINCT FROM 'piece'
                                               AND product.name !~* '\\m(rinfuz|cca)\\M',
                                               FALSE
                                           ) AS by_piece
                                ) AS counted
                            ) pack
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN item.target_quantity IS NULL THEN item.quantity
                                    ELSE CEIL(item.target_quantity * item.quantity / NULLIF(pack.size, 0))
                                END AS packages
                            ) need
                            WHERE item.shopping_list_id = :listId
                              AND item.matching_rule = 'FLEXIBLE_CATEGORY'
                              AND requested_intent.shopping_intent_id IS NOT NULL
                              AND (item.target_quantity IS NULL OR (
                                  pack.size > 0 AND pack.unit = item.required_base_unit
                                  AND need.packages * pack.size <= item.target_quantity * item.quantity * 1.25
                              ))
                              AND (
                                  (
                                      item.matching_rule = 'EXACT_PRODUCT'
                                      AND item.matching_status IN (
                                          'AUTO_MATCHED',
                                          'CONFIRMED'
                                      )
                                      -- A case of twenty sharing the
                                      -- bottle's barcode is not one bottle (V77).
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM app.retailer_product AS single_piece
                                          WHERE single_piece.product_family_id =
                                                product.product_family_id
                                            AND single_piece.package_count <
                                                product.package_count
                                      )
                                      AND (
                                          product.canonical_product_id =
                                              item.matched_canonical_product_id
                                          -- The same product under another
                                          -- chain's barcode.
                                          OR product.product_family_id IN (
                                              SELECT member.family_id
                                              FROM app.product_family_member
                                                  AS member
                                              WHERE member.canonical_product_id =
                                                  item.matched_canonical_product_id
                                          )
                                          OR (
                                              item.barcode IS NOT NULL
                                              AND product.barcode = item.barcode
                                          )
                                      )
                                  )
                                  OR
                                  (
                                      item.matching_rule = 'PRODUCT_FAMILY'
                                      AND item.matching_status = 'CONFIRMED'
                                      AND product.product_family_id =
                                          item.matched_product_family_id
                                      -- A case of twenty sharing the
                                      -- bottle's barcode is not one bottle (V77).
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM app.retailer_product AS single_piece
                                          WHERE single_piece.product_family_id =
                                                product.product_family_id
                                            AND single_piece.package_count <
                                                product.package_count
                                      )
                                  )
                                  OR
                                  (
                                      item.matching_rule = 'FLEXIBLE_CATEGORY'
                                      AND (
                                          EXISTS (
                                              SELECT 1
                                              FROM app.retailer_product_type
                                                  AS assignment
                                              JOIN app.shopping_intent_product_type
                                                  AS allowed_type
                                                ON allowed_type.product_type_id =
                                                   assignment.product_type_id
                                              WHERE assignment.retailer_product_id =
                                                    product.id
                                                AND allowed_type.shopping_intent_id =
                                                    requested_intent.shopping_intent_id
                                                AND allowed_type.enabled_by_default =
                                                    TRUE
                                          )
                                          OR (
                                              requested_intent.shopping_intent_id
                                                  IS NULL
                                              AND
                                              NOT EXISTS (
                                                  SELECT 1
                                                  FROM app.retailer_product_type
                                                      AS known_type
                                                  WHERE known_type.retailer_product_id =
                                                        product.id
                                              )
                                              AND (
                                                  COALESCE(
                                                      product.normalized_name,
                                                      LOWER(product.name)
                                                  ) = item.flexible_category_normalized
                                                  OR COALESCE(
                                                      product.normalized_name,
                                                      LOWER(product.name)
                                                  ) LIKE
                                                      item.flexible_category_normalized
                                                      || ' %'
                                              )
                                          )
                                      )
                                  )
                              )
                              AND (
                                  item.matching_rule <> 'FLEXIBLE_CATEGORY'
                                  OR (
                                      (requested_intent.required_name_pattern IS NULL
                                       OR product.normalized_name ~ requested_intent.required_name_pattern)
                                      AND
                                      (
                                          item.required_brand IS NULL
                                          OR LOWER(BTRIM(COALESCE(
                                              canonical.brand,
                                              product.brand,
                                              ''
                                          ))) = LOWER(BTRIM(
                                              item.required_brand
                                          ))
                                          -- One brand, several spellings:
                                          -- "Zaječarsko", "ZAJEČARSKO",
                                          -- "Zajecarsko".
                                          OR (
                                              app.product_match_brand_key(item.required_brand) <> ''
                                              AND app.product_match_brand_key(item.required_brand) IN (
                                                  app.product_match_brand_key(canonical.brand),
                                                  app.product_match_brand_key(product.brand)
                                              )
                                          )
                                      )
                                      AND (
                                          item.required_base_unit IS NULL
                                          OR pack.unit = item.required_base_unit
                                      )
                                      AND (
                                          item.min_package_quantity IS NULL
                                          OR pack.size >= item.min_package_quantity
                                      )
                                      AND (
                                          item.max_package_quantity IS NULL
                                          OR pack.size <= item.max_package_quantity
                                      )
                                  )
                              )
                            UNION
                            SELECT item.id,
                                   product.id
                            FROM (
                                SELECT DISTINCT requested_store.retailer_id
                                FROM app.store AS requested_store
                                WHERE requested_store.id IN (:storeIds)
                            ) AS chain
                            CROSS JOIN app.shopping_list_item AS item
                        LEFT JOIN LATERAL (
                            SELECT intent.id AS shopping_intent_id,
                                   intent.default_min_package_quantity,
                                   intent.default_max_package_quantity,
                                   intent.default_base_unit,
                                   alias.required_name_pattern
                            FROM app.shopping_intent AS intent
                            LEFT JOIN app.shopping_intent_alias AS alias
                              ON alias.shopping_intent_id = intent.id
                             AND alias.normalized_alias = item.flexible_category_normalized
                            WHERE item.matching_rule =
                                  'FLEXIBLE_CATEGORY'
                              AND intent.id = item.shopping_intent_id
                              AND intent.active = TRUE
                        ) AS requested_intent ON TRUE
                            JOIN app.retailer_product AS product
                              ON product.retailer_id = chain.retailer_id
                            LEFT JOIN app.canonical_product AS canonical
                              ON canonical.id = product.canonical_product_id
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN counted.by_piece
                                            THEN product.package_count::NUMERIC
                                            ELSE product.quantity_value
                                       END AS size,
                                       CASE WHEN counted.by_piece
                                            THEN 'piece'
                                            ELSE product.base_unit
                                       END AS unit,
                                       -- One bottle or can, for the usual size.
                                       CASE WHEN counted.by_piece
                                            THEN product.quantity_value
                                                 / product.package_count
                                            ELSE product.quantity_value
                                       END AS one_size,
                                       product.base_unit AS one_unit,
                                       counted.by_piece
                                FROM (
                                    SELECT COALESCE(
                                               item.required_base_unit = 'piece'
                                               AND product.base_unit IN ('g', 'ml')
                                               AND requested_intent.default_base_unit
                                                   IS DISTINCT FROM 'piece'
                                               AND product.name !~* '\\m(rinfuz|cca)\\M',
                                               FALSE
                                           ) AS by_piece
                                ) AS counted
                            ) pack
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN item.target_quantity IS NULL THEN item.quantity
                                    ELSE CEIL(item.target_quantity * item.quantity / NULLIF(pack.size, 0))
                                END AS packages
                            ) need
                            WHERE item.shopping_list_id = :listId
                              AND NOT (
                                  item.matching_rule = 'FLEXIBLE_CATEGORY'
                                  AND requested_intent.shopping_intent_id IS NOT NULL
                              )
                              AND (item.target_quantity IS NULL OR (
                                  pack.size > 0 AND pack.unit = item.required_base_unit
                                  AND need.packages * pack.size <= item.target_quantity * item.quantity * 1.25
                              ))
                              AND (
                                  (
                                      item.matching_rule = 'EXACT_PRODUCT'
                                      AND item.matching_status IN (
                                          'AUTO_MATCHED',
                                          'CONFIRMED'
                                      )
                                      -- A case of twenty sharing the
                                      -- bottle's barcode is not one bottle (V77).
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM app.retailer_product AS single_piece
                                          WHERE single_piece.product_family_id =
                                                product.product_family_id
                                            AND single_piece.package_count <
                                                product.package_count
                                      )
                                      AND (
                                          product.canonical_product_id =
                                              item.matched_canonical_product_id
                                          -- The same product under another
                                          -- chain's barcode.
                                          OR product.product_family_id IN (
                                              SELECT member.family_id
                                              FROM app.product_family_member
                                                  AS member
                                              WHERE member.canonical_product_id =
                                                  item.matched_canonical_product_id
                                          )
                                          OR (
                                              item.barcode IS NOT NULL
                                              AND product.barcode = item.barcode
                                          )
                                      )
                                  )
                                  OR
                                  (
                                      item.matching_rule = 'PRODUCT_FAMILY'
                                      AND item.matching_status = 'CONFIRMED'
                                      AND product.product_family_id =
                                          item.matched_product_family_id
                                      -- A case of twenty sharing the
                                      -- bottle's barcode is not one bottle (V77).
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM app.retailer_product AS single_piece
                                          WHERE single_piece.product_family_id =
                                                product.product_family_id
                                            AND single_piece.package_count <
                                                product.package_count
                                      )
                                  )
                                  OR
                                  (
                                      item.matching_rule = 'FLEXIBLE_CATEGORY'
                                      AND (
                                          EXISTS (
                                              SELECT 1
                                              FROM app.retailer_product_type
                                                  AS assignment
                                              JOIN app.shopping_intent_product_type
                                                  AS allowed_type
                                                ON allowed_type.product_type_id =
                                                   assignment.product_type_id
                                              WHERE assignment.retailer_product_id =
                                                    product.id
                                                AND allowed_type.shopping_intent_id =
                                                    requested_intent.shopping_intent_id
                                                AND allowed_type.enabled_by_default =
                                                    TRUE
                                          )
                                          OR (
                                              requested_intent.shopping_intent_id
                                                  IS NULL
                                              AND
                                              NOT EXISTS (
                                                  SELECT 1
                                                  FROM app.retailer_product_type
                                                      AS known_type
                                                  WHERE known_type.retailer_product_id =
                                                        product.id
                                              )
                                              AND (
                                                  COALESCE(
                                                      product.normalized_name,
                                                      LOWER(product.name)
                                                  ) = item.flexible_category_normalized
                                                  OR COALESCE(
                                                      product.normalized_name,
                                                      LOWER(product.name)
                                                  ) LIKE
                                                      item.flexible_category_normalized
                                                      || ' %'
                                              )
                                          )
                                      )
                                  )
                              )
                              AND (
                                  item.matching_rule <> 'FLEXIBLE_CATEGORY'
                                  OR (
                                      (requested_intent.required_name_pattern IS NULL
                                       OR product.normalized_name ~ requested_intent.required_name_pattern)
                                      AND
                                      (
                                          item.required_brand IS NULL
                                          OR LOWER(BTRIM(COALESCE(
                                              canonical.brand,
                                              product.brand,
                                              ''
                                          ))) = LOWER(BTRIM(
                                              item.required_brand
                                          ))
                                          -- One brand, several spellings:
                                          -- "Zaječarsko", "ZAJEČARSKO",
                                          -- "Zajecarsko".
                                          OR (
                                              app.product_match_brand_key(item.required_brand) <> ''
                                              AND app.product_match_brand_key(item.required_brand) IN (
                                                  app.product_match_brand_key(canonical.brand),
                                                  app.product_match_brand_key(product.brand)
                                              )
                                          )
                                      )
                                      AND (
                                          item.required_base_unit IS NULL
                                          OR pack.unit = item.required_base_unit
                                      )
                                      AND (
                                          item.min_package_quantity IS NULL
                                          OR pack.size >= item.min_package_quantity
                                      )
                                      AND (
                                          item.max_package_quantity IS NULL
                                          OR pack.size <= item.max_package_quantity
                                      )
                                  )
                              )
                        )
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
                                   offer.effective_price * offer.packages,
                                   2
                               ) AS line_total,
                               offer.price_scope,
                               offer.packages, offer.package_size, offer.package_unit,
                               item.target_quantity * item.quantity AS target_amount,
                               offer.packages * offer.package_size AS supplied_amount,
                               offer.packages * offer.package_size - item.target_quantity * item.quantity AS extra_amount,
                               ROUND(offer.effective_price / NULLIF(offer.package_size, 0) *
                                   CASE WHEN offer.package_unit IN ('g','ml') THEN 1000 ELSE 1 END, 2) AS unit_price
                        FROM app.store AS store
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        JOIN app.store_format AS format
                          ON format.id = store.store_format_id
                        LEFT JOIN app.store_price_format_mapping
                            AS price_mapping
                          ON price_mapping.retailer_id =
                             store.retailer_id
                         AND price_mapping.store_external_code =
                             store.external_code
                        AND price_mapping.active = TRUE
                         AND price_mapping.verification_status = 'VERIFIED'
                        CROSS JOIN app.shopping_list_item AS item
                        LEFT JOIN LATERAL (
                            SELECT intent.id AS shopping_intent_id,
                                   intent.default_min_package_quantity,
                                   intent.default_max_package_quantity,
                                   intent.default_base_unit,
                                   alias.required_name_pattern
                            FROM app.shopping_intent AS intent
                            LEFT JOIN app.shopping_intent_alias AS alias
                              ON alias.shopping_intent_id = intent.id
                             AND alias.normalized_alias = item.flexible_category_normalized
                            WHERE item.matching_rule =
                                  'FLEXIBLE_CATEGORY'
                              AND intent.id = item.shopping_intent_id
                              AND intent.active = TRUE
                        ) AS requested_intent ON TRUE
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
                                   selected_price.price_scope,
                                   pack.size AS package_size, pack.unit AS package_unit,
                                   need.packages
                            FROM candidate
                            JOIN app.retailer_product AS product
                              ON product.id = candidate.retailer_product_id
                             AND candidate.item_id = item.id
                            LEFT JOIN app.canonical_product AS canonical
                              ON canonical.id =
                                  product.canonical_product_id
                            LEFT JOIN app.product_family_typical_price
                                AS typical_price
                              ON typical_price.product_family_id =
                                  product.product_family_id
                            -- "6 kom" of something sold by volume or weight
                            -- counts bottles, cans and bags, so six bottles
                            -- and a case of six compare by price (V73). Not
                            -- where the usual amount is itself a number of
                            -- pieces, as a litre of liquid egg is no egg, and
                            -- not for loose goods priced by weight.
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN counted.by_piece
                                            THEN product.package_count::NUMERIC
                                            ELSE product.quantity_value
                                       END AS size,
                                       CASE WHEN counted.by_piece
                                            THEN 'piece'
                                            ELSE product.base_unit
                                       END AS unit,
                                       -- One bottle or can, for the usual size.
                                       CASE WHEN counted.by_piece
                                            THEN product.quantity_value
                                                 / product.package_count
                                            ELSE product.quantity_value
                                       END AS one_size,
                                       product.base_unit AS one_unit,
                                       counted.by_piece
                                FROM (
                                    SELECT COALESCE(
                                               item.required_base_unit = 'piece'
                                               AND product.base_unit IN ('g', 'ml')
                                               AND requested_intent.default_base_unit
                                                   IS DISTINCT FROM 'piece'
                                               AND product.name !~* '\\m(rinfuz|cca)\\M',
                                               FALSE
                                           ) AS by_piece
                                ) AS counted
                            ) pack
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN item.target_quantity IS NULL THEN item.quantity
                                    ELSE CEIL(item.target_quantity * item.quantity / NULLIF(pack.size, 0))
                                END AS packages
                            ) need
                            JOIN LATERAL (
                                SELECT priced.price_date,
                                       priced.regular_price,
                                       priced.discounted_price,
                                       priced.effective_price,
                                       priced.price_scope
                                FROM (
                                    SELECT current_offer.price_date,
                                           current_offer.regular_price,
                                           current_offer.discounted_price,
                                           COALESCE(
                                               CASE
                                                   WHEN current_offer.discounted_price
                                                            > 0
                                                    AND (
                                                        current_offer.discount_start
                                                            IS NULL
                                                        OR current_offer.discount_start
                                                            <= :asOfDate
                                                    )
                                                    AND (
                                                        current_offer.discount_end
                                                            IS NULL
                                                        OR current_offer.discount_end
                                                            >= :asOfDate
                                                    )
                                                   THEN current_offer.discounted_price
                                               END,
                                               CASE
                                                   WHEN current_offer.regular_price
                                                            > 0
                                                       THEN current_offer.regular_price
                                               END
                                           ) AS effective_price,
                                           current_offer.scope_type AS price_scope,
                                           CASE
                                               WHEN current_offer.store_id = store.id
                                                   THEN 1
                                               WHEN current_offer.scope_type =
                                                    'STORE_FORMAT'
                                                AND (
                                                    LOWER(BTRIM(
                                                        current_offer.retailer_format_name
                                                    )) = LOWER(BTRIM(COALESCE(
                                                        price_mapping.retailer_format_name,
                                                        format.name
                                                    )))
                                                    OR (
                                                        price_mapping.id IS NULL
                                                        AND LOWER(BTRIM(
                                                        current_offer.retailer_format_name
                                                        )) = LOWER(BTRIM(
                                                            format.code
                                                        ))
                                                    )
                                                )
                                                   THEN 2
                                               ELSE 3
                                           END AS scope_priority,
                                           0 AS source_priority,
                                           current_offer.id
                                    FROM app.current_price_offer AS current_offer
                                    WHERE current_offer.retailer_product_id =
                                            product.id
                                      AND current_offer.price_date <= :asOfDate
                                      -- Only a price in the chain's newest list:
                                      -- a dropped product keeps its old price (V75).
                                      AND app.in_latest_price_list(
                                          product.retailer_id,
                                          current_offer.scope_key,
                                          current_offer.price_date,
                                          :asOfDate
                                      )
                                      AND (
                                          current_offer.store_id = store.id
                                          OR (
                                              current_offer.scope_type =
                                                  'STORE_FORMAT'
                                                  AND (
                                                      LOWER(BTRIM(
                                                          current_offer.retailer_format_name
                                                      )) = LOWER(BTRIM(COALESCE(
                                                          price_mapping.retailer_format_name,
                                                          format.name
                                                      )))
                                                      OR (
                                                          price_mapping.id IS NULL
                                                          AND LOWER(BTRIM(
                                                          current_offer.retailer_format_name
                                                          )) = LOWER(BTRIM(
                                                              format.code
                                                          ))
                                                      )
                                                  )
                                          )
                                          OR current_offer.scope_type = 'RETAILER'
                                      )
                                    UNION ALL
                                    SELECT observation.price_date,
                                           observation.regular_price,
                                           observation.discounted_price,
                                           COALESCE(
                                               CASE
                                                   WHEN observation.discounted_price
                                                            > 0
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
                                               CASE
                                                   WHEN observation.regular_price
                                                            > 0
                                                       THEN observation.regular_price
                                               END
                                           ) AS effective_price,
                                           CASE
                                               WHEN observation.store_id = store.id
                                                   THEN 'STORE'
                                               WHEN NULLIF(BTRIM(
                                                   observation.retailer_format_name
                                               ), '') IS NOT NULL
                                                   THEN 'STORE_FORMAT'
                                               ELSE 'RETAILER'
                                           END AS price_scope,
                                           CASE
                                               WHEN observation.store_id = store.id
                                                   THEN 1
                                               WHEN observation.store_id IS NULL
                                                AND NULLIF(BTRIM(
                                                    observation.retailer_format_name
                                                ), '') IS NOT NULL
                                                AND (
                                                    LOWER(BTRIM(
                                                        observation.retailer_format_name
                                                    )) = LOWER(BTRIM(COALESCE(
                                                        price_mapping.retailer_format_name,
                                                        format.name
                                                    )))
                                                    OR (
                                                        price_mapping.id IS NULL
                                                        AND LOWER(BTRIM(
                                                        observation.retailer_format_name
                                                        )) = LOWER(BTRIM(
                                                            format.code
                                                        ))
                                                    )
                                                )
                                                   THEN 2
                                               ELSE 3
                                           END AS scope_priority,
                                           1 AS source_priority,
                                           observation.id
                                    FROM app.price_observation AS observation
                                    WHERE observation.retailer_product_id =
                                            product.id
                                      AND observation.price_date <= :asOfDate
                                      AND app.in_latest_price_list(
                                          product.retailer_id,
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
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM app.current_price_offer
                                              AS available_current
                                          WHERE available_current.retailer_product_id =
                                                observation.retailer_product_id
                                            AND available_current.price_date <=
                                                :asOfDate
                                            AND available_current.scope_key =
                                                CASE
                                                    WHEN observation.store_id
                                                            IS NOT NULL
                                                        THEN 'STORE:' ||
                                                             observation.store_id::TEXT
                                                    WHEN NULLIF(BTRIM(
                                                        observation.retailer_format_name
                                                    ), '') IS NOT NULL
                                                        THEN 'STORE_FORMAT:' ||
                                                             LOWER(BTRIM(
                                                                 observation.retailer_format_name
                                                             ))
                                                    ELSE 'RETAILER'
                                                END
                                      )
                                      AND (
                                          observation.store_id = store.id
                                          OR (
                                              observation.store_id IS NULL
                                              AND NULLIF(BTRIM(
                                                  observation.retailer_format_name
                                              ), '') IS NOT NULL
                                              AND (
                                                  LOWER(BTRIM(
                                                      observation.retailer_format_name
                                                  )) = LOWER(BTRIM(COALESCE(
                                                      price_mapping.retailer_format_name,
                                                      format.name
                                                  )))
                                                  OR (
                                                      price_mapping.id IS NULL
                                                      AND LOWER(BTRIM(
                                                      observation.retailer_format_name
                                                      )) = LOWER(BTRIM(
                                                          format.code
                                                      ))
                                                  )
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
                                WHERE priced.effective_price > 0
                                ORDER BY priced.scope_priority ASC,
                                         priced.price_date DESC,
                                         priced.source_priority ASC,
                                         priced.id DESC
                                LIMIT 1
                            ) AS selected_price ON TRUE
                            WHERE product.retailer_id = store.retailer_id
                              -- Far below what other chains charge, the price
                              -- is most likely for one piece or one kilogram
                              -- of a bigger pack (V72). The product screen
                              -- shows it to be checked; a plan never counts
                              -- on it.
                              AND NOT app.price_needs_check(
                                  selected_price.regular_price,
                                  selected_price.discounted_price,
                                  typical_price.typical_price
                              )
                              AND (item.target_quantity IS NULL OR (
                                  pack.size > 0 AND pack.unit = item.required_base_unit
                                  AND need.packages * pack.size <= item.target_quantity * item.quantity * 1.25
                              ))
                              AND (
                                  (
                                      item.matching_rule = 'EXACT_PRODUCT'
                                      AND item.matching_status IN (
                                          'AUTO_MATCHED',
                                          'CONFIRMED'
                                      )
                                      -- A case of twenty sharing the
                                      -- bottle's barcode is not one bottle (V77).
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM app.retailer_product AS single_piece
                                          WHERE single_piece.product_family_id =
                                                product.product_family_id
                                            AND single_piece.package_count <
                                                product.package_count
                                      )
                                      AND (
                                          product.canonical_product_id =
                                              item.matched_canonical_product_id
                                          -- The same product under another
                                          -- chain's barcode.
                                          OR product.product_family_id IN (
                                              SELECT member.family_id
                                              FROM app.product_family_member
                                                  AS member
                                              WHERE member.canonical_product_id =
                                                  item.matched_canonical_product_id
                                          )
                                          OR (
                                              item.barcode IS NOT NULL
                                              AND product.barcode = item.barcode
                                          )
                                      )
                                  )
                                  OR
                                  (
                                      item.matching_rule = 'PRODUCT_FAMILY'
                                      AND item.matching_status = 'CONFIRMED'
                                      AND product.product_family_id =
                                          item.matched_product_family_id
                                      -- A case of twenty sharing the
                                      -- bottle's barcode is not one bottle (V77).
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM app.retailer_product AS single_piece
                                          WHERE single_piece.product_family_id =
                                                product.product_family_id
                                            AND single_piece.package_count <
                                                product.package_count
                                      )
                                  )
                                  OR
                                  (
                                      item.matching_rule = 'FLEXIBLE_CATEGORY'
                                      AND (
                                          EXISTS (
                                              SELECT 1
                                              FROM app.retailer_product_type
                                                  AS assignment
                                              JOIN app.shopping_intent_product_type
                                                  AS allowed_type
                                                ON allowed_type.product_type_id =
                                                   assignment.product_type_id
                                              WHERE assignment.retailer_product_id =
                                                    product.id
                                                AND allowed_type.shopping_intent_id =
                                                    requested_intent.shopping_intent_id
                                                AND allowed_type.enabled_by_default =
                                                    TRUE
                                          )
                                          OR (
                                              requested_intent.shopping_intent_id
                                                  IS NULL
                                              AND
                                              NOT EXISTS (
                                                  SELECT 1
                                                  FROM app.retailer_product_type
                                                      AS known_type
                                                  WHERE known_type.retailer_product_id =
                                                        product.id
                                              )
                                              AND (
                                                  COALESCE(
                                                      product.normalized_name,
                                                      LOWER(product.name)
                                                  ) = item.flexible_category_normalized
                                                  OR COALESCE(
                                                      product.normalized_name,
                                                      LOWER(product.name)
                                                  ) LIKE
                                                      item.flexible_category_normalized
                                                      || ' %'
                                              )
                                          )
                                      )
                                  )
                              )
                              AND (
                                  item.matching_rule <> 'FLEXIBLE_CATEGORY'
                                  OR (
                                      (requested_intent.required_name_pattern IS NULL
                                       OR product.normalized_name ~ requested_intent.required_name_pattern)
                                      AND
                                      (
                                          item.required_brand IS NULL
                                          OR LOWER(BTRIM(COALESCE(
                                              canonical.brand,
                                              product.brand,
                                              ''
                                          ))) = LOWER(BTRIM(
                                              item.required_brand
                                          ))
                                          -- One brand, several spellings:
                                          -- "Zaječarsko", "ZAJEČARSKO",
                                          -- "Zajecarsko".
                                          OR (
                                              app.product_match_brand_key(item.required_brand) <> ''
                                              AND app.product_match_brand_key(item.required_brand) IN (
                                                  app.product_match_brand_key(canonical.brand),
                                                  app.product_match_brand_key(product.brand)
                                              )
                                          )
                                      )
                                      AND (
                                          item.required_base_unit IS NULL
                                          OR pack.unit = item.required_base_unit
                                      )
                                      AND (
                                          item.min_package_quantity IS NULL
                                          OR pack.size >= item.min_package_quantity
                                      )
                                      AND (
                                          item.max_package_quantity IS NULL
                                          OR pack.size <= item.max_package_quantity
                                      )
                                  )
                              )
                            ORDER BY CASE
                                         WHEN item.matching_rule =
                                              'FLEXIBLE_CATEGORY'
                                         THEN COALESCE((
                                             SELECT MIN(
                                                 allowed_type.match_priority
                                             )
                                             FROM app.retailer_product_type
                                                 AS assignment
                                             JOIN app.shopping_intent_product_type
                                                 AS allowed_type
                                               ON allowed_type.product_type_id =
                                                  assignment.product_type_id
                                             WHERE assignment.retailer_product_id =
                                                   product.id
                                               AND allowed_type.shopping_intent_id =
                                                   requested_intent.shopping_intent_id
                                               AND allowed_type.enabled_by_default =
                                                   TRUE
                                         ), 32767)
                                         ELSE 0
                                     END ASC,
                                     -- Someone who writes only "mleko" means a
                                     -- litre of it, not the cheapest 200ml cup.
                                     -- Ranked, not filtered: a store that only
                                     -- stocks small packs still offers milk
                                     -- instead of dropping out of the basket.
                                     -- Once an amount is actually stated, the
                                     -- cheapest way to supply it wins again:
                                     -- five 200g cups may beat one 1kg tub.
                                     CASE
                                         WHEN item.matching_rule <>
                                              'FLEXIBLE_CATEGORY'
                                             THEN 0
                                         -- A number of bottles or cans still
                                         -- prefers the usual size of one.
                                         WHEN NOT pack.by_piece AND (
                                             item.target_quantity IS NOT NULL
                                             OR item.min_package_quantity
                                                 IS NOT NULL
                                             OR item.max_package_quantity
                                                 IS NOT NULL
                                         )
                                             THEN 0
                                         WHEN pack.one_size IS NULL
                                             THEN 1
                                         WHEN (
                                             requested_intent.default_min_package_quantity
                                                 IS NULL
                                             OR pack.one_size >=
                                                requested_intent.default_min_package_quantity
                                         ) AND (
                                             requested_intent.default_max_package_quantity
                                                 IS NULL
                                             OR pack.one_size <=
                                                requested_intent.default_max_package_quantity
                                         ) AND (
                                             requested_intent.default_base_unit
                                                 IS NULL
                                             OR pack.one_unit =
                                                requested_intent.default_base_unit
                                         ) THEN 0
                                         ELSE 1
                                     END ASC,
                                     selected_price.effective_price * need.packages ASC,
                                     need.packages * pack.size ASC NULLS LAST,
                                     product.id ASC
                            LIMIT 1
                        ) AS offer ON TRUE
                        WHERE item.shopping_list_id = :listId
                          AND store.id IN (:storeIds)
                          AND (
                              store.pricing_eligible = TRUE
                              OR (:includeUnlocated AND store.location IS NULL)
                          )
                        ORDER BY store.id ASC,
                                 item.created_at ASC,
                                 item.id ASC
                        """)
                .param("asOfDate", asOfDate)
                .param("listId", listId)
                .param("storeIds", storeIds)
                .param("includeUnlocated", includeUnlocated)
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * Prices a basket against published price lists whose shop location is
     * unknown (see V65). These entries have no coordinates and are never
     * pricing_eligible, so they cannot be routed to; this exists only to say
     * "it would be cheaper here" next to a plan the shopper can actually
     * follow.
     */
    public List<StoreItemOffer> findPriceListOffers(
            Long listId,
            List<Long> priceListEntryIds,
            LocalDate asOfDate
    ) {
        return findOffers(listId, priceListEntryIds, asOfDate, true);
    }

    /** Published price lists that no shop location could be attached to. */
    public List<PriceListEntry> findPriceListEntriesWithoutLocation() {
        return jdbcClient.sql("""
                        SELECT store.id AS store_id,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               store.name AS label
                        FROM app.store AS store
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        JOIN app.store_price_format_mapping AS mapping
                          ON mapping.retailer_id = store.retailer_id
                         AND mapping.store_external_code = store.external_code
                         AND mapping.active = TRUE
                         AND mapping.verification_status = 'VERIFIED'
                        WHERE store.active = TRUE
                          AND store.location IS NULL
                        ORDER BY retailer.code, store.name
                        """)
                .query((resultSet, rowNumber) -> new PriceListEntry(
                        resultSet.getLong("store_id"),
                        resultSet.getString("retailer_code"),
                        resultSet.getString("retailer_name"),
                        resultSet.getString("label")
                ))
                .list();
    }

    public record PriceListEntry(
            Long storeId,
            String retailerCode,
            String retailerName,
            String label
    ) {
    }
}
