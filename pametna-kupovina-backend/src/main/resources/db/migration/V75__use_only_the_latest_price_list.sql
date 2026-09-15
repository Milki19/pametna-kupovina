-- A chain's price list is a snapshot, but current_price_offer keeps a
-- product's last price after the chain drops it from the list. On 15.09. the
-- cheapest slava basket still counted Lidl "Pileći batak i karabatak MIX" from
-- 09.09. and "Pileći file MK12" from 11.09., neither in Lidl's list of 14.09.
-- A price counts only when it is in the newest snapshot of its own list.

CREATE TABLE app.price_list_snapshot (
    retailer_id BIGINT NOT NULL REFERENCES app.retailer (id) ON DELETE CASCADE,
    scope_key TEXT NOT NULL,
    latest_price_date DATE NOT NULL,
    -- No longer published: days behind the chain's newest list and used by no
    -- store. IDEA's zone lists stopped on 09.09. when it moved to brand lists.
    retired BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (retailer_id, scope_key)
);

CREATE OR REPLACE FUNCTION app.refresh_price_list_snapshots()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM app.price_list_snapshot;

    INSERT INTO app.price_list_snapshot (retailer_id, scope_key, latest_price_date, retired)
    WITH scope AS (
        SELECT product.retailer_id,
               offer.scope_key,
               MAX(offer.price_date) AS latest_price_date
        FROM app.current_price_offer AS offer
        JOIN app.retailer_product AS product
          ON product.id = offer.retailer_product_id
        GROUP BY product.retailer_id, offer.scope_key
    ), chain AS (
        SELECT retailer_id, MAX(latest_price_date) AS newest_price_date
        FROM scope
        GROUP BY retailer_id
    )
    SELECT scope.retailer_id,
           scope.scope_key,
           scope.latest_price_date,
           scope.scope_key LIKE 'STORE_FORMAT:%'
               AND scope.latest_price_date < chain.newest_price_date - 2
               AND NOT EXISTS (
                   SELECT 1
                   FROM app.store_price_format_mapping AS mapping
                   WHERE mapping.retailer_id = scope.retailer_id
                     AND mapping.active = TRUE
                     AND 'STORE_FORMAT:' || LOWER(BTRIM(mapping.retailer_format_name))
                         = scope.scope_key
               )
               AND NOT EXISTS (
                   SELECT 1
                   FROM app.store_format AS format
                   JOIN app.store AS store
                     ON store.store_format_id = format.id
                    AND store.active = TRUE
                   WHERE format.retailer_id = scope.retailer_id
                     AND scope.scope_key IN (
                         'STORE_FORMAT:' || LOWER(BTRIM(format.name)),
                         'STORE_FORMAT:' || LOWER(BTRIM(format.code))
                     )
               )
    FROM scope
    JOIN chain ON chain.retailer_id = scope.retailer_id;
END;
$$;

-- True when a price is in the newest snapshot of its list. A plan for a day
-- before that snapshot uses what was known then; a list with no snapshot yet
-- is not held back.
CREATE OR REPLACE FUNCTION app.in_latest_price_list(
    target_retailer_id BIGINT,
    target_scope_key TEXT,
    target_price_date DATE,
    as_of_date DATE
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE((
        SELECT CASE
                   WHEN snapshot.latest_price_date > as_of_date THEN TRUE
                   ELSE snapshot.latest_price_date = target_price_date
                        AND NOT snapshot.retired
               END
        FROM app.price_list_snapshot AS snapshot
        WHERE snapshot.retailer_id = target_retailer_id
          AND snapshot.scope_key = target_scope_key
    ), TRUE)
$$;

-- What a product typically costs is read from the prices chains publish now.
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
        GROUP BY product.product_family_id, product.retailer_id
    ) AS chain_price
    GROUP BY chain_price.product_family_id
    HAVING COUNT(*) >= 3;
END;
$function$;

SELECT app.refresh_price_list_snapshots();

-- Presence in search counts only prices still published.
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
GROUP BY product.product_family_id, product.retailer_id;

SELECT app.refresh_typical_prices();
