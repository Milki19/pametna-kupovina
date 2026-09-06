-- Tip proizvoda opisuje šta artikal jeste. Shopping intent opisuje šta je
-- korisnik spreman da kupi. Ta dva pojma namerno nisu ista tabela: sinonim
-- proizvoda ne sme automatski da proširi dozvoljene zamene u korpi.

ALTER TABLE app.product_type
    ADD COLUMN product_category_id BIGINT;

ALTER TABLE app.product_type
    ADD CONSTRAINT fk_product_type_category
        FOREIGN KEY (product_category_id)
            REFERENCES app.product_category (id);

INSERT INTO app.product_type (code, name)
VALUES ('BAKERY_ROLL', 'Pecivo')
ON CONFLICT (code) DO NOTHING;

UPDATE app.product_type AS type
SET product_category_id = category.id,
    updated_at = NOW()
FROM app.product_category AS category
WHERE category.code = CASE
    WHEN type.code IN (
        'BREAD', 'BAKERY_ROLL', 'BREADCRUMBS', 'YEAST'
    ) THEN 'BREAD'
    WHEN type.code IN (
        'MILK', 'SOUR_MILK', 'FLAVORED_MILK',
        'POWDERED_MILK', 'PLANT_DRINK'
    ) THEN 'MILK'
    WHEN type.code = 'YOGURT' THEN 'YOGURT'
    WHEN type.code = 'EGGS' THEN 'EGGS'
    WHEN type.code = 'WATER' THEN 'WATER'
    WHEN type.code = 'BEER' THEN 'BEER'
    WHEN type.code = 'WINE' THEN 'WINE'
    WHEN type.code = 'MAYONNAISE' THEN 'MAYONNAISE'
    WHEN type.code = 'FLOUR' THEN 'FLOUR'
    WHEN type.code = 'SUGAR' THEN 'SUGAR'
    WHEN type.code = 'SALT' THEN 'SALT'
    WHEN type.code = 'OIL' THEN 'OIL'
    WHEN type.code = 'VINEGAR' THEN 'VINEGAR'
    WHEN type.code = 'COFFEE' THEN 'COFFEE'
    WHEN type.code = 'PASTA' THEN 'PASTA'
    WHEN type.code = 'RICE' THEN 'RICE'
    WHEN type.code IN ('CHEESE', 'BUTTER', 'CREAM')
        THEN 'DAIRY_EGGS'
    WHEN type.code = 'MEAT' THEN 'MEAT'
    WHEN type.code = 'FISH' THEN 'FISH'
    WHEN type.code = 'FRESH_PRODUCE' THEN 'FRESH_PRODUCE'
    WHEN type.code IN ('JUICE') THEN 'BEVERAGES'
    ELSE NULL
END
  AND type.product_category_id IS DISTINCT FROM category.id;

CREATE INDEX idx_product_type_category
    ON app.product_type (product_category_id, id)
    WHERE product_category_id IS NOT NULL;

-- Tehnički aliasi služe klasifikaciji artikala. Premeštanje peciva iz
-- BREAD ne menja istorijske nazive, samo precizira njihovo značenje.
INSERT INTO app.product_type_alias (
    product_type_id,
    normalized_alias,
    priority
)
SELECT type.id, alias.normalized_alias, alias.priority
FROM (
    VALUES
        ('BAKERY_ROLL', 'pecivo', 10),
        ('BAKERY_ROLL', 'kifla', 10),
        ('BAKERY_ROLL', 'zemicka', 10),
        ('BAKERY_ROLL', 'lepinja', 10),
        ('BAKERY_ROLL', 'kroasan', 10),
        ('SOUR_MILK', 'kis mleko', 5),
        ('SOUR_MILK', 'kisela mleka', 5),
        ('BREAD', 'lebac', 15),
        ('BREAD', 'vekna', 15)
) AS alias(type_code, normalized_alias, priority)
JOIN app.product_type AS type ON type.code = alias.type_code
ON CONFLICT (normalized_alias) DO UPDATE SET
    product_type_id = EXCLUDED.product_type_id,
    priority = EXCLUDED.priority;

