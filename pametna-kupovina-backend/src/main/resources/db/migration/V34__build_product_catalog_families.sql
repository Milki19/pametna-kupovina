-- Canonical proizvod ostaje jedna konkretna GTIN varijanta. Porodica proizvoda
-- grupiše semantički isti proizvod bez brisanja ili prepisivanja barkodova.
CREATE TABLE app.brand (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    normalized_name VARCHAR(200) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    private_label BOOLEAN NOT NULL DEFAULT FALSE,
    owner_retailer_id BIGINT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_brand_owner_retailer
        FOREIGN KEY (owner_retailer_id) REFERENCES app.retailer (id),
    CONSTRAINT chk_brand_normalized_name_not_blank
        CHECK (BTRIM(normalized_name) <> ''),
    CONSTRAINT chk_brand_display_name_not_blank
        CHECK (BTRIM(display_name) <> '')
);

CREATE TABLE app.brand_alias (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand_id BIGINT NOT NULL,
    normalized_alias VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_brand_alias_brand
        FOREIGN KEY (brand_id) REFERENCES app.brand (id) ON DELETE CASCADE,
    CONSTRAINT chk_brand_alias_not_blank
        CHECK (BTRIM(normalized_alias) <> '')
);

ALTER TABLE app.canonical_product ADD COLUMN brand_id BIGINT;
ALTER TABLE app.retailer_product ADD COLUMN brand_id BIGINT;

ALTER TABLE app.canonical_product
    ADD CONSTRAINT fk_canonical_product_brand
        FOREIGN KEY (brand_id) REFERENCES app.brand (id);
ALTER TABLE app.retailer_product
    ADD CONSTRAINT fk_retailer_product_brand
        FOREIGN KEY (brand_id) REFERENCES app.brand (id);

-- Najpre se registruju svi postojeći deklarisani brendovi. Normalizovana
-- vrednost je identitet, a originalni tekst ostaje display naziv.
INSERT INTO app.brand (normalized_name, display_name)
SELECT normalized_name, MIN(display_name)
FROM (
    SELECT LOWER(REGEXP_REPLACE(BTRIM(brand), '[^[:alnum:]]+', ' ', 'g'))
               AS normalized_name,
           BTRIM(brand) AS display_name
    FROM app.canonical_product
    WHERE NULLIF(BTRIM(brand), '') IS NOT NULL
    UNION ALL
    SELECT LOWER(REGEXP_REPLACE(BTRIM(brand), '[^[:alnum:]]+', ' ', 'g')),
           BTRIM(brand)
    FROM app.retailer_product
    WHERE NULLIF(BTRIM(brand), '') IS NOT NULL
) AS source_brand
WHERE normalized_name <> ''
GROUP BY normalized_name
ON CONFLICT (normalized_name) DO NOTHING;

-- Privatne robne marke se vode kao brendovi sa vlasnikom. Ovo ne znači da
-- se proizvod prodaje samo kod vlasnika; stvarna prisutnost dolazi iz ponuda.
INSERT INTO app.brand (
    normalized_name,
    display_name,
    private_label,
    owner_retailer_id
)
SELECT private_brand.normalized_name,
       private_brand.display_name,
       TRUE,
       retailer.id
FROM (
    VALUES
        ('premia', 'Premia', 'MAXI'),
        ('k plus', 'K Plus', 'IDEA_RODA'),
        ('pilos', 'Pilos', 'LIDL')
) AS private_brand(normalized_name, display_name, retailer_code)
JOIN app.retailer AS retailer ON retailer.code = private_brand.retailer_code
ON CONFLICT (normalized_name)
DO UPDATE SET
    display_name = EXCLUDED.display_name,
    private_label = TRUE,
    owner_retailer_id = EXCLUDED.owner_retailer_id,
    updated_at = NOW();

INSERT INTO app.brand_alias (brand_id, normalized_alias)
SELECT id, normalized_name FROM app.brand
ON CONFLICT (normalized_alias) DO NOTHING;

