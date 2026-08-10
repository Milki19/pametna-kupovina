-- Kanonski format čiji naziv odgovara formatu iz Europrom cenovnika.
INSERT INTO app.store_format (
    retailer_id,
    code,
    name,
    active
)
SELECT retailer.id,
       'EUROPROM',
       'Europrom',
       TRUE
FROM app.retailer AS retailer
WHERE UPPER(BTRIM(retailer.code)) = 'EUROPROM'
    ON CONFLICT (retailer_id, code)
DO UPDATE SET
    name = EXCLUDED.name,
           active = TRUE,
           updated_at = NOW();


-- Pet pilot-prodavnica povezujemo sa Europrom formatom.
UPDATE app.store AS store
SET store_format_id = format.id,
    updated_at = NOW()
    FROM app.retailer AS retailer
JOIN app.store_format AS format
ON format.retailer_id = retailer.id
    AND format.code = 'EUROPROM'
WHERE store.retailer_id = retailer.id
  AND UPPER(BTRIM(retailer.code)) = 'EUROPROM'
  AND LOWER(BTRIM(store.external_code)) IN (
    'radnicka',
    'kolubara-mala',
    'kod-kasarne',
    'brdjani',
    'pocuta'
    );