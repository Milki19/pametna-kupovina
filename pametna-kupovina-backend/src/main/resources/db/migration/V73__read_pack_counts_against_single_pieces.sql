-- "0.33L CORONA NB 6/1" is six bottles of a third of a litre, but "NUDLE UKUS
-- PILETINE INDOMIE 700G 10/1" is 700 g in ten bags. The name cannot say which,
-- so V71 left every "N/1" alone, and METRO's case of six looked like one
-- bottle for 1.200 RSD.
--
-- A single piece of the same brand decides: a 0,33 l bottle means the size is
-- per piece, a 70 g bag means the size is the whole pack. The single must be
-- in the same product category, and its price times the count must be within
-- 60% to 150% of the pack's price (the median over all such singles). Where
-- both readings fit, or neither, the name is left alone. Without the category
-- check, "KOLAČ KREMPITA 2/1" was read against an ice cream cup and a tortilla
-- pack against crisps.
--
-- The pack keeps its single piece when the two names are alike, and takes
-- that single's product type when it has none of its own. METRO's "CORONA NB
-- 6/1" never says "pivo", so a shopping list asking for beer never saw it.
-- A list item that asks for a number of pieces ("pivo", brand Corona, 6 kom)
-- now counts bottles and cans (StoreShoppingOfferRepository), so six bottles
-- and a case of six are compared by price.

ALTER TABLE app.retailer_product
    ADD COLUMN package_unit_product_id BIGINT;

ALTER TABLE app.retailer_product
    ADD CONSTRAINT fk_retailer_product_package_unit
        FOREIGN KEY (package_unit_product_id)
        REFERENCES app.retailer_product (id)
        ON DELETE SET NULL;

