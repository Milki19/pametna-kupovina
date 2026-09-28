-- The owner's list of look-alikes still held pairs that are plainly one
-- product, only written two ways (V99 decided a pair only when both names
-- left exactly the same words):
--   "Pringles Hot&Spicy 165g"         / "CIPS HOT SPICY PRINGLES 165G"
--   "Milka straw cheesecake 300g #12"  / "COKOLADA MILKA STRAWBERRY CHEESECAKE 300G"
--   "PAST.ARGETA JUNIOR KOKOKREM 95g"  / "PAŠTETA ARGETA JUNIOR 95G KOKO KREM"
--   "KREKERI JAFFA TAK CLASSIC 100G"   / "TAK 100G CLASSIC 1/24 JAFFA"
--   "DEO ROLL-ON PROTECTION&CARE NIVEA 50ML" / "NIVEA ROLL ON 50ml PROTECT&CARE"
-- Three things kept them apart: a word cut short ("straw", "past",
-- "protect"), two words written as one ("kokokrem"), and one chain naming
-- what kind of product it is ("čips", "čokolada", "krekeri", "deo") where the
-- other does not. With those read the same, each name says nothing the other
-- does not, and the pair is decided as V99 decides one.
--
-- Numbers other than the size and marks of one or two letters must still be
-- the same: "SPF0" is not "SPF6", "+33%" is not "+20%", "M" deodorant is not
-- "W". A pair where one name still says something the other does not stays
-- on the list: "MEN" deodorant is not the plain one, herring "u paradajz
-- sosu" is not plain herring.

-- Words of a catalogue language the rules below read specially:
-- KIND only says what kind of product it is ("čips", "deo"); one name having
-- it where the other has none tells nothing apart. FILLER is a short word
-- that says nothing ("u", "sa").
CREATE TABLE app.product_name_word (
    language_code VARCHAR(35) NOT NULL,
    word VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,
    PRIMARY KEY (language_code, word),
    CONSTRAINT chk_product_name_word CHECK (word ~ '^[a-z]+$'),
    CONSTRAINT chk_product_name_word_role CHECK (role IN ('KIND', 'FILLER'))
);

INSERT INTO app.product_name_word (language_code, word, role)
SELECT app.default_language(), word, role
FROM (
    VALUES
        ('cips', 'KIND'), ('cokolada', 'KIND'), ('cokoladica', 'KIND'),
        ('krekeri', 'KIND'), ('kreker', 'KIND'), ('pasteta', 'KIND'),
        ('deo', 'KIND'), ('dezodorans', 'KIND'), ('keks', 'KIND'),
        ('biskvit', 'KIND'), ('bombone', 'KIND'), ('bombona', 'KIND'),
        ('u', 'FILLER'), ('i', 'FILLER'), ('s', 'FILLER'), ('sa', 'FILLER'),
        ('za', 'FILLER'), ('od', 'FILLER'), ('na', 'FILLER'), ('po', 'FILLER'),
        ('do', 'FILLER'), ('uz', 'FILLER')
) AS name_word(word, role);

-- What the words leave out and still tells two products apart: a number
-- that is not the size ("SPF0" and "SPF6", "+33%" and "+20%") and a single
-- letter ("M" and "W" deodorant). A chain's stock code ("#12") and a case
-- count ("1/24") are not part of the product, and "}" or "|" is a letter some
-- chains' files lost ("pile}a" is "pileća"). Both names must carry the same
-- ones.
CREATE OR REPLACE FUNCTION app.product_name_marks(product_name TEXT, filler_words TEXT[])
RETURNS TEXT[]
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH cleaned AS (
        SELECT REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
                   REGEXP_REPLACE(
                       REGEXP_REPLACE(LOWER(COALESCE(product_name, '')), '[][{}|~`@^\\]', 'q', 'g'),
                       '([0-9]),([0-9])', '\1.\2', 'g'),
                   '[0-9]+(\.[0-9]+)?\s*(ml|l|lit|g|gr|kg|cl|dl)(?![[:alpha:]])', ' ', 'g'),
                   '#\s*[0-9]+', ' ', 'g'),
                   '[0-9]+\s*/\s*[0-9]+', ' ', 'g'),
                   '[0-9]+\s*x(?![[:alpha:]])|(?<![[:alpha:]])x\s*[0-9]+', ' ', 'g') AS text
    )
    SELECT COALESCE(ARRAY_AGG(DISTINCT mark ORDER BY mark), '{}')
    FROM (
        SELECT TRIM_SCALE(number[1]::NUMERIC)::TEXT AS mark
        FROM cleaned, REGEXP_MATCHES(cleaned.text, '([0-9]+(\.[0-9]+)?)', 'g') AS number
        UNION ALL
        SELECT word
        FROM cleaned, REGEXP_SPLIT_TO_TABLE(app.fold_match_text(cleaned.text), '[^a-z0-9]+') AS word
        WHERE word ~ '^[a-z]$'
          AND word NOT IN ('g', 'l', 'x')
          AND word <> ALL (filler_words)
    ) AS marks
