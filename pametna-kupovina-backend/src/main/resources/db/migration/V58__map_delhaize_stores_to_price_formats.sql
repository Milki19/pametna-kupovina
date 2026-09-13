-- Delhaize prices differ per brand (20% of products cost something else in
-- Shop and Go than in Maxi), so a shop must read its own list. The locator
-- only separates MAXI and SHOPNGO, while Profi shops sit inside MAXI and are
-- recognisable by name. Mega Maxi is not distinguishable in the locator feed
-- at all, so those shops read the Maxi list; that is recorded as its own
-- mapping method so the approximation stays visible.

INSERT INTO app.store_price_format_mapping (
    retailer_id, source_store_code, store_external_code, retailer_format_name,
    verification_status, mapping_method, source_url, source_last_seen_at, active
)
SELECT store.retailer_id,
       'DELHAIZE:' || store.external_code,
       store.external_code,
       CASE
           WHEN store.name ILIKE 'profi%' THEN 'Profi'
           WHEN format.code = 'SHOPNGO' THEN 'Shop and Go'
           ELSE 'Maxi'
       END,
       'VERIFIED',
       CASE
           WHEN store.name ILIKE 'profi%' THEN 'OFFICIAL_LOCATOR_BRAND'
           WHEN format.code = 'SHOPNGO' THEN 'OFFICIAL_LOCATOR_BRAND'
           ELSE 'OFFICIAL_LOCATOR_BRAND_MAXI_DEFAULT'
       END,
       'https://www.maxi.rs/storelocator',
       NOW(),
       TRUE
FROM app.store AS store
JOIN app.store_format AS format ON format.id = store.store_format_id
JOIN app.retailer AS retailer ON retailer.id = store.retailer_id
WHERE retailer.code = 'MAXI'
  AND store.active = TRUE
  AND store.external_code IS NOT NULL
ON CONFLICT (retailer_id, source_store_code) DO UPDATE SET
    store_external_code = EXCLUDED.store_external_code,
    retailer_format_name = EXCLUDED.retailer_format_name,
    verification_status = 'VERIFIED',
    mapping_method = EXCLUDED.mapping_method,
    source_last_seen_at = EXCLUDED.source_last_seen_at,
    active = TRUE,
    updated_at = NOW();
