-- Two kinds of price a shopper saw and should not have believed.
--
-- 1. A sale too deep to be true. price_needs_check (V72) looked only at the
--    regular price, so a sale price of a tenth of it led the list of sales:
--    soap 96 -> 10, "-90%". A sale is now doubted too when it takes off more
--    than 80% or is under half of what the product typically costs in the
--    market. Such an offer is listed last with a note, is left out of the
--    list of sales and is never used in a shopping plan, as before. Below a
--    tenth of the regular price the sale is already ignored as a typo
--    (app.sale_price, V115) and the regular price stands, so it is not doubted.
--
-- 2. A price per kilogram read as the price of the piece. Goods of variable
--    weight ("cca 3,3 kg" cheese) are quoted per kilogram. The chain-wide
--    import scaled them to the piece when both price columns repeat the same
--    figure, but the per-shop import (Maxi's shops) did not, so the same
--    Paladin tilsiter in Maxi cost 1.399 in one shop and 4.616,70 in the
--    chain's list, and the list of sales showed either depending on the
--    shopper's location. The per-shop import now scales the same way, and the
--    rows it already wrote are scaled here.
--    A row that gives no such hint (a 3,3 kg edamer at 899,99 in RIČ) is
--    read against the market: a "cca" price under half of the product's
--    typical price that comes to between half and twice the typical price
--    once multiplied by the weight is a price per kilogram. The import asks
--    app.price_per_unit_factor for every such row of 1,5 kg and more, so the
--    total is computed once, there. Goods of a fixed weight are left as they
--    are: a 25 kg bag of sugar quoted per kilogram stays doubted (V72), as
--    the chain may not sell the bag for 25 times that.
--
-- Nothing here is about one country: the weight, the typical price and the
-- market come from each chain's own data.

CREATE OR REPLACE FUNCTION app.price_needs_check(
    regular_price NUMERIC,
    discounted_price NUMERIC,
    typical_price NUMERIC
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(
               COALESCE(NULLIF(regular_price, 0), discounted_price)
                   < typical_price * 0.5,
               FALSE
           )
        OR COALESCE(
               discounted_price > 0
               AND regular_price > discounted_price
               AND discounted_price >= regular_price * 0.1
               AND (
                   discounted_price < regular_price * 0.2
                   OR discounted_price < typical_price * 0.5
               ),
               FALSE
           )
$$;

-- How many kilograms (or litres) a quoted price has to be multiplied by
-- because it is the price of one kilogram of a heavier piece of variable
-- weight, or NULL when it is the price of the piece. Only a weight of 1,5 and
-- more is read this way, and only when the quote alone is too cheap to be
-- believed (under half of the typical price) while the quote times the weight
-- is between half and twice the typical price: 899,99 a kilogram of a 3,3 kg
-- cheese whose piece typically costs 4.500 is 2.970 for the piece, a real low
-- price, not one to doubt.
CREATE OR REPLACE FUNCTION app.price_per_unit_factor(
    normalized_name TEXT,
    quoted_price NUMERIC,
    quantity_value NUMERIC,
    base_unit TEXT,
    typical_price NUMERIC
)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT quantity_value / 1000
    WHERE normalized_name ~ '\mcca\M'
      AND quoted_price > 0
      AND typical_price > 0
      AND base_unit IN ('g', 'ml')
      AND quantity_value >= 1500
      AND quoted_price < typical_price * 0.5
      AND quoted_price * quantity_value / 1000
          BETWEEN typical_price * 0.5 AND typical_price * 2
$$;

-- The same, for one chain's product against its market's typical price; the
-- import calls this for a row it is about to write.
CREATE OR REPLACE FUNCTION app.price_per_unit_factor(
    target_retailer_product_id BIGINT,
    quoted_price NUMERIC
)
RETURNS NUMERIC
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT app.price_per_unit_factor(
               product.normalized_name,
               quoted_price,
               product.quantity_value,
               product.base_unit,
               typical.typical_price
           )
    FROM app.retailer_product AS product
    JOIN app.retailer AS chain
      ON chain.id = product.retailer_id
    JOIN app.product_family_typical_price AS typical
      ON typical.product_family_id = product.product_family_id
     AND typical.market_id = chain.market_id
    WHERE product.id = target_retailer_product_id
      AND product.package_count = 1
$$;

-- Rows the per-shop import wrote per kilogram: "cca" in the name, the unit
-- price per kilogram or litre equal to the price, and a piece that is not
-- exactly one unit. The chain-wide import already scaled its rows, whose
-- unit price no longer equals the price, so they are not touched again.
CREATE TEMP TABLE per_kilogram_product ON COMMIT DROP AS
SELECT product.id,
       product.quantity_value / 1000 AS factor
FROM app.retailer_product AS product
WHERE product.normalized_name ~ '\mcca\M'
  AND UPPER(BTRIM(product.unit)) IN ('KG', 'L')
  AND product.base_unit IN ('g', 'ml')
  AND product.quantity_value > 0
  AND product.quantity_value <> 1000;

UPDATE app.current_price_offer AS offer
SET regular_price = ROUND(offer.regular_price * scaled.factor, 2),
    discounted_price = ROUND(offer.discounted_price * scaled.factor, 2),
    updated_at = NOW()
FROM per_kilogram_product AS scaled
WHERE offer.retailer_product_id = scaled.id
  AND offer.regular_price > 0
  AND offer.unit_price = offer.regular_price;

UPDATE app.price_observation AS observation
SET regular_price = ROUND(observation.regular_price * scaled.factor, 2),
    discounted_price = ROUND(observation.discounted_price * scaled.factor, 2)
FROM per_kilogram_product AS scaled
WHERE observation.retailer_product_id = scaled.id
  AND observation.regular_price > 0
  AND observation.unit_price = observation.regular_price;

-- What is typical now that those rows are per piece, then the prices that
-- are still per kilogram against it.
SELECT app.refresh_typical_prices();

CREATE TEMP TABLE per_kilogram_offer ON COMMIT DROP AS
SELECT offer.id, scaled.factor
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
JOIN app.retailer AS chain
  ON chain.id = product.retailer_id
JOIN app.product_family_typical_price AS typical
  ON typical.product_family_id = product.product_family_id
 AND typical.market_id = chain.market_id
CROSS JOIN LATERAL (
    SELECT app.price_per_unit_factor(
               product.normalized_name,
               COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price),
               product.quantity_value,
               product.base_unit,
               typical.typical_price
           ) AS factor
) AS scaled
WHERE product.package_count = 1
  AND scaled.factor IS NOT NULL;

CREATE TEMP TABLE per_kilogram_observation ON COMMIT DROP AS
SELECT observation.id, scaled.factor
FROM app.price_observation AS observation
JOIN app.retailer_product AS product
  ON product.id = observation.retailer_product_id
JOIN app.retailer AS chain
  ON chain.id = product.retailer_id
JOIN app.product_family_typical_price AS typical
  ON typical.product_family_id = product.product_family_id
 AND typical.market_id = chain.market_id
CROSS JOIN LATERAL (
    SELECT app.price_per_unit_factor(
               product.normalized_name,
               COALESCE(NULLIF(observation.regular_price, 0), observation.discounted_price),
               product.quantity_value,
               product.base_unit,
               typical.typical_price
           ) AS factor
) AS scaled
WHERE product.package_count = 1
  AND scaled.factor IS NOT NULL;

UPDATE app.current_price_offer AS offer
SET regular_price = ROUND(offer.regular_price * scaled.factor, 2),
    discounted_price = ROUND(offer.discounted_price * scaled.factor, 2),
    updated_at = NOW()
FROM per_kilogram_offer AS scaled
WHERE offer.id = scaled.id;

UPDATE app.price_observation AS observation
SET regular_price = ROUND(observation.regular_price * scaled.factor, 2),
    discounted_price = ROUND(observation.discounted_price * scaled.factor, 2)
FROM per_kilogram_observation AS scaled
WHERE observation.id = scaled.id;

SELECT app.refresh_typical_prices();

-- Each chain's lowest price as it stands today (as in V115).
UPDATE app.product_retailer_presence AS presence
SET minimum_effective_price = lowest.price
FROM (
    SELECT product.product_family_id,
           product.retailer_id,
           MIN(app.effective_price(
               offer.regular_price,
               offer.discounted_price,
               offer.discount_start,
               offer.discount_end,
               market.today
           )) AS price
    FROM app.current_price_offer AS offer
    JOIN app.retailer_product AS product
      ON product.id = offer.retailer_product_id
    JOIN app.retailer AS chain
      ON chain.id = product.retailer_id
    JOIN (
        SELECT id, (NOW() AT TIME ZONE time_zone)::DATE AS today
        FROM app.market
    ) AS market
      ON market.id = chain.market_id
    WHERE product.product_family_id IS NOT NULL
      AND app.in_latest_price_list(
          product.retailer_id,
          offer.scope_key,
          offer.price_date,
          market.today
      )
      AND product.package_count =
          app.family_base_package_count(product.product_family_id)
    GROUP BY product.product_family_id, product.retailer_id
) AS lowest
WHERE presence.product_family_id = lowest.product_family_id
  AND presence.retailer_id = lowest.retailer_id
  AND presence.minimum_effective_price IS DISTINCT FROM lowest.price;

-- The list of sales (V117) reads the doubted sales and the scaled prices
-- from now on.
REFRESH MATERIALIZED VIEW app.current_sale_list;