$$;

-- A word of one name is found in the other: the same word, one cut short
-- from the other (at least four letters, so "men" is not "mentol"), or two
-- words written as one ("kokokrem" and "koko krem").
CREATE OR REPLACE FUNCTION app.name_word_found(word TEXT, own_words TEXT[], other_words TEXT[])
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT EXISTS (
               SELECT 1
               FROM UNNEST(other_words) AS other
               WHERE other = word
                  OR (LEAST(LENGTH(other), LENGTH(word)) >= 4
                      AND (other LIKE word || '%' OR word LIKE other || '%'))
           )
        OR EXISTS (
               SELECT 1
               FROM UNNEST(other_words) AS other
               CROSS JOIN UNNEST(own_words) AS own
               WHERE own <> word
                 AND (other = own || word OR other = word || own)
           )
        OR EXISTS (
               SELECT 1
               FROM UNNEST(other_words) AS first_part
               CROSS JOIN UNNEST(other_words) AS second_part
               WHERE first_part <> second_part
                 AND word = first_part || second_part
           )
$$;

-- Two names say the same thing: brand shortenings ("NIV") and a kind word
-- only one of them has set aside, every word of each is found in the other.
CREATE OR REPLACE FUNCTION app.names_are_one_product(
    left_words TEXT[],
    right_words TEXT[],
    brand_name TEXT,
    kind_words TEXT[]
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH brand AS (
        SELECT COALESCE(ARRAY_AGG(brand_word), '{}') AS words
        FROM REGEXP_SPLIT_TO_TABLE(app.fold_match_text(COALESCE(brand_name, '')), '[^a-z0-9]+')
            AS brand_word
        WHERE brand_word <> ''
    ), side AS (
        SELECT input.side_name,
               ARRAY(
                   SELECT word
                   FROM UNNEST(input.name_words) AS word
                   WHERE NOT EXISTS (
                       SELECT 1 FROM UNNEST(brand.words) AS brand_word
                       WHERE brand_word LIKE word || '%'
                   )
               ) AS words
        FROM brand,
             (VALUES ('left', left_words), ('right', right_words)) AS input(side_name, name_words)
    ), kind AS (
        SELECT side.side_name,
               side.words,
               ARRAY(
                   SELECT word
                   FROM UNNEST(side.words) AS word
                   WHERE EXISTS (
                       SELECT 1 FROM UNNEST(kind_words) AS kind_word
                       WHERE kind_word = word
                          OR (LENGTH(word) >= 4 AND kind_word LIKE word || '%')
                   )
               ) AS kind_found
        FROM side
    ), compared AS (
        SELECT this.side_name,
               -- A kind word goes only when the other name has none.
               CASE WHEN CARDINALITY(other.kind_found) = 0
                    THEN ARRAY(SELECT UNNEST(this.words) EXCEPT SELECT UNNEST(this.kind_found))
                    ELSE this.words
               END AS words
        FROM kind AS this
        JOIN kind AS other ON other.side_name <> this.side_name
    )
    SELECT BOOL_AND(CARDINALITY(this.words) > 0)
       AND BOOL_AND(NOT EXISTS (
               SELECT 1
               FROM UNNEST(this.words) AS word
               WHERE NOT app.name_word_found(word, this.words, other.words)
           ))
    FROM compared AS this
    JOIN compared AS other ON other.side_name <> this.side_name
$$;

CREATE OR REPLACE FUNCTION app.refresh_product_merge_suggestions()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DROP TABLE IF EXISTS pg_temp.merge_family;
    DROP TABLE IF EXISTS pg_temp.merge_candidate;

    CREATE TEMP TABLE merge_family ON COMMIT DROP AS
    SELECT family.id,
           SUBSTRING(family.family_key FROM 4) AS match_key,
           family.normalized_name,
           family.brand_id,
           brand.display_name AS brand_name,
           family.quantity_value,
           family.base_unit,
           family.product_type_id,
           app.product_distinguishing_words(family.display_name, brand.display_name) AS words,
           app.product_package_material(family.display_name) AS material,
           app.family_base_package_count(family.id) AS package_count,
           (SELECT COUNT(*) FROM app.product_retailer_presence AS presence
            WHERE presence.product_family_id = family.id) AS chain_count,
           family.display_name,
           language.code AS language_code
    FROM app.product_family AS family
    JOIN app.brand AS brand
      ON brand.id = family.brand_id
    -- The language of the chains that sell it.
    CROSS JOIN LATERAL (
        SELECT MIN(market.default_language) AS code
        FROM app.product_retailer_presence AS presence
        JOIN app.retailer AS retailer ON retailer.id = presence.retailer_id
        JOIN app.market AS market ON market.id = retailer.market_id
        WHERE presence.product_family_id = family.id
    ) AS language
    WHERE family.family_key LIKE 'MK:%'
      AND family.quantity_value IS NOT NULL
      AND EXISTS (
          SELECT 1
          FROM app.product_retailer_presence AS presence
          WHERE presence.product_family_id = family.id
      );

    CREATE INDEX ON merge_family (brand_id, quantity_value, base_unit);
    ANALYZE merge_family;

    -- Every pair close enough to ask about, same test as before (V80).
    CREATE TEMP TABLE merge_candidate ON COMMIT DROP AS
    SELECT left_family.id AS left_id,
           right_family.id AS right_id,
           left_family.match_key AS left_key,
           right_family.match_key AS right_key,
           left_family.chain_count AS left_chain_count,
           right_family.chain_count AS right_chain_count,
           left_family.normalized_name AS left_name,
           right_family.normalized_name AS right_name,
           -- Read only for pairs that got this far: marks are slow to read
           -- for every product.
           app.product_name_marks(left_family.display_name, filler.words)
               = app.product_name_marks(right_family.display_name, filler.words)
           AND (
               left_family.words = right_family.words
               OR (
                   left_family.language_code = right_family.language_code
                   AND app.names_are_one_product(
                       left_family.words,
                       right_family.words,
                       left_family.brand_name,
                       ARRAY(
                           SELECT name_word.word FROM app.product_name_word AS name_word
                           WHERE name_word.language_code = left_family.language_code
                             AND name_word.role = 'KIND'
                       )
                   )
               )
           ) AS exact_match
    FROM merge_family AS left_family
    JOIN merge_family AS right_family
      ON right_family.brand_id = left_family.brand_id
     AND right_family.quantity_value = left_family.quantity_value
     AND right_family.base_unit = left_family.base_unit
     AND right_family.package_count = left_family.package_count
     AND right_family.id > left_family.id
     AND right_family.product_type_id IS NOT DISTINCT FROM left_family.product_type_id
    CROSS JOIN LATERAL (
        SELECT ARRAY(
                   SELECT name_word.word FROM app.product_name_word AS name_word
                   WHERE name_word.language_code = left_family.language_code
                     AND name_word.role = 'FILLER'
               ) AS words
    ) AS filler
    WHERE (
              left_family.material IS NULL
              OR right_family.material IS NULL
              OR left_family.material = right_family.material
          )
      AND app.names_say_the_same(left_family.words, right_family.words)
      -- A chain that sells both sells two products.
      AND NOT EXISTS (
          SELECT 1
          FROM app.product_retailer_presence AS left_presence
          JOIN app.product_retailer_presence AS right_presence
            ON right_presence.retailer_id = left_presence.retailer_id
           AND right_presence.product_family_id = right_family.id
          WHERE left_presence.product_family_id = left_family.id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM app.product_merge_decision AS decision
          WHERE decision.left_key = LEAST(left_family.match_key, right_family.match_key)
            AND decision.right_key = GREATEST(left_family.match_key, right_family.match_key)
      );

    INSERT INTO app.product_merge_decision (left_key, right_key, into_key, decision)
    SELECT LEAST(left_key, right_key),
           GREATEST(left_key, right_key),
           CASE WHEN left_chain_count >= right_chain_count THEN left_key ELSE right_key END,
           'SAME'
    FROM merge_candidate
    WHERE exact_match
    ON CONFLICT (left_key, right_key) DO UPDATE SET
        into_key = EXCLUDED.into_key,
        decision = EXCLUDED.decision,
        decided_at = NOW();

    DELETE FROM app.product_merge_suggestion;

    INSERT INTO app.product_merge_suggestion (left_family_id, right_family_id, score)
    SELECT left_id,
           right_id,
           ROUND(public.similarity(left_name, right_name)::NUMERIC, 4)
    FROM merge_candidate
    WHERE NOT exact_match;
END;
$function$;

SELECT app.refresh_product_merge_suggestions();
