-- Two things the merge made visible.
--
-- "mleko" now ranks products of the type milk first, and that put Becutan baby
-- lotion at the top: one chain writes it "Mleko za decu Becutan 200ml", the
-- milk rule took it, and the merged product carries that type for all seven
-- chains. Sun lotions, body milk, coffee creamer portions and a powdered milk
-- substitute had slipped through the same way. Milk for coffee in a carton
-- stays milk.
--
-- A merged product keeps the name of its oldest family, and a few of those
-- start with a stray symbol or blank ("# SVINJSKI VRAT BK").

WITH retired AS (
    UPDATE app.product_type_rule AS rule
    SET active = FALSE,
        updated_at = NOW()
    FROM app.product_type AS type
    WHERE type.id = rule.product_type_id
      AND type.code = 'MILK'
      AND rule.retailer_id IS NULL
      AND rule.active
    RETURNING rule.product_type_id,
              rule.include_pattern,
              rule.exclude_pattern,
              rule.priority,
              rule.confidence
)
INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT product_type_id,
       include_pattern,
       exclude_pattern
           || '|\m(becutan|bekutan|body|sun|suncanja|spf|carroten|carotten|losion|kupka|zamena)\M'
           || '|\m10 ?x ?10\M',
       priority,
       confidence
FROM retired;

-- Keep the shared prediction query but version the changed rule set for audit.
DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v9', 'taxonomy-v10');
END $$;
-- Only the milk rule changed, so only products named "mleko" can change type.
-- Reclassifying all of them instead held the migration (and the app) for
-- four and a half minutes.
CREATE TEMP TABLE milk_named_product ON COMMIT DROP AS
SELECT id FROM app.retailer_product
WHERE normalized_name ~ '\m(mleko|mlijeko)\M';

DELETE FROM app.product_type_candidate
WHERE status='PENDING'
  AND retailer_product_id IN (SELECT id FROM milk_named_product);
INSERT INTO app.product_type_candidate(retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version)
SELECT p.retailer_product_id,p.product_type_id,p.confidence,p.prediction_source,p.evidence,p.algorithm_version
FROM app.product_type_prediction p
WHERE p.confidence>=0.75 AND p.confidence<0.95
 AND p.retailer_product_id IN (SELECT id FROM milk_named_product)
 AND NOT EXISTS (SELECT 1 FROM app.retailer_product_type t WHERE t.retailer_product_id=p.retailer_product_id AND t.reviewed)
ON CONFLICT DO NOTHING;

-- Refresh only machine classifications; human-reviewed assignments survive.
DELETE FROM app.retailer_product_type
WHERE NOT reviewed
  AND retailer_product_id IN (SELECT id FROM milk_named_product);
INSERT INTO app.retailer_product_type(retailer_product_id, product_type_id, confidence, assignment_source, evidence, algorithm_version)
SELECT retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version
FROM app.product_type_prediction p
WHERE confidence>=0.95
  AND p.retailer_product_id IN (SELECT id FROM milk_named_product)
ON CONFLICT(retailer_product_id) DO NOTHING;
UPDATE app.product_family f SET product_type_id=NULL WHERE NOT EXISTS (
 SELECT 1 FROM app.retailer_product p JOIN app.retailer_product_type t ON t.retailer_product_id=p.id
 WHERE p.product_family_id=f.id AND t.product_type_id=f.product_type_id);

UPDATE app.product_family AS family
SET display_name = better.name,
    updated_at = NOW()
FROM (
    SELECT DISTINCT ON (product.product_family_id)
           product.product_family_id,
           product.name
    FROM app.retailer_product AS product
    WHERE product.name ~ '^[[:alnum:]]'
    GROUP BY product.product_family_id, product.name
    ORDER BY product.product_family_id,
             COUNT(*) DESC,
             LENGTH(product.name),
             product.name
) AS better
WHERE better.product_family_id = family.id
  AND family.display_name ~ '^[^[:alnum:]]';
