-- Prvi plan za 100 stavki trajao je 4,0 s umesto 2,3 s (provera uživo
-- 11.11.2026, posle V165). Uzrok je app.asked_in_shop (V156, V165): plan je
-- pita za svaki proizvod svake stavke (desetine hiljada redova), a funkcija
-- koja vraća jedan red (OUT parametri) Postgres ne ume da ugradi u upit,
-- pa je svaki red bio poseban poziv sa svojim izvršavanjem.
--
-- Ista funkcija kao skup redova (RETURNS TABLE) Postgres ugrađuje u upit
-- plana kao podupit: telo, parametri i rezultat su isti, i telo i dalje
-- vraća tačno jedan red, pa CROSS JOIN LATERAL daje iste redove i iste
-- izbore. Proba u oblaku (Postgres 16, 230.400 parova stavka × proizvod):
-- stara 19,5 s, nova 0,7 s, nijedan različit rezultat.
--
-- Potpis se menja (skup redova umesto jednog), pa funkcija mora da se
-- obriše i napravi ponovo. Ništa u bazi ne zavisi od nje; plan je zove po
-- imenu (StoreShoppingOfferRepository).

DROP FUNCTION app.asked_in_shop(NUMERIC, VARCHAR, BOOLEAN, BIGINT, BIGINT, TEXT);

CREATE FUNCTION app.asked_in_shop(
    item_target_quantity NUMERIC,
    item_base_unit VARCHAR,
    weighed_out BOOLEAN,
    target_product_id BIGINT,
    target_shopping_intent_id BIGINT,
    target_word TEXT
)
RETURNS TABLE (
    target_quantity NUMERIC,
    required_base_unit VARCHAR,
    by_the_piece BOOLEAN
)
LANGUAGE sql
STABLE
PARALLEL SAFE
ROWS 1
AS $$
    SELECT CASE WHEN piece.grams IS NULL OR piece.sold_by_the_piece
                THEN item_target_quantity
                ELSE item_target_quantity * piece.grams
           END,
           CASE WHEN piece.grams IS NULL OR piece.sold_by_the_piece
                THEN item_base_unit
                ELSE 'g'
           END::VARCHAR,
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
            -- Masa se pita jednom po redu: bez ovoga Postgres prepiše
            -- izraz na četiri mesta i pita app.piece_grams četiri puta.
            OFFSET 0
        ) AS weighed
        LEFT JOIN app.retailer_product AS product
          ON weighed.grams IS NOT NULL
         AND product.id = target_product_id
    ) AS piece
$$;

COMMENT ON FUNCTION app.asked_in_shop(NUMERIC, VARCHAR, BOOLEAN, BIGINT, BIGINT, TEXT) IS
    'Šta stavka traži u jedinici u kojoj lanac meri proizvod (V156, V165); skup od jednog reda da bi ga plan ugradio u upit (V170).';
