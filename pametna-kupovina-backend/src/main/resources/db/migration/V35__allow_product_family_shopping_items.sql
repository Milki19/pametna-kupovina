ALTER TABLE app.shopping_list_item
    ADD COLUMN matched_product_family_id BIGINT;

ALTER TABLE app.shopping_list_item
    ADD CONSTRAINT fk_shopping_list_item_product_family
        FOREIGN KEY (matched_product_family_id)
            REFERENCES app.product_family (id);

ALTER TABLE app.shopping_list_item
    DROP CONSTRAINT chk_shopping_list_item_matching_rule,
    DROP CONSTRAINT chk_shopping_list_item_match_consistency,
    DROP CONSTRAINT chk_shopping_list_item_flexible_category;

ALTER TABLE app.shopping_list_item
    ADD CONSTRAINT chk_shopping_list_item_matching_rule
        CHECK (matching_rule IN (
            'EXACT_PRODUCT',
            'PRODUCT_FAMILY',
            'FLEXIBLE_CATEGORY'
        )),
    ADD CONSTRAINT chk_shopping_list_item_match_consistency
        CHECK (
            (
                matching_rule = 'EXACT_PRODUCT'
                AND matched_product_family_id IS NULL
                AND (
                    (matching_status IN ('AUTO_MATCHED', 'CONFIRMED')
                        AND matched_canonical_product_id IS NOT NULL)
                    OR
                    (matching_status IN ('PENDING', 'NEEDS_CONFIRMATION', 'UNMATCHED')
                        AND matched_canonical_product_id IS NULL)
                )
            )
            OR
            (
                matching_rule = 'PRODUCT_FAMILY'
                AND matching_status = 'CONFIRMED'
                AND matched_canonical_product_id IS NULL
                AND matched_product_family_id IS NOT NULL
            )
            OR
            (
                matching_rule = 'FLEXIBLE_CATEGORY'
                AND matched_canonical_product_id IS NULL
                AND matched_product_family_id IS NULL
            )
        ),
    ADD CONSTRAINT chk_shopping_list_item_flexible_category
        CHECK (
            (
                matching_rule IN ('EXACT_PRODUCT', 'PRODUCT_FAMILY')
                AND flexible_category IS NULL
                AND flexible_category_normalized IS NULL
                AND required_brand IS NULL
                AND min_package_quantity IS NULL
                AND max_package_quantity IS NULL
                AND required_base_unit IS NULL
            )
            OR
            (
                matching_rule = 'FLEXIBLE_CATEGORY'
                AND BTRIM(flexible_category) <> ''
                AND BTRIM(flexible_category_normalized) <> ''
            )
        );

CREATE INDEX idx_shopping_list_item_product_family
    ON app.shopping_list_item (matched_product_family_id)
    WHERE matched_product_family_id IS NOT NULL;
