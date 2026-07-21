CREATE TABLE app.retailer
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    dataset_url TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_retailer_code UNIQUE (code)
);


CREATE TABLE app.retailer_product
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id   BIGINT       NOT NULL,
    category_code VARCHAR(100),
    category_name VARCHAR(200),
    name          VARCHAR(500) NOT NULL,
    brand         VARCHAR(200),
    barcode       VARCHAR(32),
    unit          VARCHAR(50),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_retailer_product_retailer
        FOREIGN KEY (retailer_id)
            REFERENCES app.retailer (id)
);


CREATE TABLE app.import_run
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id   BIGINT      NOT NULL,
    source_url    TEXT        NOT NULL,
    checksum      VARCHAR(64),
    status        VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ,
    rows_read     INTEGER     NOT NULL DEFAULT 0,
    rows_saved    INTEGER     NOT NULL DEFAULT 0,
    error_message TEXT,

    CONSTRAINT fk_import_run_retailer
        FOREIGN KEY (retailer_id)
            REFERENCES app.retailer (id),

    CONSTRAINT chk_import_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),

    CONSTRAINT chk_import_run_rows_read
        CHECK (rows_read >= 0),

    CONSTRAINT chk_import_run_rows_saved
        CHECK (rows_saved >= 0),

    CONSTRAINT chk_import_run_finished_at
        CHECK (finished_at IS NULL OR finished_at >= started_at)
);


CREATE TABLE app.price_observation
(
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_product_id  BIGINT      NOT NULL,
    import_run_id        BIGINT      NOT NULL,
    retailer_format_name VARCHAR(200),
    price_date           DATE        NOT NULL,
    regular_price        NUMERIC(12, 2),
    unit_price           NUMERIC(14, 4),
    discounted_price     NUMERIC(12, 2),
    discount_start       DATE,
    discount_end         DATE,
    vat_rate             NUMERIC(5, 2),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_price_observation_retailer_product
        FOREIGN KEY (retailer_product_id)
            REFERENCES app.retailer_product (id),

    CONSTRAINT fk_price_observation_import_run
        FOREIGN KEY (import_run_id)
            REFERENCES app.import_run (id),

    CONSTRAINT chk_price_observation_has_price
        CHECK (
            regular_price IS NOT NULL
                OR discounted_price IS NOT NULL
            ),

    CONSTRAINT chk_price_observation_regular_price
        CHECK (regular_price IS NULL OR regular_price >= 0),

    CONSTRAINT chk_price_observation_unit_price
        CHECK (unit_price IS NULL OR unit_price >= 0),

    CONSTRAINT chk_price_observation_discounted_price
        CHECK (discounted_price IS NULL OR discounted_price >= 0),

    CONSTRAINT chk_price_observation_discount_dates
        CHECK (
            discount_start IS NULL
                OR discount_end IS NULL
                OR discount_end >= discount_start
            ),

    CONSTRAINT chk_price_observation_vat_rate
        CHECK (
            vat_rate IS NULL
                OR (vat_rate >= 0 AND vat_rate <= 100)
            )
);


CREATE INDEX idx_retailer_product_retailer
    ON app.retailer_product (retailer_id);

CREATE INDEX idx_retailer_product_barcode
    ON app.retailer_product (barcode)
    WHERE barcode IS NOT NULL;

CREATE INDEX idx_import_run_retailer_started
    ON app.import_run (retailer_id, started_at DESC);

CREATE INDEX idx_price_observation_product_date
    ON app.price_observation (retailer_product_id, price_date DESC);

CREATE INDEX idx_price_observation_import_run
    ON app.price_observation (import_run_id);