UPDATE app.product_type_alias AS alias
SET product_type_id = bakery.id,
    priority = LEAST(alias.priority, 10)
FROM app.product_type AS bakery
WHERE bakery.code = 'BAKERY_ROLL'
  AND alias.normalized_alias IN (
      'pecivo', 'kifla', 'zemicka', 'lepinja', 'kroasan'
  );

-- Stara široka pravila se deaktiviraju, ali ostaju u bazi radi audita.
UPDATE app.product_type_rule AS rule
SET active = FALSE,
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code IN (
      'BREAD', 'MILK', 'SOUR_MILK', 'EGGS', 'WINE'
  )
  AND rule.active = TRUE;

INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id,
       rule.include_pattern,
       rule.exclude_pattern,
       rule.priority,
       rule.confidence
FROM (
    VALUES
        (
            'SOUR_MILK',
            '(^| )((kiselo|kis) (mleko|mlijeko)|kisela mleka|fermentisano mleko)( |$)',
            NULL,
            3,
            0.9950
        ),
        (
            'BAKERY_ROLL',
            '(^| )(pecivo|kifla|zemicka|lepinja|kroasan)([a-z]*)( |$)',
            '(^| )(prasak za pecivo|pecilni prasak|prezle|krusne mrvice)( |$)',
            5,
            0.9850
        ),
        (
            'BREAD',
            '(^| )(hleb[a-z]*|kruh|lebac|vekna|baget|tost)( |$)',
            '(^| )(prasak za pecivo|pecilni prasak|prezle|krusne mrvice|kvasac|stapic|kesa|igrack[a-z]*)( |$)',
            10,
            0.9900
        ),
        (
            'MILK',
            '(^| )(mleko|mlijeko)( |$)',
            '(^| )((kiselo|kis) (mleko|mlijeko)|kisela mleka|fermentisano mleko|cokoladno mleko|aromatizovano mleko|mleko sa ukusom|mleko u prahu|mleko za telo|mleko za suncanje|mleko za ciscenje|biljno mleko)( |$)',
            10,
            0.9900
        ),
        (
            'EGGS',
            '(^| )(jaja|jaje)( |$)',
            '(^| )(boja|boje|farba|farbanje|ukras[a-z]*|nalepnic[a-z]*|kinder|cokolad[a-z]*|igrack[a-z]*)( |$)',
            10,
            0.9950
        ),
        (
            'WINE',
            '(^| )vino( |$)',
            '(^| )(kesa|case|casa|otvarac|stalak|poklon|etiketa|sveca)( |$)',
            10,
            0.9800
        )
) AS rule(
    type_code,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
JOIN app.product_type AS type ON type.code = rule.type_code
ON CONFLICT DO NOTHING;

ALTER TABLE app.retailer_product_type
    ADD COLUMN algorithm_version VARCHAR(40)
        NOT NULL DEFAULT 'taxonomy-v1';

CREATE TABLE app.product_type_candidate (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_product_id BIGINT NOT NULL,
    product_type_id BIGINT NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL,
    prediction_source VARCHAR(40) NOT NULL,
    evidence VARCHAR(1000) NOT NULL,
    algorithm_version VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_type_candidate_product
        FOREIGN KEY (retailer_product_id)
            REFERENCES app.retailer_product (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_product_type_candidate_type
        FOREIGN KEY (product_type_id)
            REFERENCES app.product_type (id),
    CONSTRAINT chk_product_type_candidate_confidence
        CHECK (confidence > 0 AND confidence < 0.95),
    CONSTRAINT chk_product_type_candidate_source
        CHECK (BTRIM(prediction_source) <> ''),
    CONSTRAINT chk_product_type_candidate_evidence
        CHECK (BTRIM(evidence) <> ''),
    CONSTRAINT chk_product_type_candidate_version
        CHECK (BTRIM(algorithm_version) <> ''),
    CONSTRAINT chk_product_type_candidate_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT uq_product_type_candidate_version
        UNIQUE (
            retailer_product_id,
            product_type_id,
            algorithm_version
        )
);

CREATE INDEX idx_product_type_candidate_review
    ON app.product_type_candidate (status, confidence DESC, id);

CREATE TABLE app.shopping_intent (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    default_min_package_quantity NUMERIC(14, 4),
    default_max_package_quantity NUMERIC(14, 4),
    default_base_unit VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_shopping_intent_code CHECK (BTRIM(code) <> ''),
    CONSTRAINT chk_shopping_intent_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT chk_shopping_intent_min
        CHECK (
            default_min_package_quantity IS NULL
            OR default_min_package_quantity > 0
        ),
    CONSTRAINT chk_shopping_intent_max
        CHECK (
            default_max_package_quantity IS NULL
            OR default_max_package_quantity > 0
        ),
    CONSTRAINT chk_shopping_intent_range
        CHECK (
            default_min_package_quantity IS NULL
            OR default_max_package_quantity IS NULL
            OR default_min_package_quantity <= default_max_package_quantity
        ),
    CONSTRAINT chk_shopping_intent_unit
        CHECK (
            default_base_unit IS NULL
            OR default_base_unit IN ('g', 'ml', 'piece')
        )
);

CREATE TABLE app.shopping_intent_alias (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shopping_intent_id BIGINT NOT NULL,
    normalized_alias VARCHAR(200) NOT NULL UNIQUE,
    priority SMALLINT NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_shopping_intent_alias_intent
        FOREIGN KEY (shopping_intent_id)
            REFERENCES app.shopping_intent (id)
            ON DELETE CASCADE,
    CONSTRAINT chk_shopping_intent_alias
        CHECK (BTRIM(normalized_alias) <> ''),
    CONSTRAINT chk_shopping_intent_alias_priority
        CHECK (priority > 0)
);

CREATE TABLE app.shopping_intent_product_type (
    shopping_intent_id BIGINT NOT NULL,
    product_type_id BIGINT NOT NULL,
    substitution_level VARCHAR(20) NOT NULL DEFAULT 'EXACT',
    match_priority SMALLINT NOT NULL DEFAULT 100,
    enabled_by_default BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (shopping_intent_id, product_type_id),

    CONSTRAINT fk_shopping_intent_type_intent
        FOREIGN KEY (shopping_intent_id)
            REFERENCES app.shopping_intent (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_shopping_intent_type_type
        FOREIGN KEY (product_type_id)
            REFERENCES app.product_type (id),
    CONSTRAINT chk_shopping_intent_substitution
        CHECK (substitution_level IN ('EXACT', 'RELATED', 'BROAD')),
    CONSTRAINT chk_shopping_intent_match_priority
        CHECK (match_priority > 0)
);

INSERT INTO app.shopping_intent (code, name)
SELECT type.code, type.name
FROM app.product_type AS type
WHERE type.code IN (
    'BREAD', 'BAKERY_ROLL', 'BAKING_POWDER', 'BREADCRUMBS',
    'YEAST', 'MILK', 'SOUR_MILK', 'FLAVORED_MILK',
    'POWDERED_MILK', 'PLANT_DRINK', 'YOGURT', 'EGGS',
    'WATER', 'BEER', 'WINE', 'MAYONNAISE', 'FLOUR',
    'SUGAR', 'SALT', 'OIL', 'VINEGAR', 'COFFEE', 'JUICE',
    'PASTA', 'RICE', 'CHEESE', 'BUTTER', 'CREAM', 'MEAT',
    'FISH', 'FRESH_PRODUCE'
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    updated_at = NOW();

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority
)
SELECT intent.id, alias.normalized_alias, alias.priority
FROM (
    VALUES
        ('BREAD', 'hleb', 10),
        ('BREAD', 'kruh', 10),
        ('BREAD', 'lebac', 10),
        ('BREAD', 'vekna', 15),
        ('BAKERY_ROLL', 'pecivo', 10),
        ('BAKERY_ROLL', 'kifla', 15),
        ('BAKERY_ROLL', 'zemicka', 15),
        ('BAKERY_ROLL', 'lepinja', 15),
        ('BAKERY_ROLL', 'kroasan', 15),
        ('BAKING_POWDER', 'prasak za pecivo', 10),
        ('BAKING_POWDER', 'pecilni prasak', 10),
        ('BREADCRUMBS', 'prezle', 10),
        ('BREADCRUMBS', 'krusne mrvice', 10),
        ('YEAST', 'kvasac', 10),
        ('MILK', 'mleko', 10),
        ('MILK', 'mlijeko', 10),
        ('SOUR_MILK', 'kiselo mleko', 10),
        ('SOUR_MILK', 'kis mleko', 10),
        ('SOUR_MILK', 'kisela mleka', 10),
        ('FLAVORED_MILK', 'cokoladno mleko', 10),
        ('POWDERED_MILK', 'mleko u prahu', 10),
        ('PLANT_DRINK', 'biljni napitak', 10),
        ('PLANT_DRINK', 'biljno mleko', 10),
        ('YOGURT', 'jogurt', 10),
        ('YOGURT', 'kefir', 15),
        ('EGGS', 'jaja', 10),
        ('EGGS', 'jaje', 15),
        ('WATER', 'voda', 10),
        ('WATER', 'mineralna voda', 10),
        ('BEER', 'pivo', 10),
        ('WINE', 'vino', 10),
        ('MAYONNAISE', 'majonez', 10),
        ('FLOUR', 'brasno', 10),
        ('SUGAR', 'secer', 10),
        ('SALT', 'so', 10),
        ('OIL', 'ulje', 10),
        ('VINEGAR', 'sirce', 10),
        ('COFFEE', 'kafa', 10),
        ('JUICE', 'sok', 10),
        ('PASTA', 'testenina', 10),
        ('PASTA', 'makarone', 15),
        ('PASTA', 'spagete', 15),
        ('RICE', 'pirinac', 10),
        ('CHEESE', 'sir', 10),
        ('BUTTER', 'puter', 10),
        ('BUTTER', 'maslac', 10),
        ('CREAM', 'pavlaka', 10),
        ('CREAM', 'kajmak', 10),
        ('MEAT', 'meso', 10),
        ('FISH', 'riba', 10),
        ('FRESH_PRODUCE', 'voce', 10),
        ('FRESH_PRODUCE', 'povrce', 10)
) AS alias(intent_code, normalized_alias, priority)
JOIN app.shopping_intent AS intent
  ON intent.code = alias.intent_code
ON CONFLICT (normalized_alias) DO UPDATE SET
    shopping_intent_id = EXCLUDED.shopping_intent_id,
    priority = EXCLUDED.priority;

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, 'EXACT', 10, TRUE
FROM app.shopping_intent AS intent
JOIN app.product_type AS type ON type.code = intent.code
ON CONFLICT (shopping_intent_id, product_type_id) DO UPDATE SET
    substitution_level = EXCLUDED.substitution_level,
    match_priority = EXCLUDED.match_priority,
    enabled_by_default = EXCLUDED.enabled_by_default;

ALTER TABLE app.shopping_list_item
    ADD COLUMN shopping_intent_id BIGINT;

ALTER TABLE app.shopping_list_item
    ADD CONSTRAINT fk_shopping_list_item_intent
        FOREIGN KEY (shopping_intent_id)
            REFERENCES app.shopping_intent (id);

UPDATE app.shopping_list_item AS item
SET shopping_intent_id = alias.shopping_intent_id,
    updated_at = NOW()
FROM app.shopping_intent_alias AS alias
WHERE item.matching_rule = 'FLEXIBLE_CATEGORY'
  AND item.flexible_category_normalized = alias.normalized_alias;

CREATE INDEX idx_shopping_list_item_intent
    ON app.shopping_list_item (shopping_intent_id, shopping_list_id)
    WHERE shopping_intent_id IS NOT NULL;

CREATE INDEX idx_shopping_intent_alias_resolution
    ON app.shopping_intent_alias (
        priority,
        normalized_alias,
        shopping_intent_id
    );

CREATE INDEX idx_shopping_intent_default_types
    ON app.shopping_intent_product_type (
        shopping_intent_id,
        enabled_by_default,
        match_priority,
        product_type_id
    );

-- Facete su odvojene od tipa: 2,8% i 3,2% su i dalje isti tip mleka.
CREATE TABLE app.product_attribute_definition (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    value_kind VARCHAR(20) NOT NULL,
    canonical_unit VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_product_attribute_code CHECK (BTRIM(code) <> ''),
    CONSTRAINT chk_product_attribute_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT chk_product_attribute_kind
        CHECK (value_kind IN ('TEXT', 'NUMBER', 'BOOLEAN'))
);

CREATE TABLE app.retailer_product_attribute (
    retailer_product_id BIGINT NOT NULL,
    attribute_definition_id BIGINT NOT NULL,
    text_value VARCHAR(500),
    numeric_value NUMERIC(14, 4),
    boolean_value BOOLEAN,
    unit VARCHAR(20),
    confidence NUMERIC(5, 4) NOT NULL,
    assignment_source VARCHAR(40) NOT NULL,
    evidence VARCHAR(1000) NOT NULL,
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (retailer_product_id, attribute_definition_id),

    CONSTRAINT fk_retailer_product_attribute_product
        FOREIGN KEY (retailer_product_id)
            REFERENCES app.retailer_product (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_retailer_product_attribute_definition
        FOREIGN KEY (attribute_definition_id)
            REFERENCES app.product_attribute_definition (id),
    CONSTRAINT chk_retailer_product_attribute_value
        CHECK (NUM_NONNULLS(text_value, numeric_value, boolean_value) = 1),
    CONSTRAINT chk_retailer_product_attribute_confidence
        CHECK (confidence > 0 AND confidence <= 1),
    CONSTRAINT chk_retailer_product_attribute_source
        CHECK (BTRIM(assignment_source) <> ''),
    CONSTRAINT chk_retailer_product_attribute_evidence
        CHECK (BTRIM(evidence) <> '')
);

INSERT INTO app.product_attribute_definition (
    code,
    name,
    value_kind,
    canonical_unit
)
VALUES
    ('FAT_PERCENT', 'Procenat mlečne masti', 'NUMBER', 'percent'),
    ('MILK_SOURCE', 'Poreklo mleka', 'TEXT', NULL),
    ('PROCESSING', 'Način obrade', 'TEXT', NULL),
    ('PACKAGING_FORM', 'Vrsta pakovanja', 'TEXT', NULL)
ON CONFLICT (code) DO NOTHING;

CREATE INDEX idx_retailer_product_attribute_lookup
    ON app.retailer_product_attribute (
        attribute_definition_id,
        text_value,
        numeric_value,
        retailer_product_id
    );

-- Jedno mesto za izračunavanje najboljeg predloga sprečava da importer,
-- backfill i administrativni izveštaj vremenom dobiju različita pravila.
-- Kompatibilna izvorna kategorija pojačava, a konfliktna smanjuje signal
-- naziva. Odsustvo izvorne kategorije nije kazna (npr. Maxi feed).
CREATE VIEW app.product_type_prediction AS
SELECT product.id AS retailer_product_id,
       matched.product_type_id,
       GREATEST(
           0.0001,
           LEAST(
               1.0000,
               matched.rule_confidence
                   + CASE
                         WHEN actual_category.id IS NULL
                           OR expected_category.id IS NULL THEN 0.0000
                         WHEN actual_category.id = expected_category.id
                           OR actual_category.id = expected_category.parent_id
                           OR actual_category.parent_id = expected_category.id
                             THEN 0.0200
                         ELSE -0.1500
                     END
           )
       )::NUMERIC(5, 4) AS confidence,
       CASE
           WHEN actual_category.id IS NULL THEN 'NAME_RULE'
           WHEN actual_category.id = expected_category.id
             OR actual_category.id = expected_category.parent_id
             OR actual_category.parent_id = expected_category.id
               THEN 'SOURCE_CATEGORY_AND_NAME'
           ELSE 'CONFLICTING_SOURCE_AND_NAME'
       END AS prediction_source,
       'RULE:' || matched.rule_id
           || CASE
                  WHEN NULLIF(BTRIM(product.category_code), '') IS NULL
                      THEN ''
                  ELSE ';CATEGORY_CODE:' || BTRIM(product.category_code)
              END AS evidence,
       'taxonomy-v2'::VARCHAR(40) AS algorithm_version
FROM app.retailer_product AS product
JOIN LATERAL (
    SELECT rule.id AS rule_id,
           rule.product_type_id,
           rule.confidence AS rule_confidence
    FROM app.product_type_rule AS rule
    JOIN app.product_type AS type
      ON type.id = rule.product_type_id
     AND type.active = TRUE
    WHERE rule.active = TRUE
      AND (
          rule.retailer_id IS NULL
          OR rule.retailer_id = product.retailer_id
      )
      AND product.normalized_name ~ rule.include_pattern
      AND (
          rule.exclude_pattern IS NULL
          OR product.normalized_name !~ rule.exclude_pattern
      )
    ORDER BY rule.priority,
             rule.confidence DESC,
             LENGTH(rule.include_pattern) DESC,
             rule.id
    LIMIT 1
) AS matched ON TRUE
JOIN app.product_type AS matched_type
  ON matched_type.id = matched.product_type_id
LEFT JOIN app.product_category AS expected_category
  ON expected_category.id = matched_type.product_category_id
LEFT JOIN app.retailer_product_category AS category_assignment
  ON category_assignment.retailer_product_id = product.id
LEFT JOIN app.product_category AS actual_category
  ON actual_category.id = category_assignment.product_category_id;

-- Jednokratni backfill postojećeg kataloga. Ručno potvrđene odluke imaju
-- prednost i nikada se ne brišu niti prepisuju.
DELETE FROM app.retailer_product_type
WHERE reviewed = FALSE;

INSERT INTO app.product_type_candidate (
    retailer_product_id,
    product_type_id,
    confidence,
    prediction_source,
    evidence,
    algorithm_version
)
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.product_type_prediction AS prediction
WHERE prediction.confidence >= 0.7500
  AND prediction.confidence < 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type AS assignment
      WHERE assignment.retailer_product_id =
            prediction.retailer_product_id
        AND assignment.reviewed = TRUE
  )
ON CONFLICT DO NOTHING;

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
FROM app.product_type_prediction AS prediction
WHERE prediction.confidence >= 0.9500
ON CONFLICT (retailer_product_id) DO NOTHING;

WITH type_counts AS (
    SELECT product.product_family_id,
           assignment.product_type_id,
           COUNT(*) AS assignment_count,
           MAX(assignment.confidence) AS maximum_confidence
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id IS NOT NULL
    GROUP BY product.product_family_id,
             assignment.product_type_id
), type_choice AS (
    SELECT DISTINCT ON (product_family_id)
           product_family_id,
           product_type_id
    FROM type_counts
    ORDER BY product_family_id,
             assignment_count DESC,
             maximum_confidence DESC,
             product_type_id
)
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM type_choice AS choice
WHERE family.id = choice.product_family_id;

UPDATE app.product_family AS family
SET product_type_id = NULL,
    updated_at = NOW()
WHERE family.product_type_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product AS product
      JOIN app.retailer_product_type AS assignment
        ON assignment.retailer_product_id = product.id
      WHERE product.product_family_id = family.id
  );

INSERT INTO app.retailer_product_attribute (
    retailer_product_id,
    attribute_definition_id,
    numeric_value,
    unit,
    confidence,
    assignment_source,
    evidence
)
SELECT product.id,
       definition.id,
       parsed.value,
       'percent',
       0.9700,
       'NAME_EXTRACTION',
       'PATTERN:FAT_PERCENT'
FROM app.retailer_product AS product
JOIN app.retailer_product_type AS type_assignment
  ON type_assignment.retailer_product_id = product.id
JOIN app.product_type AS type
  ON type.id = type_assignment.product_type_id
 AND type.code IN (
     'MILK', 'SOUR_MILK', 'FLAVORED_MILK',
     'YOGURT', 'CHEESE', 'BUTTER', 'CREAM'
 )
JOIN app.product_attribute_definition AS definition
  ON definition.code = 'FAT_PERCENT'
CROSS JOIN LATERAL (
    SELECT REPLACE(match[1], ',', '.')::NUMERIC AS value
    FROM REGEXP_MATCH(
        product.normalized_name,
        '([0-9]+([.,][0-9]+)?) ?%'
    ) AS match
) AS parsed
WHERE parsed.value > 0
  AND parsed.value <= 100
ON CONFLICT DO NOTHING;

INSERT INTO app.retailer_product_attribute (
    retailer_product_id,
    attribute_definition_id,
    text_value,
    confidence,
    assignment_source,
    evidence
)
SELECT product.id,
       definition.id,
       extracted.value,
       0.9500,
       'NAME_EXTRACTION',
       'PATTERN:' || definition.code
FROM app.retailer_product AS product
CROSS JOIN app.product_attribute_definition AS definition
CROSS JOIN LATERAL (
    SELECT CASE definition.code
        WHEN 'MILK_SOURCE' THEN CASE
            WHEN product.normalized_name ~ '(^| )kozj[a-z]*( |$)'
                THEN 'GOAT'
            WHEN product.normalized_name ~ '(^| )ovcij[a-z]*( |$)'
                THEN 'SHEEP'
            WHEN product.normalized_name ~ '(^| )kravlj[a-z]*( |$)'
                THEN 'COW'
            WHEN product.normalized_name
                ~ '(^| )(badem|soj[a-z]*|ovas|ovsen[a-z]*|kokos)( |$)'
                THEN 'PLANT'
            ELSE NULL
        END
        WHEN 'PROCESSING' THEN CASE
            WHEN product.normalized_name
                ~ '(^| )(uht|sterilizovan[a-z]*)( |$)'
                THEN 'UHT'
            WHEN product.normalized_name
                ~ '(^| )pasterizovan[a-z]*( |$)'
                THEN 'PASTEURIZED'
            WHEN product.normalized_name
                ~ '(^| )fermentisan[a-z]*( |$)'
                THEN 'FERMENTED'
            ELSE NULL
        END
        WHEN 'PACKAGING_FORM' THEN CASE
            WHEN product.normalized_name ~ '(^| )pet( |$)'
                THEN 'PET'
            WHEN product.normalized_name
                ~ '(^| )(tetra ?pak|tetrapak)( |$)'
                THEN 'CARTON'
            WHEN product.normalized_name
                ~ '(^| )(limenka|can)( |$)'
                THEN 'CAN'
            WHEN product.normalized_name
                ~ '(^| )(staklo|staklena)( |$)'
                THEN 'GLASS'
            ELSE NULL
        END
        ELSE NULL
    END AS value
) AS extracted
WHERE definition.code IN (
    'MILK_SOURCE', 'PROCESSING', 'PACKAGING_FORM'
)
  AND extracted.value IS NOT NULL
  AND (
      definition.code = 'PACKAGING_FORM'
      OR EXISTS (
          SELECT 1
          FROM app.retailer_product_type AS type_assignment
          JOIN app.product_type AS product_type
            ON product_type.id = type_assignment.product_type_id
          WHERE type_assignment.retailer_product_id = product.id
            AND (
                (
                    definition.code = 'MILK_SOURCE'
                    AND product_type.code IN (
                        'MILK', 'SOUR_MILK', 'FLAVORED_MILK',
                        'POWDERED_MILK', 'PLANT_DRINK', 'YOGURT',
                        'CHEESE', 'BUTTER', 'CREAM'
                    )
                )
                OR (
                    definition.code = 'PROCESSING'
                    AND product_type.code IN (
                        'MILK', 'SOUR_MILK', 'FLAVORED_MILK',
                        'POWDERED_MILK', 'PLANT_DRINK', 'YOGURT',
                        'CHEESE', 'BUTTER', 'CREAM', 'JUICE'
                    )
                )
            )
      )
  )
ON CONFLICT DO NOTHING;
