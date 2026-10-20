-- Prepoznavanje van primera (četvrto testiranje, 20.10.2026.):
--
-- * "feta sir" je postao „Mladi sir 250g“, "mleko 3,2% 2l" mleko 1,5%, a
--   "šampon za decu" šampon protiv peruti. Ono što red traži preko vrste
--   (feta, 3,2 %, za decu) sad stoji uz stavku kao izraz nad nazivom
--   proizvoda. Plan prvo bira proizvode koji ga kažu, a drugi je zamena koju
--   plan tako i nazove.
-- * Za red koji nijedan rečnik ne zna ("tortilje", "vlažne maramice") isti
--   izraz je cela vrsta: proizvodi čiji naziv kaže te reči, bilo gde u nazivu
--   i bilo koje vrste.

ALTER TABLE app.shopping_list_item
    ADD COLUMN wanted_name_pattern VARCHAR(600);

-- "maslinovo ulje" je bilo „Ulje od komine masline“ (slabiji kvalitet):
-- ulje od komine dolazi tek kad radnja nema drugo maslinovo ulje.
UPDATE app.shopping_intent_alias AS alias
SET uncommon_name_pattern = '\m(komin[a-z]*|pomace|sansa)\M'
FROM app.shopping_intent AS intent
WHERE intent.id = alias.shopping_intent_id
  AND intent.code = 'OLIVE_OIL'
  AND alias.uncommon_name_pattern IS NULL;

-- Naziv proizvoda 54753 „PALADIN sir kačkavalj edamer blok 40% mm 3,3 1 kg“:
-- blok koji se prodaje na kilogram (V127) zadržao je „3,3“ iz „3,3 kg“, jer
-- se broj odvojen od jedinice čitao kao reč naziva. Sad je i to veličina.
CREATE OR REPLACE FUNCTION app.compose_product_name(target_family_id BIGINT)
RETURNS TEXT
LANGUAGE plpgsql
STABLE
AS $fn$
DECLARE
    base TEXT;
    brand_text TEXT;
    brand_folded TEXT[];
    quantity NUMERIC;
    unit TEXT;
    pieces INTEGER;
    sibling_words TEXT[];
    raw TEXT;
    token TEXT;
    token_key TEXT;
    word TEXT;
    abbreviated BOOLEAN;
    is_beer BOOLEAN;
    result_words TEXT[] := '{}';
    seen TEXT[] := '{}';
    size_label TEXT := '';
    one NUMERIC;
    pack_count INTEGER;
    pack_size NUMERIC;
    pack_unit TEXT;
    composed TEXT;
    pieces_in_pack INTEGER;
    expansions CONSTANT JSONB := '{
        "lim": "limenka", "can": "limenka", "limenke": "limenka",
        "pb": "povratna flaša", "rgb": "povratna flaša",
        "nb": "nepovratna flaša", "npb": "nepovratna flaša", "nrgb": "nepovratna flaša", "nep": "nepovratna flaša",
        "pvc": "PET", "pet": "PET",
        "gaz": "gazirani", "negaz": "negazirana", "tec": "tečni", "cok": "čokolada", "inst": "instant",
        "crv": "crveno", "psen": "pšenično", "jub": "jubilarna", "jubil": "jubilarna", "zp": "pasta za zube",
        "deo": "dezodorans", "om": "omekšivač", "det": "deterdžent", "zv": "žvakaća guma"
    }';
    has_size BOOLEAN;
    unit_words CONSTANT TEXT[] := ARRAY['kom', 'komada', 'g', 'gr', 'ml', 'l', 'kg', 'cl', 'x'];
    noise CONSTANT TEXT[] := ARRAY['stand', 'var', 'boca', 'rf', 'rnf', 'pak',
        'heineken', 'hein', 'heinek', 'carlsberg', 'carlsb', 'p&g', 'unilever', 'beiersd', 'beiersdorf', 'nestle', 'wrigley',
        'droga', 'kolinska', 'apf', 'mc', 'mk', 'sk', 'gratis'];
