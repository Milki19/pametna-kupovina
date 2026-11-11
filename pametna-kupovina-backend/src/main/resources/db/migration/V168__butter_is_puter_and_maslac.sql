-- „puter“ je i „maslac“ (šesti test, sitnica F3, 11.11.2026). Prodavnice
-- pišu jednu ili drugu reč, a manje od pola maslaca kaže „puter“. Zato je
-- „puter“ na spisku čitan kao uža reč, kao „kajmak“ među pavlakama (V140),
-- pa je plan birao samo nazive sa rečju „puter“ i preskakao jeftiniji
-- maslac.
--
-- Reči koje prodavnice pišu drugačije su do sada bile u kodu (WantedWords,
-- „sojino mleko“ = „soja drink“). Ovde postaju rečnik po tržištu i jeziku,
-- kao app.unit_word i app.piece_mass (V155): red bez tržišta važi za ceo
-- jezik, a tržište koje ima svoje redove za reč koristi samo njih. Drugo
-- tržište dodaje svoje reči (npr. „butter“ i „beurre“) bez koda.

CREATE TABLE app.same_word (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id SMALLINT,
    language VARCHAR(35) NOT NULL,
    -- Reč sa spiska, kao ProductNameNormalizer: latinica, mala slova, bez
    -- kvačica. Svaki oblik koji se piše na spisku je svoj red.
    normalized_word VARCHAR(60) NOT NULL,
    -- Reč kojom naziv proizvoda kaže isto, u bilo kom padežu
    -- (WholeWords.anyFormOf): „maslac“ je i „maslaca“, „puter“ i „puterom“.
    -- I sama reč sa spiska ima svoj red, inače se više ne bi tražila.
    name_word VARCHAR(60) NOT NULL,
    -- Odakle je par: „šesti test 6.11.“, „rečnik“, „nazivi 3 lanca“.
    source VARCHAR(300) NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_same_word_market
        FOREIGN KEY (market_id) REFERENCES app.market (id),
    CONSTRAINT chk_same_word_language CHECK (BTRIM(language) <> ''),
    CONSTRAINT chk_same_word_word CHECK (normalized_word ~ '^[a-z0-9]+$'),
    -- Samo slova i cifre: reč ide u regularni izraz bez escapovanja.
    CONSTRAINT chk_same_word_name_word CHECK (name_word ~ '^[a-z0-9]+$'),
    CONSTRAINT chk_same_word_priority CHECK (priority > 0)
);

CREATE UNIQUE INDEX uq_same_word
    ON app.same_word (COALESCE(market_id, 0), language, normalized_word, name_word);

COMMENT ON TABLE app.same_word IS
    'Reči spiska koje prodavnice pišu i drugačije, po jeziku i tržištu: puter = maslac (V168).';

INSERT INTO app.same_word (market_id, language, normalized_word, name_word, source)
SELECT NULL, 'sr-Latn', pair.word, pair.name_word, 'puter i maslac su isto, sitnica F3 (V168)'
FROM (
    VALUES
        ('puter', 'puter'), ('puter', 'maslac'),
        ('putera', 'puter'), ('putera', 'maslac'),
        ('puteru', 'puter'), ('puteru', 'maslac'),
        ('puterom', 'puter'), ('puterom', 'maslac'),
        ('maslac', 'maslac'), ('maslac', 'puter'),
        ('maslaca', 'maslac'), ('maslaca', 'puter'),
        ('maslacu', 'maslac'), ('maslacu', 'puter'),
        ('maslacem', 'maslac'), ('maslacem', 'puter')
) AS pair (word, name_word)
ON CONFLICT DO NOTHING;

-- Stavke koje su već sačuvane sa „puter“ ili „maslac“ kao užom reči: izraz
-- uže reči počinje glavom bez vrste (WantedWords.HEAD), a vrsta ostaje
-- cela samo ako reč i njena druga imena kaže bar pola proizvoda vrste,
-- isti prag kao u ShoppingListRepository (NARROWER_WORD_SHARE).
WITH said AS (
    SELECT item.id,
           (
               SELECT AVG(CASE WHEN product.normalized_name
                                    ~ ('\m(' || words.name_words || ')')
                               THEN 1.0 ELSE 0.0 END)
               FROM app.shopping_intent_product_type AS allowed
               JOIN app.retailer_product_type AS assignment
                 ON assignment.product_type_id = allowed.product_type_id
               JOIN app.retailer_product AS product
                 ON product.id = assignment.retailer_product_id
               WHERE allowed.shopping_intent_id = item.shopping_intent_id
                 AND allowed.enabled_by_default = TRUE
           ) AS share
    FROM app.shopping_list_item AS item
    JOIN LATERAL (
        SELECT STRING_AGG(same.name_word, '|') AS name_words
        FROM app.same_word AS same
        WHERE same.active
          AND same.normalized_word = item.flexible_category_normalized
    ) AS words ON words.name_words IS NOT NULL
    WHERE item.matching_rule = 'FLEXIBLE_CATEGORY'
      AND item.shopping_intent_id IS NOT NULL
      AND STARTS_WITH(item.wanted_name_pattern, '^(?=([^ ]+ ){0,2}')
)
UPDATE app.shopping_list_item AS item
SET wanted_name_pattern = NULL,
    wanted_name_hint = NULL
FROM said
WHERE said.id = item.id
  AND said.share >= 0.5;
