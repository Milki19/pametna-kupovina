-- The owner's slava list of 14.09. got olive oil for "ulje", "Proteinski
-- cevap" for ćevapi and boiled chicken sausage for the grill, and could not
-- tell light Zaječarsko from dark. Each rule below says what a shopper means
-- by the word and was checked against the chains' own names of 14.09.

CREATE TEMP TABLE v74_words(name TEXT PRIMARY KEY, pattern TEXT NOT NULL)
    ON COMMIT DROP;

INSERT INTO v74_words VALUES
 -- "ulje" on a label that is not cooking oil: cosmetics, sun and baby care,
 -- cleaning, fish canned in oil.
 ('not_cooking_oil',
  '\m(spf[0-9]*|suncanj[a-z]*|sun|tamnjenj[a-z]*|bronz[a-z]*|carroten|kos[aeu]'
  || '|telo|tela|lic[ae]|beb[ae]|baby|dec|dec[aeu]|decij[a-z]*|masaz[a-z]*'
  || '|sapun[a-z]*|sampon[a-z]*|gel|tus[a-z]*|kupk[a-z]*|krem[a-z]*|losion'
  || '|brad[aeu]|maramic[a-z]*|mask[a-z]*|pen[ae]|neg[aeu]|eteric[a-z]*'
  || '|motorn[a-z]*|tuna|tunj[a-z]*|sardin[a-z]*|skus[a-z]*|haring[a-z]*'
  || '|papalin[a-z]*|fleke|det|deterdz[a-z]*|kantarion[a-z]*|ricinus[a-z]*'
  || '|argan|cickov[a-z]*|parfem[a-z]*)\M'),
 ('sunflower', '\m(suncokret[a-z]*|sunc)\M'),
 ('olive', '\m(masl|maslin[a-z]*|olive|oliva|olio|komin[a-z]*)\M'),
 ('pet_food', '\m(pse|psa|pas|macke|woof)\M');

INSERT INTO app.product_type(code, name, product_category_id)
SELECT v.code, v.name, category.id
FROM (VALUES
    ('SUNFLOWER_OIL',           'Suncokretovo ulje',        'OIL'),
    ('OLIVE_OIL',               'Maslinovo ulje',           'OIL'),
    ('DARK_BEER',               'Tamno pivo',               'BEER'),
    ('WHEAT_BEER',              'Pšenično pivo',            'BEER'),
    ('FLAVORED_BEER',           'Pivo sa ukusom',           'BEER'),
    ('CHICKEN_WINGS_MARINATED', 'Marinirana pileća krilca', 'MEAT')
) AS v(code, name, category)
JOIN app.product_category AS category ON category.code = v.category;

-- 1. Oil. Chains write "Ulje suncokretovo Maxi 1l" and "ULJE SUNCOKRET 1L
-- DIJAMANT", which the phrase "suncokretovo ulje" never matched, so Maxi had
-- only olive oil to offer. "Ulje" on a list is sunflower oil; olive oil costs
-- five times as much and is asked for by name. A blend of both is neither.
INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT type.id, rule.include_pattern, rule.exclude_pattern, 4, 0.9950
FROM (VALUES
 ('SUNFLOWER_OIL',
  '\mulje\M.*\m(suncokret[a-z]*|sunc)\M|\m(suncokret[a-z]*|sunc)\M.*\mulje\M',
  (SELECT pattern FROM v74_words WHERE name = 'not_cooking_oil')
      || '|' || (SELECT pattern FROM v74_words WHERE name = 'olive')),
 ('OLIVE_OIL',
  '\mulje\M.*\m(masl|maslin[a-z]*|olive|oliva|olio|komin[a-z]*)\M|\m(masl|maslin[a-z]*|olive)\M.*\mulje\M',
  (SELECT pattern FROM v74_words WHERE name = 'not_cooking_oil')
      || '|' || (SELECT pattern FROM v74_words WHERE name = 'sunflower')
      -- Olives in oil, not oil.
      || '|^masline\M')
) AS rule(code, include_pattern, exclude_pattern)
JOIN app.product_type AS type ON type.code = rule.code;

