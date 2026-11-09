-- Varijanta sa spiska je obavezan filter (šesti test, 6.11.2026): „Nespresso
-- kapsule“ su postale mlevena kafa, „Coca Cola zero“ obična Coca Cola, a
-- „orasi“ mešavina sa suvim voćem. Reč varijante je do sada samo podizala
-- rang (V140), pa je prodavnica bez kapsula nudila mlevenu kafu kao zamenu.
--
-- Oblik je iz V155: reč sa spiska (app.variant_word) traži vrednost osobine,
-- a vrsta kojoj je osobina obavezna (app.product_type_required_attribute)
-- mora da je ima zapisanu. Ovde se dodaje samo ono što V155 nema: kako se
-- vrednost osobine čita iz naziva proizvoda (app.attribute_value_rule), opet
-- po tržištu i jeziku, da bi drugo tržište dodalo svoje redove bez koda.
--
-- Pravilo izbora (StoreShoppingOfferRepository): kad stavka traži osobinu A
-- sa vrednošću X, proizvod nije izbor ako ima A sa drugom vrednošću, ili ako
-- njegova vrsta zahteva A, a vrednost nije zapisana. Proizvod bez vrednosti
-- čija vrsta A ne zahteva ostaje izbor („orasi jezgro 100 g“ za „orasi“).

-- 1. Osobine ----------------------------------------------------------------

INSERT INTO app.product_attribute_definition (code, name, value_kind)
VALUES
    ('SUGAR_CONTENT', 'Šećer: obično, zero ili light', 'TEXT'),
    ('COFFEE_FORM', 'Oblik kafe: mlevena, zrno, kapsule, instant', 'TEXT'),
    ('CAPSULE_SYSTEM', 'Sistem kapsula: Nespresso, Dolce Gusto, Tassimo', 'TEXT'),
    ('NUT_MIX', 'Orašasti plodovi: čisti ili mešavina', 'TEXT'),
    ('STORAGE_STATE', 'Sveže ili smrznuto', 'TEXT')
ON CONFLICT (code) DO NOTHING;

-- 2. Kako se vrednost čita iz naziva ------------------------------------------
--
-- include_pattern je izraz nad retailer_product.normalized_name (latinica,
-- mala slova, bez kvačica). Bez izraza red je podrazumevana vrednost vrste
-- (product_type_id je tada obavezan): kafa bez reči o obliku je mlevena,
-- gazirani sok bez „zero“ i „light“ je običan. Za jedan proizvod i jednu
-- osobinu važi prvi red: prvo onaj sa izrazom, pa tržište pre jezika, pa
-- manji priority.
CREATE TABLE app.attribute_value_rule (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id SMALLINT,
    language VARCHAR(35) NOT NULL,
    attribute_definition_id BIGINT NOT NULL,
    text_value VARCHAR(200) NOT NULL,
    -- Samo proizvodi ove vrste; NULL je svaki proizvod.
    product_type_id BIGINT,
    include_pattern VARCHAR(600),
    exclude_pattern VARCHAR(600),
    priority SMALLINT NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_attribute_value_rule_market
        FOREIGN KEY (market_id) REFERENCES app.market (id),
    CONSTRAINT fk_attribute_value_rule_attribute
        FOREIGN KEY (attribute_definition_id)
            REFERENCES app.product_attribute_definition (id),
    CONSTRAINT fk_attribute_value_rule_type
        FOREIGN KEY (product_type_id) REFERENCES app.product_type (id)
            ON DELETE CASCADE,
    CONSTRAINT chk_attribute_value_rule_language CHECK (BTRIM(language) <> ''),
    CONSTRAINT chk_attribute_value_rule_value CHECK (BTRIM(text_value) <> ''),
    CONSTRAINT chk_attribute_value_rule_default_has_kind
        CHECK (include_pattern IS NOT NULL OR product_type_id IS NOT NULL),
    CONSTRAINT chk_attribute_value_rule_priority CHECK (priority > 0)
);

CREATE INDEX idx_attribute_value_rule_lookup
    ON app.attribute_value_rule (language, attribute_definition_id, priority)
    WHERE active;

COMMENT ON TABLE app.attribute_value_rule IS
    'Kako se vrednost osobine čita iz naziva proizvoda, po tržištu i jeziku (V160).';

-- Šećer: svaki proizvod koji to piše; običan je podrazumevan za gazirane i
-- energetske sokove.
INSERT INTO app.attribute_value_rule (
    language, attribute_definition_id, text_value, product_type_id,
    include_pattern, exclude_pattern, priority
)
SELECT 'sr-Latn', definition.id, rule.value, type.id,
       rule.include_pattern, rule.exclude_pattern, rule.priority
