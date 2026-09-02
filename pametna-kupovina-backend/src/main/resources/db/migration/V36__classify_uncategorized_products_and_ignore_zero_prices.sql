-- Neki izvori, trenutno pre svega Maxi, ne šalju kategoriju proizvoda.
-- Pravila ispod služe samo kao kontrolisan fallback. Autoritativna izvorna
-- kategorija i ručno pregledana dodela uvek imaju prednost.
CREATE TABLE app.product_category_rule (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id BIGINT,
    product_category_id BIGINT NOT NULL,
    name_pattern VARCHAR(1000) NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 100,
    confidence NUMERIC(5, 4) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_category_rule_retailer
        FOREIGN KEY (retailer_id) REFERENCES app.retailer (id),
    CONSTRAINT fk_product_category_rule_category
        FOREIGN KEY (product_category_id) REFERENCES app.product_category (id),
    CONSTRAINT chk_product_category_rule_pattern
        CHECK (BTRIM(name_pattern) <> ''),
    CONSTRAINT chk_product_category_rule_priority
        CHECK (priority > 0),
    CONSTRAINT chk_product_category_rule_confidence
        CHECK (confidence > 0 AND confidence <= 1)
);

CREATE UNIQUE INDEX uq_product_category_rule_scope_pattern
    ON app.product_category_rule (
        COALESCE(retailer_id, 0),
        product_category_id,
        name_pattern
    );

CREATE INDEX idx_product_category_rule_retailer
    ON app.product_category_rule (retailer_id, active, priority);

-- Kategorije koje se pojavljuju u supermarketima, a nisu deo zvaničnih
-- prehrambenih grupa 1-23. Nepoznat proizvod se ne gura u OTHER kategoriju:
-- ostaje nekategorisan dok ne postoji dovoljno pouzdano pravilo.
INSERT INTO app.product_category (code, name)
VALUES
    ('SPIRITS', 'Žestoka alkoholna pića'),
    ('TOBACCO', 'Duvanski proizvodi'),
    ('PET_CARE', 'Hrana i oprema za kućne ljubimce'),
    ('BOOKS_STATIONERY', 'Knjige, časopisi i školski pribor'),
    ('TOYS', 'Igračke'),
    ('HOME_GARDEN', 'Dom, bašta i sezonski program')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.product_category_rule (
    product_category_id,
    name_pattern,
    priority,
    confidence
)
SELECT category.id,
       rule.name_pattern,
       rule.priority,
       rule.confidence
FROM (
    VALUES
        -- Precizne potkategorije hrane i pića.
        ('MILK', '(^| )(mleko|mlijeko)( |$)', 20, 0.9000),
        ('YOGURT', '(^| )(jogurt|yoghurt|kefir|ayran)( |$)', 20, 0.9000),
        ('YOGURT', '(^| )fermentisani proizvod( |$)', 20, 0.8800),
        ('EGGS', '(^| )jaja( |$)', 20, 0.9200),
        ('BREAD', '(^| )(hleb|baget|zemicka|kifla|lepinja|pecivo|tost)( |$)', 20, 0.8800),
        ('FLOUR', '(^| )(brasno|griz)( |$)', 20, 0.8900),
        ('PASTA', '(^| )(testenina|makarone|spagete|fusili|njoke)( |$)', 20, 0.8800),
        ('RICE', '(^| )pirinac( |$)', 20, 0.9200),
        ('OIL', '(^| )(jestivo ulje|suncokretovo ulje|maslinovo ulje)( |$)', 20, 0.9000),
        ('VINEGAR', '(^| )(sirce|balsamico)( |$)', 20, 0.9000),
        ('COFFEE', '(^| )(kafa|cappuccino|kapucino)( |$)', 20, 0.9000),
        ('WATER', '(^| )(voda|mineralna voda)( |$)', 30, 0.8000),
        ('BEER', '(^| )pivo( |$)', 20, 0.9200),
        ('WINE', '(^| )vino( |$)', 30, 0.8200),
        ('SPIRITS', '(^| )(rakija|vodka|viski|whisky|vinjak|konjak|liker|dzin|gin|rum)( |$)', 20, 0.8800),
        ('DAIRY_EGGS', '(^| )(sir|pavlaka|kajmak|puter|maslac|surutka)( |$)', 30, 0.8400),
        ('MEAT', '(^| )(meso|kobasica|salama|sunka|prsuta|slanina|pasteta|virsla|cevap|pljeskavica|piletina|pileci|pileca)( |$)', 30, 0.8500),
        ('FISH', '(^| )(riba|tuna|sardina|oslic|losos|skusa)( |$)', 30, 0.8600),
        ('FROZEN', '(^| )(smrznut|smrznuta|smrznuti|sladoled)( |$)', 30, 0.8600),
        ('PROCESSED_PRODUCE', '(^| )(ajvar|dzem|marmelada|kompot|kornison|turšija|tursija)( |$)', 30, 0.8500),
        ('FRESH_PRODUCE', '^(banana|jabuka|kruska|breskva|nektarina|sljiva|grozdje|pomorandza|mandarina|limun|ananas|avokado|paradajz|krastavac|paprika|krompir|luk|sargarepa|kupus)( |$)', 40, 0.8200),
        ('SAVOURY_SNACKS', '(^| )(cips|smoki|kokice|kreker|slani stapici|tortilja cips)( |$)', 30, 0.8500),
        ('SWEETS_CEREALS', '(^| )(cokolada|keks|bombone|bananica|vafl|napolitanke|musli|pahuljice|cornflakes)( |$)', 30, 0.8400),
        ('SUGAR_HONEY', '(^| )(secer|med)( |$)', 30, 0.8500),
        ('SALT_SPICES', '(^| )(so|zacin|biber|cimet|origano)( |$)', 30, 0.8400),

        -- Neprehrambene kategorije. Specifični izrazi imaju viši prioritet
        -- da, na primer, "pasta za zube" ne završi među testeninama.
        ('PERSONAL_CARE', '(^| )(pasta za zube|cetkica za zube|sampon|sapun|dezodorans|balzam za kosu|farba za kosu|gel za tusiranje|krema za lice|ulosci)( |$)', 10, 0.9000),
        ('DIAPERS', '(^| )(pelene|pelena)( |$)', 10, 0.9200),
        ('BABY_FOOD', '(^| )(hrana za bebe|kasica|aptamil|bebelac)( |$)', 20, 0.8800),
        ('HOUSEHOLD_CLEANING', '(^| )(deterdzent|omeksivac|izbeljivac|sredstvo za ciscenje|abrazivno sredstvo|tablete za sudove|kapsule za pranje)( |$)', 20, 0.8800),
        ('PAPER_KITCHEN', '(^| )(toalet papir|papirni ubrus|salvete|alu folija|aluminijumska folija|papir za pecenje|kesa za zamrzivac)( |$)', 20, 0.8800),
        ('TOBACCO', '(^| )(cigarete|cigareta|duvan)( |$)', 20, 0.9300),
        ('PET_CARE', '(^| )(hrana za pse|hrana za macke|posip za macke)( |$)', 20, 0.9000),
        ('BOOKS_STATIONERY', '(^| )(knjiga|casopis|sveska|bojanka|album za slicice)( |$)', 30, 0.8500),
        ('TOYS', '(^| )(igracka|puzzle|puzle|lutka|autic)( |$)', 30, 0.8400),
        ('HOME_GARDEN', '(^| )(baterija|bastenski|sijalica|produzni kabl|sveca mirisna)( |$)', 40, 0.7800)
) AS rule(category_code, name_pattern, priority, confidence)
JOIN app.product_category AS category
  ON category.code = rule.category_code