-- Other cooking oil keeps the general type: named by use ("jestivo", "za
-- prženje") or by a brand that only makes cooking oil.
UPDATE app.product_type_rule
SET include_pattern =
        '\mulje\M.*\m(jestiv[a-z]*|repic[a-z]*|kukuruz[a-z]*|przenje|dijamant|vital|iskon|omegol|mediteran|salatno|biljno)\M'
        || '|\m(jestiv[a-z]*|dijamant|vital|iskon|omegol|sunce|biser|salatno|biljno)\M.*\mulje\M'
        || '|(^| )(jestivo ulje|suncokretovo ulje|maslinovo ulje|ulje repice)( |$)',
    exclude_pattern = (SELECT pattern FROM v74_words WHERE name = 'not_cooking_oil')
        || '|(^| )(motorno ulje|ulje za telo|ulje za kosu)( |$)',
    updated_at = NOW()
WHERE product_type_id = (SELECT id FROM app.product_type WHERE code = 'OIL')
  AND retailer_id IS NULL
  AND active;

-- 2. Beer. Plain "pivo" is light beer, as the owner writes it; dark, wheat
-- and flavoured beer are other drinks and have their own words.
INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT type.id, rule.include_pattern, beer.exclude_pattern, rule.priority, 0.9950
FROM (VALUES
 ('DARK_BEER', 3,
  '\m(pivo|beer|bier)\M.*\m(crn[aio]|tamn[aio]|dark|stout|porter|black)\M|\m(crn[aio]|tamn[aio]|dark)\M.*\m(pivo|beer|bier)\M|\m(stout|porter)\M'),
 ('WHEAT_BEER', 4,
  '\m(pivo|beer|bier)\M.*\m(psenic[a-z]*|weiss[a-z]*|weizen[a-z]*|hefe[a-z]*|wheat|witbier)\M|\m(psenic[a-z]*|weiss[a-z]*|weizen[a-z]*|hefe[a-z]*)\M.*\m(pivo|beer|bier)\M'),
 ('FLAVORED_BEER', 4,
  '\m(pivo|beer|bier)\M.*\m(radler|limun[a-z]*|lemon|grejp[a-z]*|grapefruit|ananas|mojito|nana|twist|fresh|visnj[a-z]*|cherry|jabuk[a-z]*|apple|agave|ukus[a-z]*)\M|\mradler\M')
) AS rule(code, priority, include_pattern)
JOIN app.product_type AS type ON type.code = rule.code
CROSS JOIN LATERAL (
    SELECT existing.exclude_pattern
    FROM app.product_type_rule AS existing
    WHERE existing.product_type_id = (SELECT id FROM app.product_type WHERE code = 'BEER')
      AND existing.retailer_id IS NULL
      AND existing.active
    ORDER BY existing.priority, existing.id
    LIMIT 1
) AS beer;

-- 3. Wine. A spritzer is a mixed drink, and two of them are not a litre of
-- wine.
UPDATE app.product_type_rule
SET exclude_pattern = exclude_pattern || '|\m(spricer|sprizer|spritzer)\M',
    updated_at = NOW()
WHERE product_type_id = (SELECT id FROM app.product_type WHERE code = 'WINE')
  AND retailer_id IS NULL
  AND active;

-- 4. Grill meat. "Proteinski cevap" is a diet product; beef neck is not the
-- pork neck of a grill; a thigh fillet is not "belo meso".
UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern || extra.pattern,
    updated_at = NOW()
FROM (VALUES
    ('CEVAPI',          '|\m(protein[a-z]*|mix|vegan[a-z]*|posn[a-z]*)\M'),
    ('PLJESKAVICA',     '|\m(protein[a-z]*|vegan[a-z]*)\M'),
    ('PORK_NECK_FRESH', '|\m(junec[a-z]*|angus|telec[a-z]*|dimnjen[a-z]*)\M'),
    ('CHICKEN_FILLET',  '|\m(batk[a-z]*|karabat[a-z]*)\M')
) AS extra(code, pattern)
WHERE rule.product_type_id = (SELECT id FROM app.product_type WHERE code = extra.code)
  AND rule.retailer_id IS NULL
  AND rule.active;

-- A grill sausage says so: roštiljska, grill, sveža, domaća, jagnjeća. The
-- cheapest "kobasice" per kilogram were posebna, pileća barena, alpska and a
-- dog food, all of which the old rule let through.
UPDATE app.product_type_rule
SET include_pattern =
        '\mkobasic[a-z]*\M.*\m(rostilj[a-z]*|ros|gril|grill|svez[a-z]*|domac[a-z]*|pecenj[a-z]*|jagnjec[a-z]*|junec[a-z]*|leskovac[a-z]*|divack[a-z]*|thuringen|bratwurst|kasapsk[a-z]*)\M'
        || '|\m(rostilj[a-z]*|ros|gril|grill|svez[a-z]*|domac[a-z]*|jagnjec[a-z]*|junec[a-z]*|leskovac[a-z]*|divack[a-z]*)\M.*\mkobasic[a-z]*\M',
    exclude_pattern = exclude_pattern
        || '|\m(ljetn[a-z]*|letnj[a-z]*|vegan[a-z]*|smesa|kranjsk[a-z]*|baren[a-z]*)\M'
        || '|' || (SELECT pattern FROM v74_words WHERE name = 'pet_food'),
    updated_at = NOW()
WHERE product_type_id = (SELECT id FROM app.product_type WHERE code = 'GRILL_SAUSAGE')
  AND retailer_id IS NULL
  AND active;

-- Marinated wings are still wings, but only where a shop has no plain ones.
INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT type.id,
       '\mkrilc[a-z]*\M.*\m(marin[a-z]*|pikant[a-z]*|bbq|sous|ljut[a-z]*|blag[a-z]*|zacinjen[a-z]*)\M|\m(marin[a-z]*|pikant[a-z]*|bbq|ljut[a-z]*|blag[a-z]*)\M.*\mkrilc[a-z]*\M',
       wings.exclude_pattern,
       4,
       0.9950
FROM app.product_type AS type
CROSS JOIN LATERAL (
    SELECT existing.exclude_pattern
    FROM app.product_type_rule AS existing
    WHERE existing.product_type_id = (SELECT id FROM app.product_type WHERE code = 'CHICKEN_WINGS')
      AND existing.retailer_id IS NULL
      AND existing.active
    ORDER BY existing.priority, existing.id
    LIMIT 1
) AS wings
WHERE type.code = 'CHICKEN_WINGS_MARINATED';

-- Words for the new kinds.
INSERT INTO app.shopping_intent(code, name)
SELECT code, name FROM app.product_type
WHERE code IN ('SUNFLOWER_OIL', 'OLIVE_OIL', 'DARK_BEER', 'WHEAT_BEER', 'FLAVORED_BEER');

INSERT INTO app.shopping_intent_product_type(shopping_intent_id, product_type_id, substitution_level, match_priority, enabled_by_default)
SELECT intent.id, type.id, 'EXACT', 10, TRUE
FROM app.shopping_intent AS intent
JOIN app.product_type AS type USING (code)
WHERE intent.code IN ('SUNFLOWER_OIL', 'OLIVE_OIL', 'DARK_BEER', 'WHEAT_BEER', 'FLAVORED_BEER');

INSERT INTO app.shopping_intent_product_type(shopping_intent_id, product_type_id, substitution_level, match_priority, enabled_by_default)
SELECT intent.id, type.id, v.level, v.priority, TRUE
FROM (VALUES
    ('OIL',           'SUNFLOWER_OIL',           'EXACT',   10),
    ('CHICKEN_WINGS', 'CHICKEN_WINGS_MARINATED', 'RELATED', 20)
) AS v(intent_code, type_code, level, priority)
JOIN app.shopping_intent AS intent ON intent.code = v.intent_code
JOIN app.product_type AS type ON type.code = v.type_code;

UPDATE app.shopping_intent_product_type
SET match_priority = 20,
    substitution_level = 'RELATED'
WHERE shopping_intent_id = (SELECT id FROM app.shopping_intent WHERE code = 'OIL')
  AND product_type_id = (SELECT id FROM app.product_type WHERE code = 'OIL');

INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority)
SELECT intent.id, a.alias, 5
FROM (VALUES
    ('SUNFLOWER_OIL', 'suncokretovo ulje'), ('SUNFLOWER_OIL', 'ulje suncokretovo'),
    ('OLIVE_OIL', 'maslinovo ulje'), ('OLIVE_OIL', 'ulje maslinovo'),
    ('DARK_BEER', 'crno pivo'), ('DARK_BEER', 'tamno pivo'),
    ('DARK_BEER', 'pivo crno'), ('DARK_BEER', 'pivo tamno'),
    ('WHEAT_BEER', 'psenicno pivo'), ('WHEAT_BEER', 'pivo psenicno'),
    ('FLAVORED_BEER', 'radler')
) AS a(code, alias)
JOIN app.shopping_intent AS intent ON intent.code = a.code
ON CONFLICT (normalized_alias) DO UPDATE SET
    shopping_intent_id = EXCLUDED.shopping_intent_id,
    priority = 5;