FROM (
    VALUES
        ('ZERO', NULL, '\m(zero|bez secera|sugar free|no sugar|0 secera|0 sugar|pepsi max)\M', NULL, 10),
        ('LIGHT', NULL, '\m(light|lajt|lite|diet)\M', NULL, 20),
        ('REGULAR', 'SOFT_DRINK', NULL, NULL, 100),
        ('REGULAR', 'ENERGY_DRINK', NULL, NULL, 100)
) AS rule(value, type_code, include_pattern, exclude_pattern, priority)
JOIN app.product_attribute_definition AS definition ON definition.code = 'SUGAR_CONTENT'
LEFT JOIN app.product_type AS type ON type.code = rule.type_code
WHERE rule.type_code IS NULL OR type.id IS NOT NULL;

-- Oblik kafe: samo za kafu, da „mleveno meso“ ne dobije oblik kafe.
-- Kafa bez reči o obliku je mlevena, instant kafa je instant.
INSERT INTO app.attribute_value_rule (
    language, attribute_definition_id, text_value, product_type_id,
    include_pattern, exclude_pattern, priority
)
SELECT 'sr-Latn', definition.id, rule.value, type.id,
       rule.include_pattern, NULL, rule.priority
FROM (
    VALUES
        ('CAPSULES', '\m(kapsul[a-z]*|kaps|caps|capsules?|pods?|jastucic[a-z]*|nespresso|vertuo|dolce gusto|tassimo|kompatibiln[a-z]*)\M', 10),
        ('BEANS', '\m(zrn[a-z]*|beans?|u zrnu)\M', 20),
        ('INSTANT', '\m(instant|3 ?(u|in) ?1|2 ?(u|in) ?1|frape|frappe)\M', 30),
        ('GROUND', '\m(mleven[a-z]*|mljeven[a-z]*|ground|turska|domaca)\M', 40)
) AS rule(value, include_pattern, priority)
CROSS JOIN (VALUES ('COFFEE'), ('INSTANT_COFFEE')) AS kind(type_code)
JOIN app.product_type AS type ON type.code = kind.type_code
JOIN app.product_attribute_definition AS definition ON definition.code = 'COFFEE_FORM';

INSERT INTO app.attribute_value_rule (
    language, attribute_definition_id, text_value, product_type_id,
    include_pattern, priority
)
SELECT 'sr-Latn', definition.id, rule.value, type.id, NULL, 100
FROM (VALUES ('GROUND', 'COFFEE'), ('INSTANT', 'INSTANT_COFFEE')) AS rule(value, type_code)
JOIN app.product_type AS type ON type.code = rule.type_code
JOIN app.product_attribute_definition AS definition ON definition.code = 'COFFEE_FORM';

-- Sistem kapsula: svaki proizvod koji ga piše; kapsule „kompatibilne sa
-- Nespresso“ su za Nespresso aparat.
INSERT INTO app.attribute_value_rule (
    language, attribute_definition_id, text_value, include_pattern, priority
)
SELECT 'sr-Latn', definition.id, rule.value, rule.include_pattern, rule.priority
FROM (
    VALUES
        ('DOLCE_GUSTO', '\m(dolce ?gusto)\M', 10),
        ('TASSIMO', '\mtassimo\M', 20),
        ('NESPRESSO', '\m(nespresso|vertuo)\M', 30)
) AS rule(value, include_pattern, priority)
JOIN app.product_attribute_definition AS definition ON definition.code = 'CAPSULE_SYSTEM';

-- Orašasti plodovi: mešavina je ono što naziv kaže da je mešavina. Čist
-- orah ne piše ništa, pa vrednost nema; za „orasi“ je tada izbor (vidi gore).
INSERT INTO app.attribute_value_rule (
    language, attribute_definition_id, text_value, include_pattern, priority
)
SELECT 'sr-Latn', definition.id, 'MIX',
       '\m(mesavin[a-z]*|mix|miks|studentsk[a-z]*|trail|koktel|cocktail|suvo voce|suvim voce?m|suvog voca|grozdj[a-z]*|brusnic[a-z]*)\M',
       10
FROM app.product_attribute_definition AS definition
WHERE definition.code = 'NUT_MIX';

-- Sveže ili smrznuto: naziv koji to kaže, a inače vrsta (V89 deli sveže i
-- smrznuto meso i ribu; smrznuti proizvodi su svoja vrsta od V34).
INSERT INTO app.attribute_value_rule (
    language, attribute_definition_id, text_value, product_type_id,
    include_pattern, exclude_pattern, priority
)
SELECT 'sr-Latn', definition.id, rule.value, type.id,
       rule.include_pattern, rule.exclude_pattern, rule.priority
