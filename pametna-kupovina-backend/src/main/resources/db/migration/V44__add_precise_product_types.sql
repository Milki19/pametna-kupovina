-- Izvorna kategorija iz cenovnika ostaje veran zapis onoga što je trgovac
-- poslao. Product type je odvojen, stroži sloj koji opisuje šta artikal
-- zaista jeste i jedini se koristi za generičke kupovne namere.
CREATE TABLE app.product_type (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    default_min_package_quantity NUMERIC(14, 4),
    default_max_package_quantity NUMERIC(14, 4),
    default_base_unit VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_product_type_code_not_blank
        CHECK (BTRIM(code) <> ''),
    CONSTRAINT chk_product_type_name_not_blank
        CHECK (BTRIM(name) <> ''),
    CONSTRAINT chk_product_type_min_quantity
        CHECK (
            default_min_package_quantity IS NULL
            OR default_min_package_quantity > 0
        ),
    CONSTRAINT chk_product_type_max_quantity
        CHECK (
            default_max_package_quantity IS NULL
            OR default_max_package_quantity > 0
        ),
    CONSTRAINT chk_product_type_quantity_range
        CHECK (
            default_min_package_quantity IS NULL
            OR default_max_package_quantity IS NULL
            OR default_min_package_quantity <= default_max_package_quantity
        ),
    CONSTRAINT chk_product_type_base_unit
        CHECK (
            default_base_unit IS NULL
            OR default_base_unit IN ('g', 'ml', 'piece')
        )
);

CREATE TABLE app.product_type_alias (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_type_id BIGINT NOT NULL,
    normalized_alias VARCHAR(200) NOT NULL UNIQUE,
    priority SMALLINT NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_type_alias_type
        FOREIGN KEY (product_type_id)
            REFERENCES app.product_type (id)
            ON DELETE CASCADE,
    CONSTRAINT chk_product_type_alias_not_blank
        CHECK (BTRIM(normalized_alias) <> ''),
    CONSTRAINT chk_product_type_alias_priority
        CHECK (priority > 0)
);

-- Pravilo ima obavezan pozitivan obrazac i opcioni obrazac koji ga
-- diskvalifikuje. Tako "kiselo mleko" i "mleko za telo" ne mogu da
-- završe kao obično mleko samo zato što sadrže reč mleko.
CREATE TABLE app.product_type_rule (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id BIGINT,
    product_type_id BIGINT NOT NULL,
    include_pattern VARCHAR(1000) NOT NULL,
    exclude_pattern VARCHAR(1000),
    priority SMALLINT NOT NULL DEFAULT 100,
    confidence NUMERIC(5, 4) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_type_rule_retailer
        FOREIGN KEY (retailer_id) REFERENCES app.retailer (id),
    CONSTRAINT fk_product_type_rule_type
        FOREIGN KEY (product_type_id) REFERENCES app.product_type (id),
    CONSTRAINT chk_product_type_rule_include
        CHECK (BTRIM(include_pattern) <> ''),
    CONSTRAINT chk_product_type_rule_exclude
        CHECK (
            exclude_pattern IS NULL
            OR BTRIM(exclude_pattern) <> ''
        ),
    CONSTRAINT chk_product_type_rule_priority
        CHECK (priority > 0),
    CONSTRAINT chk_product_type_rule_confidence
        CHECK (confidence > 0 AND confidence <= 1)
);

CREATE UNIQUE INDEX uq_product_type_rule_scope_pattern
    ON app.product_type_rule (
        COALESCE(retailer_id, 0),
        product_type_id,
        include_pattern,
        COALESCE(exclude_pattern, '')
    );

