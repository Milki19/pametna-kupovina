ALTER TABLE app.retailer_product
    ADD COLUMN source_product_key VARCHAR(80);


-- Uklanjamo razmake oko postojećih barkodova.
UPDATE app.retailer_product
SET barcode = BTRIM(barcode)
WHERE barcode IS NOT NULL;


-- Prazne, nenumeričke, prekratke/predugačke i all-zero
-- vrednosti nisu upotrebljivi EAN/GTIN barkodovi.
UPDATE app.retailer_product
SET barcode = NULL
WHERE barcode IS NOT NULL
  AND (
    barcode = ''
        OR barcode !~ '^[0-9]{8,14}$'
        OR barcode ~ '^0+$'
    );


-- Postojeći validni proizvodi zadržavaju stabilan ključ preko barkoda.
-- Stari proizvodi bez validnog barkoda dobijaju privremeni jedinstveni
-- LEGACY ključ. Novi importer će za takve proizvode praviti fingerprint.
UPDATE app.retailer_product
SET source_product_key =
        CASE
            WHEN barcode IS NOT NULL
                THEN 'BARCODE:' || barcode
            ELSE 'LEGACY:' || id::text
            END;


ALTER TABLE app.retailer_product
    ALTER COLUMN source_product_key SET NOT NULL;


ALTER TABLE app.retailer_product
    ADD CONSTRAINT chk_retailer_product_source_key
        CHECK (BTRIM(source_product_key) <> '');


ALTER TABLE app.retailer_product
    ADD CONSTRAINT uq_retailer_product_source_key
        UNIQUE (retailer_id, source_product_key);


-- I stare stavke spiskova ne smeju koristiti lažne barkodove.
UPDATE app.shopping_list_item
SET barcode = BTRIM(barcode)
WHERE barcode IS NOT NULL;


UPDATE app.shopping_list_item
SET barcode = NULL
WHERE barcode IS NOT NULL
  AND (
    barcode = ''
        OR barcode !~ '^[0-9]{8,14}$'
        OR barcode ~ '^0+$'
    );


-- Poslovnica se kasnije povezuje sa tačnim formatom cenovnika.
ALTER TABLE app.retailer_location
    ADD COLUMN retailer_format_name VARCHAR(200);


-- Europrom trenutno ima jedan format za sve poslovnice.
UPDATE app.retailer_location AS location
SET retailer_format_name = 'Europrom'
    FROM app.retailer AS retailer
WHERE retailer.id = location.retailer_id
  AND retailer.code = 'EUROPROM'
  AND location.external_code NOT LIKE 'DEMO-%';


-- Ova Tekijanka vrednost je potvrđena u zvaničnom CSV-u.
UPDATE app.retailer_location AS location
SET retailer_format_name =
        'TEKIJANKA D.O.O. TEKIJA Kladovo'
    FROM app.retailer AS retailer
WHERE retailer.id = location.retailer_id
  AND retailer.code = 'TEKIJANKA'
  AND location.city = 'Kladovo'
  AND location.external_code NOT LIKE 'DEMO-%';


-- Podaci potrebni za praćenje importa kompletnog najnovijeg preseka.
ALTER TABLE app.import_run
    ADD COLUMN snapshot_date DATE,
    ADD COLUMN rows_selected INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rows_skipped INTEGER NOT NULL DEFAULT 0;


-- Ispravne početne vrednosti za ranije importe.
UPDATE app.import_run
SET rows_selected = rows_saved,
    rows_skipped = GREATEST(rows_read - rows_saved, 0);


ALTER TABLE app.import_run
    ADD CONSTRAINT chk_import_run_rows_selected
        CHECK (
            rows_selected >= 0
                AND rows_selected <= rows_read
            ),
    ADD CONSTRAINT chk_import_run_rows_skipped
        CHECK (rows_skipped >= 0),
    ADD CONSTRAINT chk_import_run_rows_saved_selected
        CHECK (rows_saved <= rows_selected);


-- Omogućava status kada je import završen,
-- ali su pojedini redovi bili neispravni.
ALTER TABLE app.import_run
DROP CONSTRAINT chk_import_run_status;


ALTER TABLE app.import_run
    ADD CONSTRAINT chk_import_run_status
        CHECK (
            status IN (
                       'RUNNING',
                       'SUCCEEDED',
                       'SUCCEEDED_WITH_ERRORS',
                       'FAILED'
                )
            );


CREATE INDEX idx_price_observation_product_format_date
    ON app.price_observation (
                              retailer_product_id,
                              retailer_format_name,
                              price_date DESC,
                              id DESC
        );


CREATE INDEX idx_retailer_location_format
    ON app.retailer_location (
                              retailer_id,
                              retailer_format_name
        )
    WHERE active = TRUE;