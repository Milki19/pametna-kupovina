-- A specific flavor must not silently select a mixed/assorted flavor SKU.
-- Use the existing alias constraint; no new product types or runtime catalog scan.
WITH flavor_roots(root) AS (VALUES
 ('jagod[a-z]*'), ('malin[a-z]*'), ('breskv[a-z]*'), ('visnj[a-z]*'),
 ('tresnj[a-z]*'), ('borov[a-z]*'), ('banan[a-z]*'), ('vanil[a-z]*'),
 ('kajsij[a-z]*'), ('ananas[a-z]*'), ('kokos[a-z]*'), ('sums[a-z]*'),
 ('cok[a-z]*'), ('kakao')
), constraints AS (
 SELECT a.id, '^(?!.*\m(' || string_agg(f.root, '|' ORDER BY f.root) ||
   '|mix|miks|mesano|razni|razliciti)\M).*' || a.required_name_pattern AS pattern
 FROM app.shopping_intent_alias a CROSS JOIN flavor_roots f
 WHERE a.required_name_pattern IS NOT NULL
   AND position(f.root IN a.required_name_pattern)=0
 GROUP BY a.id,a.required_name_pattern
)
UPDATE app.shopping_intent_alias a SET required_name_pattern=c.pattern
FROM constraints c WHERE a.id=c.id;