CREATE TABLE app.retailer_product_type (
    retailer_product_id BIGINT PRIMARY KEY,
    product_type_id BIGINT NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL,
    assignment_source VARCHAR(40) NOT NULL,
    evidence VARCHAR(1000) NOT NULL,
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_retailer_product_type_product
        FOREIGN KEY (retailer_product_id)
            REFERENCES app.retailer_product (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_retailer_product_type_type
        FOREIGN KEY (product_type_id) REFERENCES app.product_type (id),
    CONSTRAINT chk_retailer_product_type_confidence
        CHECK (confidence > 0 AND confidence <= 1),
    CONSTRAINT chk_retailer_product_type_source
        CHECK (BTRIM(assignment_source) <> ''),
    CONSTRAINT chk_retailer_product_type_evidence
        CHECK (BTRIM(evidence) <> '')
);

ALTER TABLE app.product_family
    ADD COLUMN product_type_id BIGINT;

ALTER TABLE app.product_family
    ADD CONSTRAINT fk_product_family_type
        FOREIGN KEY (product_type_id) REFERENCES app.product_type (id);

INSERT INTO app.product_type (code, name)
VALUES
    ('BREAD', 'Hleb'),
    ('BAKING_POWDER', 'Prašak za pecivo'),
    ('BREADCRUMBS', 'Prezle'),
    ('YEAST', 'Kvasac'),
    ('MILK', 'Mleko'),
    ('SOUR_MILK', 'Kiselo mleko'),
    ('FLAVORED_MILK', 'Aromatizovano mleko'),
    ('POWDERED_MILK', 'Mleko u prahu'),
    ('PLANT_DRINK', 'Biljni napitak'),
    ('YOGURT', 'Jogurt'),
    ('EGGS', 'Jaja'),
    ('WATER', 'Voda'),
    ('BEER', 'Pivo'),
    ('WINE', 'Vino'),
    ('MAYONNAISE', 'Majonez'),
    ('FLOUR', 'Brašno'),
    ('SUGAR', 'Šećer'),
    ('SALT', 'So'),
    ('OIL', 'Jestivo ulje'),
    ('VINEGAR', 'Sirće'),
    ('COFFEE', 'Kafa'),
    ('JUICE', 'Sok'),
    ('PASTA', 'Testenina'),
    ('RICE', 'Pirinač'),
    ('CHEESE', 'Sir'),
    ('BUTTER', 'Puter i maslac'),
    ('CREAM', 'Pavlaka i kajmak'),
    ('MEAT', 'Meso i mesne prerađevine'),
    ('FISH', 'Riba'),
    ('FRESH_PRODUCE', 'Sveže voće i povrće');

INSERT INTO app.product_type_alias (
    product_type_id,
    normalized_alias,
    priority
)
SELECT type.id, alias.normalized_alias, alias.priority
FROM (
    VALUES
        ('BREAD', 'hleb', 20),
        ('BREAD', 'kruh', 20),
        ('BREAD', 'pecivo', 30),
        ('BREAD', 'kifla', 30),
        ('BREAD', 'lepinja', 30),
        ('BAKING_POWDER', 'prasak za pecivo', 10),
        ('BAKING_POWDER', 'pecilni prasak', 10),
        ('BREADCRUMBS', 'prezle', 10),
        ('BREADCRUMBS', 'krusne mrvice', 10),
        ('YEAST', 'kvasac', 10),
        ('MILK', 'mleko', 20),
        ('MILK', 'mlijeko', 20),
        ('SOUR_MILK', 'kiselo mleko', 10),
        ('SOUR_MILK', 'kiselo mlijeko', 10),
        ('FLAVORED_MILK', 'cokoladno mleko', 10),
        ('FLAVORED_MILK', 'mleko sa ukusom', 10),
        ('POWDERED_MILK', 'mleko u prahu', 10),
        ('PLANT_DRINK', 'biljno mleko', 10),
        ('PLANT_DRINK', 'biljni napitak', 10),
        ('YOGURT', 'jogurt', 20),
        ('YOGURT', 'kefir', 20),
        ('EGGS', 'jaja', 20),
        ('EGGS', 'jaje', 20),
        ('WATER', 'voda', 20),
        ('WATER', 'mineralna voda', 10),
        ('BEER', 'pivo', 20),
        ('WINE', 'vino', 20),
        ('MAYONNAISE', 'majonez', 20),
        ('FLOUR', 'brasno', 20),
        ('SUGAR', 'secer', 20),
        ('SALT', 'so', 20),
        ('OIL', 'ulje', 20),
        ('OIL', 'jestivo ulje', 10),
        ('VINEGAR', 'sirce', 20),
        ('COFFEE', 'kafa', 20),
        ('JUICE', 'sok', 20),
        ('PASTA', 'testenina', 20),
        ('PASTA', 'makarone', 20),
        ('PASTA', 'spagete', 20),
        ('RICE', 'pirinac', 20),
        ('CHEESE', 'sir', 20),
        ('BUTTER', 'puter', 20),
        ('BUTTER', 'maslac', 20),
        ('CREAM', 'pavlaka', 20),
        ('CREAM', 'kajmak', 20),
        ('MEAT', 'meso', 30),
        ('FISH', 'riba', 30),
        ('FRESH_PRODUCE', 'voce', 40),
        ('FRESH_PRODUCE', 'povrce', 40)
) AS alias(type_code, normalized_alias, priority)
JOIN app.product_type AS type ON type.code = alias.type_code;

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
        ('BAKING_POWDER', '(^| )(prasak za pecivo|pecilni prasak)( |$)', NULL, 5, 0.9900),
        ('BREADCRUMBS', '(^| )(prezle|krusne mrvice)( |$)', NULL, 5, 0.9800),
        ('YEAST', '(^| )kvasac( |$)', NULL, 5, 0.9800),
        ('SOUR_MILK', '(^| )(kiselo mleko|kiselo mlijeko)( |$)', NULL, 5, 0.9900),
        ('FLAVORED_MILK', '(^| )(cokoladno mleko|mleko sa ukusom|aromatizovano mleko)( |$)', NULL, 5, 0.9800),
        ('POWDERED_MILK', '(^| )(mleko u prahu|mleko prah)( |$)', NULL, 5, 0.9800),
        ('PLANT_DRINK', '(^| )(biljni napitak|bademov napitak|sojin napitak|ovseni napitak|kokosov napitak)( |$)', NULL, 5, 0.9700),
        ('BREAD', '(^| )(hleb[a-z]*|kruh|baget|zemicka|kifla|lepinja|tost|pecivo)( |$)', '(^| )(prasak za pecivo|pecilni prasak|prezle|krusne mrvice|kvasac|stapic.*hleb|kesa.*hleb|igrack[a-z]*)( |$)', 20, 0.9400),
        ('MILK', '(^| )(mleko|mlijeko)( |$)', '(^| )(kiselo mleko|kiselo mlijeko|cokoladno mleko|aromatizovano mleko|mleko sa ukusom|mleko u prahu|mleko za telo|mleko za suncanje|mleko za ciscenje|biljno mleko)( |$)', 20, 0.9600),
        ('YOGURT', '(^| )(jogurt|yoghurt|kefir|ayran)( |$)', NULL, 20, 0.9600),
        ('EGGS', '(^| )(jaja|jaje)( |$)', '(^| )(kinder|cokolad[a-z]*|igrack[a-z]*)( |$)', 20, 0.9600),
        ('WATER', '(^| )(voda|mineralna voda|izvorska voda|stona voda)( |$)', '(^| )(lopta|igrack[a-z]*|vodica|toaletna voda|micelarna voda|flasica za vodu)( |$)', 20, 0.9500),
        ('BEER', '(^| )pivo( |$)', NULL, 20, 0.9700),
        ('WINE', '(^| )vino( |$)', '(^| )(kesa.*vino|case za vino|otvarac za vino)( |$)', 20, 0.9500),
        ('MAYONNAISE', '(^| )majonez( |$)', NULL, 20, 0.9800),
        ('FLOUR', '(^| )(brasno|griz)( |$)', NULL, 20, 0.9600),
        ('SUGAR', '(^| )secer( |$)', NULL, 20, 0.9600),
        ('SALT', '(^| )so( |$)', '(^| )(so za kupanje|tabletirana so)( |$)', 30, 0.9000),
        ('OIL', '(^| )(jestivo ulje|suncokretovo ulje|maslinovo ulje|ulje repice)( |$)', '(^| )(motorno ulje|ulje za telo|ulje za kosu)( |$)', 20, 0.9600),
        ('VINEGAR', '(^| )(sirce|balsamico)( |$)', NULL, 20, 0.9600),
        ('COFFEE', '(^| )(kafa|cappuccino|kapucino)( |$)', NULL, 20, 0.9500),
        ('JUICE', '(^| )(sok|nektar)( |$)', NULL, 20, 0.9300),
        ('PASTA', '(^| )(testenina|makarone|spagete|fusili|njoke)( |$)', '(^| )pasta za zube( |$)', 20, 0.9600),
        ('RICE', '(^| )pirinac( |$)', NULL, 20, 0.9700),
        ('CHEESE', '(^| )(sir|gauda|edamer|trapist|mozzarella|mocarela)( |$)', '(^| )(sirup|sircetna)( |$)', 20, 0.9300),
        ('BUTTER', '(^| )(puter|maslac)( |$)', NULL, 20, 0.9600),
        ('CREAM', '(^| )(pavlaka|kajmak)( |$)', NULL, 20, 0.9500),
        ('MEAT', '(^| )(meso|kobasica|salama|sunka|prsuta|slanina|pasteta|virsla|cevap|pljeskavica|piletina|pileci|pileca)( |$)', NULL, 30, 0.9000),
        ('FISH', '(^| )(riba|tuna|sardina|oslic|losos|skusa)( |$)', NULL, 30, 0.9200),
        ('FRESH_PRODUCE', '^(banana|jabuka|kruska|breskva|nektarina|sljiva|grozdje|pomorandza|mandarina|limun|ananas|avokado|paradajz|krastavac|paprika|krompir|luk|sargarepa|kupus)( |$)', NULL, 40, 0.8800)
) AS rule(
    type_code,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
JOIN app.product_type AS type ON type.code = rule.type_code;

INSERT INTO app.retailer_product_type (
    retailer_product_id,
    product_type_id,
    confidence,
    assignment_source,
    evidence
)
SELECT product.id,
       matched.product_type_id,
       matched.confidence,
       'NAME_RULE',
       'RULE:' || matched.rule_id
FROM app.retailer_product AS product
JOIN LATERAL (
    SELECT rule.id AS rule_id,
           rule.product_type_id,
           rule.confidence
    FROM app.product_type_rule AS rule
    WHERE rule.active = TRUE
      AND (rule.retailer_id IS NULL
           OR rule.retailer_id = product.retailer_id)
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
) AS matched ON TRUE;

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
WHERE choice.product_family_id = family.id;

CREATE INDEX idx_product_type_alias_resolution
    ON app.product_type_alias (priority, normalized_alias, product_type_id);

CREATE INDEX idx_product_type_rule_retailer
    ON app.product_type_rule (retailer_id, active, priority);

CREATE INDEX idx_retailer_product_type_lookup
    ON app.retailer_product_type (product_type_id, retailer_product_id);

CREATE INDEX idx_product_family_type
    ON app.product_family (product_type_id)
    WHERE product_type_id IS NOT NULL;
