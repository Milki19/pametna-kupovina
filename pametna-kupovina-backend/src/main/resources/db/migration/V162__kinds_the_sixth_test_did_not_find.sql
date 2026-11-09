-- Vrste koje šesti test nije našao (6.11.2026., stavka 9):
--
-- * "zobeno mleko" je bilo mleko sa rečju „zobeno“, a nijedno mleko je ne
--   kaže. Radnje ga zovu „NAPITAK OVAS 1L ALPRO“, „NAPITAK OD ZOBI 1L DAR“,
--   „Oatly Oat drink“. Biljni napitak je imao pravilo samo za „ovseni
--   napitak“ kao celu frazu, pa ovi napici nisu imali vrstu. Sad je vrsta
--   svaki napitak (ili mleko) od ovsa, badema ili soje, a "zobeno mleko",
--   "ovseno mleko", "bademovo mleko" i "sojino mleko" su reči na spisku koje
--   traže baš tu biljku.
-- * "kesice za zamrzivač" su tražene po nazivu („kesice“ i „zamrzivac“), a
--   radnje pišu „KESE ZA ZAMRZIVAČ ZIPER FINO“. Kese za zamrzivač su vrsta.
-- * "vanilin šećer" je nalazio samo naziv sa obe reči; sad je vrsta, sa
--   veličinom kesice (oko 10 g).
-- * "margarin za kolače" nije imao cenu u krugu od 3 km: tražio je naziv sa
--   rečima „margarin“ i „kolace“. Margarin je vrsta, a za kolače je obično
--   margarin u kocki; stoni, light i namazi dolaze tek kad radnja nema drugi.
--
-- Vrste nemaju kategoriju (kao sapun, V139): lanci biljne napitke stavljaju
-- u mleko ili u pića, margarin u mlečne proizvode ili u masti, a vanilin
-- šećer u šećer ili u dodatke za kolače. Odeljenje (V153) i dalje drži kese
-- u kućnoj hemiji, a hranu u hrani. Reči su po jeziku (language_code), pa
-- drugo tržište dodaje svoje bez izmene pravila.

-- Nove vrste ----------------------------------------------------------------

INSERT INTO app.product_type (code, name, product_category_id, domain)
VALUES
    ('FREEZER_BAG', 'Kese za zamrzivač', NULL, 'HOUSEHOLD'),
    ('VANILLA_SUGAR', 'Vanilin šećer', NULL, 'FOOD'),
    ('MARGARINE', 'Margarin', NULL, 'FOOD')
ON CONFLICT (code) DO NOTHING;

-- Biljni napitak je bio u kategoriji mleka: napitak koji lanac svrsta u
-- pića padao je ispod praga sigurnosti (0,97 - 0,15).
UPDATE app.product_type
SET product_category_id = NULL,
    updated_at = NOW()
WHERE code = 'PLANT_DRINK'
  AND product_category_id IS NOT NULL;

INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id, rule.include_pattern, rule.exclude_pattern, 5, 0.9700
FROM (
    VALUES
        (
            'PLANT_DRINK',
            -- Napitak (ili mleko) od ovsa, badema ili soje; Oatly pravi samo napitke.
            '\moatly\M'
                || '|^(?=.*\m(napit[a-z]*|napic[a-z]*|drink|mleko|mlijeko|milk|barista)\M)'
                || '.*\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|badem[a-z]*|almond'
                || '|soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            -- Kaša, pahuljice, keks i čokolada „sa bademom i mlekom“ nisu napitak;
            -- ni kozmetika „mleko za telo sa bademom“.
            '\m(kasa|kase|kasu|kasic[a-z]*|pahuljic[a-z]*|musli|granol[a-z]*|keks[a-z]*|kreker[a-z]*|plocic[a-z]*'
                || '|bar|barovi|cokolad[a-z]*|bombon[a-z]*|desert[a-z]*|puding[a-z]*|jogurt[a-z]*|sladoled[a-z]*'
                || '|krem[a-z]*|namaz[a-z]*|pasta|ulje|ulja|brasno|brasna|mekinj[a-z]*|kafa|kapsul[a-z]*|zrn[a-z]*'
                || '|sir|sira|tofu|sos|sosa|kocke|kockic[a-z]*|ljuspic[a-z]*|pecen[a-z]*|slan[a-z]*|ljusten[a-z]*'
                || '|telo|tela|lice|lica|ruke|kozu|losion[a-z]*|sapun[a-z]*|sampon[a-z]*|gel|tus|kupk[a-z]*'
                || '|balzam[a-z]*|maska|dezodor[a-z]*|cetk[a-z]*|hrana|hrane|bebe|bebi)\M'
        ),
        (
            'FREEZER_BAG',
            -- Kesa (ili vrećica) za zamrzivač; zip kese za čuvanje hrane.
            '^(?=.*\m(kes[a-z]*|vrec[a-z]*|vreck[a-z]*|bag|bags)\M).*\m(zamrziv[a-z]*|zamrzavanj[a-z]*'
                || '|freezer|freez[a-z]*)\M'
                || '|\m(zip|ziper|zipper|ziploc|ziplock|zip lock) (kes[a-z]*|vrec[a-z]*)\M'
                || '|\m(kes[a-z]*|vrec[a-z]*) (zip|ziper|zipper|ziploc|ziplock)\M',
            -- Kese za smeće, za led, za pečenje i usisivač nisu za zamrzivač;
            -- ni smrznuta hrana u kesi.
            '\m(smec[a-z]*|djubr[a-z]*|otpad[a-z]*|usisiv[a-z]*|pecenj[a-z]*|rerna|rernu|led|leda|kock[a-z]*'
                || '|torb[a-z]*|ceger[a-z]*|povrc[a-z]*|grasak|pomfrit[a-z]*|riba|ribe|meso|mesa|sladoled[a-z]*'
                || '|pica|pizza|zamrznut[a-z]*|smrznut[a-z]*)\M'
        ),
        (
            'VANILLA_SUGAR',
            -- „VANILIN SECER 10G DR.OETKER“, „Burbon vanilin šećer“, „šećer vanila“.
            '\mvanilin[a-z]*\M'
                || '|^(?=.*\m(vanil[a-z]*|burbon|bourbon)\M).*\msecer[a-z]*\M',
            -- Sladoled, puding, keks i aroma sa vanilom nisu vanilin šećer;
            -- ni šećer u prahu sa vanilom.
            '\m(sladoled[a-z]*|puding[a-z]*|jogurt[a-z]*|mlek[a-z]*|napit[a-z]*|keks[a-z]*|kroasan[a-z]*'
                || '|cokolad[a-z]*|krem[a-z]*|desert[a-z]*|torta|torte|kolac[a-z]*|vafl[a-z]*|napolitank[a-z]*'
                || '|bombon[a-z]*|sirup[a-z]*|liker[a-z]*|kafa|caj|arom[a-z]*|ekstrakt[a-z]*|prahu|prah'
                || '|sapun[a-z]*|sampon[a-z]*|gel|tus|losion[a-z]*|parfem[a-z]*|svec[a-z]*|osvezivac[a-z]*'
                || '|mirisn[a-z]*|kupk[a-z]*|duvan[a-z]*)\M'
        ),
        (
            'MARGARINE',
            -- „MARGARIN DIJAMANT CLASSIC STONI 250G“, „DIJAMANT ST.MARG.MASLAC“.
            '\m(margarin[a-z]*|marg)\M',
            -- Testo, kore i peciva sa margarinom nisu margarin.
            '\m(testo|testa|kor[ae]|lisnat[a-z]*|kroasan[a-z]*|pogac[a-z]*|burek[a-z]*|pita|pite|kifl[a-z]*'
                || '|pecivo|peciva|keks[a-z]*|kreker[a-z]*|tort[ae]|vafl[a-z]*|cokolad[a-z]*|kalup[a-z]*|pizza|pica)\M'
        )
) AS rule (type_code, include_pattern, exclude_pattern)
JOIN app.product_type AS type
  ON type.code = rule.type_code;

-- Pravila su se promenila: predviđanja nose novu verziju. Broj se čita iz
-- funkcije, jer i druge migracije ovog talasa mogu da ga podignu.
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

-- Vanilin šećer se prodaje u kesicama od 8 do 10 g, margarin u kocki od
-- 250 g ili 500 g. Kese se broje u komadima, kao ubrusi.
INSERT INTO app.shopping_intent (
    code,
    name,
    default_min_package_quantity,
    default_max_package_quantity,
    default_base_unit
)
VALUES
    ('FREEZER_BAG', 'Kese za zamrzivač', NULL, NULL, NULL),
    ('VANILLA_SUGAR', 'Vanilin šećer', 7, 12, 'g'),
    ('MARGARINE', 'Margarin', 200, 500, 'g')
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
        ('FREEZER_BAG', 'FREEZER_BAG'),
        ('VANILLA_SUGAR', 'VANILLA_SUGAR'),
        ('MARGARINE', 'MARGARINE')
) AS allowed (intent_code, type_code)
JOIN app.shopping_intent AS intent
  ON intent.code = allowed.intent_code
JOIN app.product_type AS type
  ON type.code = allowed.type_code
