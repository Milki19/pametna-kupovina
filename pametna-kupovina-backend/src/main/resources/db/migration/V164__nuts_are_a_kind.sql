-- Orasi su vrsta (proba S18 na kopiji baze, 10.11.2026): „orasi“ nije imao
-- reč na spisku ni vrstu, pa se tražio samo po reči u nazivu. Kad je V160
-- izbacio mešavinu sa suvim voćem, najjeftiniji naziv sa „ORASI“ bila je
-- „BAKLAVA BADEM ORASI 42G“. U katalogu je 795 proizvoda sa orahom u nazivu
-- bez vrste (štrudle, torte, Plazma, Marlenka), a „ORASI“ je i robna marka
-- napitaka i pavlake.
--
-- Vrsta su orašasti plodovi: orah, badem, lešnik, indijski orah, pistaći,
-- makadamija, pekan. Kolač, čokolada, hleb, napitak, sapun ili vino sa
-- orasima nisu orasi. Mešavina „orašasti plodovi mix“ jeste vrsta, ali
-- „orasi“ je ne bira (V160).
-- Kikiriki ostaje kako je bio: „kikiriki puter“ i flips nisu ovde.
-- Kokosov orah je voće (FRUIT) i ostaje voće.

INSERT INTO app.product_type (code, name, product_category_id, domain)
VALUES ('NUTS', 'Orašasti plodovi', NULL, 'FOOD')
ON CONFLICT (code) DO NOTHING;

-- Naziv je orah kad njime počinje („ORAH JEZGRO 300G“, „BIO LEŠNIK SIROVI“,
-- „Indijski orah slani Emma“) ili kad posle ploda kaže kakav je („BADEM
-- PEČENI“, „LEŠNIK JEZGRA PRŽENI“). Prva proba je pustila svaki naziv sa
-- rečju ploda, pa su vrsta postali ratluk, karamele, Milka, mortadela sa
-- pistaćima i muskatni orah; to se ovde ne ponavlja. Zabranjene reči
-- ostaju za nazive koji počinju plodom, a nisu plod („ORASI PAVLAKA“,
-- „BADEM NAPITAK“, „LEŠNIK KREM“).
--
-- Pravilo dolazi posle svih postojećih (priority 95): proizvod koji već
-- ima užu vrstu (napitak, sapun, čokolada) zadržava je.
INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id,
       '^(bio |pl |organsk[a-z]* )?(orah|oraha|orasi|orasast[a-z]*|badem|bademi|lesnik|lesnici|pistac[a-z]*'
           || '|indijsk[a-z]* orah[a-z]*|ind orah|brazilsk[a-z]* orah[a-z]*|makadamij[a-z]*|pekan)\M'
           || '|\m(orah|oraha|orasi|badem|bademi|lesnik|lesnici|pistac[a-z]*|makadamij[a-z]*|pekan)'
           || ' (jezgr[a-z]*|sirov[a-z]*|pecen[a-z]*|przen[a-z]*|oljust[a-z]*|ociscen[a-z]*|blansir[a-z]*'
           || '|listic[a-z]*|mleven[a-z]*|rinfuz[a-z]*|slan[a-z]*|neslan[a-z]*|celi|ceo|polovin[a-z]*|brazilsk[a-z]*|suv[a-z]*)\M',
       '\m(kokos[a-z]*|baklav[a-z]*|strudl[a-z]*|tort[a-z]*|kolac[a-z]*|keks[a-z]*|kreker[a-z]*|vafl[a-z]*'
           || '|napolitank[a-z]*|cokol[a-z]*|coko|cok|bombon[a-z]*|pralin[a-z]*|krem[a-z]*|namaz[a-z]*|puter|pasta'
           || '|halv[a-z]*|nugat|marcipan[a-z]*|sladoled[a-z]*|desert[a-z]*|puding[a-z]*|jogurt[a-z]*|mlek[a-z]*'
           || '|milk|napit[a-z]*|drink|pavlak[a-z]*|sir|vino|liker[a-z]*|rakij[a-z]*|ulje|ulja|brasn[a-z]*'
           || '|hleb[a-z]*|pecivo|peciva|kifl[a-z]*|kroasan[a-z]*|krofn[a-z]*|pita|pite|musli|granol[a-z]*'
           || '|pahuljic[a-z]*|plocic[a-z]*|bar|med|urm[a-z]*|kafa|caj|sapun[a-z]*|sampon[a-z]*|gel|losion[a-z]*'
           || '|ratluk[a-z]*|slatko|karamel[a-z]*|stapic[a-z]*|mortadel[a-z]*|salam[a-z]*|muskat[a-z]*'
           || '|protein[a-z]*|sejk|shake|obrok|knjig[a-z]*|cappuc[a-z]*|kapucin[a-z]*|flips|cips|sos|pesto'
           || '|sirup[a-z]*|arom[a-z]*|ekstrakt[a-z]*|kore|testo|hrana|hrane)\M',
       95,
       0.9700
