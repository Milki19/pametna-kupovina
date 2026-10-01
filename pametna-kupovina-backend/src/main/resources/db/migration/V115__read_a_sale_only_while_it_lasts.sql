-- A chain's sale price is the price only while the sale lasts. Search and the
-- chain's lowest price used to take any discounted price, so a sale that had
-- ended in a list the chain had not replaced yet (Veropoulos and Metro publish
-- days late) still looked like today's price, and so did a "sale" price above
-- the regular one or one a tenth of it.
--
-- Nothing here is about one country's file: a sale is a regular price, a
-- lower price and the days the lower one holds, in the chain's own market.

-- The price the chain sells for on as_of_date because of a sale, or NULL when
-- there is no sale that day. Below a tenth of the regular price it is a typo
-- in the chain's file, not a sale, and the regular price stands.
CREATE OR REPLACE FUNCTION app.sale_price(
    regular_price NUMERIC,
    discounted_price NUMERIC,
    discount_start DATE,
    discount_end DATE,
    as_of_date DATE
)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
               WHEN discounted_price > 0
                AND regular_price > discounted_price
                AND discounted_price >= regular_price * 0.1
                AND (discount_start IS NULL OR discount_start <= as_of_date)
                AND (discount_end IS NULL OR discount_end >= as_of_date)
                   THEN discounted_price
           END
$$;

-- What a shopper pays on as_of_date: the sale price while there is one, else
-- the regular price. A list that gives only a discounted price still counts
-- on the days it is meant for, as before.
CREATE OR REPLACE FUNCTION app.effective_price(
    regular_price NUMERIC,
    discounted_price NUMERIC,
    discount_start DATE,
    discount_end DATE,
    as_of_date DATE
)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(
        app.sale_price(regular_price, discounted_price, discount_start, discount_end, as_of_date),
        CASE WHEN regular_price > 0 THEN regular_price END,
        CASE
            WHEN discounted_price > 0
             AND (discount_start IS NULL OR discount_start <= as_of_date)
             AND (discount_end IS NULL OR discount_end >= as_of_date)
                THEN discounted_price
        END
    )
$$;

-- The list of everything on sale reads only offers that carry a discounted
-- price, about one in ten.
CREATE INDEX IF NOT EXISTS ix_current_price_offer_discounted
    ON app.current_price_offer (retailer_product_id)
    WHERE discounted_price IS NOT NULL;

-- Each chain's lowest price as it stands today; the daily import keeps it so.
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

-- Every offer on sale today, in each chain's own market and day: in the
-- chain's newest list, the size the product is sold by, and not a price that
-- needs checking first (V72). Search and the list of sales read only this.
CREATE OR REPLACE VIEW app.current_sale AS
SELECT offer.id AS offer_id,
       product.id AS retailer_product_id,
       product.canonical_product_id,
       product.product_family_id,
       product.retailer_id,
       chain.market_id,
       offer.scope_type,
       offer.store_id,
       offer.regular_price,
       sale.price AS sale_price,
       ROUND(100 * (1 - sale.price / offer.regular_price))::INTEGER
           AS discount_percent,
       offer.discount_end
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
JOIN app.retailer AS chain
  ON chain.id = product.retailer_id
JOIN app.market AS market
  ON market.id = chain.market_id
LEFT JOIN app.product_family_typical_price AS typical_price
  ON typical_price.product_family_id = product.product_family_id
 AND typical_price.market_id = chain.market_id
CROSS JOIN LATERAL (
    SELECT app.sale_price(
               offer.regular_price,
               offer.discounted_price,
               offer.discount_start,
               offer.discount_end,
               (NOW() AT TIME ZONE market.time_zone)::DATE
           ) AS price
) AS sale
WHERE offer.discounted_price IS NOT NULL
  AND sale.price IS NOT NULL
  -- Under half a percent off is a rounding, not a sale to show.
  AND ROUND(100 * (1 - sale.price / offer.regular_price)) >= 1
  AND product.product_family_id IS NOT NULL
  AND app.in_latest_price_list(
      product.retailer_id,
      offer.scope_key,
      offer.price_date,
      (NOW() AT TIME ZONE market.time_zone)::DATE
  )
  AND product.package_count =
      app.family_base_package_count(product.product_family_id)
  AND NOT app.price_needs_check(
      offer.regular_price,
      offer.discounted_price,
      typical_price.typical_price
  );
