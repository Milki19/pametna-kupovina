-- „riba 4“ su četiri ribe, ne četiri pakovanja (proba S18 na kopiji baze,
-- 10.11.2026): goli broj uz ribu čitao se kao broj pakovanja, pa je „riba 4“
-- bila 4 × 600 g smrznute ribe, 2,4 kg za 2.628 RSD (60 % korpe šestog
-- testa). Kupac broji ribe kao limunove (V156): sada je „riba 4“ četiri
-- komada {4, piece, 1}, a plan ih meri kao 4 × 250 g = 1 kg, na kg ili
-- u pakovanja („2 × 600 g“). Spisak piše „4 kom (≈1 kg)“.
--
-- 250 g je porcija obične ribe (oslić, pangasius, skuša, pastrmka); test skup
-- (L221) kaže „četiri porcije, oko 1 kg“. Red je uz reč spiska, kao kod voća.
--
-- Do sada je masu komada (app.asked_in_shop) čitala samo vrsta na meru
-- (weighed_out: voće i povrće). Masa postoji samo za reči iz
-- app.piece_mass, pa je taj uslov višak: riba nije „na meru bez količine“
-- (V137), ali komad ribe ima masu. Parametar weighed_out ostaje zbog
-- potpisa funkcije.

INSERT INTO app.piece_mass (market_id, language, normalized_word, grams, source)
SELECT NULL, 'sr-Latn', word.word, 250,
       'srednja riba ili porcija, domaći prosek (V165)'
FROM (VALUES ('riba'), ('ribe'), ('ribu'), ('sveza riba'), ('sveze ribe')) AS word (word)
WHERE NOT EXISTS (
    SELECT 1 FROM app.piece_mass AS mass
    WHERE mass.market_id IS NULL
      AND mass.language = 'sr-Latn'
      AND mass.normalized_word = word.word
);

CREATE OR REPLACE FUNCTION app.asked_in_shop(
    item_target_quantity NUMERIC,
    item_base_unit VARCHAR,
    weighed_out BOOLEAN,
    target_product_id BIGINT,
    target_shopping_intent_id BIGINT,
    target_word TEXT,
    OUT target_quantity NUMERIC,
    OUT required_base_unit VARCHAR,
    OUT by_the_piece BOOLEAN
)
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT CASE WHEN piece.grams IS NULL OR piece.sold_by_the_piece
                THEN item_target_quantity
                ELSE item_target_quantity * piece.grams
           END,
           CASE WHEN piece.grams IS NULL OR piece.sold_by_the_piece
                THEN item_base_unit
                ELSE 'g'
           END,
           COALESCE(piece.grams IS NOT NULL AND piece.sold_by_the_piece, FALSE)
    FROM (
        SELECT weighed.grams,
               COALESCE(product.base_unit, 'piece') = 'piece'
               AND COALESCE(
                   app.sale_mode_of(product.sale_mode, product.sale_unit,
                                    product.quantity_value, product.base_unit) = 'PIECE',
                   -- Bez veličine i bez sale_unit: lanac piše „KOM“ kao
                   -- jedinicu („AVOKADO KOM“), ili naziv kaže komad uz
                   -- jedinicu kg (Maxi „Mango komad“, V90).
                   product.quantity_value IS NULL
                   AND (UPPER(BTRIM(product.unit)) IN ('KOM', 'KOMAD', 'KO', 'PCS', 'PC')
                        OR product.normalized_name ~ '\m(kom|komad)\M'),
                   FALSE)
                   AS sold_by_the_piece
        FROM (
            SELECT CASE WHEN item_base_unit = 'piece'
                         AND item_target_quantity IS NOT NULL
                        THEN app.piece_grams(
                            (SELECT chain.market_id::INTEGER
                             FROM app.retailer_product AS one
                             JOIN app.retailer AS chain ON chain.id = one.retailer_id
                             WHERE one.id = target_product_id),
                            NULL, target_shopping_intent_id, target_word)
                   END AS grams
        ) AS weighed
        LEFT JOIN app.retailer_product AS product
          ON weighed.grams IS NOT NULL
         AND product.id = target_product_id
    ) AS piece
$$;


-- „riba“ je cela vrsta, iako je malo riba tako i nazvano: radnja piše
-- „brancin“, „oslić“. Spisak je tu reč čitao kao užu, kao „kajmak“ među
-- pavlakama, pa „riba“ nije bila obična stavka i nije dobila granicu cene
-- vrste (S6): „Jedna stanica“ je kupila četiri brancina za 12.520 RSD
-- (provera uživo S18, 10.11.2026). Reč koja imenuje celu vrstu sada se ne
-- sužava. Upit na kopiji (10.11.) našao je 104 takve reči, ali granicu vrste
-- imaju samo vrste sa podvrstama, a od njih samo „riba“ (11 % naziva) i
-- „sveža riba“ (0,4 %).
ALTER TABLE app.shopping_intent_alias
    ADD COLUMN names_the_whole_kind BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE app.shopping_intent_alias
SET names_the_whole_kind = TRUE
WHERE normalized_alias IN ('riba', 'ribe', 'ribu', 'sveza riba', 'sveze ribe');

-- Stavke koje su već sačuvane sa tom reči kao užom.
UPDATE app.shopping_list_item AS item
SET wanted_name_pattern = NULL,
    wanted_name_hint = NULL
FROM app.shopping_intent_alias AS alias
WHERE alias.names_the_whole_kind
  AND alias.shopping_intent_id = item.shopping_intent_id
  AND alias.normalized_alias = item.flexible_category_normalized
  AND item.matching_rule = 'FLEXIBLE_CATEGORY'
  AND item.wanted_name_pattern IS NOT NULL;
