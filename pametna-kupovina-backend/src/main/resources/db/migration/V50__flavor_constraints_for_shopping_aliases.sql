-- An attribute of the requested dairy type, not a category per flavor or SKU.
ALTER TABLE app.shopping_intent_alias ADD COLUMN required_name_pattern TEXT;

WITH flavors(code, noun, adjective, pattern) AS (VALUES
 ('FRUIT_YOGURT','jagoda','jagode','\mjagod[a-z]*\M'),
 ('FRUIT_YOGURT','malina','maline','\mmalin[a-z]*\M'),
 ('FRUIT_YOGURT','breskva','breskve','\mbreskv[a-z]*\M'),
 ('FRUIT_YOGURT','visnja','visnje','\mvisnj[a-z]*\M'),
 ('FRUIT_YOGURT','borovnica','borovnice','\mborov[a-z]*\M'),
 ('FRUIT_YOGURT','banana','banane','\mbanan[a-z]*\M'),
 ('FRUIT_YOGURT','vanila','vanile','\mvanil[a-z]*\M')
), aliases AS (
 SELECT code, phrase AS alias, pattern FROM flavors
 CROSS JOIN LATERAL (VALUES
   ('jogurt ' || noun), ('vocni jogurt ' || noun),
   ('jogurt sa ukusom ' || adjective), ('jogurt od ' || adjective)
 ) a(phrase)
 UNION ALL
 SELECT 'FLAVORED_MILK', phrase, '\m(cok[a-z]*|kakao)\M'
 FROM (VALUES ('mleko cokoladno'), ('cokoladno mleko'), ('mleko coko'),
              ('coko mleko'), ('mleko cokolada'), ('mleko sa ukusom cokolade')) a(phrase)
)
INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority, required_name_pattern)
SELECT i.id, a.alias, 5, a.pattern FROM aliases a JOIN app.shopping_intent i ON i.code=a.code
ON CONFLICT(normalized_alias) DO UPDATE SET shopping_intent_id=EXCLUDED.shopping_intent_id,
 priority=EXCLUDED.priority, required_name_pattern=EXCLUDED.required_name_pattern;

-- Real source abbreviations include "MLEKO COK."; milk in a cheese's brand is not milk.
UPDATE app.product_type_rule SET
 include_pattern=replace(include_pattern,'coko[a-z]*','cok[a-z]*'),
 exclude_pattern=replace(exclude_pattern,'coko[a-z]*','cok[a-z]*'), updated_at=NOW()
WHERE active AND retailer_id IS NULL AND product_type_id IN
 (SELECT id FROM app.product_type WHERE code IN ('MILK','FLAVORED_MILK'));
UPDATE app.product_type_rule SET exclude_pattern=exclude_pattern || '|\m(sir|sira|sirni|sirni[a-z]*|puding)\M'
WHERE active AND retailer_id IS NULL AND product_type_id IN
 (SELECT id FROM app.product_type WHERE code IN ('MILK','FLAVORED_MILK'));

-- Refresh affected machine assignments only; preserve manual review and all raw data.
DELETE FROM app.retailer_product_type a USING app.product_type t
WHERE t.id=a.product_type_id AND t.code IN ('MILK','FLAVORED_MILK') AND NOT a.reviewed;
INSERT INTO app.retailer_product_type(retailer_product_id,product_type_id,confidence,assignment_source,evidence,algorithm_version)
SELECT p.retailer_product_id,p.product_type_id,p.confidence,p.prediction_source,p.evidence,p.algorithm_version
FROM app.product_type_prediction p JOIN app.product_type t ON t.id=p.product_type_id
WHERE t.code IN ('MILK','FLAVORED_MILK') AND p.confidence>=0.95
ON CONFLICT(retailer_product_id) DO NOTHING;