FROM app.product_type AS type
WHERE type.code = 'NUTS';

-- Pravila su se promenila: predviđanja nose novu verziju (kao V162).
DO $$
DECLARE
    definition TEXT := pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure);
    current_version INTEGER := SUBSTRING(definition FROM 'taxonomy-v([0-9]+)')::INTEGER;
BEGIN
    EXECUTE REPLACE(
        definition,
        'taxonomy-v' || current_version,
        'taxonomy-v' || (current_version + 1)
    );
END $$;

-- Reči na spisku -----------------------------------------------------------

-- Orasi se prodaju u kesicama od 100 do 500 g.
INSERT INTO app.shopping_intent (
    code,
    name,
    default_min_package_quantity,
    default_max_package_quantity,
    default_base_unit
)
VALUES ('NUTS', 'Orašasti plodovi', 100, 500, 'g')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, 'EXACT', 10, TRUE
FROM app.shopping_intent AS intent
JOIN app.product_type AS type
  ON type.code = 'NUTS'
WHERE intent.code = 'NUTS'
ON CONFLICT (shopping_intent_id, product_type_id) DO NOTHING;

-- „orasi“ je orah (ne badem ni indijski orah), „bademi“ badem, a
-- „orašasti plodovi“ bilo koji. „kokosov orah“ je voće: reč je ovde da ga
-- „orah“ ne bi uzeo, i važi samo ako voće ima nameru.
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern,
    uncommon_name_pattern
)
SELECT intent.id, word.alias, 5, word.required, word.uncommon
FROM (
    VALUES
        ('orasi', '^(?!.*\m(indijsk[a-z]*|brazilsk[a-z]*|pekan|makadamij[a-z]*)\M).*\m(orah|oraha|orahe|orasi|walnut[a-z]*)\M',
            '\m(mesavin[a-z]*|mix|miks|slan[a-z]*|pecen[a-z]*|mleven[a-z]*)\M'),
        ('orah', '^(?!.*\m(indijsk[a-z]*|brazilsk[a-z]*|pekan|makadamij[a-z]*)\M).*\m(orah|oraha|orahe|orasi|walnut[a-z]*)\M',
            '\m(mesavin[a-z]*|mix|miks|slan[a-z]*|pecen[a-z]*|mleven[a-z]*)\M'),
        ('oraha', '^(?!.*\m(indijsk[a-z]*|brazilsk[a-z]*|pekan|makadamij[a-z]*)\M).*\m(orah|oraha|orahe|orasi|walnut[a-z]*)\M',
            '\m(mesavin[a-z]*|mix|miks|slan[a-z]*|pecen[a-z]*|mleven[a-z]*)\M'),
        ('orahe', '^(?!.*\m(indijsk[a-z]*|brazilsk[a-z]*|pekan|makadamij[a-z]*)\M).*\m(orah|oraha|orahe|orasi|walnut[a-z]*)\M',
            '\m(mesavin[a-z]*|mix|miks|slan[a-z]*|pecen[a-z]*|mleven[a-z]*)\M'),
        ('jezgro oraha', '^(?!.*\m(indijsk[a-z]*|brazilsk[a-z]*|pekan|makadamij[a-z]*)\M).*\m(orah|oraha|orahe|orasi|walnut[a-z]*)\M',
            '\m(mesavin[a-z]*|mix|miks|slan[a-z]*|pecen[a-z]*)\M'),
        ('mleveni orasi', '^(?!.*\m(indijsk[a-z]*|brazilsk[a-z]*|pekan|makadamij[a-z]*)\M).*\m(orah|oraha|orahe|orasi|walnut[a-z]*)\M',
            '\m(mesavin[a-z]*|mix|miks|slan[a-z]*)\M'),
        ('indijski orah', '\m(indijsk[a-z]*|cashew[a-z]*)\M', NULL),
        ('indijski orasi', '\m(indijsk[a-z]*|cashew[a-z]*)\M', NULL),
        ('bademi', '\m(badem|bademi|badema)\M', '\m(mesavin[a-z]*|mix|miks|listic[a-z]*|mleven[a-z]*|brasn[a-z]*)\M'),
        ('lesnici', '\m(lesnik|lesnici|lesnika)\M', '\m(mesavin[a-z]*|mix|miks|mleven[a-z]*)\M'),
        ('pistaci', '\m(pistac[a-z]*|pistachio[a-z]*)\M', '\m(mesavin[a-z]*|mix|miks)\M'),
        ('orasasti plodovi', NULL, NULL)
) AS word (alias, required, uncommon)
JOIN app.shopping_intent AS intent
  ON intent.code = 'NUTS'
