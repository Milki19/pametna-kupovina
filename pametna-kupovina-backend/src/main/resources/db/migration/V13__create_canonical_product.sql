CREATE TABLE app.canonical_product (
                                       id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                       canonical_key VARCHAR(100) NOT NULL,
                                       name VARCHAR(500) NOT NULL,
                                       brand VARCHAR(200),
                                       barcode VARCHAR(14),
                                       quantity_value NUMERIC(14, 4),
                                       base_unit VARCHAR(20),
                                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                       updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()

    -- TODO: UNIQUE canonical_key
    -- TODO: canonical_key i name ne smeju biti prazni
    -- TODO: quantity_value mora biti pozitivna kada postoji
    -- TODO: barcode mora imati 8–14 cifara i ne sme biti samo od nula
);

-- TODO: parcijalni UNIQUE indeks za postojeći barcode

ALTER TABLE app.retailer_product
    ADD COLUMN canonical_product_id BIGINT;

-- TODO: foreign key prema app.canonical_product(id)

-- TODO: indeks nad retailer_product.canonical_product_id