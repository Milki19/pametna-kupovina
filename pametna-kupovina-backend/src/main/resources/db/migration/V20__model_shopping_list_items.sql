ALTER TABLE app.shopping_list_item
    ADD COLUMN raw_input VARCHAR(1000),
    ADD COLUMN matching_rule VARCHAR(40),
    ADD COLUMN matching_status VARCHAR(40),
    ADD COLUMN matched_canonical_product_id BIGINT,
    ADD COLUMN updated_at TIMESTAMPTZ;


UPDATE app.shopping_list_item
SET raw_input = name,
    matching_rule = 'EXACT_PRODUCT',
    matching_status = 'PENDING',
    updated_at = created_at;


UPDATE app.shopping_list_item AS item
SET matched_canonical_product_id = product.id,
    matching_status = 'CONFIRMED'
FROM app.canonical_product AS product
WHERE item.barcode IS NOT NULL
  AND product.barcode = item.barcode;


ALTER TABLE app.shopping_list_item
    ALTER COLUMN raw_input SET NOT NULL,
    ALTER COLUMN matching_rule SET NOT NULL,
    ALTER COLUMN matching_rule SET DEFAULT 'EXACT_PRODUCT',
    ALTER COLUMN matching_status SET NOT NULL,
    ALTER COLUMN matching_status SET DEFAULT 'PENDING',
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW();


ALTER TABLE app.shopping_list_item
    ADD CONSTRAINT fk_shopping_list_item_canonical_product
        FOREIGN KEY (matched_canonical_product_id)
            REFERENCES app.canonical_product (id),

    ADD CONSTRAINT chk_shopping_list_item_raw_input
        CHECK (BTRIM(raw_input) <> ''),

    ADD CONSTRAINT chk_shopping_list_item_matching_rule
        CHECK (
            matching_rule IN (
                'EXACT_PRODUCT',
                'FLEXIBLE_CATEGORY'
            )
        ),

    ADD CONSTRAINT chk_shopping_list_item_matching_status
        CHECK (
            matching_status IN (
                'PENDING',
                'AUTO_MATCHED',
                'NEEDS_CONFIRMATION',
                'CONFIRMED',
                'UNMATCHED'
            )
        ),

    ADD CONSTRAINT chk_shopping_list_item_match_consistency
        CHECK (
            (
                matching_status IN (
                    'AUTO_MATCHED',
                    'CONFIRMED'
                )
                AND matched_canonical_product_id IS NOT NULL
            )
            OR
            (
                matching_status IN (
                    'PENDING',
                    'NEEDS_CONFIRMATION',
                    'UNMATCHED'
                )
                AND matched_canonical_product_id IS NULL
            )
        );


CREATE INDEX idx_shopping_list_item_canonical_product
    ON app.shopping_list_item (matched_canonical_product_id)
    WHERE matched_canonical_product_id IS NOT NULL;


CREATE INDEX idx_shopping_list_item_matching_status
    ON app.shopping_list_item (shopping_list_id, matching_status);
