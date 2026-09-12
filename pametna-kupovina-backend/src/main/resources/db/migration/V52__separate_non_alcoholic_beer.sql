-- Same broad category, distinct acceptable substitutes. No categories per SKU.
INSERT INTO app.product_type(code, name, product_category_id)
SELECT 'NON_ALCOHOLIC_BEER', 'Bezalkoholno pivo', id
FROM app.product_category WHERE code='BEER';

UPDATE app.product_type_rule SET active=FALSE, updated_at=NOW()
WHERE retailer_id IS NULL AND product_type_id=(SELECT id FROM app.product_type WHERE code='BEER');

INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT t.id, r.inc, r.exc, r.priority, 0.9950
FROM (VALUES
 ('NON_ALCOHOLIC_BEER', '\m(pivo|beer|bier)\M.*\m(bezalkohol[a-z]*|bez alkohola|non alcoholic|alcohol free|alkoholfrei|0 0)\M|\m(bezalkohol[a-z]*|bez alkohola|non alcoholic|alcohol free|alkoholfrei|0 0)\M.*\m(pivo|beer|bier)\M', '\m(casa|case|otvarac|sampon|kupka)\M', 2),
 ('BEER', '\m(pivo|beer|bier)\M', '\m(bezalkohol[a-z]*|bez alkohola|non alcoholic|alcohol free|alkoholfrei|0 0|casa|case|otvarac|sampon|kupka)\M', 20)
) r(code, inc, exc, priority) JOIN app.product_type t ON t.code=r.code;

INSERT INTO app.shopping_intent(code,name) VALUES('NON_ALCOHOLIC_BEER','Bezalkoholno pivo');
INSERT INTO app.shopping_intent_product_type(shopping_intent_id,product_type_id,substitution_level,match_priority,enabled_by_default)
SELECT i.id,t.id,'EXACT',10,TRUE FROM app.shopping_intent i JOIN app.product_type t USING(code)
WHERE i.code='NON_ALCOHOLIC_BEER';
INSERT INTO app.shopping_intent_alias(shopping_intent_id,normalized_alias,priority)
SELECT i.id,a.alias,5 FROM app.shopping_intent i CROSS JOIN (VALUES
 ('bezalkoholno pivo'),('pivo bezalkoholno'),('pivo bez alkohola'),
 ('pivo 0 0'),('0 0 pivo'),('non alcoholic beer'),('alcohol free beer')
) a(alias) WHERE i.code='NON_ALCOHOLIC_BEER';

UPDATE app.shopping_list_item item SET shopping_intent_id=a.shopping_intent_id
FROM app.shopping_intent_alias a WHERE item.matching_rule='FLEXIBLE_CATEGORY'
 AND item.flexible_category_normalized=a.normalized_alias;

-- Refresh affected automatic assignments; keep reviewed classifications and raw data.
DELETE FROM app.retailer_product_type a USING app.product_type t
WHERE t.id=a.product_type_id AND t.code='BEER' AND NOT a.reviewed;
INSERT INTO app.retailer_product_type(retailer_product_id,product_type_id,confidence,assignment_source,evidence,algorithm_version)
SELECT p.retailer_product_id,p.product_type_id,p.confidence,p.prediction_source,p.evidence,p.algorithm_version
FROM app.product_type_prediction p JOIN app.product_type t ON t.id=p.product_type_id
WHERE t.code IN ('BEER','NON_ALCOHOLIC_BEER') AND p.confidence>=0.95
ON CONFLICT(retailer_product_id) DO NOTHING;
