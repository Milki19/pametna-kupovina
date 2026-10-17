-- Izbor proizvoda, treći krug (ponovni test 17.10.2026.):
--
-- * "maslac" je u Jednoj stanici u Novom Sadu (METRO) postao
--   „DIJAMANT ST.MARG.MASLAC“, stoni margarin. Skraćenica „MARG.“ i „stoni“
--   nisu bili među izuzecima maslaca. „Maslac namaz“ i „namazni maslac“
--   (Lurpak, Biser, Moja kravica) su pravi maslac i ostaju (proba 17.10.).
-- * "piletina" je u METRO-u postala „PIL. JETRA I SRCE“, a svuda drugde
--   „pileća leđa“. Iznutrice nisu piletina (kao što ni "meso" nije, V87), a
--   leđa, krila, vratovi i kosti dolaze tek kad radnja nema drugi komad.
-- * "šampon" je u Jednoj stanici postao dečiji šampon: dečiji i suvi šampon
--   dolaze tek kad radnja nema drugi.
-- * "deterdžent za sudove", "pasta za zube" i "čokolada" nisu bile vrste
--   proizvoda, pa ih korak 1 nije prepoznao. Sad jesu, sa svojim rečima.

-- Maslac -------------------------------------------------------------------

UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern || '|\m(marg|ston[a-z]*)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'BUTTER'
  AND rule.active
  AND rule.retailer_id IS NULL
  AND rule.exclude_pattern IS NOT NULL
  AND POSITION('ston[a-z]*' IN rule.exclude_pattern) = 0;

-- Piletina i meso po vrsti ---------------------------------------------------

-- Iznutrice nisu "piletina" ni "svinjetina": ko ih hoće, napiše ih.
UPDATE app.shopping_intent_alias
SET required_name_pattern =
        '^(?!.*\m(jetr[a-z]*|dzigeric[a-z]*|srce|srca|zelud[a-z]*|zeluc[a-z]*|iznutric[a-z]*'
        || '|bubreg|bubrezi|skembic[a-z]*|mozak|jezik|jezici)\M).*' || required_name_pattern
WHERE normalized_alias IN (
          'piletina', 'piletinu', 'pilece meso',
          'svinjetina', 'svinjetinu', 'svinjsko meso',
          'junetina', 'junetinu', 'junece meso',
          'teletina', 'govedina', 'curetina', 'jagnjetina'
      )
  AND required_name_pattern IS NOT NULL
  AND POSITION('jetr[a-z]*' IN required_name_pattern) = 0;

-- Leđa, krila, vrat i kosti su piletina za supu: dolaze tek kad radnja nema
-- drugi komad. Kod ostalog mesa to su kosti, glava, noge, koža i mast; vrat
-- i kare sa kosti su obično meso.
UPDATE app.shopping_intent_alias
SET uncommon_name_pattern =
        '\m(ledj[a-z]*|krila|krilc[a-z]*|vrat|vratovi|vratov[a-z]*|kost|kosti|koske|kostur[a-z]*'
        || '|karlic[a-z]*|trtic[a-z]*|nog[a-z]*|glav(a|e)|kozic[a-z]*|supu|supa)\M'
WHERE normalized_alias IN ('piletina', 'piletinu', 'pilece meso')
  AND uncommon_name_pattern IS NULL;

UPDATE app.shopping_intent_alias
SET uncommon_name_pattern =
        '(?<!bez )(?<!sa )\m(kost|kosti|koske)\M'
        || '|\m(kostur[a-z]*|nogic[a-z]*|papci|glav(a|e)|rep|kozic[a-z]*|kozur[a-z]*|supu|supa'
        || '|mast|salo|cvarc[a-z]*)\M'
WHERE normalized_alias IN (
          'svinjetina', 'svinjetinu', 'svinjsko meso',
          'junetina', 'junetinu', 'junece meso',
          'teletina', 'govedina', 'curetina', 'jagnjetina'
      )
  AND uncommon_name_pattern IS NULL;

-- Za "maslac" je obično pun maslac; tričetvrt masni i lagani dolaze tek kad
-- radnja nema drugi (proba 17.10.: Lidl „Tričetvrt masni maslac 60% mm“).
UPDATE app.shopping_intent_alias
SET uncommon_name_pattern = '\m(tricetvrt[a-z]*|light|lajt|lagan[a-z]*|polumasn[a-z]*|smanjen[a-z]*)\M'
WHERE normalized_alias IN ('maslac', 'puter')
  AND uncommon_name_pattern IS NULL;