BEGIN
    SELECT family.display_name, brand.display_name, family.quantity_value, family.base_unit,
           app.family_base_package_count(family.id)
    INTO base, brand_text, quantity, unit, pieces
    FROM app.product_family AS family
    LEFT JOIN app.brand AS brand ON brand.id = family.brand_id
    WHERE family.id = target_family_id;

    is_beer := app.fold_letters(base) ~ '(^|[^[:alpha:]])pivo([^[:alpha:]]|$)';

    SELECT ARRAY_AGG(DISTINCT sibling_word)
    INTO sibling_words
    FROM app.retailer_product AS product
    CROSS JOIN LATERAL REGEXP_SPLIT_TO_TABLE(product.name, '[^[:alnum:]%&+]+') AS sibling_word
    WHERE product.product_family_id = target_family_id
      AND LENGTH(sibling_word) >= 2;

    IF brand_text IS NOT NULL AND brand_text !~ '_' THEN
        SELECT ARRAY_AGG(part) INTO brand_folded
        FROM REGEXP_SPLIT_TO_TABLE(app.fold_letters(brand_text), '[^[:alnum:]&]+') AS part
        WHERE part <> '';
        SELECT STRING_AGG(COALESCE((
                   SELECT spelling FROM app.product_word_spelling AS word_spelling WHERE word_spelling.folded = part
               ), part), ' ' ORDER BY ordinality)
        INTO brand_text
        FROM UNNEST(brand_folded) WITH ORDINALITY AS part;
        brand_text := UPPER(brand_text);
    ELSE
        IF brand_text IS NOT NULL THEN
            SELECT ARRAY_AGG(part) INTO brand_folded
            FROM REGEXP_SPLIT_TO_TABLE(app.fold_letters(brand_text), '[^[:alnum:]&]+') AS part
            WHERE part <> '';
        END IF;
        brand_text := NULL;
    END IF;

    -- "4x0.5l", "2X1.49L": several packs of one size
    SELECT m[1]::INTEGER, REPLACE(m[2], ',', '.')::NUMERIC, LOWER(m[3])
    INTO pack_count, pack_size, pack_unit
    FROM REGEXP_MATCHES(base, '(?:^|\s)([2-9]|[1-4][0-9])\s?[x×]\s?([0-9]+(?:[.,][0-9]+)?)\s?(ml|l|g|gr|kg)\M', 'i') AS m
    LIMIT 1;

    -- A name keeps its own "1l" when the size was not read from it.
    has_size := quantity IS NOT NULL OR pack_count IS NOT NULL;

    -- "3,3 kg" with a space is one size, like "3,3kg": a per-kilogram block
    -- named for its weight was "… mm 3,3 1 kg" (V140).
    FOR raw IN SELECT t FROM REGEXP_SPLIT_TO_TABLE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(base, '([[:digit:]])\s+(kg|gr|g|ml|l|cl)(?![[:alpha:]])', '\1\2', 'gi'), '([[:alpha:]])[./+-]([[:alnum:]])', '\1 \2', 'g'), '([[:digit:]][[:alpha:]]*)[+-]([[:alpha:]])', '\1 \2', 'g'), '\s+') AS t WHERE t <> '' LOOP
        token := REGEXP_REPLACE(raw, '^[^[:alnum:]%&]+|[^[:alnum:]%&]+$', '', 'g');
        CONTINUE WHEN token = '';
        -- written with a dot after it somewhere in the name: "SLAD.", "PARF."
        abbreviated := token ~ '^[[:alpha:]]{3,}$' AND POSITION(token || '.' IN base) > 0;
        token_key := app.fold_letters(token);
        -- "7/1", "72/1": pieces in the pack, said once at the end.
        IF token_key ~ '^[0-9]+/1$' THEN
            pieces_in_pack := SPLIT_PART(token_key, '/', 1)::INTEGER;
            CONTINUE;
        END IF;
        -- A bare number is the size only when it is the size ("0.33" on a
        -- 330 ml can); "7.07" on a hair dye is its shade.
        CONTINUE WHEN has_size AND token_key = ANY(unit_words);
        IF token_key ~ '^[0-9]+([.,][0-9]+)?$' THEN
            CONTINUE WHEN quantity IS NOT NULL
                AND REPLACE(token_key, ',', '.')::NUMERIC IN (
                    quantity / GREATEST(pieces, 1),
                    quantity / GREATEST(pieces, 1) / 1000,
                    quantity,
                    quantity / 1000
                );
        ELSE
            CONTINUE WHEN has_size
                AND token_key ~ '^[0-9]+([.,][0-9]+)?\s?(x|×)?\s?([0-9]+([.,][0-9]+)?)?(l|ml|g|gr|kg|kom|cl|mm)$';
        END IF;
        CONTINUE WHEN token_key ~ '^(mk|sk)[0-9]+$';
        CONTINUE WHEN token_key ~ '^[0-9]+[a-z]*([-/][0-9]+)+$';
        CONTINUE WHEN token_key ~ '^cca?[0-9]';
        CONTINUE WHEN token_key = ANY(noise);
        CONTINUE WHEN is_beer AND token_key IN ('svetlo', 'svet', 'svetl');
        CONTINUE WHEN brand_folded IS NOT NULL AND (token_key = ANY(brand_folded)
            OR EXISTS (SELECT 1 FROM UNNEST(brand_folded) b WHERE LENGTH(token_key) >= 4 AND b LIKE token_key || '%'));
        IF expansions ? token_key THEN
            word := expansions ->> token_key;
        ELSIF token ~ '[0-9]' THEN
            word := REPLACE(LOWER(token), '.', ',');
        ELSE
            word := NULL;
            -- a word cut short: whole in another chain's name, or in any name
            IF abbreviated OR LENGTH(token_key) <= 5 THEN
                SELECT LOWER(candidate) INTO word
                FROM UNNEST(sibling_words) AS candidate
                WHERE app.fold_letters(candidate) LIKE token_key || '_%'
                  AND LENGTH(token_key) >= 3
                  AND candidate !~ '[0-9]'
                ORDER BY LENGTH(candidate) DESC
                LIMIT 1;
            END IF;
            IF word IS NULL AND abbreviated AND LENGTH(token_key) >= 4 THEN
                SELECT spelling INTO word
                FROM app.product_word_spelling AS word_spelling
                WHERE word_spelling.folded LIKE token_key || '_%'
                ORDER BY word_spelling.uses DESC
                LIMIT 1;
            END IF;
            word := COALESCE(word, LOWER(token));
            IF LENGTH(word) >= 4 THEN
                word := COALESCE((SELECT spelling FROM app.product_word_spelling AS word_spelling WHERE word_spelling.folded = app.fold_letters(word)), word);
            END IF;
        END IF;
        CONTINUE WHEN app.fold_letters(word) = ANY(seen);
        seen := seen || app.fold_letters(word);
        result_words := result_words || word;
    END LOOP;

    IF pack_count IS NOT NULL AND pieces <= 1 THEN
        pieces := pack_count;
        IF pack_unit IN ('l') THEN one := pack_size * 1000; unit := 'ml';
        ELSIF pack_unit = 'kg' THEN one := pack_size * 1000; unit := 'g';
        ELSE one := pack_size; unit := CASE WHEN pack_unit = 'gr' THEN 'g' ELSE pack_unit END; END IF;
    ELSIF quantity IS NOT NULL THEN
        one := quantity / GREATEST(pieces, 1);
    END IF;

    IF one IS NOT NULL THEN
        size_label := CASE
            WHEN unit = 'ml' AND one >= 200 THEN REPLACE(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM (one / 1000)::TEXT)), '.', ',') || ' l'
            WHEN unit = 'ml' THEN TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM one::TEXT)) || ' ml'
            WHEN unit = 'g' AND one >= 1000 THEN REPLACE(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM (one / 1000)::TEXT)), '.', ',') || ' kg'
            WHEN unit = 'g' THEN TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM one::TEXT)) || ' g'
            WHEN unit = 'piece' THEN TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM one::TEXT)) || ' kom'
            ELSE ''
        END;
        IF pieces > 1 AND size_label <> '' THEN size_label := pieces || ' × ' || size_label; END IF;
        size_label := REGEXP_REPLACE(size_label, '([0-9])\.([0-9])', '\1,\2', 'g');
    END IF;
    IF size_label = '' AND pieces_in_pack IS NOT NULL THEN
        size_label := pieces_in_pack || ' kom';
    END IF;

    composed := NULLIF(BTRIM(CONCAT_WS(' ', brand_text, ARRAY_TO_STRING(result_words, ' '), NULLIF(size_label, ''))), '');
    IF brand_text IS NULL AND composed IS NOT NULL THEN
        composed := UPPER(LEFT(composed, 1)) || SUBSTRING(composed FROM 2);
    END IF;
    RETURN composed;
END;
$fn$;


UPDATE app.product_family AS family
SET composed_name = LEFT(app.compose_product_name(family.id), 500),
    updated_at = NOW()
WHERE family.composed_name ~ '[0-9]+(,[0-9]+)? [0-9]+(,[0-9]+)? (kg|g|l|ml)$'
  AND family.composed_name IS DISTINCT FROM LEFT(app.compose_product_name(family.id), 500);
