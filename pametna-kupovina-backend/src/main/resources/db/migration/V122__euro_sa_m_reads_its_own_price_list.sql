-- Euro Ša M's dataset page on data.gov.rs (cenovnik-5) has pointed at another
-- company's price list since 9 October 2026, so its import failed every day.
-- The chain publishes the same list at a stable address on its own site, so
-- read it from there and stop following the portal page.
UPDATE app.retailer_data_source AS source
SET source_url = 'https://eurosam.rs/cenovnik/cene_proizvoda_eurosam.csv',
    discovery_url = NULL,
    updated_at = NOW()
FROM app.retailer AS retailer
WHERE source.retailer_id = retailer.id
  AND retailer.code = 'PROIZVODNO_TRGOVINSKO_PREDUZEC'
  AND source.source_type = 'PRICE_CATALOG'
  AND source.price_scope <> 'STORE';

UPDATE app.retailer
SET dataset_url = 'https://eurosam.rs/cenovnik/cene_proizvoda_eurosam.csv'
WHERE code = 'PROIZVODNO_TRGOVINSKO_PREDUZEC';
