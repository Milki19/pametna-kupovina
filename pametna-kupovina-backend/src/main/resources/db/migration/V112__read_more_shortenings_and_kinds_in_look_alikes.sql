-- The rest of the owner's look-alike list, read on production after V111,
-- held more pairs that are one product written two ways:
--   "COK.MILKA OREO 100g"            / "COKOLADA MILKA OREO 100gr"
--   "Aleva paprika slatka 100 g"     / "ZAČIN ALEVA PAPRIKA SLATKA 100G"
--   "GRAŠAK SMRZ 400G FLORA"         / "SMRZNUTO POVRĆE FLORA GRAŠAK 400G"
--   "Borotalco original deo 150ml."  / "DEO SPREJ ORIGINAL BOROTALCO 150ML"
--   "NIVEA tuš/gel Creme Soft 250ml" / "GEL ZA TUŠIRANJE CREME SOFT NIVEA 250ML"
-- Chains cut some words to three letters ("COK", "BOM", "TUS"), too short to
-- be read as the start of the full word in general ("men" is not "mentol"),
-- so each such shortening is listed with the word it stands for. More words
-- only say what kind of food it is (začin, smrznuto povrće, sok, kafa).
--
-- Cosmetics are different: one brand sells a cream, a shower gel and a
-- deodorant under the same line name and size ("NIVEA CREME SOFT 250ML" does
-- not say which). A word like "gel", "sprej" or "deo" is set aside only when
-- the other name also says what kind of product it is and they share one.
--
-- A pack count must match too: "6x1l" water is not a 0,5 l bottle.

ALTER TABLE app.product_name_word
    ADD COLUMN means VARCHAR(50);

ALTER TABLE app.product_name_word
    DROP CONSTRAINT chk_product_name_word_role;

-- KIND: one name having it where the other has none tells nothing apart.
-- SHARED_KIND: set aside only when both names name a kind and share one.
-- SHORT: a chain's shortening of the word in "means".
ALTER TABLE app.product_name_word
    ADD CONSTRAINT chk_product_name_word_role CHECK (
        (role IN ('KIND', 'SHARED_KIND', 'FILLER') AND means IS NULL)
        OR (role = 'SHORT' AND means ~ '^[a-z]+$')
    );

UPDATE app.product_name_word
SET role = 'SHARED_KIND'
WHERE language_code = app.default_language()
  AND word IN ('deo', 'dezodorans');

INSERT INTO app.product_name_word (language_code, word, role, means)
SELECT app.default_language(), word, role, means
FROM (
    VALUES
        ('zacin', 'KIND', NULL), ('povrce', 'KIND', NULL), ('smrznuto', 'KIND', NULL),
        ('energetsko', 'KIND', NULL), ('energetski', 'KIND', NULL), ('pice', 'KIND', NULL),
        ('napitak', 'KIND', NULL), ('kafa', 'KIND', NULL), ('sok', 'KIND', NULL),
        ('supa', 'KIND', NULL), ('bombon', 'KIND', NULL),
        ('sprej', 'SHARED_KIND', NULL), ('gel', 'SHARED_KIND', NULL),
        ('tusiranje', 'SHARED_KIND', NULL), ('losion', 'SHARED_KIND', NULL),
        ('pasta', 'SHARED_KIND', NULL), ('zube', 'SHARED_KIND', NULL),
        ('tus', 'SHORT', 'tusiranje'), ('jag', 'SHORT', 'jagoda'),
        ('kis', 'SHORT', 'kisela'), ('gaz', 'SHORT', 'gazirana'),
        ('kob', 'SHORT', 'kobasica'), ('bom', 'SHORT', 'bombone'),
        ('cok', 'SHORT', 'cokolada'), ('smrz', 'SHORT', 'smrznuto')
) AS name_word(word, role, means)
ON CONFLICT (language_code, word) DO NOTHING;

-- As V111, and a pack count ("6x", "x6") is a mark of its own.
CREATE OR REPLACE FUNCTION app.product_name_marks(product_name TEXT, filler_words TEXT[])
RETURNS TEXT[]
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH lowered AS (
        SELECT REGEXP_REPLACE(
                   REGEXP_REPLACE(LOWER(COALESCE(product_name, '')), '[][{}|~`@^\\]', 'q', 'g'),
                   '([0-9]),([0-9])', '\1.\2', 'g') AS text
    ), cleaned AS (
        SELECT REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
                   lowered.text,
                   '[0-9]+(\.[0-9]+)?\s*(ml|l|lit|g|gr|kg|cl|dl)(?![[:alpha:]])', ' ', 'g'),
                   '#\s*[0-9]+', ' ', 'g'),
                   '[0-9]+\s*/\s*[0-9]+', ' ', 'g'),
                   '[0-9]+\s*x(?![[:alpha:]])|(?<![[:alpha:]])x\s*[0-9]+', ' ', 'g') AS text
        FROM lowered
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
        UNION ALL
        SELECT COALESCE(pack[1], pack[2]) || 'x'
        FROM lowered,
             REGEXP_MATCHES(lowered.text,
                            '([0-9]+)\s*x(?![[:alpha:]])|(?<![[:alpha:]])x\s*([0-9]+)', 'g') AS pack
    ) AS marks
