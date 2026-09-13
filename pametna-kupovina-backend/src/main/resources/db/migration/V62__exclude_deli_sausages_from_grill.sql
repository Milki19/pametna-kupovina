-- With a 2kg target the cheapest kilogram won, and that turned out to be a
-- 2.2kg pariska. Pariska, mortadela, frankfurters and similar boiled deli
-- sausages are sliced for sandwiches, not put on a grill, so they belong in
-- the broad MEAT type rather than in the grill one.

UPDATE app.product_type_rule
SET exclude_pattern = replace(
        exclude_pattern,
        '|\m(zacin|supa|kocka|cips|sos|hrana)\M',
        '|\m(zacin|supa|kocka|cips|sos|hrana|parisk[a-z]*|mortadel[a-z]*|frankf[a-z]*|hrenovk[a-z]*|virsl[a-z]*|safalad[a-z]*|kranjsk[a-z]*)\M'
    ),
    updated_at = NOW()
WHERE active = TRUE
  AND retailer_id IS NULL
  AND product_type_id = (SELECT id FROM app.product_type WHERE code = 'GRILL_SAUSAGE');

DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v7', 'taxonomy-v8');
END $$;
DELETE FROM app.product_type_candidate WHERE status='PENDING';
INSERT INTO app.product_type_candidate(retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version)
SELECT p.retailer_product_id,p.product_type_id,p.confidence,p.prediction_source,p.evidence,p.algorithm_version
FROM app.product_type_prediction p
WHERE p.confidence>=0.75 AND p.confidence<0.95
 AND NOT EXISTS (SELECT 1 FROM app.retailer_product_type t WHERE t.retailer_product_id=p.retailer_product_id AND t.reviewed)
ON CONFLICT DO NOTHING;

DELETE FROM app.retailer_product_type WHERE NOT reviewed;
INSERT INTO app.retailer_product_type(retailer_product_id, product_type_id, confidence, assignment_source, evidence, algorithm_version)
SELECT retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version
FROM app.product_type_prediction p WHERE confidence>=0.95
ON CONFLICT(retailer_product_id) DO NOTHING;
UPDATE app.product_family f SET product_type_id=NULL WHERE NOT EXISTS (
 SELECT 1 FROM app.retailer_product p JOIN app.retailer_product_type t ON t.retailer_product_id=p.id
 WHERE p.product_family_id=f.id AND t.product_type_id=f.product_type_id);
