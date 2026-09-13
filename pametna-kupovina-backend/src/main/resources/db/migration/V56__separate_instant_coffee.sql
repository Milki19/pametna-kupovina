-- "Kafa" in a Serbian shopping list means ground coffee. Instant, 3-in-1 and
-- cappuccino sachets are a different purchase and were winning the category on
-- price alone. Someone who wants them writes "instant kafa" or "3u1".

INSERT INTO app.product_type(code, name, product_category_id)
SELECT 'INSTANT_COFFEE', 'Instant kafa i 3u1', c.id
FROM app.product_category c WHERE c.code = 'COFFEE';

UPDATE app.product_type_rule SET active = FALSE, updated_at = NOW()
WHERE retailer_id IS NULL AND product_type_id IN
    (SELECT id FROM app.product_type WHERE code = 'COFFEE');

INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT t.id, r.inc, r.exc, r.priority, 0.9950
FROM (VALUES
 ('INSTANT_COFFEE', '\m(kafa|kafe|coffee)\M.*\m(instant|cappuccino|capuccino|kapucino|frape|frappe)\M|\m(instant|cappuccino|capuccino|kapucino)\M.*\m(kafa|kafe|coffee)\M|\m3 (u|in) 1\M', '\m(sladoled|keks[a-z]*|bombon[a-z]*|torta|krem)\M', 2),
 ('COFFEE', '\m(kafa|kafe|coffee)\M', '\m(instant|cappuccino|capuccino|kapucino|frape|frappe|sladoled|keks[a-z]*|bombon[a-z]*|torta|krem|aparat|filter papir)\M|\m3 (u|in) 1\M', 10)
) r(code, inc, exc, priority)
JOIN app.product_type t ON t.code = r.code;

INSERT INTO app.shopping_intent(code, name)
SELECT code, name FROM app.product_type WHERE code = 'INSTANT_COFFEE';

INSERT INTO app.shopping_intent_product_type(shopping_intent_id, product_type_id, substitution_level, match_priority, enabled_by_default)
SELECT i.id, t.id, 'EXACT', 10, TRUE
FROM app.shopping_intent i JOIN app.product_type t USING(code)
WHERE i.code = 'INSTANT_COFFEE';

INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority)
SELECT i.id, a.alias, 5 FROM (VALUES
 ('INSTANT_COFFEE','instant kafa'), ('INSTANT_COFFEE','kafa instant'),
 ('INSTANT_COFFEE','nes kafa'), ('INSTANT_COFFEE','neskafa'),
 ('INSTANT_COFFEE','3u1'), ('INSTANT_COFFEE','3 u 1'),
 ('INSTANT_COFFEE','kapucino'), ('INSTANT_COFFEE','cappuccino'),
 ('COFFEE','mlevena kafa'), ('COFFEE','domaca kafa'), ('COFFEE','kafa mlevena')
) a(code, alias) JOIN app.shopping_intent i ON i.code = a.code
ON CONFLICT(normalized_alias) DO UPDATE SET shopping_intent_id = EXCLUDED.shopping_intent_id, priority = 5;

UPDATE app.shopping_list_item item SET shopping_intent_id = a.shopping_intent_id
FROM app.shopping_intent_alias a
WHERE item.matching_rule = 'FLEXIBLE_CATEGORY' AND item.flexible_category_normalized = a.normalized_alias;

-- Instant coffee is sold in jars and sachet boxes, not the 200g ground pack.
UPDATE app.shopping_intent SET
    default_min_package_quantity = 100,
    default_max_package_quantity = 250,
    default_base_unit = 'g',
    updated_at = NOW()
WHERE code = 'INSTANT_COFFEE';

-- Keep the shared prediction query but version the changed rule set for audit.
DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v3', 'taxonomy-v4');
END $$;
DELETE FROM app.product_type_candidate WHERE status='PENDING';
INSERT INTO app.product_type_candidate(retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version)
SELECT p.retailer_product_id,p.product_type_id,p.confidence,p.prediction_source,p.evidence,p.algorithm_version
FROM app.product_type_prediction p
WHERE p.confidence>=0.75 AND p.confidence<0.95
 AND NOT EXISTS (SELECT 1 FROM app.retailer_product_type t WHERE t.retailer_product_id=p.retailer_product_id AND t.reviewed)
ON CONFLICT DO NOTHING;

-- Refresh only machine classifications; human-reviewed assignments survive.
DELETE FROM app.retailer_product_type WHERE NOT reviewed;
INSERT INTO app.retailer_product_type(retailer_product_id, product_type_id, confidence, assignment_source, evidence, algorithm_version)
SELECT retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version
FROM app.product_type_prediction p WHERE confidence>=0.95
ON CONFLICT(retailer_product_id) DO NOTHING;
UPDATE app.product_family f SET product_type_id=NULL WHERE NOT EXISTS (
 SELECT 1 FROM app.retailer_product p JOIN app.retailer_product_type t ON t.retailer_product_id=p.id
 WHERE p.product_family_id=f.id AND t.product_type_id=f.product_type_id);
