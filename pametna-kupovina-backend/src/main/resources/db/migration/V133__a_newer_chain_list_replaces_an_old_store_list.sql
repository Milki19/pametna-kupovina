-- A store's own price list outranks the chain's list for that store, also
-- when the store's list stopped coming. Maxi's six Valjevo store files have
-- been refused since 12.10.; Delhaize's chain list came every day, yet on
-- 17.10. a plan for those shops still counted the store prices of 11.10.
-- A store's list days behind a list the chain publishes for every shop is
-- retired like a format list no one uses (V75), so the chain's newer price
-- takes its place. When the store files come again, they count again.

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
        SELECT retailer_id,
               MAX(latest_price_date) AS newest_price_date,
               -- The newest list that is not one store's own.
               MAX(latest_price_date) FILTER (
                   WHERE scope_key NOT LIKE 'STORE:%'
               ) AS newest_shared_price_date
        FROM scope
        GROUP BY retailer_id
    )
    SELECT scope.retailer_id,
           scope.scope_key,
           scope.latest_price_date,
           (
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
           ) OR COALESCE(
               -- A chain with store lists only has nothing to replace them.
               scope.scope_key LIKE 'STORE:%'
               AND scope.latest_price_date < chain.newest_shared_price_date - 2,
               FALSE
           )
    FROM scope
    JOIN chain ON chain.retailer_id = scope.retailer_id;
END;
$$;

SELECT app.refresh_price_list_snapshots();