ON CONFLICT DO NOTHING;

-- Backfill se radi samo za proizvode bez dodele. Postojeća izvorna ili
-- ručno potvrđena kategorija se ne menja.
INSERT INTO app.retailer_product_category (
    retailer_product_id,
    product_category_id,
    confidence,
    assignment_source
)
SELECT product.id,
       matched.product_category_id,
       matched.confidence,
       'NAME_PATTERN_RULE'
FROM app.retailer_product AS product
JOIN LATERAL (
    SELECT rule.product_category_id,
           rule.confidence
    FROM app.product_category_rule AS rule
    WHERE rule.active = TRUE
      AND (rule.retailer_id IS NULL
           OR rule.retailer_id = product.retailer_id)
      AND product.normalized_name ~ rule.name_pattern
    ORDER BY rule.priority,
             rule.confidence DESC,
             LENGTH(rule.name_pattern) DESC,
             rule.id
    LIMIT 1
) AS matched ON TRUE
WHERE NOT EXISTS (
    SELECT 1
    FROM app.retailer_product_category AS existing
    WHERE existing.retailer_product_id = product.id
)
ON CONFLICT (retailer_product_id) DO NOTHING;

-- Porodica nasleđuje najčešću i najpouzdaniju kategoriju svojih ponuda.
WITH category_counts AS (
    SELECT product.product_family_id,
           assignment.product_category_id,
           COUNT(*) AS assignment_count,
           MAX(assignment.confidence) AS maximum_confidence
    FROM app.retailer_product AS product
    JOIN app.retailer_product_category AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id IS NOT NULL
    GROUP BY product.product_family_id,
             assignment.product_category_id
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
SET product_category_id = choice.product_category_id,
    updated_at = NOW()
FROM category_choice AS choice
WHERE choice.product_family_id = family.id;

-- Nula u zvaničnom cenovniku je placeholder/neupotrebljiva vrednost. Sirovi
-- red ostaje sačuvan radi audita, ali nula nije minimalna kupovna cena.
WITH valid_minimum AS (
    SELECT product.product_family_id,
           product.retailer_id,
           MIN(
               CASE
                   WHEN offer.discounted_price > 0
                       THEN offer.discounted_price
                   WHEN offer.regular_price > 0
                       THEN offer.regular_price
               END
           ) AS minimum_effective_price
    FROM app.current_price_offer AS offer
    JOIN app.retailer_product AS product
      ON product.id = offer.retailer_product_id
    WHERE product.product_family_id IS NOT NULL
    GROUP BY product.product_family_id,
             product.retailer_id
)
UPDATE app.product_retailer_presence AS presence
SET minimum_effective_price = minimum.minimum_effective_price,
    updated_at = NOW()
FROM valid_minimum AS minimum
WHERE minimum.product_family_id = presence.product_family_id
  AND minimum.retailer_id = presence.retailer_id;
