-- Jedinstven registar omogućava da URL, parser, raspored i poslednji uspeh
-- izvora budu podaci u bazi, umesto razbacane konfiguracije u kodu.
CREATE TABLE app.retailer_data_source (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    parser_profile VARCHAR(50) NOT NULL,
    source_url TEXT,
    discovery_url TEXT,
    encoding VARCHAR(30) NOT NULL DEFAULT 'UTF-8',
    delimiter VARCHAR(10),
    price_scope VARCHAR(30),
    schedule_cron VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_status VARCHAR(30) NOT NULL DEFAULT 'NEVER_RUN',
    last_started_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_snapshot_date DATE,
    last_checksum VARCHAR(64),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_retailer_data_source_retailer
        FOREIGN KEY (retailer_id)
            REFERENCES app.retailer (id),

    CONSTRAINT uq_retailer_data_source_code
        UNIQUE (retailer_id, code),

    CONSTRAINT chk_retailer_data_source_type
        CHECK (source_type IN ('PRICE_CATALOG', 'STORE_LOCATIONS')),

    CONSTRAINT chk_retailer_data_source_status
        CHECK (last_status IN (
            'NEVER_RUN',
            'RUNNING',
            'SUCCEEDED',
            'SUCCEEDED_WITH_ERRORS',
            'FAILED'
        )),

    CONSTRAINT chk_retailer_data_source_code_not_blank
        CHECK (BTRIM(code) <> ''),

    CONSTRAINT chk_retailer_data_source_parser_not_blank
        CHECK (BTRIM(parser_profile) <> ''),

    CONSTRAINT chk_retailer_data_source_timestamps
        CHECK (updated_at >= created_at)
);


ALTER TABLE app.import_run
    ADD COLUMN data_source_id BIGINT;

ALTER TABLE app.import_run
    ADD CONSTRAINT fk_import_run_data_source
        FOREIGN KEY (data_source_id)
            REFERENCES app.retailer_data_source (id);


-- Svi do sada potvrđeni državni CSV izvori ulaze u registar. Importer i dalje
-- podržava dataset_url kao kompatibilni fallback za lokalne/test retailere.
INSERT INTO app.retailer_data_source (
    retailer_id,
    code,
    source_type,
    parser_profile,
    source_url,
    discovery_url,
    encoding,
    delimiter,
    price_scope,
    schedule_cron
)
SELECT retailer.id,
       'PRIMARY_PRICE_CATALOG',
       'PRICE_CATALOG',
       'GOV_RS_SEMICOLON_CSV',
       retailer.dataset_url,
       'https://data.gov.rs/sr/reuses/cenovnici-po-uredbi/',
       'AUTO',
       ';',
       'RETAILER_OR_FORMAT',
       '0 0 3 * * *'
FROM app.retailer AS retailer
WHERE NULLIF(BTRIM(retailer.dataset_url), '') IS NOT NULL
ON CONFLICT (retailer_id, code)
DO UPDATE SET
    source_url = EXCLUDED.source_url,
    active = TRUE,
    updated_at = NOW();


-- Maxi je poseban store-level izvor čiji se dnevni fajlovi otkrivaju preko
-- zvanične stranice/API-ja, pa source_url namerno nije fiksni CSV.
INSERT INTO app.retailer_data_source (
    retailer_id,
    code,
    source_type,
    parser_profile,
    discovery_url,
    encoding,
    delimiter,
    price_scope,
    schedule_cron
)
SELECT retailer.id,
       'MAXI_DAILY_STORE_FILES',
       'PRICE_CATALOG',
       'MAXI_STORE_CSV',
       'https://www.maxi.rs/cenovnici',
       'UTF-8',
       ';',
       'STORE',
       '0 0 4 * * *'
FROM app.retailer AS retailer
WHERE retailer.code = 'MAXI'
ON CONFLICT (retailer_id, code)
DO UPDATE SET
    discovery_url = EXCLUDED.discovery_url,
    active = TRUE,
    updated_at = NOW();


-- Poreklo fizičkih lokacija ostaje odvojeno od rezultata geokodiranja.
ALTER TABLE app.store
    ADD COLUMN data_source_id BIGINT,
    ADD COLUMN source_record_key VARCHAR(200),
    ADD COLUMN source_last_seen_at TIMESTAMPTZ,
    ADD COLUMN verified_at TIMESTAMPTZ;

ALTER TABLE app.store
    ADD CONSTRAINT fk_store_data_source
        FOREIGN KEY (data_source_id)
            REFERENCES app.retailer_data_source (id),

    ADD CONSTRAINT chk_store_source_record_key_not_blank
        CHECK (
            source_record_key IS NULL
            OR BTRIM(source_record_key) <> ''
        );

