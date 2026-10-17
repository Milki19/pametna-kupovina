-- RIČ's price list of 17 October listed its Paladin Maasdamer and trapist
-- blocks as pieces of 3,3 kg again, so Maasdamer stood alone and the product
-- pages read them at a third of a kilogram's price. The import tells a heavy
-- piece of cheese by its name, and its list lacked "trapis", "emental" and
-- "maasdamer", which the database rule (app.per_kilogram_factor, V128) has:
-- RIČ writes "Paladin Trapis Blok" and "Paladin Maasdamer blok", without
-- "sir". The import now reads the same words (PriceImportService), and the
-- rows read since V128 are read again the same way.

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

-- The price of a kilogram is now the price itself, also in the unit price
-- the product page shows (a tilsiter block at 1.599,99 kept 484,84).
UPDATE app.current_price_offer AS offer
SET unit_price = COALESCE(offer.regular_price, offer.discounted_price),
    updated_at = NOW()
FROM per_kilogram_product AS product
WHERE offer.retailer_product_id = product.id
  AND offer.unit_price IS DISTINCT FROM COALESCE(offer.regular_price, offer.discounted_price);

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

UPDATE app.price_observation AS observation
SET unit_price = COALESCE(observation.regular_price, observation.discounted_price)
FROM per_kilogram_product AS product
WHERE observation.retailer_product_id = product.id
  AND observation.unit_price
      IS DISTINCT FROM COALESCE(observation.regular_price, observation.discounted_price);

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

-- The blocks are grouped by their new size, join the blocks of one cheese
-- (V130), and are grouped again with those decisions, as an import does.
SELECT app.assign_product_families(NULL);

SELECT app.join_blocks_of_one_cheese();

SELECT app.assign_product_families(NULL);

SELECT app.refresh_family_display_names();

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
