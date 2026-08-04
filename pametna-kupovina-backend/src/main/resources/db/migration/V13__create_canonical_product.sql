CREATE TABLE app.canonical_product
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canonical_key  VARCHAR(100)   NOT NULL,
    name           VARCHAR(500)   NOT NULL,
    brand          VARCHAR(200),
    barcode        VARCHAR(14),
    quantity_value NUMERIC(14, 4),
    base_unit      VARCHAR(20),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_canonical_product_canonical_key
        UNIQUE (canonical_key),

    CONSTRAINT chk_canonical_product_canonical_key_not_blank
        CHECK (BTRIM(canonical_key) <> ''),

    CONSTRAINT chk_canonical_product_name_not_blank
        CHECK (BTRIM(name) <> ''),

    CONSTRAINT chk_canonical_product_quantity_positive
        CHECK (quantity_value IS NULL OR quantity_value > 0),

    CONSTRAINT chk_canonical_product_barcode_valid
        CHECK (
            barcode IS NULL
                OR (
                barcode ~ '^[0-9]{8,14}$'
                    AND barcode !~ '^0+$'
                )
            )
);

CREATE UNIQUE INDEX uq_canonical_product_barcode
    ON app.canonical_product (barcode)
    WHERE barcode IS NOT NULL;

ALTER TABLE app.retailer_product
    ADD COLUMN canonical_product_id BIGINT;

ALTER TABLE app.retailer_product
    ADD CONSTRAINT fk_retailer_product_canonical_product
        FOREIGN KEY (canonical_product_id)
            REFERENCES app.canonical_product (id);

CREATE INDEX idx_retailer_product_canonical_product_id
    ON app.retailer_product (canonical_product_id);
