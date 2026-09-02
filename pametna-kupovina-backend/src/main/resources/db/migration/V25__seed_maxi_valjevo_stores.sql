INSERT INTO app.retailer (
    code,
    name,
    dataset_url
)
VALUES (
    'MAXI',
    'Maxi',
    NULL
)
ON CONFLICT (code)
DO UPDATE SET
    name = EXCLUDED.name;


INSERT INTO app.store_format (
    retailer_id,
    code,
    name,
    active
)
SELECT retailer.id,
       'MAXI',
       'Maxi',
       TRUE
FROM app.retailer AS retailer
WHERE retailer.code = 'MAXI'
ON CONFLICT (retailer_id, code)
DO UPDATE SET
    name = EXCLUDED.name,
    active = TRUE,
    updated_at = NOW();


INSERT INTO app.store (
    retailer_id,
    external_code,
    name,
    address,
    city,
    location,
    active,
    store_format_id,
    geocoding_candidate,
    geocoding_status,
    geocoding_query,
    geocoding_source,
    geocoding_source_reference,
    geocoding_matched_address,
    geocoding_confidence,
    geocoded_at,
    geocoding_review_note,
    geocoding_reviewed_at
)
SELECT retailer.id,
       seed.external_code,
       seed.name,
       seed.address,
       'Valjevo',
       ST_SetSRID(
           ST_MakePoint(seed.longitude, seed.latitude),
           4326
       )::geography,
       TRUE,
       format.id,
       ST_SetSRID(
           ST_MakePoint(seed.longitude, seed.latitude),
           4326
       )::geography,
       'MANUALLY_VERIFIED',
       LOWER(seed.address || ', Valjevo'),
       'MAXI_STORE_LOCATOR',
       seed.source_reference,
       seed.address || ', Valjevo',
       1.0000,
       NOW(),
       'Adresa i koordinate potvrđene na zvaničnoj Maxi stranici prodavnice.',
       NOW()
FROM app.retailer AS retailer
JOIN app.store_format AS format
  ON format.retailer_id = retailer.id
 AND format.code = 'MAXI'
CROSS JOIN (
    VALUES
        (
            '508',
            'Maxi 508',
            'Kneza Mihaila 84-86',
            44.263836::DOUBLE PRECISION,
            19.891145::DOUBLE PRECISION,
            'https://www.maxi.rs/storedetails/maxi-508'
        ),
        (
            '538',
            'Maxi 538',
            'Karađorđeva 92',
            44.270759::DOUBLE PRECISION,
            19.890337::DOUBLE PRECISION,
            'https://www.maxi.rs/storedetails/maxi-538'
        )
) AS seed(
    external_code,
    name,
    address,
    latitude,
    longitude,
    source_reference
)
WHERE retailer.code = 'MAXI'
ON CONFLICT (retailer_id, external_code)
DO UPDATE SET
    name = EXCLUDED.name,
    address = EXCLUDED.address,
    city = EXCLUDED.city,
    location = EXCLUDED.location,
    active = TRUE,
    store_format_id = EXCLUDED.store_format_id,
    geocoding_candidate = EXCLUDED.geocoding_candidate,
    geocoding_status = EXCLUDED.geocoding_status,
    geocoding_query = EXCLUDED.geocoding_query,
    geocoding_source = EXCLUDED.geocoding_source,
    geocoding_source_reference = EXCLUDED.geocoding_source_reference,
    geocoding_matched_address = EXCLUDED.geocoding_matched_address,
    geocoding_confidence = EXCLUDED.geocoding_confidence,
    geocoded_at = EXCLUDED.geocoded_at,
    geocoding_suspicious_reason = NULL,
    geocoding_review_note = EXCLUDED.geocoding_review_note,
    geocoding_reviewed_at = EXCLUDED.geocoding_reviewed_at,
    updated_at = NOW();
