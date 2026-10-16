-- Samo "sir" na spisku je obični beli ili žuti sir (V126). Proveri uživo
-- 16.10.2026. je ipak izabrao Lidlov sveži sir 0% od 500 g: najjeftiniji je,
-- a "sveži" i "0% mm" nisu bili među neobičnim vrstama. Sveži, niskomasni,
-- zrnasti i krem sir sad dolaze tek kad radnja nema drugi. "Sveži sir" i
-- "sitan sir" na spisku i dalje traže baš tu vrstu.
--
-- Naziv je normalizovan: "Sveži sir 0% m.m." je "svezi sir 0 m m".

UPDATE app.shopping_intent_alias
SET uncommon_name_pattern = uncommon_name_pattern
        || '|\m(svez[a-z]*|svjez[a-z]*|niskomasn[a-z]*|light|lajt|zrnast[a-z]*|cottage|kvark|quark'
        || '|skyr|krem|philadelphia|0 m ?m)\M'
WHERE normalized_alias = 'sir'
  AND uncommon_name_pattern IS NOT NULL
  AND uncommon_name_pattern NOT LIKE '%svez%';

-- Ko napiše "sveži sir", traži baš njega, kao "gauda" ili "trapist" (V120).
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, alias.phrase, 5, '\m(svez[a-z]*|svjez[a-z]*)\M'
FROM (
    VALUES ('svezi sir'), ('sir svezi'), ('svjezi sir')
) AS alias (phrase)
JOIN app.shopping_intent AS intent
  ON intent.code = 'CHEESE'
ON CONFLICT (normalized_alias) DO NOTHING;
