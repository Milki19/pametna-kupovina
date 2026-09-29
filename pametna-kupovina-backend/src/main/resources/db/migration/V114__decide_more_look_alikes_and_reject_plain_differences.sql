-- The owner went through the whole look-alike list (825 pairs on 29
-- September) and asked for rules that hold in any market. Most pairs fell
-- into a few kinds.
--
-- One product written two ways:
--   "Energ.nap.Red Bull lubenica 0,25l"  / "ENERGETSKI NAPITAK RED BULL LUBENICA 0.25L"
--   "Krema Soft Nivea 200ml"              / "krema nivea soft 200 ml delta dmd"
--   "Mini Pileća Posebna YUHOR 350 g"     / "PILEĆA POSEBNA MINI YUHOR 350G 601696"
--   "Vino Vranac 1l status"               / "VINO CRVENO STATUS VRANAC 1L"
-- A word of three letters followed by a full stop is cut short, as a longer
-- one already was. Distributors' and importers' names ("Delta DMD", "BB
-- Company") and packaging or promotion words ("brik", "gratis") say nothing
-- about the product. A number of five digits or more is a chain's article
-- code, and a "+20%" bonus is an offer, not a product. More food words only
-- say what kind it is. All of these are words of a language, kept as data.
--
-- Two products, plainly:
--   "Vino belo Chardonnay 11% 0,75l"      / "VINO CHARDONNAY 13% 0.75L RUBIN"
--   "NIVEA ROLLON M PROTECT CARE 50ml"    / "DEZODORANS NIVEA ROLL ON W PROTECT & CARE 50ML"
--   "Dove Roll On Original 50 ml"         / "DEZODORANS NIVEA ROLL ON W ORIGINAL 50ML"
-- Each name carries a number the other does not, the two names say
-- different things of one kind (men and women, white and red), or one name
-- is another brand's product filed under this brand. Those pairs are
-- decided DIFFERENT and leave the list.
--
-- What stays for the owner is a pair where one name says something the
-- other leaves out ("Palmolive tečni sapun 300ml" and "... ALMOND MILK").

-- NOISE and COMPANY words are set aside from a name. VARIANT words of one
-- group ("means") tell two products apart when each name has a different one.
ALTER TABLE app.product_name_word
    DROP CONSTRAINT chk_product_name_word_role;

ALTER TABLE app.product_name_word
    ADD CONSTRAINT chk_product_name_word_role CHECK (
        (role IN ('KIND', 'SHARED_KIND', 'FILLER', 'NOISE', 'COMPANY') AND means IS NULL)
        OR (role IN ('SHORT', 'VARIANT') AND means ~ '^[a-z]+$')
    );

INSERT INTO app.product_name_word (language_code, word, role, means)
SELECT app.default_language(), word, role, means
FROM (
    VALUES
        -- Packaging and offers.
        ('brik', 'NOISE', NULL), ('balon', 'NOISE', NULL), ('casa', 'NOISE', NULL),
        ('kantica', 'NOISE', NULL), ('teglica', 'NOISE', NULL), ('doypack', 'NOISE', NULL),
        ('dojpak', 'NOISE', NULL), ('multipak', 'NOISE', NULL), ('alu', 'NOISE', NULL),
        ('bottle', 'NOISE', NULL), ('box', 'NOISE', NULL), ('kutija', 'NOISE', NULL),
        ('paket', 'NOISE', NULL), ('pack', 'NOISE', NULL), ('overfil', 'NOISE', NULL),
        ('pakovanje', 'NOISE', NULL), ('meko', 'NOISE', NULL), ('gratis', 'NOISE', NULL),
        ('grat', 'NOISE', NULL), ('akcija', 'NOISE', NULL),
        -- Distributors, importers and makers that are not the brand.
        ('delta', 'COMPANY', NULL), ('dmd', 'COMPANY', NULL), ('company', 'COMPANY', NULL),
        ('grosso', 'COMPANY', NULL), ('adriatic', 'COMPANY', NULL), ('adriatik', 'COMPANY', NULL),
        ('nestle', 'COMPANY', NULL), ('mlekoprodukt', 'COMPANY', NULL), ('omnico', 'COMPANY', NULL),
        ('distribucija', 'COMPANY', NULL), ('rauch', 'COMPANY', NULL), ('ferrero', 'COMPANY', NULL),
        ('barilla', 'COMPANY', NULL), ('gsk', 'COMPANY', NULL), ('kimby', 'COMPANY', NULL),
        ('mercata', 'COMPANY', NULL), ('yuton', 'COMPANY', NULL), ('vasovic', 'COMPANY', NULL),
        ('lomax', 'COMPANY', NULL), ('inkofoods', 'COMPANY', NULL), ('wrigley', 'COMPANY', NULL),
        ('zott', 'COMPANY', NULL), ('takovo', 'COMPANY', NULL), ('stark', 'COMPANY', NULL),
        ('podravka', 'COMPANY', NULL), ('commerce', 'COMPANY', NULL), ('mlekara', 'COMPANY', NULL),
        ('homolje', 'COMPANY', NULL), ('atlantic', 'COMPANY', NULL), ('erdal', 'COMPANY', NULL),
        ('froneri', 'COMPANY', NULL), ('bioland', 'COMPANY', NULL), ('gpp', 'COMPANY', NULL),
        ('doo', 'COMPANY', NULL), ('dopek', 'COMPANY', NULL), ('dopeka', 'COMPANY', NULL),
        ('moravka', 'COMPANY', NULL), ('swisslion', 'COMPANY', NULL), ('bergman', 'COMPANY', NULL), ('neoplanta', 'COMPANY', NULL),
        ('fina', 'COMPANY', NULL),
        ('apatinska', 'COMPANY', NULL), ('pivara', 'COMPANY', NULL), ('unijapak', 'COMPANY', NULL),
        -- Kinds of food and household goods.
        ('vino', 'KIND', NULL), ('sir', 'KIND', NULL), ('hleb', 'KIND', NULL),
        ('kobasica', 'KIND', NULL), ('testenina', 'KIND', NULL), ('rakija', 'KIND', NULL),
        ('liker', 'KIND', NULL), ('zacini', 'KIND', NULL), ('cokoladna', 'KIND', NULL),
        ('cokoladno', 'KIND', NULL), ('zvaka', 'KIND', NULL), ('zvake', 'KIND', NULL),
        ('jogurt', 'KIND', NULL), ('kasica', 'KIND', NULL), ('pire', 'KIND', NULL),
        ('granule', 'KIND', NULL), ('briketi', 'KIND', NULL), ('hrana', 'KIND', NULL),
        ('dezert', 'KIND', NULL), ('bar', 'KIND', NULL), ('osvezivac', 'KIND', NULL),
        ('deterdzent', 'KIND', NULL), ('lizalica', 'KIND', NULL), ('pastile', 'KIND', NULL),
        ('sapun', 'SHARED_KIND', NULL), ('kupka', 'SHARED_KIND', NULL),
        ('sampon', 'SHARED_KIND', NULL),
        -- Shortenings and words of one meaning.
        ('tec', 'SHORT', 'tecni'), ('sap', 'SHORT', 'sapun'), ('los', 'SHORT', 'losion'),
        ('ovs', 'SHORT', 'ovsena'), ('zac', 'SHORT', 'zacin'), ('nap', 'SHORT', 'napitak'),
        ('men', 'SHORT', 'm'), ('muski', 'SHORT', 'm'), ('man', 'SHORT', 'm'),
        ('women', 'SHORT', 'w'), ('woman', 'SHORT', 'w'), ('zenski', 'SHORT', 'w'),
        ('zene', 'SHORT', 'w'), ('z', 'SHORT', 'w'),
        ('rose', 'SHORT', 'roze'), ('ruzicasto', 'SHORT', 'roze'),
        ('ljuta', 'SHORT', 'ljuti'), ('blaga', 'SHORT', 'blagi'), ('slatki', 'SHORT', 'slatka'),
        -- Words of one group that tell two products apart.
        ('m', 'VARIANT', 'pol'), ('w', 'VARIANT', 'pol'),
        ('belo', 'VARIANT', 'boja'), ('crveno', 'VARIANT', 'boja'), ('roze', 'VARIANT', 'boja'),
        ('gazirana', 'VARIANT', 'gas'), ('negazirana', 'VARIANT', 'gas'),
        ('ljuti', 'VARIANT', 'ljutina'), ('blagi', 'VARIANT', 'ljutina'),
        ('slatka', 'VARIANT', 'ljutina')
) AS name_word(word, role, means)
ON CONFLICT (language_code, word) DO NOTHING;

-- As V112, and: a number of five digits or more is a chain's article code,
-- a "+20%" is an offer, "8m+" is an age in months, two letters joined by "&"
-- ("B&W", "D&N") are a line's name, not a mark, and a letter is read through
-- the language's shortenings ("ž" deodorant is "w").
CREATE OR REPLACE FUNCTION app.product_name_marks(
    product_name TEXT,
    filler_words TEXT[],
    short_words TEXT[],
    short_means TEXT[]
)
RETURNS TEXT[]
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH lowered AS (
        SELECT REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
                   REGEXP_REPLACE(LOWER(COALESCE(product_name, '')), '[][{}|~`@^\\]', 'q', 'g'),
                   '([0-9]),([0-9])', '\1.\2', 'g'),
                   '\+\s*[0-9]+(\.[0-9]+)?\s*%', ' ', 'g'),
                   '([0-9]+)\s*m\s*\+', ' \1 ', 'g'),
                   '(?<![[:alpha:]])[[:alpha:]]\s*&\s*[[:alpha:]](?![[:alpha:]])', ' ', 'g') AS text
    ), cleaned AS (
        SELECT REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
                   lowered.text,
                   '[0-9]+(\.[0-9]+)?\s*(ml|l|lit|g|gr|kg|cl|dl)(?![[:alpha:]])', ' ', 'g'),
                   '#\s*[0-9]+', ' ', 'g'),
                   '[0-9]+\s*/\s*[0-9]+', ' ', 'g'),
                   '[0-9]+\s*x(?![[:alpha:]])|(?<![[:alpha:]])x\s*[0-9]+', ' ', 'g'),
                   '[0-9]{5,}', ' ', 'g') AS text
        FROM lowered
    )
    SELECT COALESCE(ARRAY_AGG(DISTINCT mark ORDER BY mark), '{}')
    FROM (
        SELECT TRIM_SCALE(number[1]::NUMERIC)::TEXT AS mark
        FROM cleaned, REGEXP_MATCHES(cleaned.text, '([0-9]+(\.[0-9]+)?)', 'g') AS number
        UNION ALL
        SELECT COALESCE(short_means[ARRAY_POSITION(short_words, word)], word)
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

DROP FUNCTION app.product_name_marks(TEXT, TEXT[]);

-- A name's words as the rules read them: shortenings spelled out, the brand
-- (also cut short) and words that say nothing set aside.
CREATE OR REPLACE FUNCTION app.product_name_read_words(
    name_words TEXT[],
    brand_name TEXT,
    noise_words TEXT[],
    short_words TEXT[],
    short_means TEXT[]
)
RETURNS TEXT[]
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT ARRAY(
        SELECT DISTINCT COALESCE(short_means[ARRAY_POSITION(short_words, word)], word)
        FROM UNNEST(name_words) AS word
        WHERE word <> ALL (noise_words)
          AND NOT EXISTS (
              SELECT 1
              FROM REGEXP_SPLIT_TO_TABLE(app.fold_match_text(COALESCE(brand_name, '')), '[^a-z0-9]+')
                  AS brand_word
              WHERE brand_word <> ''
                AND brand_word LIKE word || '%'
          )
        ORDER BY 1
    )
$$;

-- As V112, reading words through app.product_name_read_words, and a word of
-- three letters found in the one longer word of the other name it starts
-- ("NAP" napitak, "PREL VAN" preliv vanila). "Men" and the like are read as
-- words of their own before this.
CREATE OR REPLACE FUNCTION app.names_are_one_product(
    left_words TEXT[],
    right_words TEXT[],
    kind_words TEXT[],
    shared_kind_words TEXT[]
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH input AS (
        SELECT *
        FROM (VALUES ('left', left_words), ('right', right_words)) AS input(side_name, words)
    ), side AS (
        SELECT this.side_name,
               ARRAY(
                   SELECT DISTINCT COALESCE(
                              (SELECT MIN(other_word)
                               FROM UNNEST(other.words) AS other_word
                               WHERE LENGTH(word) = 3
                                 AND word <> ALL (other.words)
                                 AND other_word LIKE word || '_%'
                               HAVING COUNT(*) = 1),
                              word)
                   FROM UNNEST(this.words) AS word
               ) AS words
        FROM input AS this
        JOIN input AS other ON other.side_name <> this.side_name
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
                   WHEN CARDINALITY(this.kind_found) > 0
                        AND CARDINALITY(other.kind_found) > 0
                        AND EXISTS (
                            SELECT 1 FROM UNNEST(this.kind_found) AS word
                            WHERE app.name_word_found(word, this.kind_found, other.kind_found)
                        )
                       THEN ARRAY(SELECT UNNEST(this.words) EXCEPT SELECT UNNEST(this.kind_found))
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

DROP FUNCTION app.names_are_one_product(TEXT[], TEXT[], TEXT, TEXT[], TEXT[], TEXT[], TEXT[]);

-- Two names plainly say two products: each carries a number the other does
-- not ("11%" and "13%"; not 0 or 1, and not a four-digit code), or each says
-- a different thing of one group (men and women, white and red).
CREATE OR REPLACE FUNCTION app.names_say_two_products(
    left_marks TEXT[],
    right_marks TEXT[],
    left_words TEXT[],
    right_words TEXT[],
    variant_words TEXT[],
    variant_groups TEXT[]
)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT (
               EXISTS (SELECT 1 FROM UNNEST(left_marks) AS mark
                       WHERE mark ~ '^[0-9]{1,3}(\.[0-9]+)?x?$' AND mark NOT IN ('0', '1')
                         AND mark <> ALL (right_marks))
               AND EXISTS (SELECT 1 FROM UNNEST(right_marks) AS mark
                           WHERE mark ~ '^[0-9]{1,3}(\.[0-9]+)?x?$' AND mark NOT IN ('0', '1')
                             AND mark <> ALL (left_marks))
           )
        OR EXISTS (
               SELECT 1
               FROM UNNEST(left_marks || left_words) AS left_word
               CROSS JOIN UNNEST(right_marks || right_words) AS right_word
               WHERE left_word <> right_word
                 AND left_word = ANY (variant_words)
                 AND right_word = ANY (variant_words)
                 AND variant_groups[ARRAY_POSITION(variant_words, left_word)]
                     = variant_groups[ARRAY_POSITION(variant_words, right_word)]
                 AND left_word <> ALL (right_marks || right_words)
                 AND right_word <> ALL (left_marks || left_words)
           )
$$;

-- refresh
-- As V112, with the rules above, and a pair that plainly names two products,
-- or one that is another brand's product under this brand, decided DIFFERENT.
CREATE OR REPLACE FUNCTION app.refresh_product_merge_suggestions()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DROP TABLE IF EXISTS pg_temp.merge_family;
    DROP TABLE IF EXISTS pg_temp.merge_candidate;
    DROP TABLE IF EXISTS pg_temp.merge_pair;
    DROP TABLE IF EXISTS pg_temp.merge_brand_word;

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
           left_family.brand_name,
           left_family.words AS left_words,
           right_family.words AS right_words,
           left_family.language_code = right_family.language_code AS one_language,
           -- Read only for pairs that got this far: slow to read for every
           -- product.
           app.product_name_marks(left_family.display_name, name_words.fillers,
                                  name_words.shorts, name_words.means) AS left_marks,
           app.product_name_marks(right_family.display_name, name_words.fillers,
                                  name_words.shorts, name_words.means) AS right_marks,
           app.product_name_read_words(left_family.words, left_family.brand_name,
                                       name_words.noise, name_words.shorts,
                                       name_words.means) AS left_read,
           app.product_name_read_words(right_family.words, left_family.brand_name,
                                       name_words.noise, name_words.shorts,
                                       name_words.means) AS right_read,
           name_words.kinds,
           name_words.shared_kinds,
           name_words.noise,
           name_words.variants,
           name_words.variant_groups
    FROM merge_family AS left_family
    JOIN merge_family AS right_family
      ON right_family.brand_id = left_family.brand_id
     AND right_family.quantity_value = left_family.quantity_value
     AND right_family.base_unit = left_family.base_unit
     AND right_family.package_count = left_family.package_count
     AND right_family.id > left_family.id
     AND right_family.product_type_id IS NOT DISTINCT FROM left_family.product_type_id
    CROSS JOIN LATERAL (
        SELECT COALESCE(ARRAY_AGG(word) FILTER (WHERE role = 'FILLER'), '{}') AS fillers,
               COALESCE(ARRAY_AGG(word) FILTER (WHERE role = 'KIND'), '{}') AS kinds,
               COALESCE(ARRAY_AGG(word) FILTER (WHERE role = 'SHARED_KIND'), '{}') AS shared_kinds,
               COALESCE(ARRAY_AGG(word) FILTER (WHERE role IN ('NOISE', 'COMPANY')), '{}') AS noise,
               COALESCE(ARRAY_AGG(word ORDER BY word) FILTER (WHERE role = 'SHORT'), '{}') AS shorts,
               COALESCE(ARRAY_AGG(means ORDER BY word) FILTER (WHERE role = 'SHORT'), '{}') AS means,
               COALESCE(ARRAY_AGG(word ORDER BY word) FILTER (WHERE role = 'VARIANT'), '{}') AS variants,
               COALESCE(ARRAY_AGG(means ORDER BY word) FILTER (WHERE role = 'VARIANT'), '{}')
                   AS variant_groups
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

    -- One-word brand names, to catch a product of one brand filed under
    -- another ("Dove" roll-on named "NIVEA ROLL ON"). A brand name that is
    -- also a common word in other brands' names ("fresh") tells nothing.
    CREATE TEMP TABLE merge_brand_word ON COMMIT DROP AS
    SELECT brand_name.word
    FROM (
        SELECT DISTINCT app.fold_match_text(display_name) AS word FROM app.brand
    ) AS brand_name
    LEFT JOIN (
        SELECT word, COUNT(*) AS families
        FROM merge_family, UNNEST(merge_family.words) AS word
        GROUP BY word
    ) AS used ON used.word = brand_name.word
    WHERE brand_name.word ~ '^[a-z]{4,}$'
      AND COALESCE(used.families, 0) <= 10
      AND NOT EXISTS (
          SELECT 1 FROM app.product_name_word AS name_word
          WHERE name_word.word = brand_name.word
      );

    CREATE UNIQUE INDEX ON merge_brand_word (word);

    CREATE TEMP TABLE merge_pair ON COMMIT DROP AS
    SELECT candidate.*,
           candidate.left_marks = candidate.right_marks
           AND (
               candidate.left_words = candidate.right_words
               OR candidate.left_read = candidate.right_read
               OR (
                   candidate.one_language
                   AND app.names_are_one_product(
                       candidate.left_read,
                       candidate.right_read,
                       candidate.kinds,
                       candidate.shared_kinds
                   )
               )
           ) AS exact_match,
           candidate.one_language
           AND (
               app.names_say_two_products(
                   candidate.left_marks,
                   candidate.right_marks,
                   candidate.left_read,
                   candidate.right_read,
                   candidate.variants,
                   candidate.variant_groups
               )
               OR EXISTS (
                   SELECT 1
                   FROM (VALUES (candidate.left_read, candidate.right_read),
                                (candidate.right_read, candidate.left_read)) AS side(own, other)
                   CROSS JOIN UNNEST(side.own) AS word
                   JOIN merge_brand_word AS brand_word ON brand_word.word = word
                   WHERE NOT app.name_word_found(word, side.own, side.other)
               )
           ) AS two_products
    FROM merge_candidate AS candidate;

    INSERT INTO app.product_merge_decision (left_key, right_key, into_key, decision)
    SELECT LEAST(left_key, right_key),
           GREATEST(left_key, right_key),
           CASE
               WHEN NOT exact_match THEN NULL
               WHEN left_chain_count >= right_chain_count THEN left_key
               ELSE right_key
           END,
           CASE WHEN exact_match THEN 'SAME' ELSE 'DIFFERENT' END
    FROM merge_pair
    WHERE exact_match OR two_products
    ON CONFLICT (left_key, right_key) DO UPDATE SET
        into_key = EXCLUDED.into_key,
        decision = EXCLUDED.decision,
        decided_at = NOW();

    DELETE FROM app.product_merge_suggestion;

    INSERT INTO app.product_merge_suggestion (left_family_id, right_family_id, score)
    SELECT left_id,
           right_id,
           ROUND(public.similarity(left_name, right_name)::NUMERIC, 4)
    FROM merge_pair
    WHERE NOT exact_match
      AND NOT two_products;
END;
$function$;

SELECT app.refresh_product_merge_suggestions();
