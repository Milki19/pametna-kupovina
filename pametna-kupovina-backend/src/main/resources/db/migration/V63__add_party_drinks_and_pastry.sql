-- Categories a celebration list needs that the grocery taxonomy never covered:
-- spirits, mixers, sparkling water, filo pastry and cottage cheese. Sizes come
-- from what the catalogues actually stock, so "sitan sir" cannot land on the
-- 10kg catering bag and gin defaults to the 0.7l bottle.

INSERT INTO app.product_type(code, name, product_category_id)
SELECT v.code, v.name, c.id
FROM (VALUES
    ('GIN',             'Džin',            'SPIRITS'),
    ('TONIC_WATER',     'Tonik',           'BEVERAGES'),
    ('SPARKLING_WATER', 'Kisela voda',     'WATER'),
    ('FILO_PASTRY',     'Kore za pitu',    'PASTA'),
    ('COTTAGE_CHEESE',  'Sitan sir',       'DAIRY_EGGS')
) v(code, name, category)
JOIN app.product_category c ON c.code = v.category;

INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT t.id, r.inc, r.exc, 5, 0.9950
FROM (VALUES
 -- A ready-mixed "gin tonic" is neither a bottle of gin nor a mixer.
 ('GIN', '\m(gin|dzin)\M',
  '\m(tonic|tonik|koktel|cocktail|bombon[a-z]*|sladoled|cips|krema|gel|sampon)\M'),
 ('TONIC_WATER', '\mtoni[ck]\M',
  '\m(gin|dzin|koktel|cocktail|sirup|bombon[a-z]*)\M'),
 ('SPARKLING_WATER',
  '\m(kisel[a-z]* voda|gazira[a-z]* voda|mineraln[a-z]* voda)\M|\mvoda\M.*\m(gazirana|kisela|mineralna)\M',
  '\m(negazirana|izvorska|destilovan[a-z]*|toaletn[a-z]*|kolonjsk[a-z]*|sirup|aroma)\M'),
 ('FILO_PASTRY', '\mkore\M',
  '\m(tortu|torte|sladoled|cokolad[a-z]*|kremom)\M'),
 ('COTTAGE_CHEESE', '\m(sitan sir|sir sitan)\M',
  '\m(namaz|krem|topljen[a-z]*)\M')
) r(code, inc, exc)
JOIN app.product_type t ON t.code = r.code;

INSERT INTO app.shopping_intent(code, name)
SELECT code, name FROM app.product_type
WHERE code IN ('GIN','TONIC_WATER','SPARKLING_WATER','FILO_PASTRY','COTTAGE_CHEESE');

INSERT INTO app.shopping_intent_product_type(shopping_intent_id, product_type_id, substitution_level, match_priority, enabled_by_default)
SELECT i.id, t.id, 'EXACT', 10, TRUE
FROM app.shopping_intent i JOIN app.product_type t USING(code)
WHERE i.code IN ('GIN','TONIC_WATER','SPARKLING_WATER','FILO_PASTRY','COTTAGE_CHEESE');

-- Narrowing a word must not shrink the broader one: plain "voda" still means
-- any water and "sir" still includes the cottage kind, just ranked after the
-- more typical choice. Only "kisela voda" and "sitan sir" insist on it.
INSERT INTO app.shopping_intent_product_type(shopping_intent_id, product_type_id, substitution_level, match_priority, enabled_by_default)
SELECT i.id, t.id, 'RELATED', 20, TRUE
FROM (VALUES ('WATER','SPARKLING_WATER'), ('CHEESE','COTTAGE_CHEESE')) v(intent_code, type_code)
JOIN app.shopping_intent i ON i.code = v.intent_code
JOIN app.product_type t ON t.code = v.type_code;

INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority)
SELECT i.id, a.alias, 5 FROM (VALUES
 ('GIN','gin'), ('GIN','dzin'),
 ('TONIC_WATER','tonik'), ('TONIC_WATER','tonic'), ('TONIC_WATER','schweppes tonik'),
 ('SPARKLING_WATER','kisela voda'), ('SPARKLING_WATER','gazirana voda'),
 ('SPARKLING_WATER','mineralna voda'), ('SPARKLING_WATER','kisela'),
 ('FILO_PASTRY','kore'), ('FILO_PASTRY','kore za pitu'), ('FILO_PASTRY','jufke'),
 ('COTTAGE_CHEESE','sitan sir')
) a(code, alias) JOIN app.shopping_intent i ON i.code = a.code
ON CONFLICT(normalized_alias) DO UPDATE SET shopping_intent_id = EXCLUDED.shopping_intent_id, priority = 5;

-- "Sok od pomorandže" is the juice category narrowed by flavour, not a new
-- category: the alias pattern is already honoured by the offer query.
INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority, required_name_pattern)
SELECT i.id, a.alias, 5, a.pattern FROM (VALUES
 ('sok od pomorandze', '\mpomorandz'),
 ('sok pomorandza',    '\mpomorandz'),
 ('sok od jabuke',     '\mjabuk'),
 ('sok od breskve',    '\mbreskv')
) a(alias, pattern)
JOIN app.shopping_intent i ON i.code = 'JUICE'
ON CONFLICT(normalized_alias) DO UPDATE SET
    shopping_intent_id = EXCLUDED.shopping_intent_id,
    priority = 5,
    required_name_pattern = EXCLUDED.required_name_pattern;

UPDATE app.shopping_intent SET
    default_min_package_quantity = v.min_quantity,
    default_max_package_quantity = v.max_quantity,
    default_base_unit = v.base_unit,
    updated_at = NOW()
FROM (VALUES
    ('GIN',             500,   1000,  'ml'),
    ('SPARKLING_WATER', 1000,  2500,  'ml'),
    ('FILO_PASTRY',     400,   800,   'g'),
    -- Keep the catering 10kg bag of cottage cheese out of a household basket.
    ('COTTAGE_CHEESE',  400,   1000,  'g')
) AS v(code, min_quantity, max_quantity, base_unit)
WHERE app.shopping_intent.code = v.code;

UPDATE app.shopping_list_item item SET shopping_intent_id = a.shopping_intent_id
FROM app.shopping_intent_alias a
WHERE item.matching_rule = 'FLEXIBLE_CATEGORY' AND item.flexible_category_normalized = a.normalized_alias;

-- Keep the shared prediction query but version the changed rule set for audit.
DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v8', 'taxonomy-v9');
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
