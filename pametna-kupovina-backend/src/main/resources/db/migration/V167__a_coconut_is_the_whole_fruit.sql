-- Kokosov orah je ceo plod (poznata sitnica posle šestog testa, 11.11.2026):
-- „kokosov orah“ je birao komadiće ili rendani kokos umesto celog oraha.
-- Reč spiska (V164) traži samo „kokos“ u nazivu među voćem, a pravilo voća
-- (V87) ne prepoznaje „rendani“ ni „komadići“ kao obrađeno voće. Bez količine
-- voće se ne meri na kg kad ga lanac prodaje na komad ili u kesici (V137), pa
-- je odlučivala cena pakovanja: kesica od 100 g je jeftinija od celog oraha.
--
-- Sada je obrađen kokos neuobičajen izbor za ovu reč (kao beli šećer pre
-- šećera u prahu, V125): rendani, komadići, kockice, listići, čips, sušeni,
-- brašno, mleko, voda, ulje, kao i kesica do 999 g. Rangira se, ne izbacuje:
-- radnja koja ima samo rendani kokos i dalje ga nudi, a plan ga zove zamenom.
-- Vrsta proizvoda se ne menja (voće ostaje voće), pa ništa ne treba ponovo
-- razvrstati.

UPDATE app.shopping_intent_alias
SET uncommon_name_pattern =
        '\m(rendan[a-z]*|renda|komadic[a-z]*|komadi|kockic[a-z]*|kocke|seckan[a-z]*|mleven[a-z]*'
        || '|ljuspic[a-z]*|listic[a-z]*|flake[a-z]*|chips|cips[a-z]*|susen[a-z]*|osusen[a-z]*|suv[a-z]*'
        || '|dehidr[a-z]*|liofil[a-z]*|desikat[a-z]*|kandir[a-z]*|zasladj[a-z]*|brasn[a-z]*|mlek[a-z]*'
        || '|voda|vode|napit[a-z]*|drink|ulj[a-z]*|secer[a-z]*|krem[a-z]*|cokol[a-z]*|bar|keks[a-z]*'
        || '|pire|pulp[a-z]*|smrznut[a-z]*|zamrznut[a-z]*)\M'
        || '|\m[0-9]{1,3} (g|gr)\M'
WHERE normalized_alias = 'kokosov orah';

-- Drugi oblici iste reči, sa istim uslovima kao „kokosov orah“ (V164).
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern,
    uncommon_name_pattern
)
SELECT coconut.shopping_intent_id,
       word.alias,
       coconut.priority,
       coconut.required_name_pattern,
       coconut.uncommon_name_pattern
FROM (VALUES ('kokos orah'), ('kokosovi orasi'), ('kokosove orahe'), ('kokosovog oraha'),
             ('ceo kokos'), ('svez kokos')) AS word (alias)
CROSS JOIN (
    SELECT alias.shopping_intent_id,
           alias.priority,
           alias.required_name_pattern,
           alias.uncommon_name_pattern
    FROM app.shopping_intent_alias AS alias
    WHERE alias.normalized_alias = 'kokosov orah'
) AS coconut
ON CONFLICT (normalized_alias) DO NOTHING;

-- „kokosov orah 2“ su dva oraha, ne 2 kg (kao „limun 3“, V156). Srednji
-- kokos sa ljuskom je oko 600 g (USDA FoodData Central daje oko 400 g samog
-- jezgra); lanac koji ga meri na kg plaća 2 × 600 g.
INSERT INTO app.piece_mass (market_id, language, normalized_word, grams, source)
SELECT NULL, 'sr-Latn', word.word, 600,
       'srednji kokos sa ljuskom, USDA FoodData Central i domaći prosek (V167)'
FROM (VALUES ('kokosov orah'), ('kokos orah'), ('kokosovi orasi'), ('kokosove orahe'),
             ('kokosovog oraha'), ('ceo kokos'), ('svez kokos')) AS word (word)
WHERE NOT EXISTS (
    SELECT 1 FROM app.piece_mass AS mass
    WHERE mass.market_id IS NULL
      AND mass.language = 'sr-Latn'
      AND mass.normalized_word = word.word
);