-- The colour of a wine is a word on its label. "Rubin roze 1l" is then rosé
-- wine of the brand Rubin, one litre.
INSERT INTO app.shopping_intent_alias(shopping_intent_id, normalized_alias, priority, required_name_pattern)
SELECT intent.id, a.alias, 5, a.pattern
FROM (VALUES
    ('roze',           '\m(roze|rose|ruzicast[a-z]*)\M'),
    ('roze vino',      '\m(roze|rose|ruzicast[a-z]*)\M'),
    ('vino roze',      '\m(roze|rose|ruzicast[a-z]*)\M'),
    ('rose vino',      '\m(roze|rose|ruzicast[a-z]*)\M'),
    ('vino rose',      '\m(roze|rose|ruzicast[a-z]*)\M'),
    ('ruzicasto vino', '\m(roze|rose|ruzicast[a-z]*)\M'),
    ('belo vino',      '\m(bel[aeo]|bijel[aeo]|white|blanc)\M'),
    ('vino belo',      '\m(bel[aeo]|bijel[aeo]|white|blanc)\M'),
    ('bijelo vino',    '\m(bel[aeo]|bijel[aeo]|white|blanc)\M'),
    ('crno vino',      '\m(crn[aeo]|crven[aeo]|red|rouge|rosso)\M'),
    ('vino crno',      '\m(crn[aeo]|crven[aeo]|red|rouge|rosso)\M'),
    ('crveno vino',    '\m(crn[aeo]|crven[aeo]|red|rouge|rosso)\M')
) AS a(alias, pattern)
JOIN app.shopping_intent AS intent ON intent.code = 'WINE'
ON CONFLICT (normalized_alias) DO UPDATE SET
    shopping_intent_id = EXCLUDED.shopping_intent_id,
    priority = 5,
    required_name_pattern = EXCLUDED.required_name_pattern;