UPDATE app.store
SET source_record_key = external_code,
    source_last_seen_at = updated_at,
    verified_at = CASE
        WHEN geocoding_status IN ('AUTO_VERIFIED', 'MANUALLY_VERIFIED')
            THEN COALESCE(geocoding_reviewed_at, geocoded_at, updated_at)
        ELSE NULL
    END;


CREATE INDEX idx_retailer_data_source_active
    ON app.retailer_data_source (source_type, active, retailer_id);

CREATE INDEX idx_store_data_source
    ON app.store (data_source_id, source_record_key)
    WHERE data_source_id IS NOT NULL;


-- Kontrolisana taksonomija je odvojena od slobodnog naziva iz cenovnika.
-- Automatska dodela ima confidence i može kasnije biti ručno potvrđena.
CREATE TABLE app.product_category (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    parent_id BIGINT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_category_parent
        FOREIGN KEY (parent_id)
            REFERENCES app.product_category (id),

    CONSTRAINT chk_product_category_code_not_blank
        CHECK (BTRIM(code) <> ''),

    CONSTRAINT chk_product_category_name_not_blank
        CHECK (BTRIM(name) <> '')
);

CREATE TABLE app.product_category_alias (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_category_id BIGINT NOT NULL,
    normalized_alias VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_category_alias_category
        FOREIGN KEY (product_category_id)
            REFERENCES app.product_category (id),

    CONSTRAINT chk_product_category_alias_not_blank
        CHECK (BTRIM(normalized_alias) <> '')
);

CREATE TABLE app.retailer_product_category (
    retailer_product_id BIGINT PRIMARY KEY,
    product_category_id BIGINT NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL,
    assignment_source VARCHAR(40) NOT NULL,
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_retailer_product_category_product
        FOREIGN KEY (retailer_product_id)
            REFERENCES app.retailer_product (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_retailer_product_category_category
        FOREIGN KEY (product_category_id)
            REFERENCES app.product_category (id),

    CONSTRAINT chk_retailer_product_category_confidence
        CHECK (confidence >= 0 AND confidence <= 1),

    CONSTRAINT chk_retailer_product_category_source_not_blank
        CHECK (BTRIM(assignment_source) <> '')
);

INSERT INTO app.product_category (code, name)
VALUES
    ('WATER', 'Voda'),
    ('BEER', 'Pivo'),
    ('WINE', 'Vino'),
    ('BREAD', 'Hleb'),
    ('MAYONNAISE', 'Majonez'),
    ('MILK', 'Mleko'),
    ('FLOUR', 'Brašno'),
    ('VINEGAR', 'Sirće'),
    ('OIL', 'Ulje'),
    ('YOGURT', 'Jogurt'),
    ('COFFEE', 'Kafa'),
    ('SUGAR', 'Šećer'),
    ('SALT', 'So'),
    ('EGGS', 'Jaja'),
    ('PASTA', 'Testenina');


INSERT INTO app.product_category_alias (
    product_category_id,
    normalized_alias
)
SELECT category.id, alias.normalized_alias
FROM (
    VALUES
        ('WATER', 'voda'),
        ('WATER', 'mineralna voda'),
        ('BEER', 'pivo'),
        ('WINE', 'vino'),
        ('BREAD', 'hleb'),
        ('MAYONNAISE', 'majonez'),
        ('MILK', 'mleko'),
        ('FLOUR', 'brasno'),
        ('VINEGAR', 'sirce'),
        ('OIL', 'ulje'),
        ('YOGURT', 'jogurt'),
        ('COFFEE', 'kafa'),
        ('SUGAR', 'secer'),
        ('SALT', 'so'),
        ('EGGS', 'jaja'),
        ('PASTA', 'testenina')
) AS alias(category_code, normalized_alias)
JOIN app.product_category AS category
  ON category.code = alias.category_code;


INSERT INTO app.retailer_product_category (
    retailer_product_id,
    product_category_id,
    confidence,
    assignment_source
)
SELECT product.id,
       matched_alias.product_category_id,
       CASE
           WHEN product.normalized_name = matched_alias.normalized_alias
               THEN 0.9500
           ELSE 0.8500
       END,
       'NORMALIZED_NAME_PREFIX'
FROM app.retailer_product AS product
JOIN LATERAL (
    SELECT alias.product_category_id,
           alias.normalized_alias
    FROM app.product_category_alias AS alias
    WHERE product.normalized_name = alias.normalized_alias
       OR product.normalized_name LIKE alias.normalized_alias || ' %'
    ORDER BY LENGTH(alias.normalized_alias) DESC,
             alias.id ASC
    LIMIT 1
) AS matched_alias ON TRUE;


CREATE INDEX idx_retailer_product_category_category
    ON app.retailer_product_category (
        product_category_id,
        retailer_product_id
    );
