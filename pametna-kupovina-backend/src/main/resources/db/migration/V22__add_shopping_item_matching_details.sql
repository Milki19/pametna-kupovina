ALTER TABLE app.shopping_list_item
    ADD COLUMN matching_decision_id BIGINT,
    ADD COLUMN matching_score NUMERIC(5, 4),
    ADD COLUMN matching_algorithm_version VARCHAR(100),
    ADD COLUMN flexible_category VARCHAR(200),
    ADD COLUMN flexible_category_normalized VARCHAR(200),
    ADD COLUMN required_brand VARCHAR(200),
    ADD COLUMN min_package_quantity NUMERIC(14, 4),
    ADD COLUMN max_package_quantity NUMERIC(14, 4),
    ADD COLUMN required_base_unit VARCHAR(20);


UPDATE app.shopping_list_item
SET flexible_category = name,
    flexible_category_normalized = LOWER(
        REGEXP_REPLACE(BTRIM(name), '[^[:alnum:]]+', ' ', 'g')
    )
WHERE matching_rule = 'FLEXIBLE_CATEGORY';


ALTER TABLE app.shopping_list_item
    ADD CONSTRAINT fk_shopping_list_item_matching_decision
        FOREIGN KEY (matching_decision_id)
            REFERENCES app.product_match_decision (id),

    ADD CONSTRAINT chk_shopping_list_item_matching_score
        CHECK (
            matching_score IS NULL
                OR (matching_score >= 0 AND matching_score <= 1)
        ),

    ADD CONSTRAINT chk_shopping_list_item_flexible_category
        CHECK (
            (
                matching_rule = 'EXACT_PRODUCT'
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
        ),

    ADD CONSTRAINT chk_shopping_list_item_package_range
        CHECK (
            (min_package_quantity IS NULL OR min_package_quantity > 0)
                AND (max_package_quantity IS NULL OR max_package_quantity > 0)
                AND (
                    min_package_quantity IS NULL
                        OR max_package_quantity IS NULL
                        OR max_package_quantity >= min_package_quantity
                )
        ),

    ADD CONSTRAINT chk_shopping_list_item_required_base_unit
        CHECK (
            required_base_unit IS NULL
                OR required_base_unit IN ('g', 'ml', 'piece')
        );


CREATE INDEX idx_shopping_list_item_matching_decision
    ON app.shopping_list_item (matching_decision_id)
    WHERE matching_decision_id IS NOT NULL;


CREATE INDEX idx_shopping_list_item_flexible_category
    ON app.shopping_list_item (
        shopping_list_id,
        flexible_category_normalized
    )
    WHERE matching_rule = 'FLEXIBLE_CATEGORY';