CREATE OR REPLACE FUNCTION app.refresh_package_sizes(target_retailer_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    -- Nothing is sold by the fraction of a millilitre, and a real 0.12 g of
    -- saffron is written with two decimals, not three.
    UPDATE app.retailer_product AS product
    SET quantity_value = product.quantity_value * 1000
    WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
      AND product.quantity_value < 1
      AND (
          product.base_unit = 'ml'
          OR (
              product.base_unit = 'g'
              AND product.name ~* '(^|[^0-9])0[.,][0-9]{3}\s*(g|gr)\M'
          )
      );

    UPDATE app.retailer_product AS product
    SET quantity_value = NULL,
        base_unit = NULL
    WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
      AND product.base_unit = 'g'
      AND product.quantity_value < 1
      AND product.name ~* '[0-9]\s*g[0-9]';

    -- Only drinks, only the usual bottle and can sizes, and never a multipack:
    -- "2X1.5" is two packs of a litre and a half.
    UPDATE app.retailer_product AS product
    SET quantity_value = drink.millilitres,
        base_unit = 'ml'
    FROM (
        SELECT candidate.id,
               REPLACE(
                   SUBSTRING(
                       candidate.name
                       FROM '(?:^|[^0-9,.x/])(0[.,](?:2|25|33|375|5|7|75)|1[.,](?:5|75))(?:[^0-9a-z%,.]|$)'
                   ),
                   ',',
                   '.'
               )::NUMERIC * 1000 AS millilitres
        FROM app.retailer_product AS candidate
        WHERE (target_retailer_id IS NULL OR candidate.retailer_id = target_retailer_id)
          AND candidate.quantity_value IS NULL
          AND candidate.name !~* '[0-9]\s*[x×]\s*[0-9]'
          AND EXISTS (
              SELECT 1
              FROM app.retailer_product_category AS assignment
              JOIN app.product_category AS category
                ON category.id = assignment.product_category_id
              WHERE assignment.retailer_product_id = candidate.id
                AND category.code IN ('BEVERAGES', 'WATER', 'BEER', 'WINE', 'SPIRITS')
          )
    ) AS drink
    WHERE drink.id = product.id
      AND drink.millilitres IS NOT NULL;

    -- Temporary tables with statistics: as common table expressions the
    -- case join below compared every product with every other one.
    DROP TABLE IF EXISTS pg_temp.package_scoped;
    DROP TABLE IF EXISTS pg_temp.package_case;

    CREATE TEMP TABLE package_scoped ON COMMIT DROP AS
    SELECT product.id,
           product.retailer_id,
           product.name,
           product.normalized_name,
           product.quantity_value,
           product.base_unit,
           product.package_count,
           named.count AS named_count,
           product.quantity_value
               / COALESCE(NULLIF(named.count, 0), product.package_count)
               AS package_size,
           offers.price
    FROM app.retailer_product AS product
    CROSS JOIN LATERAL (
        SELECT NULLIF(SUBSTRING(
                   LOWER(product.name)
                   FROM '(?:^|[^-a-z0-9])([0-9]+)\s*[x×]\s*[0-9]+(?:[.,][0-9]+)?\s*(?:kilograma?|kg|grama?|gr|g|mililit[a-z]*|ml|litar[a-z]*|litr[a-z]*|l)(?:[^a-z]|$)'
               ), '')::INTEGER AS count
    ) AS named
    LEFT JOIN (
        SELECT offer.retailer_product_id,
               MIN(offer.regular_price) AS price
        FROM app.current_price_offer AS offer
        JOIN app.retailer_product AS priced_product
          ON priced_product.id = offer.retailer_product_id
        WHERE offer.regular_price > 0
          AND (target_retailer_id IS NULL OR priced_product.retailer_id = target_retailer_id)
        GROUP BY offer.retailer_product_id
    ) AS offers
      ON offers.retailer_product_id = product.id
    WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
      AND product.quantity_value IS NOT NULL;

    ANALYZE package_scoped;

    -- The same chain sells one unit and a whole case of it under one name:
    -- the case costs an exact multiple of the unit. Goods sold by weight
    -- ("cca") are priced per kilogram and excluded.
    CREATE TEMP TABLE package_case ON COMMIT DROP AS
    SELECT DISTINCT ON (whole.id)
           whole.id,
           ROUND(whole.price / unit.price)::INTEGER AS case_count
    FROM package_scoped AS whole
    JOIN package_scoped AS unit
      ON unit.retailer_id = whole.retailer_id
     AND unit.normalized_name = whole.normalized_name
     AND unit.base_unit = whole.base_unit
     AND unit.package_size = whole.package_size
     AND unit.id <> whole.id
     AND unit.package_count = 1
    WHERE whole.named_count IS NULL
      AND unit.named_count IS NULL
      AND whole.name !~* '\mcca\M'
      AND whole.price >= 1.95 * unit.price
      AND ABS(whole.price / unit.price - ROUND(whole.price / unit.price))
          <= 0.01 * (whole.price / unit.price)
    ORDER BY whole.id, unit.price;

    ANALYZE package_case;

    UPDATE app.retailer_product AS product
    SET package_count = sizes.package_count,
        quantity_value = sizes.quantity_value
    FROM (
        SELECT scoped.id,
               scoped.named_count,
               COALESCE(package_case.case_count, NULLIF(scoped.named_count, 0), 1)
                   AS package_count,
               CASE
                   WHEN scoped.named_count IS NOT NULL THEN scoped.quantity_value
                   ELSE scoped.package_size * COALESCE(package_case.case_count, 1)
               END AS quantity_value
        FROM package_scoped AS scoped
        LEFT JOIN package_case ON package_case.id = scoped.id
    ) AS sizes
    WHERE sizes.id = product.id
      AND (
          product.package_count <> sizes.package_count
          OR product.quantity_value <> sizes.quantity_value
      )
      -- A pack read from its "N/1" below keeps that reading until its price
      -- list is imported again, which puts back the size the name states.
      -- Read again from what is left, a price that moved in the meantime
      -- could turn 700 g in ten bags into 70 g.
      AND NOT (
          sizes.named_count IS NULL
          AND product.package_count > 1
          AND product.package_count IS NOT DISTINCT FROM SUBSTRING(
              product.name FROM '(?:^|[^0-9/.,])([0-9]{1,3})/1(?:[^0-9]|$)'
          )::INTEGER
      );

    -- "N/1" packs of this chain, and single pieces of the same brands in every
    -- chain: METRO sells Corona only by the six, other chains by the bottle.
    DROP TABLE IF EXISTS pg_temp.piece_named;
    DROP TABLE IF EXISTS pg_temp.piece_candidate;
    DROP TABLE IF EXISTS pg_temp.piece_link;
    DROP TABLE IF EXISTS pg_temp.piece_reading;

    CREATE TEMP TABLE piece_named ON COMMIT DROP AS
    SELECT product.id,
           TRUE AS is_pack,
           product.brand_id,
           product.base_unit,
           product.quantity_value,
           named.count
    FROM app.retailer_product AS product
    CROSS JOIN LATERAL (
        SELECT SUBSTRING(
                   product.name FROM '(?:^|[^0-9/.,])([0-9]{1,3})/1(?:[^0-9]|$)'
               )::INTEGER AS count
    ) AS named
    WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
      AND product.package_count = 1
      AND product.quantity_value IS NOT NULL
      AND product.brand_id IS NOT NULL
      AND named.count >= 2
      AND product.name !~* '[0-9]\s*[x×]\s*[0-9]';

    INSERT INTO piece_named
    SELECT product.id,
           FALSE,
           product.brand_id,
           product.base_unit,
           product.quantity_value,
           1
    FROM app.retailer_product AS product
    WHERE product.brand_id IN (
              SELECT pack.brand_id FROM piece_named AS pack
          )
      AND product.package_count = 1
      AND product.quantity_value IS NOT NULL
      AND COALESCE(
              SUBSTRING(
                  product.name FROM '(?:^|[^0-9/.,])([0-9]{1,3})/1(?:[^0-9]|$)'
              )::INTEGER,
              1
          ) = 1
      AND product.name !~* '[0-9]\s*[x×]\s*[0-9]'
      AND product.name !~* '\mcca\M';

    CREATE TEMP TABLE piece_candidate ON COMMIT DROP AS
    SELECT candidate.*,
           -- The words without sizes and counts, to tell the same product
           -- from another one of the brand in the same size.
           app.fold_match_text(
               REGEXP_REPLACE(product.name, '\S*[0-9]\S*', ' ', 'g')
           ) AS core,
           category.product_category_id,
           priced.price,
           EXISTS (
               SELECT 1
               FROM app.retailer_product_type AS assignment
               WHERE assignment.retailer_product_id = candidate.id
           ) AS typed
    FROM piece_named AS candidate
    JOIN app.retailer_product AS product
      ON product.id = candidate.id
    CROSS JOIN LATERAL (
        SELECT MIN(assignment.product_category_id) AS product_category_id
        FROM app.retailer_product_category AS assignment
        WHERE assignment.retailer_product_id = candidate.id
    ) AS category
    CROSS JOIN LATERAL (
        SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (
                   ORDER BY COALESCE(
                       NULLIF(offer.regular_price, 0),
                       offer.discounted_price
                   )
               ) AS price
        FROM app.current_price_offer AS offer
        WHERE offer.retailer_product_id = candidate.id
          AND COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price) > 0
    ) AS priced
    WHERE priced.price IS NOT NULL;

    ANALYZE piece_candidate;

    CREATE TEMP TABLE piece_link ON COMMIT DROP AS
    SELECT pack.id AS pack_id,
           single.id AS single_id,
           single.quantity_value = pack.quantity_value AS per_piece,
           pack.price / (pack.count * single.price) AS ratio,
           public.similarity(pack.core, single.core) AS likeness,
           single.typed
    FROM piece_candidate AS pack
    JOIN piece_candidate AS single
      ON single.brand_id = pack.brand_id
     AND single.base_unit = pack.base_unit
     AND NOT single.is_pack
     AND (
         single.quantity_value = pack.quantity_value
         OR single.quantity_value * pack.count = pack.quantity_value
     )
     AND (
         pack.product_category_id IS NULL
         OR single.product_category_id IS NULL
         OR single.product_category_id = pack.product_category_id
     )
    WHERE pack.is_pack;

    ANALYZE piece_link;

    CREATE TEMP TABLE piece_reading ON COMMIT DROP AS
    SELECT fitting.pack_id,
           BOOL_AND(fitting.per_piece) AS per_piece
    FROM (
        SELECT link.pack_id,
               link.per_piece
        FROM piece_link AS link
        GROUP BY link.pack_id, link.per_piece
        HAVING PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY link.ratio)
               BETWEEN 0.6 AND 1.5
    ) AS fitting
    GROUP BY fitting.pack_id
    HAVING COUNT(*) = 1;

    -- An import put the count back to one; the single piece goes with it
    -- unless the pack is read again just below.
    UPDATE app.retailer_product AS product
    SET package_unit_product_id = NULL
    WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
      AND product.package_unit_product_id IS NOT NULL
      AND product.package_count = 1;

    -- The size stays the whole package, as everywhere else.
    UPDATE app.retailer_product AS product
    SET package_count = pack.count,
        quantity_value = CASE
                             WHEN reading.per_piece
                                 THEN pack.quantity_value * pack.count
                             ELSE pack.quantity_value
                         END,
        package_unit_product_id = (
            SELECT link.single_id
            FROM piece_link AS link
            WHERE link.pack_id = reading.pack_id
              AND link.per_piece = reading.per_piece
              AND link.likeness >= 0.5
            ORDER BY link.likeness DESC,
                     link.typed DESC,
                     ABS(link.ratio - 1),
                     link.single_id
            LIMIT 1
        )
    FROM piece_reading AS reading
    JOIN piece_candidate AS pack
      ON pack.id = reading.pack_id
    WHERE product.id = reading.pack_id;

    -- A barcode keeps the size of the first price list it appeared in; follow
    -- its products once they all agree, so the merged product shows it too.
    UPDATE app.canonical_product AS canonical
    SET quantity_value = agreed.quantity_value,
        base_unit = agreed.base_unit,
        updated_at = NOW()
    FROM (
        SELECT product.canonical_product_id,
               MIN(product.quantity_value) AS quantity_value,
               MIN(product.base_unit) AS base_unit
        FROM app.retailer_product AS product
        WHERE product.canonical_product_id IN (
            SELECT own.canonical_product_id
            FROM app.retailer_product AS own
            WHERE (target_retailer_id IS NULL OR own.retailer_id = target_retailer_id)
              AND own.canonical_product_id IS NOT NULL
        )
        GROUP BY product.canonical_product_id
        HAVING COUNT(*) = COUNT(product.quantity_value)
           AND COUNT(DISTINCT product.quantity_value) = 1
           AND COUNT(DISTINCT product.base_unit) = 1
    ) AS agreed
    WHERE agreed.canonical_product_id = canonical.id
      AND (
          canonical.quantity_value IS DISTINCT FROM agreed.quantity_value
          OR canonical.base_unit IS DISTINCT FROM agreed.base_unit
      );