ON CONFLICT (shopping_intent_id, product_type_id) DO NOTHING;

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
        -- Biljka koju red kaže je obavezna; ukus i protein dolaze tek kad nema običnog.
        ('PLANT_DRINK', 'zobeno mleko', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko zobeno', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'zobeni napitak', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'napitak od zobi', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko od zobi', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'ovseno mleko', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko ovseno', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'ovsano mleko', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'ovseni napitak', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'napitak od ovsa', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko od ovsa', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'oat milk', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'oat drink', '\m(ovas|ovsa|ovsen[a-z]*|ovsan[a-z]*|zob|zobi|zoben[a-z]*|oat|oats|oatly)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'bademovo mleko', '\m(badem[a-z]*|almond)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko bademovo', '\m(badem[a-z]*|almond)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'bademov napitak', '\m(badem[a-z]*|almond)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'napitak od badema', '\m(badem[a-z]*|almond)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko od badema', '\m(badem[a-z]*|almond)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'almond milk', '\m(badem[a-z]*|almond)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'sojino mleko', '\m(soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko sojino', '\m(soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'sojin napitak', '\m(soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'napitak od soje', '\m(soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'mleko od soje', '\m(soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'soja mleko', '\m(soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        ('PLANT_DRINK', 'soy milk', '\m(soja|soje|soji|sojin[a-z]*|soy|soya)\M',
            '\m(cokolad[a-z]*|kakao|vanil[a-z]*|jagod[a-z]*|banan[a-z]*|kafa|coffee|protein[a-z]*)\M'),
        -- Kese za zamrzivač; „za zamrzivač“ ostaje i kad parser uzme „kesice“ kao jedinicu.
        ('FREEZER_BAG', 'kesice za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'kesica za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'kesicu za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'kese za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'kesa za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'kesu za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'vrecice za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'kesice za zamrzavanje', NULL, NULL),
        ('FREEZER_BAG', 'kese za zamrzavanje', NULL, NULL),
        ('FREEZER_BAG', 'kesa za zamrzavanje', NULL, NULL),
        ('FREEZER_BAG', 'vrecice za zamrzavanje', NULL, NULL),
        ('FREEZER_BAG', 'za zamrzivac', NULL, NULL),
        ('FREEZER_BAG', 'za zamrzavanje', NULL, NULL),
        ('FREEZER_BAG', 'zip kese', NULL, NULL),
        ('FREEZER_BAG', 'zip kesice', NULL, NULL),
        ('FREEZER_BAG', 'ziper kese', NULL, NULL),
        ('FREEZER_BAG', 'ziper kesice', NULL, NULL),
        ('FREEZER_BAG', 'freezer bags', NULL, NULL),
        -- Vanilin šećer; burbon samo kad ga red kaže.
        ('VANILLA_SUGAR', 'vanilin secer', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'vanilin secera', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'vanilinski secer', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'vanilin seceri', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'vanila secer', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'secer vanilin', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'secer vanila', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'vanilin', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'vanilina', NULL, '\m(burbon|bourbon|prirodn[a-z]*|bio|organsk[a-z]*)\M'),
        ('VANILLA_SUGAR', 'burbon secer', '\m(burbon|bourbon)\M', NULL),
        ('VANILLA_SUGAR', 'burbon vanilin secer', '\m(burbon|bourbon)\M', NULL),
        ('VANILLA_SUGAR', 'vanilin secer burbon', '\m(burbon|bourbon)\M', NULL),
        -- Margarin; za kolače je obično kocka, stoni i namazi tek kad nema druge.
        ('MARGARINE', 'margarin', NULL, '\m(light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*)\M'),
        ('MARGARINE', 'margarina', NULL, '\m(light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*)\M'),
        ('MARGARINE', 'stoni margarin', '\m(ston[a-z]*|st)\M', NULL),
        ('MARGARINE', 'margarin za kolace', NULL,
            '\m(ston[a-z]*|st|light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*|sendvic[a-z]*|omega)\M'),
        ('MARGARINE', 'margarin za kolac', NULL,
            '\m(ston[a-z]*|st|light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*|sendvic[a-z]*|omega)\M'),
        ('MARGARINE', 'margarin za pecivo', NULL,
            '\m(ston[a-z]*|st|light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*|sendvic[a-z]*|omega)\M'),
        ('MARGARINE', 'margarin za pecenje', NULL,
            '\m(ston[a-z]*|st|light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*|sendvic[a-z]*|omega)\M'),
        ('MARGARINE', 'margarin za testo', NULL,
            '\m(ston[a-z]*|st|light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*|sendvic[a-z]*|omega)\M'),
        ('MARGARINE', 'margarin u kocki', NULL,
            '\m(ston[a-z]*|st|light|lajt|polumasn[a-z]*|smanjen[a-z]*|maslinov[a-z]*|namaz[a-z]*|sendvic[a-z]*|omega)\M')
) AS word (intent_code, alias, required, uncommon)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
ON CONFLICT (normalized_alias) DO NOTHING;

-- Vrste po novim pravilima ---------------------------------------------------

CREATE TEMPORARY TABLE named_kind ON COMMIT DROP AS
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       type.code AS type_code,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
 AND type.code IN ('PLANT_DRINK', 'FREEZER_BAG', 'VANILLA_SUGAR', 'MARGARINE')
WHERE prediction.confidence >= 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  );

-- Mašinska vrsta takvog proizvoda se menja; ručno potvrđena ostaje.
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
    algorithm_version
)
SELECT retailer_product_id,
       product_type_id,
       confidence,
       prediction_source,
       evidence,
       algorithm_version
FROM named_kind
ON CONFLICT (retailer_product_id) DO NOTHING;

-- Svaka porodica proizvoda u ovim vrstama pokazuje vrstu koju ima većina.
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
