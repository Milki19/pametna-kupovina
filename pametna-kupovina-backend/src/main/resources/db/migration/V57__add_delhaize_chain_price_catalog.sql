-- Delhaize publishes one Pravilnik 76/2026 catalogue for all of its brands
-- (Maxi, Mega Maxi, Shop and Go, Profi), in the same CSV shape we already
-- parse. The existing MAXI_DAILY_STORE_FILES source stays: store-level prices
-- outrank format-level ones, so the six confirmed Valjevo shops keep their own
-- prices while every other Delhaize shop finally gets one.
-- Maxi Online is a delivery price list, not a shop, so no location maps to it.

INSERT INTO app.retailer_data_source (
    retailer_id, code, source_type, parser_profile, source_url, discovery_url,
    price_scope, schedule_cron, active, expected_min_rows_saved,
    minimum_volume_ratio, max_success_age_hours
)
SELECT r.id,
       'DELHAIZE_CHAIN_CATALOG',
       'PRICE_CATALOG',
       'PRAVILNIK_76_2026_CSV',
       'https://tsmdelhaizeserbia.delhaize.rs/PublicDoc/cene_proizvoda_Delhaize.csv',
       'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-delhaize-serbia-doo-beograd/',
       'RETAILER_OR_FORMAT',
       '0 0 3 * * *',
       TRUE,
       10000,
       0.6000,
       48
FROM app.retailer r
WHERE r.code = 'MAXI';

-- Brands carried by that catalogue, so shops can be linked to their own list.
INSERT INTO app.store_format (retailer_id, code, name)
SELECT r.id, v.code, v.name
FROM app.retailer r
CROSS JOIN (VALUES
    ('MEGA_MAXI', 'Mega Maxi'),
    ('SHOP_AND_GO', 'Shop and Go'),
    ('PROFI', 'Profi')
) AS v(code, name)
WHERE r.code = 'MAXI'
  AND NOT EXISTS (
      SELECT 1 FROM app.store_format existing
      WHERE existing.retailer_id = r.id AND existing.code = v.code
  );
