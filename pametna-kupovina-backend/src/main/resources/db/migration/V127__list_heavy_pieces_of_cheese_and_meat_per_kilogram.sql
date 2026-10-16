-- A heavy piece of cheese or meat is listed as one kilogram at the price of
-- one kilogram.
--
-- Some twenty chains quote a 3,3 kg block of Paladin gauda per kilogram
-- (1.300-1.900) and name it "3,3KG"; a few write "cca 3,3kg", and those the
-- import multiplied by the weight into the price of the piece (4.000-6.600).
-- Side by side that is a threefold difference: RIČ's gauda block at 899,99 a
-- kilogram on sale was the "best price" of the piece, and the price per
-- kilogram the screen derived from 3,3 kg was a third of the real one.
--
-- A piece of 2,5 kg and more whose name says cheese, meat or "cca" is now one
-- kilogram, wherever the chain's own columns tell how it was quoted:
--   * the unit price repeats the price, by the kilogram: per kilogram;
--   * a "cca" piece whose price is the unit price times the weight: the
--     piece, divided by it (this also undoes the import's "cca"
--     multiplication);
--   * "cca" and no unit price: per kilogram.
-- Without "cca", a unit price that is the price divided by the weight proves
-- nothing: most chains compute it that way whatever the price means (a
-- tilsiter block at 1.599,99 a kilogram with 484,84 "per kilogram", METRO's
-- 4,5 kg tub of white cheese). Pet food is never cut by weight. A product
-- whose prices do not say either way is left as the chain wrote it.
-- The import reads new rows the same way (PriceImportService).
--
-- Nothing here is about one country: the weight, the words and the price
-- columns are the chain's own, and the rule asks no other chain.

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
                   THEN CASE WHEN normalized_name ~ '\mcca\M' THEN 1 END
               WHEN unit_price = quoted_price
                    AND UPPER(BTRIM(unit)) IN ('KG', 'KGR')
                   THEN 1
               WHEN normalized_name ~ '\mcca\M'
                    AND ABS(ROUND(unit_price * quantity_value / 1000, 2) - quoted_price)
                        <= GREATEST(quoted_price * 0.01, 1)
                   THEN ROUND(1000 / quantity_value, 6)
           END
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