END;
$$;

SELECT app.refresh_package_sizes(NULL);

-- A pack without a type takes its single piece's, unless the taxonomy is
-- still waiting for a review of it. After every import this happens in
-- ProductCatalogMaintenanceService.synchronizeProductTypes.
INSERT INTO app.retailer_product_type (
    retailer_product_id,
    product_type_id,
    confidence,
    assignment_source,
    evidence,
    algorithm_version
)
SELECT pack.id,
       unit_type.product_type_id,
       unit_type.confidence,
       'PACKAGE_UNIT',
       LEFT('Pakovanje od ' || pack.package_count || ' kom: ' || unit.name, 1000),
       unit_type.algorithm_version
FROM app.retailer_product AS pack
JOIN app.retailer_product AS unit
  ON unit.id = pack.package_unit_product_id
JOIN app.retailer_product_type AS unit_type
  ON unit_type.retailer_product_id = unit.id
WHERE NOT EXISTS (
    SELECT 1
    FROM app.product_type_candidate AS candidate
    WHERE candidate.retailer_product_id = pack.id
      AND candidate.status = 'PENDING'
)
ON CONFLICT (retailer_product_id) DO NOTHING;

-- The count is part of the product key (V71), so the packs read above are
-- grouped again.
SELECT app.assign_product_families(NULL);

