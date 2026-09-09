-- Parse raw names, never normalized names that lost decimal separators.
-- Only explicit compatible units; never assume one litre equals one kilogram.
WITH parsed AS (
 SELECT id, regexp_match(lower(name), '([0-9]+(?:[.,][0-9]+)?)\s*(g|gr|ml)?\s*\+\s*([0-9]+(?:[.,][0-9]+)?)\s*(g|gr|ml)\M') AS m
 FROM app.retailer_product
 WHERE name LIKE '%+%' AND name NOT LIKE '%+%+%' AND lower(name) !~ '[0-9]\s*[x×]'
)
UPDATE app.retailer_product p
SET quantity_value=replace(m[1],',','.')::numeric+replace(m[3],',','.')::numeric,
    base_unit=CASE WHEN m[4]='ml' THEN 'ml' ELSE 'g' END
FROM parsed WHERE p.id=parsed.id AND m IS NOT NULL
 AND (m[2] IS NULL OR m[2]=m[4] OR (m[2] IN ('g','gr') AND m[4] IN ('g','gr')));

WITH parsed AS (
 SELECT id, regexp_match(lower(name), '\m(?:jaja|jaje)\M.*?\m([1-9][0-9]*)\s*/\s*1\M') AS m
 FROM app.retailer_product WHERE normalized_name ~ '\m(jaja|jaje)\M'
)
UPDATE app.retailer_product p SET quantity_value=m[1]::numeric,base_unit='piece'
FROM parsed WHERE p.id=parsed.id AND m IS NOT NULL;
