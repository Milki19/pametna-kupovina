ALTER TABLE app.canonical_product
    ADD COLUMN normalized_name TEXT;

ALTER TABLE app.canonical_product
    ADD CONSTRAINT chk_canonical_product_normalized_name_not_blank
        CHECK (
            normalized_name IS NULL
                OR BTRIM(normalized_name) <> ''
            );

ALTER TABLE app.retailer_product
    ADD COLUMN normalized_name TEXT,
    ADD COLUMN quantity_value NUMERIC(14, 4),
    ADD COLUMN base_unit VARCHAR(20);

ALTER TABLE app.retailer_product
    ADD CONSTRAINT chk_retailer_product_normalized_name_not_blank
        CHECK (
            normalized_name IS NULL
                OR BTRIM(normalized_name) <> ''
            ),
    ADD CONSTRAINT chk_retailer_product_quantity_positive
        CHECK (
            quantity_value IS NULL
                OR quantity_value > 0
            ),
    ADD CONSTRAINT chk_retailer_product_quantity_unit_pair
        CHECK (
            (quantity_value IS NULL AND base_unit IS NULL)
                OR (
                    quantity_value IS NOT NULL
                        AND base_unit IN ('g', 'ml', 'piece')
                    )
            );
