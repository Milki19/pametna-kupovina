-- METRO lists a case of twenty "0.5L ZAJECARSKO SVETLO PIVO PB" under the
-- barcode of one bottle, so the case sits in the bottle's product even after
-- V76 read it as twenty bottles. Its 1.680 RSD is not what the bottle
-- typically costs and not the chain's lowest price for a bottle.

CREATE INDEX IF NOT EXISTS idx_retailer_product_family_package
    ON app.retailer_product (product_family_id, package_count);

-- How many pieces the product itself is sold as: 1 for a bottle, 4 for a
-- product that only exists as a four-pack.
CREATE OR REPLACE FUNCTION app.family_base_package_count(target_family_id BIGINT)
RETURNS INTEGER
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(MIN(product.package_count), 1)
    FROM app.retailer_product AS product
    WHERE product.product_family_id = target_family_id
$$;

CREATE OR REPLACE FUNCTION app.refresh_typical_prices()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DELETE FROM app.product_family_typical_price;

    INSERT INTO app.product_family_typical_price (
        product_family_id,
        typical_price,
        retailer_count
    )
    SELECT chain_price.product_family_id,
           ROUND(
               PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY chain_price.price)
                   ::NUMERIC,
               2
           ),
           COUNT(*)::INTEGER
    FROM (
        -- The chain's own median: one store with a clearance price, or a
        -- chain with many stores, does not decide what is typical.
        SELECT product.product_family_id,
               product.retailer_id,
               PERCENTILE_CONT(0.5) WITHIN GROUP (
                   ORDER BY COALESCE(
                       NULLIF(offer.regular_price, 0),
                       offer.discounted_price
                   )
               ) AS price
        FROM app.current_price_offer AS offer
        JOIN app.retailer_product AS product
          ON product.id = offer.retailer_product_id
        WHERE product.product_family_id IS NOT NULL
          AND COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price) > 0
          AND app.in_latest_price_list(
              product.retailer_id,
              offer.scope_key,
              offer.price_date,
              CURRENT_DATE
          )
          -- A case of the product is priced for all its pieces.
          AND product.package_count = app.family_base_package_count(product.product_family_id)
        GROUP BY product.product_family_id, product.retailer_id
    ) AS chain_price
    GROUP BY chain_price.product_family_id
    HAVING COUNT(*) >= 3;
END;
$function$;

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
       COUNT(DISTINCT offer.store_id) FILTER (WHERE offer.store_id IS NOT NULL)::INTEGER,
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name))) FILTER (
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
  AND app.in_latest_price_list(product.retailer_id, offer.scope_key, offer.price_date, CURRENT_DATE)
  AND product.package_count = app.family_base_package_count(product.product_family_id)
GROUP BY product.product_family_id, product.retailer_id;

SELECT app.refresh_typical_prices();
