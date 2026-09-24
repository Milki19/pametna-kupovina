-- „Deshodes Zemun" je uključen 23.09. po nazivu, a to je IT prodavnica
-- (itprodavnica.rs): računari, ne hrana. Uvoz mu svaki dan pada, a proizvoda
-- nema, pa ga dnevni uvoz samo preskače.

UPDATE app.retailer_data_source AS source
   SET active = FALSE, updated_at = NOW()
  FROM app.retailer AS retailer
 WHERE retailer.id = source.retailer_id
   AND retailer.code = 'DESHODES_ZEMUN';
