-- Kad pregledač izgubi zapamćen spisak (obrisani podaci), web je pravio nov
-- prazan spisak pored starog na istom uređaju (šesti test, stavka 6). Ti
-- prazni spiskovi se više ne otvaraju, a nalog ih vuče u svakom pregledu.
--
-- Briše se samo spisak koji:
--   * nema nijednu stavku,
--   * nije ni napravljen ni menjan poslednjih 7 dana,
--   * je na nalogu (uređaju) koji ima i drugi spisak.
-- Nalog uvek zadržava bar jedan spisak: ako su mu svi spiskovi ovakvi,
-- ostaje najskorije menjan. Obrisani (active = FALSE) se ne diraju.
--
-- Pre brisanja se redovi kopiraju u app.shopping_list_removed_v163, pa se
-- svaki spisak može vratiti (vidi kraj fajla). Pun backup baze je ops/backup.sh.

CREATE TABLE app.shopping_list_removed_v163 AS
WITH active_list AS (
    SELECT list.*,
           NOT EXISTS (
               SELECT 1
               FROM app.shopping_list_item AS item
               WHERE item.shopping_list_id = list.id
           )
           AND list.created_at < NOW() - INTERVAL '7 days'
           AND list.updated_at < NOW() - INTERVAL '7 days' AS abandoned
    FROM app.shopping_list AS list
    WHERE list.active = TRUE
),
ranked AS (
    SELECT active_list.*,
           ROW_NUMBER() OVER (
               PARTITION BY account_id
               ORDER BY abandoned ASC, updated_at DESC, id DESC
           ) AS kept_rank
    FROM active_list
)
SELECT id, public_id, account_id, name, created_at, updated_at, active,
       NOW() AS removed_at
FROM ranked
WHERE abandoned
  AND kept_rank > 1;

ALTER TABLE app.shopping_list_removed_v163
    ADD PRIMARY KEY (id);

DELETE FROM app.shopping_list AS list
USING app.shopping_list_removed_v163 AS removed
WHERE list.id = removed.id
  AND NOT EXISTS (
      SELECT 1
      FROM app.shopping_list_item AS item
      WHERE item.shopping_list_id = list.id
  );

-- Vraćanje jednog ili svih (isti broj i isti nasumični ID):
--   INSERT INTO app.shopping_list (id, public_id, account_id, name, created_at, updated_at, active)
--   OVERRIDING SYSTEM VALUE
--   SELECT id, public_id, account_id, name, created_at, updated_at, active
--   FROM app.shopping_list_removed_v163
--   WHERE account_id IN (SELECT id FROM app.account);
