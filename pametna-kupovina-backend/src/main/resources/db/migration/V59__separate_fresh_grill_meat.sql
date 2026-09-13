-- A grill shopping list asks for fresh meat, but the catalogues are dominated
-- by the cured version of the same word: "vrat" is mostly dry sliced neck,
-- "pileći file" is mostly smoked fillet, and "krilca" even matches crisps.
-- Picking on price alone inside one broad MEAT type therefore buys the wrong
-- thing. These types separate fresh grill cuts the way V47 separated plain
-- yogurt from flavoured; the cured products stay in the broad MEAT type.

INSERT INTO app.product_type(code, name, product_category_id)
SELECT v.code, v.name, c.id
FROM (VALUES
    ('CHICKEN_FILLET',   'Sveži pileći file'),
    ('CHICKEN_DRUMSTICK','Svež pileći batak'),
    ('CHICKEN_WINGS',    'Sveža pileća krilca'),
    ('PORK_NECK_FRESH',  'Svež svinjski vrat'),
    ('CEVAPI',           'Ćevapi'),
    ('PLJESKAVICA',      'Pljeskavice'),
    ('GRILL_SAUSAGE',    'Kobasice za roštilj')
) v(code, name)
JOIN app.product_category c ON c.code = 'MEAT';

INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT t.id, r.inc, r.exc, 5, 0.9950
FROM (VALUES
 ('CHICKEN_FILLET',
  '\m(pilec[a-z]*|pilet[a-z]*|pile)\M.*\m(file|filet[a-z]*)\M|\m(file|filet[a-z]*)\M.*\m(pilec[a-z]*|pilet[a-z]*)\M|\mbelo meso\M',
  '\m(dimljen[a-z]*|suv[a-z]*|narez[a-z]*|slajs|salam[a-z]*|pasteta|konzerv[a-z]*|u crevu|pizza|cips|pringles|zacin|supa|kocka|hrana|sos|paniran[a-z]*|pohovan[a-z]*)\M'),
 ('CHICKEN_DRUMSTICK',
  '\m(batak|bataci|karabatak)\M',
  '\m(curec[a-z]*|dimljen[a-z]*|suv[a-z]*|narez[a-z]*|slajs|konzerv[a-z]*|zacin|hrana|cips|pringles|paniran[a-z]*|pohovan[a-z]*)\M'),
 ('CHICKEN_WINGS',
  '\mkrilc[a-z]*\M',
  '\m(cips|pringles|grick[a-z]*|zacin|sos|supa|kocka|hrana|konzerv[a-z]*|dimljen[a-z]*|suv[a-z]*|paniran[a-z]*|pohovan[a-z]*)\M'),
 ('PORK_NECK_FRESH',
  '\m(svinjski vrat|vrat svinjski|vratina)\M|\mvrat\M',
  '\m(dimljen[a-z]*|suv[a-z]*|narez[a-z]*|narezak|slajs|budola|kraski|salam[a-z]*|pasteta|konzerv[a-z]*|u mrezi|mreza|pronto|lokavski|kosmajsk[a-z]*)\M'),
 ('CEVAPI',
  '\mcevap[a-z]*\M',
  '\m(zacin|cips|pringles|supa|kocka|hrana|sos|pasteta|konzerv[a-z]*)\M'),
 ('PLJESKAVICA',
  '\mpljeskav[a-z]*\M',
  '\m(zacin|povrc[a-z]*|riblj[a-z]*|posn[a-z]*|sos|hrana|konzerv[a-z]*|cips)\M'),
 ('GRILL_SAUSAGE',
  '\mkobasic[a-z]*\M',
  '\m(suv[a-z]*|cajn[a-z]*|zimsk[a-z]*|salam[a-z]*|konzerv[a-z]*|pasteta|hrana|zacin|supa|kocka|cips|sos)\M')
) r(code, inc, exc)
JOIN app.product_type t ON t.code = r.code;

INSERT INTO app.shopping_intent(code, name)
SELECT code, name FROM app.product_type
WHERE code IN ('CHICKEN_FILLET','CHICKEN_DRUMSTICK','CHICKEN_WINGS',
               'PORK_NECK_FRESH','CEVAPI','PLJESKAVICA','GRILL_SAUSAGE');

INSERT INTO app.shopping_intent_product_type(shopping_intent_id, product_type_id, substitution_level, match_priority, enabled_by_default)
SELECT i.id, t.id, 'EXACT', 10, TRUE
FROM app.shopping_intent i JOIN app.product_type t USING(code)
WHERE i.code IN ('CHICKEN_FILLET','CHICKEN_DRUMSTICK','CHICKEN_WINGS',
                 'PORK_NECK_FRESH','CEVAPI','PLJESKAVICA','GRILL_SAUSAGE');

INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority)
SELECT i.id, a.alias, 5 FROM (VALUES
 ('CHICKEN_FILLET','belo meso'), ('CHICKEN_FILLET','pilece belo meso'),
 ('CHICKEN_FILLET','pileci file'), ('CHICKEN_FILLET','file'),
 ('CHICKEN_DRUMSTICK','batak'), ('CHICKEN_DRUMSTICK','bataci'),
 ('CHICKEN_DRUMSTICK','karabatak'), ('CHICKEN_DRUMSTICK','pileci batak'),
 ('CHICKEN_WINGS','krilca'), ('CHICKEN_WINGS','pileca krilca'),
 ('CHICKEN_WINGS','krilca za rostilj'),
 ('PORK_NECK_FRESH','vrat'), ('PORK_NECK_FRESH','svinjski vrat'),
 ('PORK_NECK_FRESH','vratina'),
 ('CEVAPI','cevapi'), ('CEVAPI','cevapcici'), ('CEVAPI','cevap'),
 ('PLJESKAVICA','pljeskavica'), ('PLJESKAVICA','pljeskavice'),
 ('GRILL_SAUSAGE','kobasice'), ('GRILL_SAUSAGE','kobasica'),
 ('GRILL_SAUSAGE','kobasice za rostilj')
) a(code, alias) JOIN app.shopping_intent i ON i.code = a.code
ON CONFLICT(normalized_alias) DO UPDATE SET shopping_intent_id = EXCLUDED.shopping_intent_id, priority = 5;

UPDATE app.shopping_list_item item SET shopping_intent_id = a.shopping_intent_id
FROM app.shopping_intent_alias a
WHERE item.matching_rule = 'FLEXIBLE_CATEGORY' AND item.flexible_category_normalized = a.normalized_alias;

-- Keep the shared prediction query but version the changed rule set for audit.
DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v4', 'taxonomy-v5');
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