-- "Schweppes tonik" names a brand of tonic, which the list now reads as a
-- brand instead of any tonic.
DELETE FROM app.shopping_intent_alias WHERE normalized_alias = 'schweppes tonik';

-- Something sold by volume: a bare "0.5" beside it is litres.
UPDATE app.shopping_intent
SET default_min_package_quantity = COALESCE(v.min_quantity, default_min_package_quantity),
    default_max_package_quantity = COALESCE(v.max_quantity, default_max_package_quantity),
    default_base_unit = v.base_unit,
    updated_at = NOW()
FROM (VALUES
    ('SUNFLOWER_OIL', 900,  1100, 'ml'),
    ('OLIVE_OIL',     500,  1000, 'ml'),
    ('DARK_BEER',     330,  550,  'ml'),
    ('WHEAT_BEER',    330,  550,  'ml'),
    ('FLAVORED_BEER', 330,  550,  'ml'),
    ('WINE',          700,  1000, 'ml'),
    ('TONIC_WATER',   NULL, NULL, 'ml'),
    ('WATER',         NULL, NULL, 'ml')
) AS v(code, min_quantity, max_quantity, base_unit)
WHERE app.shopping_intent.code = v.code;

-- Assign types again with the new rules, as the catalogue refresh does.
DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v10', 'taxonomy-v11');
END $$;

