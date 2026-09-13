-- Some chains publish prices but never say where their shops are: Univerexport
-- names only opaque pricing zones (C0..C3), METRO names the town but not the
-- spot, Veropoulos publishes one list for everything. Today those prices are
-- invisible, which loses a genuinely cheaper option.
--
-- Each published price list gets an entry that is deliberately NOT a shop: it
-- has no coordinates and stays pricing_eligible = FALSE, so it can never be
-- routed to or recommended. It exists only so the basket can be priced and
-- shown as "cheaper here, but we cannot tell you which shop".

INSERT INTO app.store_format (retailer_id, code, name)
SELECT r.id, r.code || '_PRICE_LIST', r.name || ' (bez potvrđene lokacije)'
FROM app.retailer r
WHERE r.code IN ('METRO', 'UNIVEREXPORT', 'VEROPOULOS')
  AND NOT EXISTS (
      SELECT 1 FROM app.store_format f
      WHERE f.retailer_id = r.id AND f.code = r.code || '_PRICE_LIST'
  );

INSERT INTO app.store (
    retailer_id, external_code, name, store_format_id,
    active, location, geocoding_status,
    pricing_eligible, pricing_ineligibility_reason
)
SELECT r.id,
       LEFT('PRICE_LIST:' || published.retailer_format_name, 100),
       LEFT(published.retailer_format_name, 200),
       f.id,
       TRUE,
       NULL,
       'PENDING',
       FALSE,
       'LOCATION_NOT_VERIFIED'
FROM app.retailer r
JOIN app.store_format f
  ON f.retailer_id = r.id AND f.code = r.code || '_PRICE_LIST'
JOIN (
    SELECT DISTINCT product.retailer_id, offer.retailer_format_name
    FROM app.current_price_offer AS offer
    JOIN app.retailer_product AS product
      ON product.id = offer.retailer_product_id
    WHERE offer.scope_type = 'STORE_FORMAT'
) AS published ON published.retailer_id = r.id
WHERE r.code IN ('METRO', 'UNIVEREXPORT', 'VEROPOULOS')
  AND NOT EXISTS (
      SELECT 1 FROM app.store s
      WHERE s.retailer_id = r.id
        AND s.external_code = LEFT('PRICE_LIST:' || published.retailer_format_name, 100)
  );

INSERT INTO app.store_price_format_mapping (
    retailer_id, source_store_code, store_external_code, retailer_format_name,
    verification_status, mapping_method, source_url, source_last_seen_at, active
)
SELECT s.retailer_id,
       s.external_code,
       s.external_code,
       s.name,
       'VERIFIED',
       'PRICE_LIST_WITHOUT_LOCATION',
       'derived:published price list',
       NOW(),
       TRUE
FROM app.store s
JOIN app.retailer r ON r.id = s.retailer_id
JOIN app.store_format f ON f.id = s.store_format_id
WHERE r.code IN ('METRO', 'UNIVEREXPORT', 'VEROPOULOS')
  AND f.code = r.code || '_PRICE_LIST'
ON CONFLICT (retailer_id, source_store_code) DO UPDATE SET
    retailer_format_name = EXCLUDED.retailer_format_name,
    verification_status = 'VERIFIED',
    mapping_method = EXCLUDED.mapping_method,
    active = TRUE,
    updated_at = NOW();