ON CONFLICT (normalized_alias) DO NOTHING;

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT fruit.shopping_intent_id, word.alias, 5, '\mkokos[a-z]*\M'
FROM (VALUES ('kokosov orah')) AS word (alias)
CROSS JOIN LATERAL (
    SELECT allowed.shopping_intent_id
    FROM app.shopping_intent_product_type AS allowed
    JOIN app.product_type AS type
      ON type.id = allowed.product_type_id
     AND type.code = 'FRUIT'
    JOIN app.shopping_intent AS intent
      ON intent.id = allowed.shopping_intent_id
     AND intent.active = TRUE
    WHERE allowed.enabled_by_default = TRUE
    ORDER BY allowed.match_priority, intent.id
    LIMIT 1
) AS fruit
ON CONFLICT (normalized_alias) DO NOTHING;

-- Vrste po novom pravilu (kao V162) ------------------------------------------

CREATE TEMPORARY TABLE named_kind ON COMMIT DROP AS
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
 AND type.code = 'NUTS'
WHERE prediction.confidence >= 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  );

DELETE FROM app.retailer_product_type AS assignment
USING named_kind AS prediction
WHERE assignment.retailer_product_id = prediction.retailer_product_id
  AND assignment.product_type_id <> prediction.product_type_id
  AND assignment.reviewed = FALSE;

INSERT INTO app.retailer_product_type (
    retailer_product_id,
    product_type_id,
    confidence,
    assignment_source,
    evidence,
    algorithm_version,
    retailer_id
)
SELECT kind.retailer_product_id,
       kind.product_type_id,
       kind.confidence,
       kind.prediction_source,
       kind.evidence,
       kind.algorithm_version,
       product.retailer_id
FROM named_kind AS kind
JOIN app.retailer_product AS product
  ON product.id = kind.retailer_product_id
ON CONFLICT (retailer_product_id) DO NOTHING;

UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    JOIN named_kind AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id IS NOT NULL
) AS affected
LEFT JOIN LATERAL (
    SELECT assignment.product_type_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = affected.id
    GROUP BY assignment.product_type_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_type_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = affected.id
  AND family.product_type_id IS DISTINCT FROM choice.product_type_id;
