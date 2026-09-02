CREATE TABLE app.product_category_source_mapping (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id BIGINT,
    source_category_code VARCHAR(100) NOT NULL,
    product_category_id BIGINT NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL DEFAULT 0.9500,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_category_source_mapping_retailer
        FOREIGN KEY (retailer_id)
            REFERENCES app.retailer (id),

    CONSTRAINT fk_product_category_source_mapping_category
        FOREIGN KEY (product_category_id)
            REFERENCES app.product_category (id),

    CONSTRAINT uq_product_category_source_mapping
        UNIQUE NULLS NOT DISTINCT (retailer_id, source_category_code),

    CONSTRAINT chk_product_category_source_mapping_code_not_blank
        CHECK (BTRIM(source_category_code) <> ''),

    CONSTRAINT chk_product_category_source_mapping_confidence
        CHECK (confidence >= 0 AND confidence <= 1)
);


INSERT INTO app.product_category_source_mapping (
    retailer_id,
    source_category_code,
    product_category_id,
    confidence
)
SELECT NULL,
       mapping.source_category_code,
       category.id,
       0.9800
FROM (
    VALUES
        ('VODA', 'WATER'),
        ('PIVO', 'BEER'),
        ('VINO', 'WINE'),
        ('HLEB', 'BREAD'),
        ('MAJONEZ', 'MAYONNAISE'),
        ('MLEKO', 'MILK'),
        ('BRASNO', 'FLOUR'),
        ('SIRCE', 'VINEGAR'),
        ('ULJE', 'OIL'),
        ('JOGURT', 'YOGURT'),
        ('KAFA', 'COFFEE'),
        ('SECER', 'SUGAR'),
        ('SO', 'SALT'),
        ('JAJA', 'EGGS'),
        ('TESTENINA', 'PASTA')
) AS mapping(source_category_code, category_code)
JOIN app.product_category AS category
  ON category.code = mapping.category_code;


-- Izvorni category_code je pouzdaniji od zaključivanja samo iz početka
-- naziva (npr. "Beli hleb" ne počinje rečju "hleb"). Ručno pregledane
-- dodele se nikada ne prepisuju.
INSERT INTO app.retailer_product_category AS assignment (
    retailer_product_id,
    product_category_id,
    confidence,
    assignment_source
)
SELECT product.id,
       matched_mapping.product_category_id,
       matched_mapping.confidence,
       'SOURCE_CATEGORY_CODE'
FROM app.retailer_product AS product
JOIN LATERAL (
    SELECT mapping.product_category_id,
           mapping.confidence
    FROM app.product_category_source_mapping AS mapping
    WHERE UPPER(BTRIM(product.category_code)) =
          mapping.source_category_code
      AND (
          mapping.retailer_id IS NULL
          OR mapping.retailer_id = product.retailer_id
      )
    ORDER BY CASE
                 WHEN mapping.retailer_id = product.retailer_id THEN 1
                 ELSE 2
             END,
             mapping.id
    LIMIT 1
) AS matched_mapping ON TRUE
ON CONFLICT (retailer_product_id)
DO UPDATE SET
    product_category_id = EXCLUDED.product_category_id,
    confidence = EXCLUDED.confidence,
    assignment_source = EXCLUDED.assignment_source,
    updated_at = NOW()
WHERE assignment.reviewed = FALSE;