DELETE FROM app.product_retailer_presence;

INSERT INTO app.product_retailer_presence (
    product_family_id,
    retailer_id,
    first_seen_date,
    last_seen_date,
    latest_price_date,
    current_offer_count,
    store_count,
    format_count,
    minimum_effective_price
)
SELECT product.product_family_id,
       product.retailer_id,
       MIN(offer.first_seen_date),
       MAX(offer.last_seen_date),
       MAX(offer.price_date),
       COUNT(*)::INTEGER,
       COUNT(DISTINCT offer.store_id)
           FILTER (WHERE offer.store_id IS NOT NULL)::INTEGER,
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name)))
           FILTER (
               WHERE NULLIF(BTRIM(offer.retailer_format_name), '') IS NOT NULL
           )::INTEGER,
       MIN(
           CASE
               WHEN offer.discounted_price > 0 THEN offer.discounted_price
               WHEN offer.regular_price > 0 THEN offer.regular_price
           END
       )
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
WHERE product.product_family_id IS NOT NULL
GROUP BY product.product_family_id, product.retailer_id;

UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM app.product_family AS target
LEFT JOIN LATERAL (
    SELECT assignment.product_type_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = target.id
    GROUP BY assignment.product_type_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_type_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = target.id
  AND family.product_type_id IS DISTINCT FROM choice.product_type_id;

UPDATE app.product_family AS family
SET product_category_id = choice.product_category_id,
    updated_at = NOW()
FROM app.product_family AS target
JOIN LATERAL (
    SELECT assignment.product_category_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_category AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = target.id
    GROUP BY assignment.product_category_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_category_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = target.id
  AND family.product_category_id IS DISTINCT FROM choice.product_category_id;

SELECT app.refresh_typical_prices();