INSERT INTO app.brand_alias (brand_id, normalized_alias)
SELECT brand.id, alias.normalized_alias
FROM (
    VALUES
        ('premia', 'premia'),
        ('k plus', 'kplus'),
        ('k plus', 'k plus'),
        ('pilos', 'pilos')
) AS alias(brand_name, normalized_alias)
JOIN app.brand AS brand ON brand.normalized_name = alias.brand_name
ON CONFLICT (normalized_alias) DO NOTHING;

UPDATE app.canonical_product AS product
SET brand_id = (
    SELECT brand.id
    FROM app.brand
    WHERE brand.normalized_name = LOWER(REGEXP_REPLACE(
              BTRIM(product.brand), '[^[:alnum:]]+', ' ', 'g'
          ))
       OR (
            NULLIF(BTRIM(product.brand), '') IS NULL
            AND (
                (brand.normalized_name = 'premia'
                    AND product.normalized_name ~ '(^| )premia( |$)')
                OR (brand.normalized_name = 'k plus'
                    AND product.normalized_name ~ '(^| )k ?plus( |$)')
                OR (brand.normalized_name = 'pilos'
                    AND product.normalized_name ~ '(^| )pilos( |$)')
            )
       )
    ORDER BY CASE WHEN NULLIF(BTRIM(product.brand), '') IS NOT NULL
                  THEN 1 ELSE 2 END,
             brand.id
    LIMIT 1
)
WHERE product.brand_id IS NULL;

UPDATE app.retailer_product AS product
SET brand_id = (
    SELECT brand.id
    FROM app.brand
    WHERE brand.normalized_name = LOWER(REGEXP_REPLACE(
              BTRIM(product.brand), '[^[:alnum:]]+', ' ', 'g'
          ))
       OR (
            NULLIF(BTRIM(product.brand), '') IS NULL
            AND (
                (brand.normalized_name = 'premia'
                    AND product.normalized_name ~ '(^| )premia( |$)')
                OR (brand.normalized_name = 'k plus'
                    AND product.normalized_name ~ '(^| )k ?plus( |$)')
                OR (brand.normalized_name = 'pilos'
                    AND product.normalized_name ~ '(^| )pilos( |$)')
            )
       )
    ORDER BY CASE WHEN NULLIF(BTRIM(product.brand), '') IS NOT NULL
                  THEN 1 ELSE 2 END,
             brand.id
    LIMIT 1
)
WHERE product.brand_id IS NULL;

CREATE INDEX idx_canonical_product_brand
    ON app.canonical_product (brand_id) WHERE brand_id IS NOT NULL;
CREATE INDEX idx_retailer_product_brand
    ON app.retailer_product (brand_id) WHERE brand_id IS NOT NULL;

-- Šira kontrolisana taksonomija prati numeričke grupe 1-23 iz zvaničnih
-- cenovnika. Postojeće precizne kategorije ostaju deca širih grupa.
INSERT INTO app.product_category (code, name)
VALUES
    ('DAIRY_EGGS', 'Mlečni proizvodi i jaja'),
    ('BEVERAGES', 'Bezalkoholna pića, kafa i čaj'),
    ('FRESH_PRODUCE', 'Sveže voće i povrće'),
    ('PROCESSED_PRODUCE', 'Prerađeno voće i povrće'),
    ('LEGUMES', 'Mahunarke'),
    ('FROZEN', 'Smrznuti proizvodi'),
    ('MEAT', 'Sveže i prerađeno meso'),
    ('FISH', 'Sveža i prerađena riba'),
    ('SAVOURY_SNACKS', 'Slani konditori'),
    ('SWEETS_CEREALS', 'Slatki konditori i cerealije'),
    ('SUGAR_HONEY', 'Šećer i med'),
    ('OILS_FATS', 'Ulja i masti'),
    ('RICE', 'Pirinač'),
    ('SALT_SPICES', 'So i začini'),
    ('HOUSEHOLD_CLEANING', 'Kućna hemija'),
    ('PAPER_KITCHEN', 'Papirna i kuhinjska galanterija'),
    ('PERSONAL_CARE', 'Lična higijena i kozmetika'),
    ('BABY_FOOD', 'Hrana za bebe'),
    ('DIAPERS', 'Pelene')
ON CONFLICT (code) DO NOTHING;

UPDATE app.product_category AS child
SET parent_id = parent.id,
    updated_at = NOW()