-- Šampon -------------------------------------------------------------------

UPDATE app.shopping_intent_alias
SET uncommon_name_pattern = '\m(dec[a-z]*|djec[a-z]*|kids?|baby|beb[a-z]*|junior|suv[a-z]*|dry)\M'
WHERE normalized_alias IN ('sampon', 'sampon za kosu', 'shampoo')
  AND uncommon_name_pattern IS NULL;

-- Nove vrste proizvoda -----------------------------------------------------

INSERT INTO app.product_type (code, name, product_category_id)
SELECT kind.code, kind.name, category.id
FROM (
    VALUES
        ('DISH_DETERGENT', 'Deterdžent za sudove', 'HOUSEHOLD_CLEANING'),
        ('TOOTHPASTE', 'Pasta za zube', 'PERSONAL_CARE'),
        ('CHOCOLATE', 'Čokolada', 'SWEETS_CEREALS')
) AS kind (code, name, category_code)
LEFT JOIN app.product_category AS category
  ON category.code = kind.category_code
ON CONFLICT (code) DO NOTHING;

-- Prodavnica koja proizvod svrsta drugde (-0,15) ostavlja ga na pregled.
INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id, rule.include_pattern, rule.exclude_pattern, rule.priority, 0.9600
FROM (
    VALUES
        (
            'DISH_DETERGENT',
            '\m(deterdzent[a-z]*|det|deter|sredstv[a-z]*|tecnost[a-z]*|tec|gel|tablet[a-z]*|kapsul[a-z]*)\M.*\m(sudov[a-z]*|sudj[a-z]*|posud[a-z]*)\M'
                || '|\m(sudov[a-z]*|sudj[a-z]*|posudj[a-z]*)\M.*\m(deterdzent[a-z]*|det|tecn[a-z]*|gel|tablet[a-z]*|kapsul[a-z]*)\M'
                || '|\m(fairy|ludwik|morning fresh|somat|finish)\M',
            '\m(ves|vesa|wc|toalet[a-z]*|stakl[a-z]*|podov[a-z]*|kupatil[a-z]*|rukavic[a-z]*|sundjer[a-z]*'
                || '|cetk[a-z]*|krp[a-z]*|stalak|susac[a-z]*|so|sol|sjaj[a-z]*|ispiranj[a-z]*|cistac[a-z]*'
                || '|osvezivac[a-z]*|ciscenj[a-z]*|maramic[a-z]*|towels?|mop|clener|cleaner)\M',
            30
        ),
        (
            'TOOTHPASTE',
            '\mpast[a-z]* za zub[a-z]*\M|\mzubn[a-z]* past[a-z]*\M|\mzp\M|\mtoothpaste\M'
                || '|\m(colgate|signal|aquafresh|sensodyne|elmex|parodontax|blend a med|blendamed|meridol|lacalut)\M'
                || '.*\m(past[a-z]*|ml)\M',
            '\m(cetk[a-z]*|konac|vodic[a-z]*|ispiranj[a-z]*|tecnost[a-z]*|protez[a-z]*|corega|tablet[a-z]*'
                || '|interdental[a-z]*|brush|floss|mouthwash|sapun[a-z]*|tus[a-z]*|set|poklon[a-z]*|serum)\M',
            30
        ),
        (
            'CHOCOLATE',
            '\mcokolad(a|e|u)\M|^cok\M',
            '\m(keks[a-z]*|kroasan[a-z]*|napolitank[a-z]*|vafl[a-z]*|wafer[a-z]*|bombon[a-z]*|pralin[a-z]*'
                || '|namaz[a-z]*|sladoled[a-z]*|napitak|napici|puding[a-z]*|preliv[a-z]*|sirup[a-z]*|musli'
                || '|cerealij[a-z]*|pahuljic[a-z]*|jogurt[a-z]*|kafa|liker[a-z]*|tort[a-z]*|kolac[a-z]*'
                || '|bananic[a-z]*|cips|protein[a-z]*|jaje|jaja|figur[a-z]*|zeka|zec|lizalic[a-z]*'
                || '|kockic[a-z]*|kapi|kapljic[a-z]*|mrvic[a-z]*|posip[a-z]*|glazur[a-z]*|fondue|rolat'
                || '|kiflic[a-z]*|flips|smoki|grickalic[a-z]*|krofn[a-z]*|mafin[a-z]*|cookie[a-z]*|brownie'
                || '|kuglic[a-z]*|drazej[a-z]*|ukus[a-z]*|arom[a-z]*|granol[a-z]*|kasa|kasic[a-z]*|ovsen[a-z]*)\M'
                -- Čokolada se ne prodaje na litar; mleko sa ukusom čokolade da.
                || '|\m(l|dl|ml)\M',
            30
        )
) AS rule (type_code, include_pattern, exclude_pattern, priority)
JOIN app.product_type AS type
  ON type.code = rule.type_code;

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v16',
        'taxonomy-v17'
    );