FROM (
    VALUES
        ('FROZEN', NULL, '\m(smrznut[a-z]*|zamrznut[a-z]*|frozen|duboko ?smrz[a-z]*|congelat[a-z]*)\M', NULL, 10),
        ('FRESH', NULL, '\m(svez|sveza|sveze|svezi|svezu|svezeg|svezih|svezoj|fresh|ohladjen[a-z]*|rashladjen[a-z]*)\M',
                    '\m(osvez[a-z]*|refresh[a-z]*)\M', 20),
        ('FROZEN', 'FROZEN', NULL, NULL, 100),
        ('FROZEN', 'FROZEN_FISH', NULL, NULL, 100),
        ('FROZEN', 'FROZEN_MEAT', NULL, NULL, 100),
        ('FRESH', 'FRESH_FISH', NULL, NULL, 100),
        ('FRESH', 'FRESH_MEAT', NULL, NULL, 100),
        ('FRESH', 'VEGETABLE', NULL, NULL, 100),
        ('FRESH', 'FRESH_PRODUCE', NULL, NULL, 100)
) AS rule(value, type_code, include_pattern, exclude_pattern, priority)
JOIN app.product_attribute_definition AS definition ON definition.code = 'STORAGE_STATE'
LEFT JOIN app.product_type AS type ON type.code = rule.type_code
WHERE rule.type_code IS NULL OR type.id IS NOT NULL;

-- 3. Obavezne osobine vrste (V155) ---------------------------------------------
--
-- Vrsta ovde mora da ima vrednost zapisanu da bi bila izbor kad je spisak
-- traži. Svaka od njih ima podrazumevanu vrednost iznad, pa joj vrednost
-- ne fali; red ovde kaže da proizvod bez vrednosti nije „možda kapsula“.
INSERT INTO app.product_type_required_attribute (product_type_id, attribute_definition_id)
SELECT type.id, definition.id
FROM (
    VALUES
        ('SOFT_DRINK', 'SUGAR_CONTENT'),
        ('ENERGY_DRINK', 'SUGAR_CONTENT'),
        ('COFFEE', 'COFFEE_FORM'),
        ('INSTANT_COFFEE', 'COFFEE_FORM'),
        ('FROZEN', 'STORAGE_STATE'),
        ('FROZEN_FISH', 'STORAGE_STATE'),
        ('FROZEN_MEAT', 'STORAGE_STATE'),
        ('FRESH_FISH', 'STORAGE_STATE'),
        ('FRESH_MEAT', 'STORAGE_STATE'),
        ('VEGETABLE', 'STORAGE_STATE'),
        ('FRESH_PRODUCE', 'STORAGE_STATE')
) AS required(type_code, attribute_code)
JOIN app.product_type AS type ON type.code = required.type_code
JOIN app.product_attribute_definition AS definition ON definition.code = required.attribute_code
ON CONFLICT DO NOTHING;

