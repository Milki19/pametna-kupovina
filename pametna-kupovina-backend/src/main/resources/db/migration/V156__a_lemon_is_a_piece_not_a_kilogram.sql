-- Komad voća i povrća nije kilogram (šesti test, 6.11.2026). „limun 3“ je
-- bio 3 kg (389,97 RSD), a „avokado 2“ 1,5 kg: broj uz voće i povrće čitao se
-- kao broj kilograma na meru (V137). Kupac broji komade. Sada je „limun 3“
-- tri komada {3, piece, 1}, a plan ih plaća po onome kako lanac prodaje:
--   * na komad („AVOKADO KOM“): 3 komada,
--   * na meru po kg: 3 × 100 g = 0,3 kg po ceni kilograma,
--   * u pakovanju („LIMUN 500G“): pakovanja koliko treba za 0,3 kg.
-- Za to treba masa jednog komada (app.piece_mass, V155). Ovde su prosečne
-- mase srednjeg komada za reči spiska koje se broje. Red bez tržišta važi za
-- sve zemlje istog jezika; tržište može da doda svoj red i on ima prednost.

-- Jedno pravilo izbora mase, za plan (SQL) i za Javu (DatabasePieceMassCatalog):
-- vrsta proizvoda pre reči spiska, reč spiska pre same reči, red tržišta pre
-- reda za ceo jezik.
CREATE FUNCTION app.piece_grams(
    target_market_id INTEGER,
    target_product_type_id BIGINT,
    target_shopping_intent_id BIGINT,
    target_word TEXT
)
RETURNS NUMERIC
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT mass.grams
    FROM app.piece_mass AS mass
    LEFT JOIN app.market AS market
      ON market.id = target_market_id
    WHERE (mass.market_id = target_market_id OR mass.market_id IS NULL)
      AND (mass.product_type_id = target_product_type_id
           OR mass.shopping_intent_id = target_shopping_intent_id
           OR (mass.normalized_word = target_word
               AND mass.language = COALESCE(market.default_language, app.default_language())))
    ORDER BY CASE WHEN mass.product_type_id IS NOT NULL THEN 0
                  WHEN mass.shopping_intent_id IS NOT NULL THEN 1
                  ELSE 2 END,
             (mass.market_id IS NULL),
             mass.id
    LIMIT 1
$$;

COMMENT ON FUNCTION app.piece_grams(INTEGER, BIGINT, BIGINT, TEXT) IS
    'Masa jednog komada po pravilu PieceMassCatalog: vrsta > reč spiska > reč, tržište > jezik (V156).';

-- Šta stavka traži, u jedinici u kojoj ovaj proizvod meri lanac. Plan
-- (StoreShoppingOfferRepository) ovo pita za svaki proizvod stavke:
--   * stavka nije komad voća ili povrća sa poznatom masom: isto što piše na
--     stavci, ništa se ne menja;
--   * proizvod se prodaje na komad (app.sale_mode_of, ili jedinica „KOM“ bez
--     veličine): isto, komadi (by_the_piece);
--   * inače grami: 3 komada × 100 g = 300 g, pa ih plan meri na kg ili u
--     pakovanja kao „limun 300 g“.
CREATE FUNCTION app.asked_in_shop(
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
                         AND weighed_out
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

-- Masa uz reč, a ne uz vrstu: vrsta je ovde samo „voće“ ili „povrće“, a
-- limun i lubenica nemaju istu masu. Svaki oblik reči sa spiska (shopping_
-- intent_alias, V87) je svoj red: „limun“ i „limuni“.
-- Brojevi su srednji komad iz tablica namirnica (USDA FoodData Central,
-- „medium“), zaokruženo; za ono što se kod nas prodaje krupnije ili sitnije
-- (domaći krastavac, crni luk) uzet je naš srednji komad. Bobice, grožđe,
-- trešnje i višnje se ne broje na komad i nemaju red.
INSERT INTO app.piece_mass (market_id, language, normalized_word, grams, source)
SELECT NULL, 'sr-Latn', word.word, word.grams,
       'srednji komad, USDA FoodData Central i domaći prosek (V156)'
FROM (
    VALUES
        -- voće
        ('limun', 100), ('limuni', 100),
        ('limeta', 70), ('limete', 70),
        ('avokado', 200),
        ('banana', 120), ('banane', 120), ('bananu', 120),
        ('pomorandza', 200), ('pomorandze', 200),
        ('narandza', 200), ('narandze', 200),
        ('mandarina', 90), ('mandarine', 90),
        ('klementine', 75),
        ('grejpfrut', 300),
        ('kivi', 75),
        ('ananas', 1500),
        ('mango', 300),
        ('jabuka', 180), ('jabuke', 180), ('jabuku', 180),
        ('kruska', 180), ('kruske', 180), ('krusku', 180),
        ('breskva', 150), ('breskve', 150),
        ('nektarina', 150), ('nektarine', 150),
        ('kajsija', 40), ('kajsije', 40),
        ('nar', 250),
        ('dunje', 300),
        ('lubenica', 7000), ('lubenicu', 7000),
        ('dinja', 1500), ('dinju', 1500),
        -- povrće
        ('paradajz', 150),
        ('krastavac', 250), ('krastavci', 250), ('krastavce', 250),
        ('paprika', 150), ('paprike', 150), ('papriku', 150),
        ('babura', 150), ('babure', 150),
        ('krompir', 200),
        ('slatki krompir', 250), ('batat', 250),
        ('luk', 120), ('crni luk', 120), ('luk crni', 120),
        ('crveni luk', 120), ('luk crveni', 120),
        ('beli luk', 50), ('luk beli', 50),
        ('praziluk', 250),
        ('sargarepa', 80), ('sargarepu', 80), ('mrkva', 80),
        ('celer', 500),
        ('cvekla', 200), ('cveklu', 200),
        ('kupus', 1500),
        ('kelj', 800),
        ('karfiol', 800),
        ('brokoli', 400),
        ('tikvica', 250), ('tikvice', 250),
        ('bundeva', 2000), ('bundevu', 2000),
        ('patlidzan', 300), ('plavi patlidzan', 300),
        ('zelena salata', 300),
        ('rotkva', 300)
) AS word (word, grams);
