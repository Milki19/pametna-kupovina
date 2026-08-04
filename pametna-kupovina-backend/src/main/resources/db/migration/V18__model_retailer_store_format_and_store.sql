CREATE TABLE app.store_format (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_store_format_retailer
        FOREIGN KEY (retailer_id)
            REFERENCES app.retailer (id),

    CONSTRAINT uq_store_format_code
        UNIQUE (retailer_id, code),

    CONSTRAINT uq_store_format_id_retailer
        UNIQUE (id, retailer_id),

    CONSTRAINT chk_store_format_code_not_blank
        CHECK (BTRIM(code) <> ''),

    CONSTRAINT chk_store_format_name_not_blank
        CHECK (BTRIM(name) <> ''),

    CONSTRAINT chk_store_format_timestamps
        CHECK (updated_at >= created_at)
);


-- Svaki postojeći lanac dobija kompatibilan podrazumevani format.
INSERT INTO app.store_format (
    retailer_id,
    code,
    name
)
SELECT retailer.id,
       'STANDARD',
       'Standardni format'
FROM app.retailer AS retailer;


-- Ranije sačuvani nazivi formata postaju pravi store_format redovi.
INSERT INTO app.store_format (
    retailer_id,
    code,
    name
)
SELECT DISTINCT
       location.retailer_id,
       'LEGACY-' || UPPER(
           SUBSTRING(
               MD5(BTRIM(location.retailer_format_name))
               FROM 1 FOR 12
           )
       ),
       BTRIM(location.retailer_format_name)
FROM app.retailer_location AS location
WHERE NULLIF(BTRIM(location.retailer_format_name), '') IS NOT NULL;


-- Postojeća tabela već predstavlja fizičke objekte; migriramo je bez
-- paralelnog dupliranja podataka.
ALTER TABLE app.retailer_location
    RENAME TO store;

ALTER TABLE app.store
    RENAME CONSTRAINT fk_retailer_location_retailer
        TO fk_store_retailer;

ALTER TABLE app.store
    RENAME CONSTRAINT uq_retailer_location_code
        TO uq_store_code;

ALTER INDEX app.idx_retailer_location_retailer
    RENAME TO idx_store_retailer;

DROP INDEX app.idx_retailer_location_location;
DROP INDEX app.idx_retailer_location_format;


ALTER TABLE app.store
    ADD COLUMN store_format_id BIGINT,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();


UPDATE app.store AS store
SET store_format_id = COALESCE(
    (
        SELECT format.id
        FROM app.store_format AS format
        WHERE format.retailer_id = store.retailer_id
          AND format.code =
              'LEGACY-' || UPPER(
                  SUBSTRING(
                      MD5(BTRIM(store.retailer_format_name))
                      FROM 1 FOR 12
                  )
              )
    ),
    (
        SELECT format.id
        FROM app.store_format AS format
        WHERE format.retailer_id = store.retailer_id
          AND format.code = 'STANDARD'
    )
);


ALTER TABLE app.store
    ALTER COLUMN store_format_id SET NOT NULL,
    ALTER COLUMN location DROP NOT NULL,
    DROP COLUMN retailer_format_name;


ALTER TABLE app.store
    ADD CONSTRAINT fk_store_store_format
        FOREIGN KEY (store_format_id, retailer_id)
            REFERENCES app.store_format (id, retailer_id),

    ADD CONSTRAINT chk_store_external_code_not_blank
        CHECK (BTRIM(external_code) <> ''),

    ADD CONSTRAINT chk_store_name_not_blank
        CHECK (BTRIM(name) <> ''),

    ADD CONSTRAINT chk_store_timestamps
        CHECK (updated_at >= created_at);


CREATE INDEX idx_store_format
    ON app.store (store_format_id)
    WHERE active = TRUE;

CREATE INDEX idx_store_location
    ON app.store
    USING GIST (location)
    WHERE location IS NOT NULL;