$$;

-- As V111, with the chains' shortenings spelled out and cosmetics' kind words
-- set aside only when both names name a kind and share one.
CREATE OR REPLACE FUNCTION app.names_are_one_product(
    left_words TEXT[],
    right_words TEXT[],
    brand_name TEXT,
    kind_words TEXT[],
    shared_kind_words TEXT[],
    short_words TEXT[],
    short_means TEXT[]
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
                   SELECT DISTINCT COALESCE(short_means[ARRAY_POSITION(short_words, word)], word)
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
                       SELECT 1 FROM UNNEST(kind_words || shared_kind_words) AS kind_word
                       WHERE kind_word = word
                          OR (LENGTH(word) >= 4 AND kind_word LIKE word || '%')
                   )
               ) AS kind_found,
               EXISTS (
                   SELECT 1
                   FROM UNNEST(side.words) AS word, UNNEST(shared_kind_words) AS kind_word
                   WHERE kind_word = word
                      OR (LENGTH(word) >= 4 AND kind_word LIKE word || '%')
               ) AS has_shared_kind
        FROM side
    ), compared AS (
        SELECT this.side_name,
               CASE
                   -- Both name a kind and share one: the kind words say nothing.
                   WHEN CARDINALITY(this.kind_found) > 0
                        AND CARDINALITY(other.kind_found) > 0
                        AND EXISTS (
                            SELECT 1 FROM UNNEST(this.kind_found) AS word
                            WHERE app.name_word_found(word, this.kind_found, other.kind_found)
                        )
                       THEN ARRAY(SELECT UNNEST(this.words) EXCEPT SELECT UNNEST(this.kind_found))
                   -- Only this one names a kind, and not a cosmetic one.
                   WHEN CARDINALITY(other.kind_found) = 0 AND NOT this.has_shared_kind
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

DROP FUNCTION app.names_are_one_product(TEXT[], TEXT[], TEXT, TEXT[]);

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
           CARDINALITY(presence.retailer_ids) AS chain_count,
           presence.retailer_ids,
           family.display_name,
           presence.language_code
    FROM app.product_family AS family
    JOIN app.brand AS brand
      ON brand.id = family.brand_id
    -- The chains that sell it, read once, and the language they sell in.
    JOIN (
        SELECT presence.product_family_id,
               ARRAY_AGG(presence.retailer_id ORDER BY presence.retailer_id) AS retailer_ids,
               MIN(market.default_language) AS language_code
        FROM app.product_retailer_presence AS presence
        JOIN app.retailer AS retailer ON retailer.id = presence.retailer_id
        JOIN app.market AS market ON market.id = retailer.market_id
        GROUP BY presence.product_family_id
    ) AS presence
      ON presence.product_family_id = family.id
    WHERE family.family_key LIKE 'MK:%'
      AND family.quantity_value IS NOT NULL;

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
                       name_words.kinds,
                       name_words.shared_kinds,
                       name_words.shorts,
                       name_words.means
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
    CROSS JOIN LATERAL (
        SELECT COALESCE(ARRAY_AGG(word) FILTER (WHERE role = 'KIND'), '{}') AS kinds,
               COALESCE(ARRAY_AGG(word) FILTER (WHERE role = 'SHARED_KIND'), '{}') AS shared_kinds,
               COALESCE(ARRAY_AGG(word ORDER BY word) FILTER (WHERE role = 'SHORT'), '{}') AS shorts,
               COALESCE(ARRAY_AGG(means ORDER BY word) FILTER (WHERE role = 'SHORT'), '{}') AS means
        FROM app.product_name_word
        WHERE language_code = left_family.language_code
    ) AS name_words
    WHERE (
              left_family.material IS NULL
              OR right_family.material IS NULL
              OR left_family.material = right_family.material
          )
      AND app.names_say_the_same(left_family.words, right_family.words)
      -- A chain that sells both sells two products.
      AND NOT left_family.retailer_ids && right_family.retailer_ids
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