-- The prediction view reads every name against every rule; once is enough.
CREATE TEMP TABLE v74_prediction ON COMMIT DROP AS
SELECT retailer_product_id, product_type_id, confidence, prediction_source, evidence, algorithm_version
FROM app.product_type_prediction;

DELETE FROM app.product_type_candidate WHERE status = 'PENDING';

INSERT INTO app.product_type_candidate(retailer_product_id, product_type_id, confidence, prediction_source, evidence, algorithm_version)
SELECT p.retailer_product_id, p.product_type_id, p.confidence, p.prediction_source, p.evidence, p.algorithm_version
FROM v74_prediction AS p
WHERE p.confidence >= 0.75
  AND p.confidence < 0.95
  AND NOT EXISTS (
      SELECT 1 FROM app.retailer_product_type AS t
      WHERE t.retailer_product_id = p.retailer_product_id AND t.reviewed
  )
ON CONFLICT DO NOTHING;

DELETE FROM app.retailer_product_type WHERE NOT reviewed;

INSERT INTO app.retailer_product_type(retailer_product_id, product_type_id, confidence, assignment_source, evidence, algorithm_version)
SELECT retailer_product_id, product_type_id, confidence, prediction_source, evidence, algorithm_version
FROM v74_prediction
WHERE confidence >= 0.95
ON CONFLICT (retailer_product_id) DO NOTHING;

-- A pack takes the type of the single piece it was read against (V73).
INSERT INTO app.retailer_product_type(retailer_product_id, product_type_id, confidence, assignment_source, evidence, algorithm_version)
SELECT pack.id,
       unit_type.product_type_id,
       unit_type.confidence,
       'PACKAGE_UNIT',
       LEFT('Pakovanje od ' || pack.package_count || ' kom: ' || unit.name, 1000),
       unit_type.algorithm_version
FROM app.retailer_product AS pack
JOIN app.retailer_product AS unit ON unit.id = pack.package_unit_product_id
JOIN app.retailer_product_type AS unit_type ON unit_type.retailer_product_id = unit.id
WHERE NOT EXISTS (
    SELECT 1 FROM app.product_type_candidate AS candidate
    WHERE candidate.retailer_product_id = pack.id AND candidate.status = 'PENDING'
)
ON CONFLICT (retailer_product_id) DO NOTHING;

UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM app.product_family AS target
LEFT JOIN LATERAL (
    SELECT assignment.product_type_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = target.id
    GROUP BY assignment.product_type_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_type_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = target.id
  AND family.product_type_id IS DISTINCT FROM choice.product_type_id;

UPDATE app.shopping_list_item AS item
SET shopping_intent_id = alias.shopping_intent_id
FROM app.shopping_intent_alias AS alias
WHERE item.matching_rule = 'FLEXIBLE_CATEGORY'
  AND item.flexible_category_normalized = alias.normalized_alias
  AND item.shopping_intent_id IS DISTINCT FROM alias.shopping_intent_id;
