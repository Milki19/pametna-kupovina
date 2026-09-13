-- Two more chains the owner wants available to everyone. METRO needs a
-- membership card, which is the shopper's business, not a reason to hide its
-- prices. Both publish the same Pravilnik 76/2026 CSV we already parse; the
-- header normaliser already copes with METRO's "DATIM CENOVNIKA" typo and its
-- unspaced "NAZIV TRGOVCA-FORMATA".
-- METRO prices are per physical store (ST ZEMUN, ST Vidikovac, ST Krnjaca...),
-- Veropoulos publishes a single list, so their locations are seeded separately.

INSERT INTO app.retailer (code, name, dataset_url)
VALUES
    ('METRO', 'METRO Cash & Carry', NULL),
    ('VEROPOULOS', 'Super Vero', NULL);

INSERT INTO app.retailer_data_source (
    retailer_id, code, source_type, parser_profile, source_url, discovery_url,
    price_scope, schedule_cron, active, expected_min_rows_saved,
    minimum_volume_ratio, max_success_age_hours
)
SELECT r.id, 'PRIMARY_PRICE_CATALOG', 'PRICE_CATALOG', 'PRAVILNIK_76_2026_CSV',
       v.source_url, v.discovery_url, 'RETAILER_OR_FORMAT', '0 0 3 * * *', TRUE,
       v.minimum_rows, 0.6000, 48
FROM (VALUES
    ('METRO',
     'https://data.gov.rs/s/resources/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/20260913-083837/cene-proizvoda-metro.csv',
     'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/',
     20000),
    ('VEROPOULOS',
     'https://data.gov.rs/s/resources/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-veropoulos-doo/20260911-064808/cene-proizvoda-veropoulos-d.o.o..csv',
     'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-veropoulos-doo/',
     5000)
) AS v(code, source_url, discovery_url, minimum_rows)
JOIN app.retailer r ON r.code = v.code;
