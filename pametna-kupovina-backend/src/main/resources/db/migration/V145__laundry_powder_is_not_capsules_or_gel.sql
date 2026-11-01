-- Oblik deterdženta za veš (peti test, 1.11.2026.):
--
-- "prašak za veš Persil 4,5kg" je u „Jedna stanica“ postao 9 kutija
-- „Persil Discs“ od 561 g, 9.531 RSD, dok redovno pakovanje praška od 4,5 kg
-- košta 1.499 RSD. Kapsule su za „prašak“ bile samo ređi izbor, a diskovi ni
-- to: radnja koja ima samo diskove ih je ponudila kao prašak.
--
-- Oblik se sad mora poklopiti: prašak je prašak, tečni deterdžent i gel su
-- tečni, a kapsule (diskovi, „pods“) su kapsule. Radnja koja nema traženi
-- oblik nema tu stavku; plan je kupuje drugde ili kaže da je nema.

-- Kapsule, diskovi, gel i litri nisu prašak.
UPDATE app.shopping_intent_alias
SET required_name_pattern =
        '^(?!.*\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?|gel[a-z]*|tecn[a-z]*|liquid|tablet[a-z]*'
        || '|tabs?|maramic[a-z]*|listic[a-z]*|sheets?|ml|l|lit|litar[a-z]*|litr[a-z]*)\M)'
WHERE normalized_alias IN ('prasak za ves', 'prasak za pranje vesa')
  AND required_name_pattern IS NULL;

-- Za tečni deterdžent i gel: tečan oblik, ne kapsule ni prašak.
UPDATE app.shopping_intent_alias
SET required_name_pattern =
        '^(?!.*\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?|prasak|praska|powder|kg)\M)'
        || '.*\m(gel[a-z]*|tecn[a-z]*|liquid|ml|l|lit|litar[a-z]*|litr[a-z]*)\M'
WHERE normalized_alias IN ('tecni deterdzent', 'gel za ves')
  AND required_name_pattern IS NULL;

-- Samo "deterdžent za veš": prašak ili tečni; kapsule i maramice tek kad
-- radnja nema drugo.
UPDATE app.shopping_intent_alias
SET uncommon_name_pattern =
        '\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?|tablet[a-z]*|maramic[a-z]*|listic[a-z]*|sheets?)\M'
WHERE normalized_alias IN ('deterdzent za ves', 'deterdzent za pranje vesa')
  AND uncommon_name_pattern IS NULL;

-- Diskovi su kapsule i za „prašak“ koji ih nema u pravilu (V125).
UPDATE app.shopping_intent_alias
SET uncommon_name_pattern = REPLACE(uncommon_name_pattern, '|caps|', '|caps|disc[a-z]*|disk[a-z]*|pods?|')
WHERE normalized_alias IN ('prasak za ves', 'prasak za pranje vesa')
  AND uncommon_name_pattern IS NOT NULL
  AND POSITION('disc[a-z]*' IN uncommon_name_pattern) = 0;

-- Reči za oblik koje spisak piše.
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, word.alias, 5, word.required
FROM (
    VALUES
        ('deterdzent u prahu',
            '^(?!.*\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?|gel[a-z]*|tecn[a-z]*|liquid|tablet[a-z]*'
            || '|tabs?|maramic[a-z]*|listic[a-z]*|sheets?|ml|l|lit|litar[a-z]*|litr[a-z]*)\M)'),
        ('tecni deterdzent za ves',
            '^(?!.*\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?|prasak|praska|powder|kg)\M)'
            || '.*\m(gel[a-z]*|tecn[a-z]*|liquid|ml|l|lit|litar[a-z]*|litr[a-z]*)\M'),
        ('gel za pranje vesa',
            '^(?!.*\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?|prasak|praska|powder|kg)\M)'
            || '.*\m(gel[a-z]*|tecn[a-z]*|liquid|ml|l|lit|litar[a-z]*|litr[a-z]*)\M'),
        ('kapsule za ves', '\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?)\M'),
        ('kapsule za pranje vesa', '\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?)\M'),
        ('kapsule za pranje', '\m(kapsul[a-z]*|caps|disc[a-z]*|disk[a-z]*|pods?)\M')
) AS word (alias, required)
JOIN app.shopping_intent AS intent
  ON intent.code = 'LAUNDRY_DETERGENT'
ON CONFLICT (normalized_alias) DO NOTHING;
