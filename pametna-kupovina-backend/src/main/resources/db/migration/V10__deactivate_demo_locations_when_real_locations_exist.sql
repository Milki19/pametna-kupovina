UPDATE app.retailer_location AS demo
SET active = FALSE
WHERE demo.external_code LIKE 'DEMO-%'
  AND EXISTS (
    SELECT 1
    FROM app.retailer_location AS real_location
    WHERE real_location.retailer_id = demo.retailer_id
      AND real_location.external_code NOT LIKE 'DEMO-%'
      AND real_location.active = TRUE
);