FROM app.product_category AS parent
WHERE (child.code, parent.code) IN (
    ('MILK', 'DAIRY_EGGS'),
    ('YOGURT', 'DAIRY_EGGS'),
    ('EGGS', 'DAIRY_EGGS'),
    ('WATER', 'BEVERAGES'),
    ('COFFEE', 'BEVERAGES'),
    ('SUGAR', 'SUGAR_HONEY'),
    ('OIL', 'OILS_FATS'),
    ('SALT', 'SALT_SPICES')
);

INSERT INTO app.product_category_source_mapping (
    retailer_id,
    source_category_code,
    product_category_id,
    confidence
)
SELECT NULL, mapping.source_code, category.id, 0.9800
FROM (
    VALUES
        ('1', 'DAIRY_EGGS'), ('2', 'BEVERAGES'),
        ('3', 'FRESH_PRODUCE'), ('4', 'PROCESSED_PRODUCE'),
        ('5', 'BREAD'), ('6', 'LEGUMES'), ('7', 'FROZEN'),
        ('8', 'MEAT'), ('9', 'FISH'), ('10', 'SAVOURY_SNACKS'),
        ('11', 'SWEETS_CEREALS'), ('12', 'SUGAR_HONEY'),
        ('13', 'FLOUR'), ('14', 'PASTA'), ('15', 'OILS_FATS'),
        ('16', 'VINEGAR'), ('17', 'RICE'), ('18', 'SALT_SPICES'),
        ('19', 'HOUSEHOLD_CLEANING'), ('20', 'PAPER_KITCHEN'),
        ('21', 'PERSONAL_CARE'), ('22', 'BABY_FOOD'),
        ('23', 'DIAPERS')
) AS mapping(source_code, category_code)
JOIN app.product_category AS category ON category.code = mapping.category_code
ON CONFLICT (retailer_id, source_category_code) DO UPDATE SET
    product_category_id = EXCLUDED.product_category_id,
    confidence = EXCLUDED.confidence;

-- Numeričke šifre sada daju kompletnu široku kategoriju. Ručno pregledane
-- dodele se ne menjaju.
INSERT INTO app.retailer_product_category AS assignment (
    retailer_product_id,
    product_category_id,
    confidence,
    assignment_source
)
SELECT product.id,
       mapping.product_category_id,
       mapping.confidence,
       'SOURCE_CATEGORY_CODE'
FROM app.retailer_product AS product
JOIN app.product_category_source_mapping AS mapping
  ON mapping.retailer_id IS NULL
 AND UPPER(BTRIM(product.category_code)) = mapping.source_category_code
ON CONFLICT (retailer_product_id) DO UPDATE SET
    product_category_id = EXCLUDED.product_category_id,
    confidence = EXCLUDED.confidence,
    assignment_source = EXCLUDED.assignment_source,
    updated_at = NOW()
WHERE assignment.reviewed = FALSE;

-- Za podgrupe kao jogurt/mleko/voda/kafa naziv je precizniji od široke
-- izvorne grupe, ali samo kada je alias dete te iste široke kategorije.
UPDATE app.retailer_product_category AS assignment
SET product_category_id = specific.product_category_id,
    confidence = specific.confidence,
    assignment_source = 'SOURCE_CATEGORY_AND_NAME',
    updated_at = NOW()
FROM app.retailer_product AS product
JOIN LATERAL (
    SELECT alias.product_category_id,
           CASE WHEN product.normalized_name = alias.normalized_alias
                THEN 0.9700 ELSE 0.9300 END AS confidence
    FROM app.product_category_alias AS alias
    JOIN app.product_category AS candidate
      ON candidate.id = alias.product_category_id
    JOIN app.product_category AS current_parent
      ON current_parent.id = (
          SELECT product_category_id
          FROM app.retailer_product_category
          WHERE retailer_product_id = product.id
      )
    WHERE candidate.parent_id = current_parent.id
      AND (product.normalized_name = alias.normalized_alias
           OR product.normalized_name LIKE alias.normalized_alias || ' %')
    ORDER BY LENGTH(alias.normalized_alias) DESC, alias.id
    LIMIT 1
) AS specific ON TRUE
WHERE assignment.retailer_product_id = product.id
  AND assignment.reviewed = FALSE;

