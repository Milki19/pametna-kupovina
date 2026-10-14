-- A piece of variable weight quoted per kilogram is still read as the price
-- of the piece when its own name does not say "cca".
--
-- RIČ lists the 3,3 kg Paladin edamer as "PALADIN EDAMER 3,3KG" at 899,99,
-- the price of one kilogram. V123 reads such a price against the market, but
-- only for a name with "cca" and only against the family's typical price,
-- which needs three chains and counts the wrong price itself. So the edamer
-- stayed at 899,99, was not doubted, and was shown as the best price.
--
-- 1. A product is of variable weight when its own name or the name of any
--    product of its family, in any chain, says "cca": Maxi's "cca 3,3kg" of
--    the same cheese tells how RIČ's is sold.
-- 2. The quote is read against what the other chains of the market charge
--    for the family (the median of each chain's own median), so the price
--    under suspicion does not decide what it is compared to, and one other
--    chain is enough.
-- 3. The import asks for every row of 1,5 kg (or litres) and more; the
--    database answers NULL at once for a product of fixed weight, so a 25 kg
--    bag of sugar quoted per kilogram stays doubted (V72) as before.
--
-- Nothing here is about one country: the weight, the names and the prices
-- come from each chain's own data, and only chains of the same market are
-- compared.

-- Whether a product is sold by weight: "cca" in its name or in the name of
-- any product of its family.
CREATE OR REPLACE FUNCTION app.is_variable_weight(
    target_product_family_id BIGINT,
    normalized_name TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(normalized_name ~ '\mcca\M', FALSE)
        OR EXISTS (
               SELECT 1
               FROM app.retailer_product AS sibling
               WHERE sibling.product_family_id = target_product_family_id
                 AND sibling.normalized_name ~ '\mcca\M'
           )
$$;

-- What the family costs in the market's other chains today: the median of
-- each chain's own median, as in app.refresh_typical_prices, without the
-- given chain. NULL when no other chain sells it.
CREATE OR REPLACE FUNCTION app.other_chains_typical_price(
    target_product_family_id BIGINT,
    excluded_retailer_id BIGINT
)
RETURNS NUMERIC
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT ROUND(
               PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY chain_price.price)
                   ::NUMERIC,
               2
           )
    FROM (
        SELECT product.retailer_id,
               PERCENTILE_CONT(0.5) WITHIN GROUP (
                   ORDER BY COALESCE(
                       NULLIF(offer.regular_price, 0),
                       offer.discounted_price
                   )
               ) AS price
        FROM app.retailer AS excluded
        JOIN app.retailer AS chain
          ON chain.market_id = excluded.market_id
         AND chain.id <> excluded.id
        JOIN app.market AS market
          ON market.id = chain.market_id
        JOIN app.retailer_product AS product
          ON product.retailer_id = chain.id
         AND product.product_family_id = target_product_family_id
         AND product.package_count =
             app.family_base_package_count(target_product_family_id)
        JOIN app.current_price_offer AS offer
          ON offer.retailer_product_id = product.id
        WHERE excluded.id = excluded_retailer_id
          AND COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price) > 0
          AND app.in_latest_price_list(
              product.retailer_id,
              offer.scope_key,
              offer.price_date,
              (NOW() AT TIME ZONE market.time_zone)::DATE
          )
        GROUP BY product.retailer_id
    ) AS chain_price
$$;

-- How many kilograms (or litres) a chain's quote for one of its products has
-- to be multiplied by, or NULL when it is the piece's price. The cheap tests
-- come first, so a product of fixed weight costs one lookup by id.
CREATE OR REPLACE FUNCTION app.price_per_unit_factor(
    target_retailer_product_id BIGINT,
    quoted_price NUMERIC
)
RETURNS NUMERIC
LANGUAGE plpgsql
STABLE
PARALLEL SAFE
AS $function$
DECLARE
    product app.retailer_product%ROWTYPE;
BEGIN
    SELECT *
    INTO product
    FROM app.retailer_product
    WHERE id = target_retailer_product_id;

    IF NOT FOUND
       OR product.product_family_id IS NULL
       OR product.package_count <> 1
       OR NOT COALESCE(
              product.base_unit IN ('g', 'ml')
                  AND product.quantity_value >= 1500
                  AND quoted_price > 0,
              FALSE
          )
       OR NOT app.is_variable_weight(
              product.product_family_id,
              product.normalized_name
          ) THEN
        RETURN NULL;
    END IF;

    -- The name test of the five-argument form is passed by the family above.
    RETURN app.price_per_unit_factor(
        'cca',
        quoted_price,
        product.quantity_value,
        product.base_unit,
        app.other_chains_typical_price(
            product.product_family_id,
            product.retailer_id
        )
    );
END;
$function$;

-- The rows already written. The heavy pieces of variable weight are few, so
-- they are found first, each with what the other chains charge for it, and
-- only their prices are read again.
CREATE TEMP TABLE heavy_piece ON COMMIT DROP AS
SELECT product.id,
       product.quantity_value,
       product.base_unit,
       app.other_chains_typical_price(
           product.product_family_id,
           product.retailer_id
       ) AS reference_price
FROM app.retailer_product AS product
WHERE product.product_family_id IS NOT NULL
  AND product.package_count = 1
  AND product.base_unit IN ('g', 'ml')
  AND product.quantity_value >= 1500
  AND app.is_variable_weight(
      product.product_family_id,
      product.normalized_name
  );

CREATE TEMP TABLE per_kilogram_offer ON COMMIT DROP AS
SELECT offer.id, scaled.factor
FROM app.current_price_offer AS offer
JOIN heavy_piece AS piece
  ON piece.id = offer.retailer_product_id
CROSS JOIN LATERAL (
    SELECT app.price_per_unit_factor(
               'cca',
               COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price),
               piece.quantity_value,
               piece.base_unit,
               piece.reference_price
           ) AS factor
) AS scaled
WHERE scaled.factor IS NOT NULL;

CREATE TEMP TABLE per_kilogram_observation ON COMMIT DROP AS
SELECT observation.id, scaled.factor
FROM app.price_observation AS observation
JOIN heavy_piece AS piece
  ON piece.id = observation.retailer_product_id
CROSS JOIN LATERAL (
    SELECT app.price_per_unit_factor(
               'cca',
               COALESCE(
                   NULLIF(observation.regular_price, 0),
                   observation.discounted_price
               ),
               piece.quantity_value,
               piece.base_unit,
               piece.reference_price
           ) AS factor
) AS scaled
WHERE scaled.factor IS NOT NULL;

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

-- Each chain's lowest price as it stands today (as in V123).
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

REFRESH MATERIALIZED VIEW app.current_sale_list;
