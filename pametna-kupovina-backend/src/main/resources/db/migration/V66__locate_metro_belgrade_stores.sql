-- METRO blocks automated access to its own locator and publishes no store list
-- on data.gov.rs, so these three coordinates were supplied and checked by the
-- project owner from Google Maps. They are recorded as MANUALLY_VERIFIED for
-- that reason, with the source kept alongside.
-- METRO prices are already per store ("Metro CashCarry - ST Krnjaca"), so a
-- located shop reads exactly its own price list. The remaining METRO towns stay
-- as price-list-only entries from V65 until someone checks those too.

INSERT INTO app.store_format (retailer_id, code, name)
SELECT r.id, 'METRO_CASH_CARRY', 'METRO Cash & Carry'
FROM app.retailer r
WHERE r.code = 'METRO'
  AND NOT EXISTS (
      SELECT 1 FROM app.store_format f
      WHERE f.retailer_id = r.id AND f.code = 'METRO_CASH_CARRY'
  );

INSERT INTO app.store (
    retailer_id, external_code, name, address, city, store_format_id,
    active, location, geocoding_candidate, geocoding_status, geocoding_source,
    geocoding_source_reference, geocoding_query, geocoding_matched_address,
    geocoding_confidence, geocoded_at, geocoding_reviewed_at, verified_at,
    pricing_eligible, pricing_ineligibility_reason
)
SELECT r.id,
       v.external_code,
       v.name,
       v.address,
       'Beograd',
       f.id,
       TRUE,
       ST_SetSRID(ST_MakePoint(v.longitude, v.latitude), 4326)::geography,
       ST_SetSRID(ST_MakePoint(v.longitude, v.latitude), 4326)::geography,
       'MANUALLY_VERIFIED',
       'OWNER_GOOGLE_MAPS',
       v.source_reference,
       v.name || ', Beograd',
       v.address || ', Beograd',
       1.0000,
       NOW(),
       NOW(),
       NOW(),
       FALSE,
       'NOT_REVIEWED'
FROM app.retailer r
JOIN app.store_format f
  ON f.retailer_id = r.id AND f.code = 'METRO_CASH_CARRY'
CROSS JOIN (VALUES
    ('METRO_KRNJACA',   'METRO Krnjača',   'Zrenjaninski put',
     44.8425918, 20.4871144, 'https://maps.app.goo.gl/SYX7eApqWBGpg14v8'),
    ('METRO_ZEMUN',     'METRO Zemun',     'Autoput za Novi Sad',
     44.8658683, 20.3485059, 'https://maps.app.goo.gl/3ciwev4cKWVAYLpo8'),
    ('METRO_VIDIKOVAC', 'METRO Vidikovac', 'Vidikovac',
     44.7269019, 20.4172014, 'https://maps.app.goo.gl/RxQHXHRdkGYFAxgc8')
) AS v(external_code, name, address, latitude, longitude, source_reference)
WHERE r.code = 'METRO'
  AND NOT EXISTS (
      SELECT 1 FROM app.store s
      WHERE s.retailer_id = r.id AND s.external_code = v.external_code
  );

-- Each located shop reads the price list published under its own name.
INSERT INTO app.store_price_format_mapping (
    retailer_id, source_store_code, store_external_code, retailer_format_name,
    verification_status, mapping_method, source_url, source_last_seen_at, active
)
SELECT s.retailer_id,
       'METRO_STORE:' || s.external_code,
       s.external_code,
       v.price_list,
       'VERIFIED',
       'PUBLISHED_PRICE_LIST_PER_STORE',
       'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/',
       NOW(),
       TRUE
FROM app.store s
JOIN app.retailer r ON r.id = s.retailer_id AND r.code = 'METRO'
JOIN (VALUES
    ('METRO_KRNJACA',   'Metro CashCarry - ST Krnjaca'),
    ('METRO_ZEMUN',     'Metro CashCarry - ST ZEMUN'),
    ('METRO_VIDIKOVAC', 'Metro CashCarry - ST Vidikovac')
) AS v(external_code, price_list) ON v.external_code = s.external_code
ON CONFLICT (retailer_id, source_store_code) DO UPDATE SET
    retailer_format_name = EXCLUDED.retailer_format_name,
    verification_status = 'VERIFIED',
    mapping_method = EXCLUDED.mapping_method,
    active = TRUE,
    updated_at = NOW();

-- The same price list must not appear twice: once as a shop you can drive to
-- and once as an "unknown location" note.
UPDATE app.store_price_format_mapping AS mapping
SET active = FALSE, updated_at = NOW()
FROM app.store AS entry
WHERE entry.id IS NOT NULL
  AND mapping.store_external_code = entry.external_code
  AND mapping.retailer_id = entry.retailer_id
  AND mapping.mapping_method = 'PRICE_LIST_WITHOUT_LOCATION'
  AND entry.name IN (
      'Metro CashCarry - ST Krnjaca',
      'Metro CashCarry - ST ZEMUN',
      'Metro CashCarry - ST Vidikovac'
  );

UPDATE app.store SET active = FALSE, updated_at = NOW()
WHERE location IS NULL
  AND name IN (
      'Metro CashCarry - ST Krnjaca',
      'Metro CashCarry - ST ZEMUN',
      'Metro CashCarry - ST Vidikovac'
  );
