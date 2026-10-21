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
                            resultSet.getBigDecimal("extra_amount"), resultSet.getBigDecimal("unit_price")),
                    resultSet.getBoolean("usual_choice"),
                    resultSet.getBoolean("wanted_missing")
            );

    private final JdbcClient jdbcClient;

    public StoreShoppingOfferRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Proizvodi koje je vlasnik ovog spiska već kupovao, po skeniranim
     * računima. Vraća se kao niz u parametru umesto kao podupit u sortiranju:
     * upit koji se izvršava za svakog kandidata u svakoj prodavnici je ono što
     * je preporuku i usporavalo. Prazan niz ne menja nijedan redosled.
     */
    private Long[] boughtFamilyIds(Long listId) {
        return jdbcClient.sql("""
                        SELECT DISTINCT item.product_family_id
                        FROM app.receipt_item AS item
                        JOIN app.receipt AS receipt
                          ON receipt.id = item.receipt_id
                        JOIN app.shopping_list AS list
                          ON list.account_id = receipt.account_id
                        WHERE list.id = :listId
                          AND item.product_family_id IS NOT NULL
                        """)
                .param("listId", listId)
                .query(Long.class)
                .list()
                .toArray(Long[]::new);
    }

    public List<StoreItemOffer> findOffers(
            Long listId,
            List<Long> storeIds,
            LocalDate asOfDate
    ) {
        return findOffers(listId, storeIds, asOfDate, false);
    }

    /**
     * @param includeUnlocated also prices the published lists whose shop we
     *                         cannot place, so one pass answers both.
     */
    public List<StoreItemOffer> findOffers(
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
                                   product.id AS retailer_product_id,
                                   product.retailer_id,
                                   pack.by_the_kilogram,
                                   pack.size AS package_size,
                                   pack.unit AS package_unit,
                                   need.packages,
                                   -- Everything the offer below ranks by but the price
                                   -- is the same in every shop of the chain: worked out
                                   -- here once, not again for every shop.
                                   -- A product has one type, the one it was found by
                                   -- here, so that type's priority is its rank: no need
                                   -- to look it up again for each candidate.
                                     candidate_type.match_priority::INTEGER AS kind_rank,
                                     -- The usual kind before an unusual one: white
                                     -- sugar before powdered, plain bananas before
                                     -- red ones. Ranked, not filtered: a shop that
                                     -- has only the unusual kind still offers it
                                     -- (V125).
                                     CASE
                                         WHEN requested_intent.uncommon_name_pattern IS NOT NULL
                                          AND product.normalized_name ~ requested_intent.uncommon_name_pattern
                                             THEN 1
                                         ELSE 0
                                     END AS uncommon_rank,
                                     -- What the line asks for beyond the kind:
                                     -- "feta sir", "mleko 3,2%". A product that
                                     -- does not say it comes after every one that
                                     -- does, and the plan names it a substitute
                                     -- (V140).
                                     -- A dictionary word narrower than its kind
                                     -- ("kajmak") only orders: "kisela voda" is
                                     -- mineral water too, "pečurke" šampinjoni, so
                                     -- another name is no substitute (proba V140).
                                     CASE
                                         WHEN item.wanted_name_pattern IS NOT NULL
                                          AND product.normalized_name !~ item.wanted_name_pattern
                                             THEN CASE
                                                 WHEN STARTS_WITH(item.wanted_name_pattern, '^(?=([^ ]+ ){0,2}')
                                                     THEN 2
                                                 ELSE 1
                                             END
                                         ELSE 0
                                     END AS wanted_rank,
                                     -- Fruit and vegetables with no amount are a
                                     -- kilogram weighed out, not the smallest
                                     -- bag: "paradajz" is loose tomatoes before
                                     -- a 500 g mix (V137).
                                     CASE
                                         WHEN requested_intent.weighed_out
                                          AND item.target_quantity IS NULL
                                          AND NOT pack.by_the_kilogram
                                             THEN 1
                                         ELSE 0
                                     END AS loose_rank,
                                     -- A pack bigger than a quarter over the amount
                                     -- only where nothing fits better.
                                     CASE
                                         WHEN item.target_quantity IS NOT NULL
                                          AND need.packages * pack.size > item.target_quantity * item.quantity
                                              * CASE WHEN requested_intent.default_base_unit = 'piece'
                                                     THEN 2 ELSE 1.25 END
                                             THEN 1
                                         ELSE 0
                                     END AS oversize_rank,
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
                                         -- "Toalet papir 10/1" is ten rolls; an
                                         -- intent counted in pieces reads the
                                         -- count from the name (V126).
                                         WHEN pack.one_size IS NULL
                                          AND requested_intent.default_base_unit IS NULL
                                          AND requested_intent.default_max_package_quantity < 100
                                          AND SUBSTRING(product.name FROM '(?:^|\\D)(\\d{1,3}) ?/ ?1(?:\\D|$)')
                                              IS NOT NULL
                                             THEN CASE
                                                      WHEN SUBSTRING(product.name FROM '(?:^|\\D)(\\d{1,3}) ?/ ?1(?:\\D|$)')::NUMERIC
                                                           BETWEEN requested_intent.default_min_package_quantity
                                                               AND requested_intent.default_max_package_quantity
                                                          THEN 0
                                                      ELSE 2
                                                  END
                                         -- A size we cannot read comes before one
                                         -- we know is wrong: one roll of toilet
                                         -- paper is not the usual pack (V126).
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
                                         ELSE 2
                                     END AS size_rank,
                                     -- Thirteen 16 g sachets for "kafa 200 g" cost more
                                     -- than the shelf price says: every pack beyond
                                     -- three, or beyond the count asked for, weighs 5%
                                     -- more. Relative, so it reads the same in any
                                     -- currency (V125).
                                     CASE
                                               WHEN item.target_quantity IS NULL
                                                 OR pack.by_piece
                                                 OR pack.by_the_kilogram
                                                   THEN 1
                                               ELSE 1 + 0.05 * GREATEST(
                                                   0,
                                                   need.packages - GREATEST(3, item.quantity)
                                               )
                                           END AS pack_weight,
                                     -- Dve ponude koje koštaju isto: bira se
                                     -- ona koju kupac zaista kupuje, po
                                     -- skeniranim računima. Stoji tek posle
                                     -- cene, jer plan ostaje najjeftiniji —
                                     -- navika ne sme da košta ni dinar. Bez
                                     -- ijednog računa lista je prazna i ovaj
                                     -- red ne razlikuje ništa.
                                     CASE
                                         WHEN product.product_family_id =
                                              ANY (:boughtFamilyIds)
                                             THEN 0
                                         ELSE 1
                                     END AS bought_rank,
                                     (
                                         SELECT typical.typical_price
                                         FROM app.retailer AS chain
                                         JOIN app.product_family_typical_price AS typical
                                           ON typical.market_id = chain.market_id
                                         WHERE chain.id = product.retailer_id
                                           AND typical.product_family_id = product.product_family_id
                                     ) AS typical_price
                            FROM app.shopping_list_item AS item
                        LEFT JOIN LATERAL (
                            SELECT intent.id AS shopping_intent_id,
                                   intent.default_min_package_quantity,
                                   intent.default_max_package_quantity,
                                   intent.default_base_unit,
                                   intent.weighed_out,
                                   alias.required_name_pattern,
                                   alias.uncommon_name_pattern
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
                            -- Only the item's types in the chains asked for (V136),
                            -- the chains read once: as a subquery they were asked
                            -- again for each of 60,000 products.
                            JOIN app.retailer_product_type AS candidate_assignment
                              ON candidate_assignment.product_type_id =
                                 candidate_type.product_type_id
                             AND candidate_assignment.retailer_id = ANY (ARRAY(
                                 SELECT requested_store.retailer_id
                                 FROM app.store AS requested_store
                                 WHERE requested_store.id IN (:storeIds)
                             ))
                            -- Each product of the item's types looked up once.
                            -- Started from every chain, the products of each chain
                            -- were gathered again for every item; joined freely,
                            -- the planner tried every typed product against every
                            -- item.
                            CROSS JOIN LATERAL (
                                SELECT typed_product.*
                                FROM app.retailer_product AS typed_product
                                WHERE typed_product.id = candidate_assignment.retailer_product_id
                                OFFSET 0
                            ) AS product
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN counted.by_piece
                                            THEN product.package_count::NUMERIC
                                            WHEN counted.by_the_kilogram
                                            THEN 1000
                                            ELSE product.quantity_value
                                       END AS size,
                                       CASE WHEN counted.by_piece
                                            THEN 'piece'
                                            WHEN counted.by_the_kilogram
                                            THEN 'g'
                                            ELSE product.base_unit
                                       END AS unit,
                                       -- One bottle or can, for the usual size.
                                       CASE WHEN counted.by_piece
                                            THEN product.quantity_value
                                                 / product.package_count
                                            ELSE product.quantity_value
                                       END AS one_size,
                                       product.base_unit AS one_unit,
                                       counted.by_piece,
                                       counted.by_the_kilogram
                                FROM (
                                    -- The name is read only for an item counted
                                    -- in pieces: it was read for every product
                                    -- of every item.
                                    SELECT COALESCE(item.required_base_unit = 'piece', FALSE)
                                           AND COALESCE(
                                               product.base_unit IN ('g', 'ml')
                                               AND requested_intent.default_base_unit
                                                   IS DISTINCT FROM 'piece'
                                               AND product.name !~* '\\m(rinfuz|cca)\\M',
                                               FALSE
                                           ) AS by_piece,
                                           -- "jabuke 2kg" of apples sold loose by
                                           -- the kilogram: each kilogram is a
                                           -- pack (V90).
                                           CASE WHEN item.target_quantity IS NOT NULL
                                                 AND item.required_base_unit = 'g'
                                                 AND product.quantity_value IS NULL
                                                THEN app.sold_by_the_kilogram(product.id)
                                                -- With no amount, a kilogram of
                                                -- fruit or vegetables (V137).
                                                WHEN item.target_quantity IS NULL
                                                 AND item.required_base_unit IS NULL
                                                 AND requested_intent.weighed_out
                                                 AND product.quantity_value IS NULL
                                                THEN app.sold_by_the_kilogram(product.id)
                                                ELSE FALSE
                                           END AS by_the_kilogram
                                    -- Worked out once per product. Folded into
                                    -- every condition that reads the pack, the
                                    -- name and the kilogram price were looked
                                    -- up again for each of them.
                                    OFFSET 0
                                ) AS counted
                            ) pack
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN item.target_quantity IS NULL THEN item.quantity
                                    -- Loose goods are weighed out: "banane 1,5 kg"
                                    -- is 1,5 kg at the price of one (V125).
                                    WHEN pack.by_the_kilogram
                                    THEN ROUND(item.target_quantity * item.quantity / NULLIF(pack.size, 0), 3)
                                    ELSE CEIL(item.target_quantity * item.quantity / NULLIF(pack.size, 0))
                                END AS packages
                            ) need
                            WHERE item.shopping_list_id = :listId
                              AND item.matching_rule = 'FLEXIBLE_CATEGORY'
                              AND requested_intent.shopping_intent_id IS NOT NULL
                              AND (item.target_quantity IS NULL OR (
                                  pack.size > 0 AND pack.unit = item.required_base_unit
                                  -- Eggs come in cartons of 6, 10, 15 or 30: twelve
                                  -- from a shop with only cartons of ten are two
                                  -- cartons, not no price. Up to twice what was
                                  -- asked; the cheaper total still wins.
                                  AND (
                                      need.packages * pack.size <= item.target_quantity * item.quantity
                                          * CASE WHEN requested_intent.default_base_unit = 'piece'
                                                 THEN 2 ELSE 1.25 END
                                      -- One pack of up to twice the amount: "šećer 500 g"
                                      -- in a shop with only a kilogram of white sugar.
                                      -- It comes after every pack that fits (V125).
                                      OR (need.packages = 1
                                          AND pack.size <= item.target_quantity * item.quantity * 2)
                                  )
                              ))
                              -- Found by one of the intent's types, so it is of a kind
                              -- the item allows: asked again, that was 30,000 lookups.
                              AND (requested_intent.required_name_pattern IS NULL
                                   OR product.normalized_name ~ requested_intent.required_name_pattern)
                              -- The product's canonical brand is read only for an item
                              -- with a brand, not for every product of every item.
                              AND (
                                  item.required_brand IS NULL
                                  OR EXISTS (
                                      SELECT 1
                                      FROM (SELECT 1) AS one
                                      LEFT JOIN app.canonical_product AS canonical
                                        ON canonical.id = product.canonical_product_id
                                      WHERE LOWER(BTRIM(COALESCE(
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
                            -- The two halves never share an item, so nothing
                            -- needs removing twice.
                            UNION ALL
                            SELECT item.id,
                                   product.id,
                                   product.retailer_id,
                                   pack.by_the_kilogram,
                                   pack.size AS package_size,
                                   pack.unit AS package_unit,
                                   need.packages,
                                   -- Everything the offer below ranks by but the price
                                   -- is the same in every shop of the chain: worked out
                                   -- here once, not again for every shop.
                                     CASE
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
                                     END AS kind_rank,
                                     -- The usual kind before an unusual one: white
                                     -- sugar before powdered, plain bananas before
                                     -- red ones. Ranked, not filtered: a shop that
                                     -- has only the unusual kind still offers it
                                     -- (V125).
                                     CASE
                                         WHEN requested_intent.uncommon_name_pattern IS NOT NULL
                                          AND product.normalized_name ~ requested_intent.uncommon_name_pattern
                                             THEN 1
                                         ELSE 0
                                     END AS uncommon_rank,
                                     -- What the line asks for beyond the kind:
                                     -- "feta sir", "mleko 3,2%". A product that
                                     -- does not say it comes after every one that
                                     -- does, and the plan names it a substitute
                                     -- (V140).
                                     -- A dictionary word narrower than its kind
                                     -- ("kajmak") only orders: "kisela voda" is
                                     -- mineral water too, "pečurke" šampinjoni, so
                                     -- another name is no substitute (proba V140).
                                     CASE
                                         WHEN item.wanted_name_pattern IS NOT NULL
                                          AND product.normalized_name !~ item.wanted_name_pattern
                                             THEN CASE
                                                 WHEN STARTS_WITH(item.wanted_name_pattern, '^(?=([^ ]+ ){0,2}')
                                                     THEN 2
                                                 ELSE 1
                                             END
                                         ELSE 0
                                     END AS wanted_rank,
                                     -- Fruit and vegetables with no amount are a
                                     -- kilogram weighed out, not the smallest
                                     -- bag: "paradajz" is loose tomatoes before
                                     -- a 500 g mix (V137).
                                     CASE
                                         WHEN requested_intent.weighed_out
                                          AND item.target_quantity IS NULL
                                          AND NOT pack.by_the_kilogram
                                             THEN 1
                                         ELSE 0
                                     END AS loose_rank,
                                     -- A pack bigger than a quarter over the amount
                                     -- only where nothing fits better.
                                     CASE
                                         WHEN item.target_quantity IS NOT NULL
                                          AND need.packages * pack.size > item.target_quantity * item.quantity
                                              * CASE WHEN requested_intent.default_base_unit = 'piece'
                                                     THEN 2 ELSE 1.25 END
                                             THEN 1
                                         ELSE 0
                                     END AS oversize_rank,
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
                                         -- "Toalet papir 10/1" is ten rolls; an
                                         -- intent counted in pieces reads the
                                         -- count from the name (V126).
                                         WHEN pack.one_size IS NULL
                                          AND requested_intent.default_base_unit IS NULL
                                          AND requested_intent.default_max_package_quantity < 100
                                          AND SUBSTRING(product.name FROM '(?:^|\\D)(\\d{1,3}) ?/ ?1(?:\\D|$)')
                                              IS NOT NULL
                                             THEN CASE
                                                      WHEN SUBSTRING(product.name FROM '(?:^|\\D)(\\d{1,3}) ?/ ?1(?:\\D|$)')::NUMERIC
                                                           BETWEEN requested_intent.default_min_package_quantity
                                                               AND requested_intent.default_max_package_quantity
                                                          THEN 0
                                                      ELSE 2
                                                  END
                                         -- A size we cannot read comes before one
                                         -- we know is wrong: one roll of toilet
                                         -- paper is not the usual pack (V126).
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
                                         ELSE 2
                                     END AS size_rank,
                                     -- Thirteen 16 g sachets for "kafa 200 g" cost more
                                     -- than the shelf price says: every pack beyond
                                     -- three, or beyond the count asked for, weighs 5%
                                     -- more. Relative, so it reads the same in any
                                     -- currency (V125).
                                     CASE
                                               WHEN item.target_quantity IS NULL
                                                 OR pack.by_piece
                                                 OR pack.by_the_kilogram
                                                   THEN 1
                                               ELSE 1 + 0.05 * GREATEST(
                                                   0,
                                                   need.packages - GREATEST(3, item.quantity)
                                               )
                                           END AS pack_weight,
                                     -- Dve ponude koje koštaju isto: bira se
                                     -- ona koju kupac zaista kupuje, po
                                     -- skeniranim računima. Stoji tek posle
                                     -- cene, jer plan ostaje najjeftiniji —
                                     -- navika ne sme da košta ni dinar. Bez
                                     -- ijednog računa lista je prazna i ovaj
                                     -- red ne razlikuje ništa.
                                     CASE
                                         WHEN product.product_family_id =
                                              ANY (:boughtFamilyIds)
                                             THEN 0
                                         ELSE 1
                                     END AS bought_rank,
                                     (
                                         SELECT typical.typical_price
                                         FROM app.retailer AS chain
                                         JOIN app.product_family_typical_price AS typical
                                           ON typical.market_id = chain.market_id
                                         WHERE chain.id = product.retailer_id
                                           AND typical.product_family_id = product.product_family_id
                                     ) AS typical_price
                            FROM (
                                SELECT DISTINCT requested_store.retailer_id
                                FROM app.store AS requested_store
                                WHERE requested_store.id IN (:storeIds)
                            ) AS chain
                            -- Only the items this half is for: an exact product,
                            -- a family, or a category without an intent. Joined
                            -- to every product first, a list of intents built
                            -- 900,000 rows here and threw all of them away
                            -- (0.95 s of a 3.9 s plan).
                            CROSS JOIN (
                                SELECT own_item.*
                                FROM app.shopping_list_item AS own_item
                                WHERE own_item.shopping_list_id = :listId
                                  AND NOT (
                                      own_item.matching_rule = 'FLEXIBLE_CATEGORY'
                                      AND EXISTS (
                                          SELECT 1
                                          FROM app.shopping_intent AS own_intent
                                          WHERE own_intent.id = own_item.shopping_intent_id
                                            AND own_intent.active = TRUE
                                      )
                                  )
                                  -- A product or family not yet found has no
                                  -- offer; it was still tried against every
                                  -- product of every chain.
                                  AND (
                                      own_item.matching_rule = 'FLEXIBLE_CATEGORY'
                                      OR (own_item.matching_rule = 'EXACT_PRODUCT'
                                          AND own_item.matching_status IN ('AUTO_MATCHED', 'CONFIRMED'))
                                      OR (own_item.matching_rule = 'PRODUCT_FAMILY'
                                          AND own_item.matching_status = 'CONFIRMED')
                                  )
                            ) AS item
                        LEFT JOIN LATERAL (
                            SELECT intent.id AS shopping_intent_id,
                                   intent.default_min_package_quantity,
                                   intent.default_max_package_quantity,
                                   intent.default_base_unit,
                                   intent.weighed_out,
                                   alias.required_name_pattern,
                                   alias.uncommon_name_pattern
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
                                            WHEN counted.by_the_kilogram
                                            THEN 1000
                                            ELSE product.quantity_value
                                       END AS size,
                                       CASE WHEN counted.by_piece
                                            THEN 'piece'
                                            WHEN counted.by_the_kilogram
                                            THEN 'g'
                                            ELSE product.base_unit
                                       END AS unit,
                                       -- One bottle or can, for the usual size.
                                       CASE WHEN counted.by_piece
                                            THEN product.quantity_value
                                                 / product.package_count
                                            ELSE product.quantity_value
                                       END AS one_size,
                                       product.base_unit AS one_unit,
                                       counted.by_piece,
                                       counted.by_the_kilogram
                                FROM (
                                    SELECT COALESCE(
                                               item.required_base_unit = 'piece'
                                               AND product.base_unit IN ('g', 'ml')
                                               AND requested_intent.default_base_unit
                                                   IS DISTINCT FROM 'piece'
                                               AND product.name !~* '\\m(rinfuz|cca)\\M',
                                               FALSE
                                           ) AS by_piece,
                                           -- "jabuke 2kg" of apples sold loose by
                                           -- the kilogram: each kilogram is a
                                           -- pack (V90).
                                           CASE WHEN item.target_quantity IS NOT NULL
                                                 AND item.required_base_unit = 'g'
                                                 AND product.quantity_value IS NULL
                                                THEN app.sold_by_the_kilogram(product.id)
                                                -- With no amount, a kilogram of
                                                -- fruit or vegetables (V137).
                                                WHEN item.target_quantity IS NULL
                                                 AND item.required_base_unit IS NULL
                                                 AND requested_intent.weighed_out
                                                 AND product.quantity_value IS NULL
                                                THEN app.sold_by_the_kilogram(product.id)
                                                ELSE FALSE
                                           END AS by_the_kilogram
                                ) AS counted
                            ) pack
                            CROSS JOIN LATERAL (
                                SELECT CASE WHEN item.target_quantity IS NULL THEN item.quantity
                                    -- Loose goods are weighed out: "banane 1,5 kg"
                                    -- is 1,5 kg at the price of one (V125).
                                    WHEN pack.by_the_kilogram
                                    THEN ROUND(item.target_quantity * item.quantity / NULLIF(pack.size, 0), 3)
                                    ELSE CEIL(item.target_quantity * item.quantity / NULLIF(pack.size, 0))
                                END AS packages
                            ) need
                            WHERE item.shopping_list_id = :listId
                              AND NOT (
                                  item.matching_rule = 'FLEXIBLE_CATEGORY'
                                  AND requested_intent.shopping_intent_id IS NOT NULL
                              )
                              -- Asked once, before any product is read: a list with
                              -- no such item skips this half. On a fresh copy the
                              -- planner read all 344,000 products first, to keep
                              -- none (0.5 s of a 0.9 s plan).
                              AND EXISTS (
                                  SELECT 1
                                  FROM app.shopping_list_item AS any_item
                                  WHERE any_item.shopping_list_id = :listId
                                    AND NOT (
                                        any_item.matching_rule = 'FLEXIBLE_CATEGORY'
                                        AND EXISTS (
                                            SELECT 1
                                            FROM app.shopping_intent AS any_intent
                                            WHERE any_intent.id = any_item.shopping_intent_id
                                              AND any_intent.active = TRUE
                                        )
                                    )
                                    AND (
                                        any_item.matching_rule = 'FLEXIBLE_CATEGORY'
                                        OR (any_item.matching_rule = 'EXACT_PRODUCT'
                                            AND any_item.matching_status IN ('AUTO_MATCHED', 'CONFIRMED'))
                                        OR (any_item.matching_rule = 'PRODUCT_FAMILY'
                                            AND any_item.matching_status = 'CONFIRMED')
                                    )
                              )
                              AND (item.target_quantity IS NULL OR (
                                  pack.size > 0 AND pack.unit = item.required_base_unit
                                  -- Eggs come in cartons of 6, 10, 15 or 30: twelve
                                  -- from a shop with only cartons of ten are two
                                  -- cartons, not no price. Up to twice what was
                                  -- asked; the cheaper total still wins.
                                  AND (
                                      need.packages * pack.size <= item.target_quantity * item.quantity
                                          * CASE WHEN requested_intent.default_base_unit = 'piece'
                                                 THEN 2 ELSE 1.25 END
                                      -- One pack of up to twice the amount: "šećer 500 g"
                                      -- in a shop with only a kilogram of white sugar.
                                      -- It comes after every pack that fits (V125).
                                      OR (need.packages = 1
                                          AND pack.size <= item.target_quantity * item.quantity * 2)
                                  )
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
                                              AND CASE
                                                  -- The line's words, wherever in the
                                                  -- name and whatever the type:
                                                  -- "vlažne maramice" are "MARAMICE
                                                  -- VLAŽNE BABY" too (V140).
                                                  WHEN item.wanted_name_pattern IS NOT NULL
                                                      THEN product.normalized_name
                                                           ~ item.wanted_name_pattern
                                                  ELSE NOT EXISTS (
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
                                              END
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
                        ),
                        -- Products shoppers said twice in two days a shop lacks
                        -- (V121), read once instead of once per shop and product.
                        missing_in_store AS MATERIALIZED (
                            SELECT report.retailer_product_id, report.store_id
                            FROM app.product_report AS report
                            WHERE report.reason = 'NOT_IN_STORE'
                              AND report.store_id IN (:storeIds)
                              AND report.created_at >= (CAST(:asOfDate AS DATE) - 2)::TIMESTAMP AT TIME ZONE 'UTC'
                              AND report.created_at < (CAST(:asOfDate AS DATE) + 1)::TIMESTAMP AT TIME ZONE 'UTC'
                            GROUP BY report.retailer_product_id, report.store_id
                            HAVING COUNT(DISTINCT COALESCE(report.client_token_hash, report.id::TEXT)) >= 2
                        ),
                        -- A shop without a price list or reports of its own pays
                        -- what its chain's list for its format says, the same as
                        -- every other such shop of that format: twenty shops of
                        -- a few chains looked up the same prices twenty times.
                        -- One shop of each such group is priced; the others
                        -- take its offers.
                        requested_store AS MATERIALIZED (
                            SELECT shop.store_id,
                                   MIN(shop.store_id) OVER (PARTITION BY shop.price_key)
                                       AS pricing_store_id
                            FROM (
                                SELECT store.id AS store_id,
                                       CASE
                                           WHEN EXISTS (
                                               SELECT 1
                                               FROM app.store_price_format_mapping AS mapping
                                               WHERE mapping.retailer_id = store.retailer_id
                                                 AND mapping.store_external_code = store.external_code
                                                 AND mapping.active = TRUE
                                                 AND mapping.verification_status = 'VERIFIED'
                                           )
                                           OR EXISTS (
                                               SELECT 1
                                               FROM app.current_price_offer AS own_offer
                                               WHERE own_offer.store_id = store.id
                                           )
                                           OR EXISTS (
                                               SELECT 1
                                               FROM app.price_observation AS own_observation
                                               WHERE own_observation.store_id = store.id
                                           )
                                           OR EXISTS (
                                               SELECT 1
                                               FROM app.product_report AS report
                                               WHERE report.store_id = store.id
                                                 AND report.reason = 'NOT_IN_STORE'
                                           )
                                               THEN 'STORE:' || store.id
                                           ELSE 'FORMAT:' || store.retailer_id
                                               || ':' || LOWER(BTRIM(format.name))
                                               || ':' || LOWER(BTRIM(format.code))
                                       END AS price_key
                                FROM app.store AS store
                                JOIN app.store_format AS format
                                  ON format.id = store.store_format_id
                                WHERE store.id IN (:storeIds)
                                  AND (
                                      store.pricing_eligible = TRUE
                                      OR (:includeUnlocated AND store.location IS NULL)
                                  )
                            ) AS shop
                        ),
                        -- The shops priced, with what their price lists are
                        -- matched by.
                        pricing_store AS MATERIALIZED (
                            SELECT store.id AS store_id,
                                   store.retailer_id,
                                   LOWER(BTRIM(COALESCE(
                                       price_mapping.retailer_format_name,
                                       format.name
                                   ))) AS format_name_key,
                                   CASE
                                       WHEN price_mapping.id IS NULL
                                           THEN LOWER(BTRIM(format.code))
                                   END AS format_code_key
                            FROM requested_store
                            JOIN app.store AS store
                              ON store.id = requested_store.store_id
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
                            WHERE requested_store.store_id =
                                  requested_store.pricing_store_id
                        ),
                        -- Whether an old observation could still price a
                        -- candidate: one whose list has no current offer of it.
                        -- Asked once per candidate, not again in every shop;
                        -- almost never true, and looking was 1.1 s of a 4.6 s
                        -- plan on the server.
                        candidate_row AS MATERIALIZED (
                            SELECT candidate.item_id,
                                   candidate.retailer_id,
                                   candidate.retailer_product_id,
                                   candidate.package_size,
                                   candidate.package_unit,
                                   candidate.packages,
                                   candidate.kind_rank,
                                   candidate.uncommon_rank,
                                   candidate.wanted_rank,
                                   candidate.loose_rank,
                                   candidate.oversize_rank,
                                   candidate.size_rank,
                                   candidate.pack_weight,
                                   candidate.bought_rank,
                                   candidate.typical_price,
                                   EXISTS (
                                       SELECT 1
                                       FROM app.price_observation AS loose
                                       CROSS JOIN LATERAL (
                                           SELECT CASE
                                                      WHEN loose.store_id IS NOT NULL
                                                          THEN 'STORE:' || loose.store_id::TEXT
                                                      WHEN NULLIF(BTRIM(
                                                          loose.retailer_format_name
                                                      ), '') IS NOT NULL
                                                          THEN 'STORE_FORMAT:' ||
                                                               LOWER(BTRIM(
                                                                   loose.retailer_format_name
                                                               ))
                                                      ELSE 'RETAILER'
                                                  END AS scope_key
                                       ) AS loose_scope
                                       -- Only one in its chain's newest list can price
                                       -- anything (V75). Without asking, IDEA's retired
                                       -- zone lists opened the search below for a
                                       -- quarter of the candidates, to find nothing.
                                       LEFT JOIN app.price_list_snapshot AS loose_list
                                         ON loose_list.retailer_id = candidate.retailer_id
                                        AND loose_list.scope_key = loose_scope.scope_key
                                       WHERE loose.retailer_product_id =
                                               candidate.retailer_product_id
                                         AND loose.price_date <= :asOfDate
                                         AND COALESCE(
                                             CASE
                                                 WHEN loose_list.latest_price_date > :asOfDate
                                                     THEN TRUE
                                                 ELSE loose_list.latest_price_date = loose.price_date
                                                      AND NOT loose_list.retired
                                             END,
                                             TRUE
                                         )
                                         AND NOT EXISTS (
                                             SELECT 1
                                             FROM app.current_price_offer
                                                 AS loose_current
                                             WHERE loose_current.retailer_product_id =
                                                   loose.retailer_product_id
                                               AND loose_current.price_date <=
                                                   :asOfDate
                                               AND loose_current.scope_key =
                                                   loose_scope.scope_key
                                         )
                                   ) AS has_loose_observation
                            FROM candidate
                        ),
                        -- Every price a candidate has in the newest lists, read
                        -- once per product. Looked up again for every shop of
                        -- the chain, the same offers were read 33,000 times for
                        -- 13,600 products (2.5 s of a 4.6 s plan on the server).
                        price_source AS MATERIALIZED (
                            SELECT wanted.retailer_product_id,
                                   source.observed,
                                   source.source_store_id,
                                   source.scope_type,
                                   source.format_key,
                                   source.price_date,
                                   source.regular_price,
                                   source.discounted_price,
                                   source.effective_price,
                                   source.price_scope,
                                   source.source_priority,
                                   source.id
                            FROM (
                                SELECT candidate_row.retailer_product_id,
                                       MIN(candidate_row.retailer_id) AS retailer_id,
                                       BOOL_OR(candidate_row.has_loose_observation)
                                           AS has_loose_observation
                                FROM candidate_row
                                GROUP BY candidate_row.retailer_product_id
                            ) AS wanted
                            CROSS JOIN LATERAL (
                                SELECT FALSE AS observed,
                                       current_offer.store_id AS source_store_id,
                                       current_offer.scope_type,
                                       LOWER(BTRIM(
                                           current_offer.retailer_format_name
                                       )) AS format_key,
                                       current_offer.price_date,
                                       current_offer.regular_price,
                                       current_offer.discounted_price,
                                       -- A sale counts only while it lasts (V115).
                                       app.effective_price(
                                           current_offer.regular_price,
                                           current_offer.discounted_price,
                                           current_offer.discount_start,
                                           current_offer.discount_end,
                                           :asOfDate
                                       ) AS effective_price,
                                       current_offer.scope_type AS price_scope,
                                       0 AS source_priority,
                                       current_offer.id,
                                       current_offer.scope_key
                                FROM app.current_price_offer AS current_offer
                                WHERE current_offer.retailer_product_id =
                                        wanted.retailer_product_id
                                  AND current_offer.price_date <= :asOfDate
                                UNION ALL
                                SELECT TRUE AS observed,
                                       observation.store_id AS source_store_id,
                                       NULL AS scope_type,
                                       LOWER(NULLIF(BTRIM(
                                           observation.retailer_format_name
                                       ), '')) AS format_key,
                                       observation.price_date,
                                       observation.regular_price,
                                       observation.discounted_price,
                                       -- A sale counts only while it lasts (V115).
                                       app.effective_price(
                                           observation.regular_price,
                                           observation.discounted_price,
                                           observation.discount_start,
                                           observation.discount_end,
                                           :asOfDate
                                       ) AS effective_price,
                                       CASE
                                           WHEN observation.store_id IS NOT NULL
                                               THEN 'STORE'
                                           WHEN NULLIF(BTRIM(
                                               observation.retailer_format_name
                                           ), '') IS NOT NULL
                                               THEN 'STORE_FORMAT'
                                           ELSE 'RETAILER'
                                       END AS price_scope,
                                       1 AS source_priority,
                                       observation.id,
                                       observation_scope.scope_key
                                FROM app.price_observation AS observation
                                CROSS JOIN LATERAL (
                                    SELECT CASE
                                               WHEN observation.store_id IS NOT NULL
                                                   THEN 'STORE:' || observation.store_id::TEXT
                                               WHEN NULLIF(BTRIM(observation.retailer_format_name), '') IS NOT NULL
                                                   THEN 'STORE_FORMAT:' || LOWER(BTRIM(observation.retailer_format_name))
                                               ELSE 'RETAILER'
                                           END AS scope_key
                                ) AS observation_scope
                                -- Almost never: see candidate_row above.
                                WHERE wanted.has_loose_observation
                                  AND observation.retailer_product_id =
                                        wanted.retailer_product_id
                                  AND observation.price_date <= :asOfDate
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM app.current_price_offer
                                          AS available_current
                                      WHERE available_current.retailer_product_id =
                                            observation.retailer_product_id
                                        AND available_current.price_date <=
                                            :asOfDate
                                        AND available_current.scope_key =
                                            observation_scope.scope_key
                                  )
                            ) AS source
                            -- Only a price in the chain's newest list: a dropped
                            -- product keeps its old price (V75). The same test as
                            -- app.in_latest_price_list, asked once for all offers:
                            -- inside each product's lookup the lists were read
                            -- again for every offer.
                            LEFT JOIN app.price_list_snapshot AS snapshot
                              ON snapshot.retailer_id = wanted.retailer_id
                             AND snapshot.scope_key = source.scope_key
                            WHERE source.effective_price > 0
                              AND COALESCE(
                                  CASE
                                      WHEN snapshot.latest_price_date > :asOfDate
                                          THEN TRUE
                                      ELSE snapshot.latest_price_date = source.price_date
                                           AND NOT snapshot.retired
                                  END,
                                  TRUE
                              )
                        ),
                        -- Each shop's price of each candidate: its own price
                        -- first, then its format's, then the chain's; the
                        -- newest, a current offer before an old observation.
                        store_price AS MATERIALIZED (
                            SELECT DISTINCT ON (
                                       matched.store_id,
                                       matched.retailer_product_id
                                   )
                                   matched.*
                            FROM (
                                -- Sorted by, first (see best_offer below).
                                SELECT pricing_store.store_id,
                                       price_source.retailer_product_id,
                                       CASE
                                           WHEN price_source.source_store_id =
                                                pricing_store.store_id
                                               THEN 1
                                           WHEN (
                                                   (NOT price_source.observed
                                                    AND price_source.scope_type =
                                                        'STORE_FORMAT')
                                                   OR (price_source.observed
                                                       AND price_source.source_store_id IS NULL
                                                       AND price_source.format_key IS NOT NULL)
                                                )
                                            AND (
                                                price_source.format_key =
                                                    pricing_store.format_name_key
                                                OR price_source.format_key =
                                                    pricing_store.format_code_key
                                            )
                                               THEN 2
                                           ELSE 3
                                       END AS scope_priority,
                                       price_source.price_date,
                                       price_source.source_priority,
                                       price_source.id,
                                       price_source.observed,
                                       price_source.source_store_id,
                                       price_source.scope_type,
                                       price_source.format_key,
                                       price_source.regular_price,
                                       price_source.discounted_price,
                                       price_source.effective_price,
                                       price_source.price_scope
                                FROM price_source
                                JOIN (
                                    SELECT DISTINCT candidate_row.retailer_id,
                                           candidate_row.retailer_product_id
                                    FROM candidate_row
                                ) AS wanted
                                  ON wanted.retailer_product_id =
                                     price_source.retailer_product_id
                                JOIN pricing_store
                                  ON pricing_store.retailer_id = wanted.retailer_id
                                WHERE price_source.source_store_id =
                                          pricing_store.store_id
                                   OR (
                                       NOT price_source.observed
                                       AND (
                                           (price_source.scope_type = 'STORE_FORMAT'
                                            AND (
                                                price_source.format_key =
                                                    pricing_store.format_name_key
                                                OR price_source.format_key =
                                                    pricing_store.format_code_key
                                            ))
                                           OR price_source.scope_type = 'RETAILER'
                                       )
                                   )
                                   OR (
                                       price_source.observed
                                       AND price_source.source_store_id IS NULL
                                       AND (
                                           price_source.format_key IS NULL
                                           OR price_source.format_key =
                                               pricing_store.format_name_key
                                           OR price_source.format_key =
                                               pricing_store.format_code_key
                                       )
                                   )
                            ) AS matched
                            ORDER BY matched.store_id,
                                     matched.retailer_product_id,
                                     matched.scope_priority ASC,
                                     matched.price_date DESC,
                                     matched.source_priority ASC,
                                     matched.id DESC
                        ),
                        -- For each shop and item, the first candidate by the
                        -- item's ranks, then by what it costs.
                        best_offer AS MATERIALIZED (
                            SELECT DISTINCT ON (
                                       store_price.store_id,
                                       candidate_row.item_id
                                   )
                                   store_price.store_id,
                                   candidate_row.item_id,
                                   -- What it is sorted by comes first, the whole
                                   -- numbers before the prices. Behind the prices,
                                   -- each comparison among an item's fifty or so
                                   -- candidates read along the row to its ranks
                                   -- again: the sort took 40 ms of a 313 ms plan.
                                   candidate_row.wanted_rank,
                                   candidate_row.kind_rank,
                                   candidate_row.uncommon_rank,
                                   candidate_row.loose_rank,
                                   candidate_row.oversize_rank,
                                   candidate_row.size_rank,
                                   candidate_row.bought_rank,
                                   -- The usual kind in the usual size: a plan over
                                   -- several shops takes it before a cheaper
                                   -- 35 g sachet elsewhere, as one shop does.
                                   (candidate_row.wanted_rank + candidate_row.uncommon_rank + candidate_row.loose_rank
                                       + candidate_row.oversize_rank + candidate_row.size_rank = 0)
                                       AS usual_choice,
                                   candidate_row.retailer_product_id,
                                   store_price.effective_price * candidate_row.packages
                                       * candidate_row.pack_weight AS weighted_price,
                                   candidate_row.packages * candidate_row.package_size
                                       AS offered_amount,
                                   candidate_row.package_size,
                                   candidate_row.package_unit,
                                   candidate_row.packages,
                                   store_price.price_date,
                                   store_price.regular_price,
                                   store_price.discounted_price,
                                   store_price.effective_price,
                                   store_price.price_scope
                            FROM candidate_row
                            JOIN store_price
                              ON store_price.retailer_product_id =
                                 candidate_row.retailer_product_id
                            WHERE NOT EXISTS (
                                      -- Kupci su u ovoj radnji javili da ga nema (V121).
                                      SELECT 1
                                      FROM missing_in_store
                                      WHERE missing_in_store.retailer_product_id =
                                            candidate_row.retailer_product_id
                                        AND missing_in_store.store_id =
                                            store_price.store_id
                                  )
                              -- Far below what other chains charge, the price
                              -- is most likely for one piece or one kilogram
                              -- of a bigger pack (V72). The product screen
                              -- shows it to be checked; a plan never counts
                              -- on it.
                              AND NOT app.price_needs_check(
                                  store_price.regular_price,
                                  store_price.discounted_price,
                                  candidate_row.typical_price
                              )
                            ORDER BY store_price.store_id,
                                     candidate_row.item_id,
                                     candidate_row.wanted_rank ASC,
                                     candidate_row.kind_rank ASC,
                                     candidate_row.uncommon_rank ASC,
                                     candidate_row.loose_rank ASC,
                                     candidate_row.oversize_rank ASC,
                                     candidate_row.size_rank ASC,
                                     store_price.effective_price * candidate_row.packages
                                         * candidate_row.pack_weight ASC,
                                     candidate_row.bought_rank ASC,
                                     candidate_row.packages * candidate_row.package_size ASC NULLS LAST,
                                     candidate_row.retailer_product_id ASC
                        ),
                        priced AS MATERIALIZED (
                        SELECT pricing_store.store_id,
                               item.created_at AS item_created_at,
                               item.id AS item_id,
                               item.name AS requested_name,
                               item.quantity AS requested_quantity,
                               item.matching_rule,
                               item.matching_status,
                               offer.retailer_product_id,
                               product.canonical_product_id,
                               product.name AS product_name,
                               COALESCE(
                                   canonical.brand,
                                   product.brand
                               ) AS product_brand,
                               product.barcode AS product_barcode,
                               offer.price_date,
                               offer.regular_price,
                               offer.discounted_price,
                               offer.effective_price,
                               ROUND(
                                   offer.effective_price * offer.packages,
                                   2
                               ) AS line_total,
                               offer.price_scope,
                               COALESCE(offer.usual_choice, TRUE) AS usual_choice,
                               COALESCE(offer.wanted_rank = 1, FALSE) AS wanted_missing,
                               offer.packages, offer.package_size, offer.package_unit,
                               item.target_quantity * item.quantity AS target_amount,
                               offer.packages * offer.package_size AS supplied_amount,
                               offer.packages * offer.package_size - item.target_quantity * item.quantity AS extra_amount,
                               ROUND(offer.effective_price / NULLIF(offer.package_size, 0) *
                                   CASE WHEN offer.package_unit IN ('g','ml') THEN 1000 ELSE 1 END, 2) AS unit_price
                        FROM pricing_store
                        CROSS JOIN app.shopping_list_item AS item
                        LEFT JOIN best_offer AS offer
                          ON offer.store_id = pricing_store.store_id
                         AND offer.item_id = item.id
                        LEFT JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        LEFT JOIN app.canonical_product AS canonical
                          ON canonical.id = product.canonical_product_id
                        WHERE item.shopping_list_id = :listId
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
                               priced.item_id,
                               priced.requested_name,
                               priced.requested_quantity,
                               priced.matching_rule,
                               priced.matching_status,
                               priced.retailer_product_id,
                               priced.canonical_product_id,
                               priced.product_name,
                               priced.product_brand,
                               priced.product_barcode,
                               priced.price_date,
                               priced.regular_price,
                               priced.discounted_price,
                               priced.effective_price,
                               priced.line_total,
                               priced.price_scope,
                               priced.usual_choice,
                               priced.wanted_missing,
                               priced.packages, priced.package_size, priced.package_unit,
                               priced.target_amount,
                               priced.supplied_amount,
                               priced.extra_amount,
                               priced.unit_price
                        FROM requested_store
                        JOIN priced
                          ON priced.store_id = requested_store.pricing_store_id
                        JOIN app.store AS store
                          ON store.id = requested_store.store_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        JOIN app.store_format AS format
                          ON format.id = store.store_format_id
                        ORDER BY store.id ASC,
                                 priced.item_created_at ASC,
                                 priced.item_id ASC
                        """)
                .param("asOfDate", asOfDate)
                .param("listId", listId)
                .param("storeIds", storeIds)
                .param("includeUnlocated", includeUnlocated)
                .param("boughtFamilyIds", boughtFamilyIds(listId))
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * Prices a basket against published price lists whose shop location is
     * unknown (see V65). The recommendation asks for these together with the
     * nearby shops; this stays for the tests and for asking on its own.
     */
    public List<StoreItemOffer> findPriceListOffers(
            Long listId,
            List<Long> priceListEntryIds,
            LocalDate asOfDate
    ) {
        return findOffers(listId, priceListEntryIds, asOfDate, true);
    }

    /** Published price lists that no shop location could be attached to. */
    public List<PriceListEntry> findPriceListEntriesWithoutLocation(int marketId) {
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
                          AND retailer.market_id = :marketId
                        ORDER BY retailer.code, store.name
                        """)
                .param("marketId", marketId)
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