END $$;

-- Isti proizvod kod dva lanca ------------------------------------------------

-- Reč za vrstu koju piše samo jedan lanac ne razdvaja proizvod (V111):
-- „COKOLADA MILKA OREO“ i „Milka straw cheesecake“. Vrsta pročitana iz te reči
-- ne sme da ih razdvoji ni sad, kad čokolada ima vrstu: par se poredi i kad
-- jedna porodica još nema vrstu. Dve različite vrste i dalje nisu par.
DO $$
DECLARE
    definition TEXT := pg_get_functiondef('app.refresh_product_merge_suggestions()'::regprocedure);
    old_condition CONSTANT TEXT :=
        'AND right_family.product_type_id IS NOT DISTINCT FROM left_family.product_type_id';
BEGIN
    IF POSITION(old_condition IN definition) = 0 THEN
        RAISE EXCEPTION 'refresh_product_merge_suggestions: uslov za vrstu nije pronađen';
    END IF;
    EXECUTE REPLACE(
        definition,
        old_condition,
        'AND (right_family.product_type_id IS NOT DISTINCT FROM left_family.product_type_id'
            || ' OR right_family.product_type_id IS NULL OR left_family.product_type_id IS NULL)'
    );
END $$;

-- Reči na spisku -----------------------------------------------------------

INSERT INTO app.shopping_intent (
    code,
    name,
    default_min_package_quantity,
    default_max_package_quantity,
    default_base_unit
)
VALUES
    ('DISH_DETERGENT', 'Deterdžent za sudove', 400, 1500, 'ml'),
    ('TOOTHPASTE', 'Pasta za zube', 50, 125, 'ml'),
    ('CHOCOLATE', 'Čokolada', 80, 300, 'g')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, 'EXACT', 10, TRUE
FROM (
    VALUES
        ('DISH_DETERGENT'),
        ('TOOTHPASTE'),
        ('CHOCOLATE')
) AS allowed (code)
JOIN app.shopping_intent AS intent
  ON intent.code = allowed.code
JOIN app.product_type AS type
  ON type.code = allowed.code