CREATE TABLE app.product_family (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    family_key VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(500) NOT NULL,
    normalized_name VARCHAR(500) NOT NULL,
    brand_id BIGINT,
    product_category_id BIGINT,
    quantity_value NUMERIC(14, 4),
    base_unit VARCHAR(20),
    review_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_family_brand
        FOREIGN KEY (brand_id) REFERENCES app.brand (id),
    CONSTRAINT fk_product_family_category
        FOREIGN KEY (product_category_id) REFERENCES app.product_category (id),
    CONSTRAINT chk_product_family_key_not_blank CHECK (BTRIM(family_key) <> ''),
    CONSTRAINT chk_product_family_name_not_blank CHECK (BTRIM(display_name) <> ''),
    CONSTRAINT chk_product_family_normalized_not_blank CHECK (BTRIM(normalized_name) <> ''),
    CONSTRAINT chk_product_family_quantity CHECK (quantity_value IS NULL OR quantity_value > 0),
    CONSTRAINT chk_product_family_review_status
        CHECK (review_status IN ('ACTIVE', 'REVIEW_REQUIRED', 'REJECTED'))
);

CREATE TABLE app.product_family_member (
    family_id BIGINT NOT NULL,
    canonical_product_id BIGINT NOT NULL UNIQUE,
    relation_type VARCHAR(40) NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL,
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (family_id, canonical_product_id),

    CONSTRAINT fk_product_family_member_family
        FOREIGN KEY (family_id) REFERENCES app.product_family (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_family_member_canonical
        FOREIGN KEY (canonical_product_id) REFERENCES app.canonical_product (id),
    CONSTRAINT chk_product_family_member_relation
        CHECK (relation_type IN ('SINGLE_GTIN', 'SEMANTIC_SIGNATURE', 'MANUAL')),
    CONSTRAINT chk_product_family_member_confidence
        CHECK (confidence >= 0 AND confidence <= 1)
);

ALTER TABLE app.retailer_product ADD COLUMN product_family_id BIGINT;
ALTER TABLE app.retailer_product
    ADD CONSTRAINT fk_retailer_product_family
        FOREIGN KEY (product_family_id) REFERENCES app.product_family (id);

-- Potpis uključuje naziv, normalizovan brend, količinu i jedinicu. Dva GTIN-a
-- sa istim potpisom su varijante iste porodice, ali oba canonical reda ostaju.
INSERT INTO app.product_family (
    family_key, display_name, normalized_name, brand_id,
    quantity_value, base_unit, review_status
)
SELECT 'SEM:' || MD5(signature),
       MIN(name),
       normalized_name,
       brand_id,
       quantity_value,
       base_unit,
       CASE WHEN COUNT(DISTINCT barcode) FILTER (WHERE barcode IS NOT NULL) > 1
            THEN 'REVIEW_REQUIRED' ELSE 'ACTIVE' END
FROM (
    SELECT product.name,
           product.normalized_name,
           product.brand_id,
           product.quantity_value,
           product.base_unit,
           product.barcode,
           product.normalized_name || '|' ||
               COALESCE(brand.normalized_name, '') || '|' ||
               COALESCE(product.quantity_value::TEXT, '') || '|' ||
               COALESCE(product.base_unit, '') AS signature
    FROM app.canonical_product AS product
    LEFT JOIN app.brand AS brand ON brand.id = product.brand_id
) AS signature_rows
GROUP BY signature, normalized_name, brand_id, quantity_value, base_unit
ON CONFLICT (family_key) DO NOTHING;

INSERT INTO app.product_family_member (
    family_id, canonical_product_id, relation_type, confidence
)
SELECT family.id,
       product.id,
       CASE WHEN family.review_status = 'REVIEW_REQUIRED'
            THEN 'SEMANTIC_SIGNATURE' ELSE 'SINGLE_GTIN' END,
       CASE WHEN family.review_status = 'REVIEW_REQUIRED'
            THEN 0.9200 ELSE 1.0000 END
FROM app.canonical_product AS product
LEFT JOIN app.brand AS brand ON brand.id = product.brand_id
JOIN app.product_family AS family
  ON family.family_key = 'SEM:' || MD5(
      product.normalized_name || '|' ||
      COALESCE(brand.normalized_name, '') || '|' ||
      COALESCE(product.quantity_value::TEXT, '') || '|' ||
      COALESCE(product.base_unit, '')
  )
ON CONFLICT (canonical_product_id) DO NOTHING;

-- Proizvodi bez validnog GTIN-a takođe dobijaju porodicu. Time generički
-- unos poput "hleb" može da pronađe ponude više trgovaca.
INSERT INTO app.product_family (
    family_key, display_name, normalized_name, brand_id,
    quantity_value, base_unit, review_status
)
SELECT 'SEM:' || MD5(signature),
       MIN(name),
       normalized_name,
       brand_id,
       quantity_value,
       base_unit,
       'ACTIVE'
FROM (
    SELECT product.name,
           product.normalized_name,
           product.brand_id,
           product.quantity_value,
           product.base_unit,
           product.normalized_name || '|' ||
               COALESCE(brand.normalized_name, '') || '|' ||
               COALESCE(product.quantity_value::TEXT, '') || '|' ||
               COALESCE(product.base_unit, '') AS signature
    FROM app.retailer_product AS product
    LEFT JOIN app.brand AS brand ON brand.id = product.brand_id
    WHERE product.canonical_product_id IS NULL
) AS signature_rows
WHERE NULLIF(BTRIM(normalized_name), '') IS NOT NULL
GROUP BY signature, normalized_name, brand_id, quantity_value, base_unit
ON CONFLICT (family_key) DO NOTHING;

UPDATE app.retailer_product AS product
SET product_family_id = family.id
FROM app.brand AS brand,
     app.product_family AS family
WHERE brand.id = product.brand_id
  AND family.family_key = 'SEM:' || MD5(
      product.normalized_name || '|' || brand.normalized_name || '|' ||
      COALESCE(product.quantity_value::TEXT, '') || '|' ||
      COALESCE(product.base_unit, '')
  );

UPDATE app.retailer_product AS product
SET product_family_id = family.id
FROM app.product_family AS family
WHERE product.brand_id IS NULL
  AND family.family_key = 'SEM:' || MD5(
      product.normalized_name || '||' ||
      COALESCE(product.quantity_value::TEXT, '') || '|' ||
      COALESCE(product.base_unit, '')
  );

-- Canonical veza ima poslednju reč ako su izvorni naziv ili brend drugačije
-- zapisani u cenovniku.
UPDATE app.retailer_product AS product
SET product_family_id = member.family_id
FROM app.product_family_member AS member
WHERE member.canonical_product_id = product.canonical_product_id;

WITH category_counts AS (
    SELECT product.product_family_id,
           assignment.product_category_id,
           COUNT(*) AS assignment_count,
           MAX(assignment.confidence) AS maximum_confidence
    FROM app.retailer_product AS product
    JOIN app.retailer_product_category AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id IS NOT NULL
    GROUP BY product.product_family_id, assignment.product_category_id
), category_choice AS (
    SELECT DISTINCT ON (product_family_id)
           product_family_id,
           product_category_id
    FROM category_counts
    ORDER BY product_family_id,
             assignment_count DESC,
             maximum_confidence DESC,
             product_category_id
)
UPDATE app.product_family AS family
SET product_category_id = category.product_category_id,
    updated_at = NOW()
FROM category_choice AS category
WHERE category.product_family_id = family.id;

CREATE TABLE app.product_identity_candidate (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    left_canonical_product_id BIGINT NOT NULL,
    right_canonical_product_id BIGINT NOT NULL,
    suggested_family_id BIGINT NOT NULL,
    score NUMERIC(5, 4) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_identity_candidate_left
        FOREIGN KEY (left_canonical_product_id) REFERENCES app.canonical_product (id),
    CONSTRAINT fk_identity_candidate_right
        FOREIGN KEY (right_canonical_product_id) REFERENCES app.canonical_product (id),
    CONSTRAINT fk_identity_candidate_family
        FOREIGN KEY (suggested_family_id) REFERENCES app.product_family (id),
    CONSTRAINT uq_identity_candidate_pair
        UNIQUE (left_canonical_product_id, right_canonical_product_id),
    CONSTRAINT chk_identity_candidate_order
        CHECK (left_canonical_product_id < right_canonical_product_id),
    CONSTRAINT chk_identity_candidate_score CHECK (score >= 0 AND score <= 1),
    CONSTRAINT chk_identity_candidate_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED'))
);

INSERT INTO app.product_identity_candidate (
    left_canonical_product_id,
    right_canonical_product_id,
    suggested_family_id,
    score,
    reason
)
SELECT left_member.canonical_product_id,
       right_member.canonical_product_id,
       left_member.family_id,
       0.9200,
       'Isti normalizovan naziv, brend, količina i jedinica; različit barkod.'
FROM app.product_family_member AS left_member
JOIN app.product_family_member AS right_member
  ON right_member.family_id = left_member.family_id
 AND right_member.canonical_product_id > left_member.canonical_product_id
JOIN app.canonical_product AS left_product
  ON left_product.id = left_member.canonical_product_id
JOIN app.canonical_product AS right_product
  ON right_product.id = right_member.canonical_product_id
WHERE left_product.barcode IS NOT NULL
  AND right_product.barcode IS NOT NULL
  AND left_product.barcode <> right_product.barcode
ON CONFLICT (left_canonical_product_id, right_canonical_product_id)
DO NOTHING;

-- Mala agregirana projekcija služi pretrazi i UI oznakama. Ne umnožava
-- format-level cenu na svaku prodavnicu.
CREATE TABLE app.product_retailer_presence (
    product_family_id BIGINT NOT NULL,
    retailer_id BIGINT NOT NULL,
    first_seen_date DATE NOT NULL,
    last_seen_date DATE NOT NULL,
    latest_price_date DATE NOT NULL,
    current_offer_count INTEGER NOT NULL,
    store_count INTEGER NOT NULL,
    format_count INTEGER NOT NULL,
    minimum_effective_price NUMERIC(12, 2),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (product_family_id, retailer_id),

    CONSTRAINT fk_product_presence_family
        FOREIGN KEY (product_family_id) REFERENCES app.product_family (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_presence_retailer
        FOREIGN KEY (retailer_id) REFERENCES app.retailer (id),
    CONSTRAINT chk_product_presence_counts
        CHECK (current_offer_count >= 0 AND store_count >= 0 AND format_count >= 0),
    CONSTRAINT chk_product_presence_dates
        CHECK (first_seen_date <= last_seen_date AND latest_price_date <= last_seen_date)
);

INSERT INTO app.product_retailer_presence (
    product_family_id, retailer_id, first_seen_date, last_seen_date,
    latest_price_date, current_offer_count, store_count, format_count,
    minimum_effective_price
)
SELECT product.product_family_id,
       product.retailer_id,
       MIN(offer.first_seen_date),
       MAX(offer.last_seen_date),
       MAX(offer.price_date),
       COUNT(*)::INTEGER,
       COUNT(DISTINCT offer.store_id) FILTER (WHERE offer.store_id IS NOT NULL)::INTEGER,
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name)))
           FILTER (WHERE NULLIF(BTRIM(offer.retailer_format_name), '') IS NOT NULL)::INTEGER,
       MIN(COALESCE(offer.discounted_price, offer.regular_price))
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product ON product.id = offer.retailer_product_id
WHERE product.product_family_id IS NOT NULL
GROUP BY product.product_family_id, product.retailer_id;

CREATE INDEX idx_product_family_name_trgm
    ON app.product_family USING GIN (normalized_name public.gin_trgm_ops);
CREATE INDEX idx_product_family_category
    ON app.product_family (product_category_id, id);
CREATE INDEX idx_product_family_member_family
    ON app.product_family_member (family_id, canonical_product_id);
CREATE INDEX idx_retailer_product_family
    ON app.retailer_product (product_family_id, retailer_id);
CREATE INDEX idx_product_presence_retailer
    ON app.product_retailer_presence (retailer_id, latest_price_date DESC);
CREATE INDEX idx_identity_candidate_status
    ON app.product_identity_candidate (status, score DESC, id);