-- 4. Reči sa spiska (V155) ------------------------------------------------------
--
-- Svaki oblik reči je svoj red (normalizovano: „sveže“ je „sveze“). Za jednu
-- osobinu važi reč manjeg priority-ja: „mešavina oraha“ je mešavina, iako
-- kaže i „oraha“.
INSERT INTO app.variant_word (
    language, normalized_word, attribute_definition_id, text_value, priority
)
SELECT 'sr-Latn', word.normalized_word, definition.id, word.value, word.priority
FROM (
    VALUES
        ('SUGAR_CONTENT', 'ZERO', 10, 'zero'),
        ('SUGAR_CONTENT', 'ZERO', 10, 'bez secera'),
        ('SUGAR_CONTENT', 'ZERO', 10, 'sugar free'),
        ('SUGAR_CONTENT', 'ZERO', 10, 'no sugar'),
        ('SUGAR_CONTENT', 'ZERO', 10, 'pepsi max'),
        ('SUGAR_CONTENT', 'LIGHT', 20, 'light'),
        ('SUGAR_CONTENT', 'LIGHT', 20, 'lajt'),
        ('SUGAR_CONTENT', 'LIGHT', 20, 'diet'),

        ('COFFEE_FORM', 'CAPSULES', 10, 'kapsule'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'kapsula'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'kapsulama'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'kapsuli'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'kapsulu'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'kaps'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'caps'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'capsules'),
        ('COFFEE_FORM', 'CAPSULES', 10, 'pods'),
        ('COFFEE_FORM', 'CAPSULES', 20, 'nespresso'),
        ('COFFEE_FORM', 'CAPSULES', 20, 'dolce gusto'),
        ('COFFEE_FORM', 'CAPSULES', 20, 'tassimo'),
        ('COFFEE_FORM', 'BEANS', 10, 'zrno'),
        ('COFFEE_FORM', 'BEANS', 10, 'zrnu'),
        ('COFFEE_FORM', 'BEANS', 10, 'zrna'),
        ('COFFEE_FORM', 'BEANS', 10, 'beans'),
        ('COFFEE_FORM', 'GROUND', 10, 'mlevena'),
        ('COFFEE_FORM', 'GROUND', 10, 'mleveno'),
        ('COFFEE_FORM', 'GROUND', 10, 'mlevene'),
        ('COFFEE_FORM', 'GROUND', 10, 'mlevenu'),
        ('COFFEE_FORM', 'GROUND', 10, 'mlevene kafe'),
        ('COFFEE_FORM', 'GROUND', 10, 'mljevena'),
        ('COFFEE_FORM', 'INSTANT', 10, 'instant'),

        ('CAPSULE_SYSTEM', 'NESPRESSO', 10, 'nespresso'),
        ('CAPSULE_SYSTEM', 'NESPRESSO', 10, 'vertuo'),
        ('CAPSULE_SYSTEM', 'DOLCE_GUSTO', 10, 'dolce gusto'),
        ('CAPSULE_SYSTEM', 'DOLCE_GUSTO', 10, 'dolcegusto'),
        ('CAPSULE_SYSTEM', 'TASSIMO', 10, 'tassimo'),

        ('NUT_MIX', 'MIX', 10, 'mesavina'),
        ('NUT_MIX', 'MIX', 10, 'mesavine'),
        ('NUT_MIX', 'MIX', 10, 'mesavinu'),
        ('NUT_MIX', 'MIX', 10, 'mix'),
        ('NUT_MIX', 'MIX', 10, 'miks'),
        ('NUT_MIX', 'MIX', 10, 'studentska'),
        ('NUT_MIX', 'MIX', 10, 'studentsku'),
        ('NUT_MIX', 'PLAIN', 50, 'orah'),
        ('NUT_MIX', 'PLAIN', 50, 'oraha'),
        ('NUT_MIX', 'PLAIN', 50, 'orahe'),
        ('NUT_MIX', 'PLAIN', 50, 'orasi'),
        ('NUT_MIX', 'PLAIN', 50, 'badem'),
        ('NUT_MIX', 'PLAIN', 50, 'bademi'),
        ('NUT_MIX', 'PLAIN', 50, 'badema'),
        ('NUT_MIX', 'PLAIN', 50, 'lesnik'),
        ('NUT_MIX', 'PLAIN', 50, 'lesnici'),
        ('NUT_MIX', 'PLAIN', 50, 'lesnika'),
        ('NUT_MIX', 'PLAIN', 50, 'indijski orah'),
        ('NUT_MIX', 'PLAIN', 50, 'kikiriki'),
        ('NUT_MIX', 'PLAIN', 50, 'pistaci'),
        ('NUT_MIX', 'PLAIN', 50, 'pistacije'),
        ('NUT_MIX', 'PLAIN', 50, 'makadamija'),

        ('STORAGE_STATE', 'FROZEN', 10, 'smrznut'),
        ('STORAGE_STATE', 'FROZEN', 10, 'smrznuta'),
        ('STORAGE_STATE', 'FROZEN', 10, 'smrznuto'),
        ('STORAGE_STATE', 'FROZEN', 10, 'smrznute'),
        ('STORAGE_STATE', 'FROZEN', 10, 'smrznuti'),
        ('STORAGE_STATE', 'FROZEN', 10, 'smrznutog'),
        ('STORAGE_STATE', 'FROZEN', 10, 'smrznutu'),
        ('STORAGE_STATE', 'FROZEN', 10, 'smrznutih'),
        ('STORAGE_STATE', 'FROZEN', 10, 'zamrznut'),
        ('STORAGE_STATE', 'FROZEN', 10, 'zamrznuta'),
        ('STORAGE_STATE', 'FROZEN', 10, 'zamrznuto'),
        ('STORAGE_STATE', 'FROZEN', 10, 'zamrznute'),
        ('STORAGE_STATE', 'FROZEN', 10, 'zamrznuti'),
        ('STORAGE_STATE', 'FROZEN', 10, 'frozen'),
        ('STORAGE_STATE', 'FRESH', 20, 'svez'),
        ('STORAGE_STATE', 'FRESH', 20, 'sveza'),
        ('STORAGE_STATE', 'FRESH', 20, 'sveze'),
        ('STORAGE_STATE', 'FRESH', 20, 'svezi'),
        ('STORAGE_STATE', 'FRESH', 20, 'svezu'),
        ('STORAGE_STATE', 'FRESH', 20, 'svezeg'),
        ('STORAGE_STATE', 'FRESH', 20, 'svezih'),
        ('STORAGE_STATE', 'FRESH', 20, 'fresh')
) AS word(attribute_code, value, priority, normalized_word)
JOIN app.product_attribute_definition AS definition ON definition.code = word.attribute_code
ON CONFLICT DO NOTHING;

