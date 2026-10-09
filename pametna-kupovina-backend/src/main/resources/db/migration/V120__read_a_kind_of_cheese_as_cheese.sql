-- „Neka aplikacija izabere najpovoljnije“ za „sir gauda“ server je odbijao:
-- „sir“ je vrsta proizvoda, a „gauda“ nije brend, pa stavka nije bila „samo
-- vrsta proizvoda“. Gauda, edamer, trapist i mocarela su vrste sira koje
-- pravilo za sir već prepoznaje (V44); sad su i reči na spisku, kao ukusi
-- jogurta (V50): sir, ali samo onaj u čijem nazivu stoji ta vrsta.
-- Postojeći alias se ne menja.

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, alias.phrase, 5, kind.pattern
FROM (
    VALUES
        ('gauda', '\m(gauda|gouda)\M'),
        ('gouda', '\m(gauda|gouda)\M'),
        ('edamer', '\medam[a-z]*\M'),
        ('trapist', '\mtrapist[a-z]*\M'),
        ('mocarela', '\m(mocarel[a-z]*|mozzarel[a-z]*|mozarel[a-z]*)\M'),
        ('mozzarella', '\m(mocarel[a-z]*|mozzarel[a-z]*|mozarel[a-z]*)\M')
) AS kind (word, pattern)
CROSS JOIN LATERAL (
    VALUES (kind.word), ('sir ' || kind.word), (kind.word || ' sir')
) AS alias (phrase)
JOIN app.shopping_intent AS intent
  ON intent.code = 'CHEESE'
ON CONFLICT (normalized_alias) DO NOTHING;
