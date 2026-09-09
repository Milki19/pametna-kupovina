-- Distinct shopping uses, not a new category for each brand or pack size.
INSERT INTO app.product_type(code, name, product_category_id)
SELECT v.code, v.name, c.id
FROM (VALUES ('FRUIT_YOGURT', 'Voćni i aromatizovani jogurt'),
             ('KEFIR', 'Kefir'), ('AYRAN', 'Ajran')) v(code, name)
JOIN app.product_category c ON c.code = 'YOGURT';

UPDATE app.product_type_rule SET active = FALSE, updated_at = NOW()
WHERE retailer_id IS NULL AND product_type_id IN
    (SELECT id FROM app.product_type WHERE code IN ('MILK','FLAVORED_MILK','YOGURT'));

INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT t.id, r.inc, r.exc, r.priority, 0.9950
FROM (VALUES
 ('FLAVORED_MILK', '(^| )(mleko|mlijeko)( |$).*\m(coko[a-z]*|kakao|ukus[a-z]*|jagod[a-z]*|vanil[a-z]*|banan[a-z]*)\M|\m(cokoladno|aromatizovano) (mleko|mlijeko)\M', '\m(napol[a-z]*|napolit[a-z]*|keks[a-z]*|cokolada|bombon[a-z]*|sladoled|telo|suncanje|ciscenje|prah[a-z]*)\M', 2),
 ('MILK', '\m(mleko|mlijeko)\M', '\m(kis|kiselo|kisela|ferment[a-z]*|coko[a-z]*|kakao|ukus[a-z]*|aroma[a-z]*|jagod[a-z]*|vanil[a-z]*|banan[a-z]*|biljn[a-z]*|sojin[a-z]*|badem[a-z]*|ovs[a-z]*|kokos[a-z]*|prah[a-z]*|telo|suncanje|ciscenje|napol[a-z]*|keks[a-z]*|sladoled|bombon[a-z]*)\M', 10),
 ('FRUIT_YOGURT', '\m(jogurt|yoghurt|yogurt)\M.*\m(voc[a-z]*|ukus[a-z]*|jagod[a-z]*|malin[a-z]*|breskv[a-z]*|visnj[a-z]*|borov[a-z]*|vanil[a-z]*|coko[a-z]*|kokos[a-z]*|banan[a-z]*|kajsij[a-z]*|ananas[a-z]*)\M|\m(vocni|aromatizovani) (jogurt|yoghurt|yogurt)\M', '\m(sladoled|keks[a-z]*|cokolada|bombon[a-z]*)\M', 2),
 ('KEFIR', '\mkefir\M', '\m(sladoled|keks[a-z]*)\M', 2),
 ('AYRAN', '\m(ayran|ajran)\M', NULL, 2),
 ('YOGURT', '\m(jogurt|yoghurt|yogurt)\M', '\m(voc[a-z]*|ukus[a-z]*|jagod[a-z]*|malin[a-z]*|breskv[a-z]*|visnj[a-z]*|borov[a-z]*|vanil[a-z]*|coko[a-z]*|kokos[a-z]*|banan[a-z]*|kajsij[a-z]*|ananas[a-z]*|kefir|ayran|ajran|sladoled|keks[a-z]*|cokolada|bombon[a-z]*)\M', 10)
) r(code, inc, exc, priority)
JOIN app.product_type t ON t.code = r.code;

INSERT INTO app.shopping_intent(code, name)
SELECT code, name FROM app.product_type WHERE code IN ('FRUIT_YOGURT','KEFIR','AYRAN');
INSERT INTO app.shopping_intent_product_type(shopping_intent_id, product_type_id, substitution_level, match_priority, enabled_by_default)
SELECT i.id,t.id,'EXACT',10,TRUE FROM app.shopping_intent i JOIN app.product_type t USING(code)
WHERE i.code IN ('FRUIT_YOGURT','KEFIR','AYRAN');

INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority)
SELECT i.id,a.alias,5 FROM (VALUES
 ('FRUIT_YOGURT','vocni jogurt'), ('FRUIT_YOGURT','aromatizovani jogurt'),
 ('KEFIR','kefir'), ('AYRAN','ajran'), ('AYRAN','ayran'),
 ('FLAVORED_MILK','coko mleko'), ('FLAVORED_MILK','mleko coko'),
 ('FLAVORED_MILK','cokoladno mleko'), ('FLAVORED_MILK','mleko sa ukusom'),
 ('MILK','obicno mleko'), ('YOGURT','obicni jogurt'), ('YOGURT','obican jogurt')
) a(code,alias) JOIN app.shopping_intent i ON i.code=a.code
ON CONFLICT(normalized_alias) DO UPDATE SET shopping_intent_id=EXCLUDED.shopping_intent_id, priority=5;

UPDATE app.shopping_list_item item SET shopping_intent_id=a.shopping_intent_id
FROM app.shopping_intent_alias a
WHERE item.matching_rule='FLEXIBLE_CATEGORY' AND item.flexible_category_normalized=a.normalized_alias;

-- Keep the shared prediction query but version the changed rule set for audit.
DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v2', 'taxonomy-v3');
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
