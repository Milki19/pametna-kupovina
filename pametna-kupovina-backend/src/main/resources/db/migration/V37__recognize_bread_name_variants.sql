-- Dopuna nakon provere stvarnog kataloga: nazivi kao "Hlebic" i "hlebni"
-- ne smeju ostati bez kategorije samo zato što nisu tačno reč "hleb".
INSERT INTO app.product_category_rule (
    product_category_id,
    name_pattern,
    priority,
    confidence
)
SELECT category.id,
       '(^| )hleb[a-z]*( |$)',
       21,
       0.8700
FROM app.product_category AS category
WHERE category.code = 'BREAD'
ON CONFLICT DO NOTHING;

INSERT INTO app.retailer_product_category (
    retailer_product_id,
    product_category_id,
    confidence,
    assignment_source
)
SELECT product.id,
       rule.product_category_id,
       rule.confidence,
       'NAME_PATTERN_RULE'
FROM app.retailer_product AS product
JOIN app.product_category_rule AS rule
  ON rule.active = TRUE
 AND (rule.retailer_id IS NULL
      OR rule.retailer_id = product.retailer_id)
 AND product.normalized_name ~ rule.name_pattern
WHERE rule.name_pattern = '(^| )hleb[a-z]*( |$)'
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_category AS existing
      WHERE existing.retailer_product_id = product.id
  )
ON CONFLICT (retailer_product_id) DO NOTHING;

WITH affected_family AS (
    SELECT DISTINCT product.product_family_id,
           assignment.product_category_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_category AS assignment
      ON assignment.retailer_product_id = product.id
    JOIN app.product_category AS category
      ON category.id = assignment.product_category_id
    WHERE product.product_family_id IS NOT NULL
      AND category.code = 'BREAD'
)
UPDATE app.product_family AS family
SET product_category_id = affected.product_category_id,
    updated_at = NOW()
FROM affected_family AS affected
WHERE affected.product_family_id = family.id
  AND family.product_category_id IS NULL;