-- 5. „Coca Cola“ bez reči zero ------------------------------------------------
--
-- V125 je za „coca cola“ i „pepsi“ isključio zero i light u samoj reči
-- rečnika, pa je „Coca Cola zero“ (koja se čita kao „coca cola“ + „zero“)
-- dobijala samo običnu. Sada odlučuje varijanta: zero i light su ređi izbor
-- za „coca cola“ (rang, kao V125 za kafu u zrnu), a „coca cola zero“ traži
-- zero kao filter.
UPDATE app.shopping_intent_alias
SET required_name_pattern = '\mcoca\M',
    uncommon_name_pattern = '\m(zero|light|lajt|diet|bez secera)\M'
WHERE normalized_alias IN ('coca cola', 'koka kola')
  AND required_name_pattern = '^(?!.*\m(zero|light|bez secera)\M).*\mcoca\M';

UPDATE app.shopping_intent_alias
SET required_name_pattern = '\mpepsi\M',
    uncommon_name_pattern = '\m(max|zero|light|lajt|diet|bez secera)\M'
WHERE normalized_alias = 'pepsi'
  AND required_name_pattern = '^(?!.*\m(max|zero|light)\M).*\mpepsi\M';

-- 6. Vrednosti u katalogu -------------------------------------------------------
--
-- Jedno mesto koje čita pravila iznad: ova migracija ga pušta za ceo
-- katalog, a ProductCatalogMaintenanceService posle svakog uvoza za jedan
-- lanac. Ručno potvrđene vrednosti (reviewed) se ne diraju.
CREATE OR REPLACE FUNCTION app.read_attribute_values(for_retailer_id BIGINT)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    written INTEGER;
BEGIN
    DELETE FROM app.retailer_product_attribute AS assignment
    USING app.retailer_product AS product
    WHERE assignment.retailer_product_id = product.id
      AND (for_retailer_id IS NULL OR product.retailer_id = for_retailer_id)
      AND assignment.reviewed = FALSE
      AND assignment.assignment_source = 'ATTRIBUTE_RULE';

    INSERT INTO app.retailer_product_attribute (
        retailer_product_id, attribute_definition_id, text_value,
        confidence, assignment_source, evidence
    )
    SELECT DISTINCT ON (product.id, rule.attribute_definition_id)
           product.id,
           rule.attribute_definition_id,
           rule.text_value,
           CASE WHEN rule.include_pattern IS NULL THEN 0.9000 ELSE 0.9500 END,
           'ATTRIBUTE_RULE',
           'RULE:' || rule.id
    FROM app.retailer_product AS product
    JOIN app.retailer AS chain ON chain.id = product.retailer_id
    JOIN app.market AS market ON market.id = chain.market_id
    LEFT JOIN app.retailer_product_type AS typed
      ON typed.retailer_product_id = product.id
    JOIN app.attribute_value_rule AS rule
      ON rule.active
     AND rule.language = market.default_language
     AND (rule.market_id IS NULL OR rule.market_id = market.id)
     AND (rule.product_type_id IS NULL OR rule.product_type_id = typed.product_type_id)
     AND (rule.include_pattern IS NULL OR product.normalized_name ~ rule.include_pattern)
     AND (rule.exclude_pattern IS NULL OR product.normalized_name !~ rule.exclude_pattern)
    WHERE (for_retailer_id IS NULL OR product.retailer_id = for_retailer_id)
      AND product.normalized_name IS NOT NULL
    ORDER BY product.id,
             rule.attribute_definition_id,
             (rule.include_pattern IS NULL),
             (rule.market_id IS NULL),
             rule.priority,
             rule.id
    ON CONFLICT (retailer_product_id, attribute_definition_id) DO NOTHING;

    GET DIAGNOSTICS written = ROW_COUNT;
    RETURN written;
END
$$;

COMMENT ON FUNCTION app.read_attribute_values(BIGINT) IS
    'Upisuje vrednosti osobina iz app.attribute_value_rule; NULL je ceo katalog (V160).';

SELECT app.read_attribute_values(NULL);
