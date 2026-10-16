-- A block of hard cheese is quoted per kilogram also where the name has no
-- "cca".
--
-- After V127 RIČ's 3,3 kg blocks of Paladin gauda and tilsiter (1.569,99 and
-- 1.749,99, no unit price) and a tilsiter block at 1.599,99 with 484,84 "per
-- kilogram" were still listed as pieces of 3,3 kg, so their kilogram read as
-- 475-530 dinars, a third of every other chain's. Hard cheese (gauda, edamer,
-- trapist, tilsiter, kackavalj...) is cut from the block and sold by the
-- kilogram, and the chains that write no "cca" quote the block that way: its
-- price is the price of a kilogram when there is no unit price, or when the
-- unit price is that price over the weight.
--
-- White cheese, meat and anything else without "cca" stay as V127 left them
-- (METRO's 4,5 kg tub of white cheese is the tub). "cca" pieces are read as
-- in V127. The import reads new rows the same way (PriceImportService).

CREATE OR REPLACE FUNCTION app.per_kilogram_factor(
    normalized_name TEXT,
    unit TEXT,
    quantity_value NUMERIC,
    base_unit TEXT,
    quoted_price NUMERIC,
    unit_price NUMERIC
)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
               WHEN NOT COALESCE(unit_price > 0, FALSE)
                   THEN CASE WHEN normalized_name ~ '\mcca\M' OR block_cheese THEN 1 END
               WHEN unit_price = quoted_price
                    AND UPPER(BTRIM(unit)) IN ('KG', 'KGR')
                   THEN 1
               WHEN block_cheese
                    AND (unit_price = quoted_price OR price_over_weight)
                   THEN 1
               WHEN normalized_name ~ '\mcca\M' AND price_over_weight
                   THEN ROUND(1000 / quantity_value, 6)
           END
    FROM (
        SELECT normalized_name !~ '\mcca\M'
                   AND normalized_name ~ ('\m(gauda|edamer|edam|trapist|tilsiter|kackavalj'
                                          '|ementaler|emmentaler|parmezan|parmigiano|grana'
                                          '|gorgonzola|pecorino|cheddar)\M') AS block_cheese,
               COALESCE(
                   ABS(ROUND(unit_price * quantity_value / 1000, 2) - quoted_price)
                       <= GREATEST(quoted_price * 0.01, 1),
                   FALSE
               ) AS price_over_weight
    ) AS reading
    WHERE quoted_price > 0
      AND base_unit = 'g'
      AND quantity_value >= 2500
      AND normalized_name ~ ('\m(cca|sir|gauda|edamer|edam|trapist|tilsiter|kackavalj'
                             '|ementaler|emmentaler|parmezan|parmigiano|grana|brie'
                             '|camembert|gorgonzola|pecorino|cheddar|sunka|slanina'
                             '|pecenica|kulen|kobasica|salama|mortadela|panceta'
                             '|prsuta|meso|file|vrat|kare|but|plecka|rebra)\M')
      AND normalized_name !~ '\m(hrana|pas|psa|pse|macke|macka|granule|briketi)\M'
$$;

-- Every current price of a heavy piece, read by its own columns.
CREATE TEMP TABLE heavy_offer ON COMMIT DROP AS
SELECT offer.id,
       offer.retailer_product_id,
       app.per_kilogram_factor(
           product.normalized_name,
           product.unit,
           product.quantity_value,
           product.base_unit,
           COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price),
           offer.unit_price
       ) AS factor
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
WHERE product.base_unit = 'g'
  AND product.quantity_value >= 2500
  AND product.package_count = 1;

-- A product turns into one kilogram only when every one of its prices says
-- how it was quoted, so no price of it is left per piece.
CREATE TEMP TABLE per_kilogram_product ON COMMIT DROP AS
SELECT retailer_product_id AS id
FROM heavy_offer
GROUP BY retailer_product_id
HAVING BOOL_AND(factor IS NOT NULL);

UPDATE app.current_price_offer AS offer
SET regular_price = ROUND(offer.regular_price * heavy.factor, 2),
    discounted_price = ROUND(offer.discounted_price * heavy.factor, 2),
    updated_at = NOW()
FROM heavy_offer AS heavy
JOIN per_kilogram_product AS product
  ON product.id = heavy.retailer_product_id
WHERE offer.id = heavy.id
  AND heavy.factor <> 1;

-- The history of those products the same way, row by row; a row whose
-- columns do not say stays as it was written.
UPDATE app.price_observation AS observation
SET regular_price = ROUND(observation.regular_price * scaled.factor, 2),
    discounted_price = ROUND(observation.discounted_price * scaled.factor, 2)
FROM (
    SELECT observation.id,
           app.per_kilogram_factor(
               product.normalized_name,
               product.unit,
               product.quantity_value,
               product.base_unit,
               COALESCE(
                   NULLIF(observation.regular_price, 0),
                   observation.discounted_price
               ),
               observation.unit_price
           ) AS factor
    FROM app.price_observation AS observation
    JOIN per_kilogram_product AS converted
      ON converted.id = observation.retailer_product_id
    JOIN app.retailer_product AS product
      ON product.id = observation.retailer_product_id
) AS scaled
WHERE observation.id = scaled.id
  AND scaled.factor IS NOT NULL
  AND scaled.factor <> 1;

-- The product, and the product its barcode stands for, are one kilogram.
UPDATE app.canonical_product AS canonical
SET quantity_value = 1000,
    updated_at = NOW()
WHERE canonical.base_unit = 'g'
  AND canonical.quantity_value >= 2500
  AND EXISTS (
      SELECT 1
      FROM app.retailer_product AS product
      JOIN per_kilogram_product AS converted
        ON converted.id = product.id
      WHERE product.canonical_product_id = canonical.id
  );

UPDATE app.retailer_product AS product
SET quantity_value = 1000
FROM per_kilogram_product AS converted
WHERE product.id = converted.id;

-- Families are keyed by the size, so the blocks of all chains are grouped
-- again, as an import does (V67).
SELECT app.assign_product_families(NULL);

SELECT app.refresh_typical_prices();

-- What each chain sells of each family, as the import counts it.
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
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name))) FILTER (
           WHERE NULLIF(BTRIM(offer.retailer_format_name), '') IS NOT NULL
       )::INTEGER,
       MIN(app.effective_price(
           offer.regular_price,
           offer.discounted_price,
           offer.discount_start,
           offer.discount_end,
           app.market_today(chain.market_id)
       ))
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
JOIN app.retailer AS chain
  ON chain.id = product.retailer_id
WHERE product.product_family_id IS NOT NULL
  AND app.in_latest_price_list(
      product.retailer_id,
      offer.scope_key,
      offer.price_date,
      app.market_today(chain.market_id)
  )
  AND product.package_count =
      app.family_base_package_count(product.product_family_id)
GROUP BY product.product_family_id, product.retailer_id;

REFRESH MATERIALIZED VIEW app.current_sale_list;
