-- Od 1. septembra 2026. veliki trgovci objavljuju objedinjeni CSV po
-- Pravilniku 76/2026. Stabilni publisher URL ostaje fallback, dok discovery
-- URL omogućava da worker preko zvaničnog data.gov.rs API-ja pronađe novu
-- verziju resursa bez izmene aplikacije.
UPDATE app.retailer AS retailer
SET dataset_url = source.source_url
FROM (
    VALUES
        (
            'LIDL',
            'https://kompanija.lidl.rs/content/download/165294/fileupload/cene_proizvoda_Lidl.csv'
        ),
        (
            'IDEA_RODA',
            'https://data.gov.rs/s/resources/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-idea-marketi-doo/20260831-173028/cene-proizvoda-ideamarketi.csv'
        ),
        (
            'UNIVEREXPORT',
            'https://data.gov.rs/s/resources/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-univerexport-export-import-doo/20260831-112950/cene310826.csv'
        ),
        (
            'EUROPROM',
            'https://api.nitsolutions.rs/api/v1/external/cenovnik/00fc017c-0f22-40c2-9e38-50f4c4a66f80.csv'
        )
) AS source(retailer_code, source_url)
WHERE retailer.code = source.retailer_code;


UPDATE app.retailer_data_source AS data_source
SET parser_profile = 'PRAVILNIK_76_2026_CSV',
    source_url = source.source_url,
    discovery_url = source.discovery_url,
    encoding = 'AUTO',
    delimiter = ';',
    price_scope = 'RETAILER_OR_FORMAT',
    active = TRUE,
    updated_at = NOW()
FROM app.retailer AS retailer
JOIN (
    VALUES
        (
            'LIDL',
            'https://kompanija.lidl.rs/content/download/165294/fileupload/cene_proizvoda_Lidl.csv',
            'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-lidl/'
        ),
        (
            'IDEA_RODA',
            'https://data.gov.rs/s/resources/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-idea-marketi-doo/20260831-173028/cene-proizvoda-ideamarketi.csv',
            'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-idea-marketi-doo/'
        ),
        (
            'UNIVEREXPORT',
            'https://data.gov.rs/s/resources/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-univerexport-export-import-doo/20260831-112950/cene310826.csv',
            'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-univerexport-export-import-doo/'
        ),
        (
            'EUROPROM',
            'https://api.nitsolutions.rs/api/v1/external/cenovnik/00fc017c-0f22-40c2-9e38-50f4c4a66f80.csv',
            'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-europrom-doo/'
        )
) AS source(retailer_code, source_url, discovery_url)
  ON retailer.code = source.retailer_code
WHERE data_source.retailer_id = retailer.id
  AND data_source.source_type = 'PRICE_CATALOG'
  AND data_source.price_scope <> 'STORE';


-- Oznake se čuvaju tačno kako stižu u IDEA/Roda CSV-u, jer se cena formata
-- povezuje sa fizičkim objektom preko normalizovanog imena formata.
UPDATE app.store_format AS format
SET name = mapping.source_format_name,
    updated_at = NOW()
FROM app.retailer AS retailer
JOIN (
    VALUES
        ('IDEA_I0', 'IDEA MARKETI_Cenovnik I0'),
        ('RODA_RPLUS', 'IDEA MARKETI_Cenovnik Rplus')
) AS mapping(format_code, source_format_name)
  ON TRUE
WHERE format.retailer_id = retailer.id
  AND retailer.code = 'IDEA_RODA'
  AND format.code = mapping.format_code;
