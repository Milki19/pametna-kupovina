-- A product was shown under the alphabetically first of its chains' names,
-- often METRO's "0.5L ZAJECARSKO SVET.PIVO LIM-STAND.-VAR." or a name led by
-- a stock code ("369680 Dove gel za tusiranje ..."). It is now shown under the
-- clearest of them: one that states the product's own size, is written in
-- upper and lower case with Serbian letters, and has no METRO suffix or stock
-- code. What is left of those is cut from the name shown.

-- "0.5L ZAJECARSKO SVET.PIVO LIM-STAND.-VAR." -> "ZAJECARSKO SVET.PIVO LIM 0.5L"
-- "369680 Dove gel 700 ml" -> "Dove gel 700 ml"; "COK.MILKA OREO 300G-362" -> "COK.MILKA OREO 300G"
CREATE OR REPLACE FUNCTION app.clean_product_name(product_name TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(
               NULLIF(BTRIM(REGEXP_REPLACE(sized, '\s{2,}', ' ', 'g')), ''),
               BTRIM(product_name)
           )
    FROM (
        SELECT CASE
                   -- A size in front, as METRO writes it, goes to the end.
                   WHEN coded ~* '^[0-9]+([.,][0-9]+)?(\s?X\s?[0-9]+([.,][0-9]+)?)?\s?(L|ML|G|GR|KG)\s+[[:alpha:]]'
                       THEN REGEXP_REPLACE(
                           coded,
                           '^([0-9]+(?:[.,][0-9]+)?(?:\s?X\s?[0-9]+(?:[.,][0-9]+)?)?\s?(?:L|ML|G|GR|KG))\s+(.*)$',
                           '\2 \1',
                           'i'
                       )
                   ELSE coded
               END AS sized
        FROM (
            SELECT REGEXP_REPLACE(
                       REGEXP_REPLACE(
                           REGEXP_REPLACE(BTRIM(product_name), '\s*-\s*STAND\.?\s*-\s*VAR\.?\s*$', '', 'i'),
                           '^[0-9]{5,}\s+',
                           ''
                       ),
                       '([[:alnum:]])\s*-\s*[0-9]{2,4}(/[0-9]{2,4})*\s*$',
                       '\1'
                   ) AS coded
        ) AS without_codes
    ) AS with_size_last
$$;

CREATE OR REPLACE FUNCTION app.refresh_family_display_names()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    UPDATE app.product_family AS family
    SET display_name = choice.display_name,
        updated_at = NOW()
    FROM (
        SELECT DISTINCT ON (product.product_family_id)
               product.product_family_id,
               LEFT(app.clean_product_name(product.name), 500) AS display_name
        FROM app.retailer_product AS product
        JOIN app.product_family AS owner
          ON owner.id = product.product_family_id
        GROUP BY product.product_family_id,
                 product.name,
                 product.quantity_value,
                 owner.quantity_value
        ORDER BY product.product_family_id,
                 -- A name stating another size belongs to a case or a
                 -- different pack of the product.
                 (CASE WHEN product.quantity_value IS NOT DISTINCT FROM owner.quantity_value THEN 8 ELSE 0 END
                  + CASE WHEN product.name !~* 'STAND\.?\s*-\s*VAR' THEN 4 ELSE 0 END
                  + CASE WHEN product.name <> UPPER(product.name) THEN 2 ELSE 0 END
                  + CASE WHEN product.name ~ '[čćšžđČĆŠŽĐ]' THEN 1 ELSE 0 END
                  + CASE WHEN product.name !~ '^[0-9]{5,}\s' THEN 1 ELSE 0 END) DESC,
                 COUNT(*) DESC,
                 LENGTH(product.name),
                 product.name
    ) AS choice
    WHERE family.id = choice.product_family_id
      AND family.display_name IS DISTINCT FROM choice.display_name;
END;
$function$;

SELECT app.refresh_family_display_names();