ON CONFLICT (shopping_intent_id, product_type_id) DO NOTHING;

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, word.alias, 5, word.pattern
FROM (
    VALUES
        ('DISH_DETERGENT', 'deterdzent za sudove', NULL),
        ('DISH_DETERGENT', 'deterdzent za sudje', NULL),
        ('DISH_DETERGENT', 'deterdzent za posudje', NULL),
        ('DISH_DETERGENT', 'deterdzent za pranje sudova', NULL),
        ('DISH_DETERGENT', 'deterdzent za pranje posudja', NULL),
        ('DISH_DETERGENT', 'sredstvo za pranje sudova', NULL),
        ('DISH_DETERGENT', 'sredstvo za pranje posudja', NULL),
        ('DISH_DETERGENT', 'tecnost za sudove', NULL),
        ('DISH_DETERGENT', 'tecnost za pranje sudova', NULL),
        ('DISH_DETERGENT', 'fairy', '\mfairy\M'),
        ('DISH_DETERGENT', 'tablete za sudove', '\m(masin[a-z]*|tablet[a-z]*|kapsul[a-z]*|caps)\M'),
        ('DISH_DETERGENT', 'tablete za masinu za sudove', '\m(masin[a-z]*|tablet[a-z]*|kapsul[a-z]*|caps)\M'),
        ('DISH_DETERGENT', 'deterdzent za masinu za sudove', '\m(masin[a-z]*|tablet[a-z]*|kapsul[a-z]*|caps)\M'),
        ('DISH_DETERGENT', 'deterdzent za masinsko pranje sudova', '\m(masin[a-z]*|tablet[a-z]*|kapsul[a-z]*|caps)\M'),
        ('TOOTHPASTE', 'pasta za zube', NULL),
        ('TOOTHPASTE', 'pastu za zube', NULL),
        ('TOOTHPASTE', 'paste za zube', NULL),
        ('TOOTHPASTE', 'zubna pasta', NULL),
        ('TOOTHPASTE', 'zubnu pastu', NULL),
        ('TOOTHPASTE', 'decija pasta za zube', '\m(dec[a-z]*|djec[a-z]*|kids?|junior|baby|beb[a-z]*)\M'),
        ('TOOTHPASTE', 'deciju pastu za zube', '\m(dec[a-z]*|djec[a-z]*|kids?|junior|baby|beb[a-z]*)\M'),
        ('CHOCOLATE', 'cokolada', NULL),
        ('CHOCOLATE', 'cokoladu', NULL),
        ('CHOCOLATE', 'cokolade', NULL),
        ('CHOCOLATE', 'tabla cokolade', NULL),
        ('CHOCOLATE', 'mlecna cokolada', NULL),
        ('CHOCOLATE', 'mlecnu cokoladu', NULL),
        ('CHOCOLATE', 'crna cokolada', '\m(crn[a-z]*|tamn[a-z]*|dark|bitter|gork[a-z]*|noir|fondant)\M|\m[5-9][0-9] (kakao|cacao|cocoa)\M'),
        ('CHOCOLATE', 'crnu cokoladu', '\m(crn[a-z]*|tamn[a-z]*|dark|bitter|gork[a-z]*|noir|fondant)\M|\m[5-9][0-9] (kakao|cacao|cocoa)\M'),
        ('CHOCOLATE', 'tamna cokolada', '\m(crn[a-z]*|tamn[a-z]*|dark|bitter|gork[a-z]*|noir|fondant)\M|\m[5-9][0-9] (kakao|cacao|cocoa)\M'),
        ('CHOCOLATE', 'bela cokolada', '\m(bela|bele|belu|white)\M'),
        ('CHOCOLATE', 'belu cokoladu', '\m(bela|bele|belu|white)\M'),
        ('CHOCOLATE', 'cokolada za kuvanje', '\m(kuvan[a-z]*|kuhanj[a-z]*|cooking|dezert[a-z]*|desert[a-z]*)\M')
) AS word (intent_code, alias, pattern)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
ON CONFLICT (normalized_alias) DO NOTHING;

-- Uobičajena vrsta: ruke, a ne mašina; odrasli, a ne deca; mlečna čokolada.
UPDATE app.shopping_intent_alias AS alias
SET uncommon_name_pattern = word.pattern
FROM (
    VALUES
        ('DISH_DETERGENT', '\m(masin[a-z]*|tablet[a-z]*|kapsul[a-z]*|caps|somat|finish)\M'),
        ('TOOTHPASTE', '\m(dec[a-z]*|djec[a-z]*|kids?|junior|baby|beb[a-z]*)\M'),
        ('CHOCOLATE', '\m(crn[a-z]*|tamn[a-z]*|dark|bitter|gork[a-z]*|noir|fondant|bela|bele|belu|white'
            || '|kuvan[a-z]*|kuhanj[a-z]*|cooking|dezert[a-z]*|desert[a-z]*|bez secera|sugar free|vegan[a-z]*|ruby)\M')
) AS word (intent_code, pattern)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
WHERE alias.shopping_intent_id = intent.id
  AND alias.required_name_pattern IS NULL
  AND alias.uncommon_name_pattern IS NULL;

-- Vrste po novim pravilima ---------------------------------------------------

-- Mašinske vrste maslaca se računaju ponovo; ručno potvrđene ostaju.
DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type
WHERE type.id = assignment.product_type_id
  AND type.code = 'BUTTER'
  AND assignment.reviewed = FALSE;

INSERT INTO app.retailer_product_type (
    retailer_product_id,
    product_type_id,
    confidence,
    assignment_source,
    evidence,
    algorithm_version
)
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
 AND type.code IN ('BUTTER', 'DISH_DETERGENT', 'TOOTHPASTE', 'CHOCOLATE')
WHERE prediction.confidence >= 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  )
ON CONFLICT (retailer_product_id) DO NOTHING;

-- Svaka porodica proizvoda u ovim vrstama pokazuje vrstu koju ima većina.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    JOIN app.product_type AS type
      ON type.id = assignment.product_type_id
    WHERE product.product_family_id IS NOT NULL
      AND type.code IN ('BUTTER', 'DISH_DETERGENT', 'TOOTHPASTE', 'CHOCOLATE')
    UNION
    SELECT family.id
    FROM app.product_family AS family
    JOIN app.product_type AS type
      ON type.id = family.product_type_id
    WHERE type.code = 'BUTTER'
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
