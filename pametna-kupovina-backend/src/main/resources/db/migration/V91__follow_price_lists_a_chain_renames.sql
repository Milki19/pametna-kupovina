-- On 2026-09-16 two chains renamed their price lists. Super Vero's one list
-- "Veropoulos d.o.o. OJ1" became "Veropoulos d.o.o.", and Univerexport's five
-- zone lists (C0-MC0 … C3-MC3) became "L", "M" and "S". The next import retired
-- every link to a list that is no longer published (as intended since
-- 2026-09-13), so Super Vero 1 dropped out of every plan and neither chain
-- was shown under "Lanci bez poznate adrese" any more, although both keep
-- publishing about 12,000 and 16,000 prices a day.
--
-- After each import a chain's links now follow its newest lists:
--
-- 1. A chain that publishes exactly one list renamed it: a placed shop left
--    without a list follows it, when every such shop was on one and the same
--    old list. With several lists (IDEA replaced five zone lists with three
--    brand lists) nothing tells which list a shop now quotes, so nothing is
--    linked.
-- 2. A chain whose lists are shown without an address (V65: METRO, Univerexport,
--    Super Vero) gets such an entry for every list in its newest snapshot that
--    no placed shop quotes; an entry for a list a placed shop quotes stays off,
--    so no list is shown twice.

CREATE FUNCTION app.keep_price_list_links_current(target_retailer_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $function$
DECLARE
    newest_lists TEXT[];
BEGIN
    WITH chain_offer AS MATERIALIZED (
        SELECT offer.retailer_format_name,
               offer.price_date
        FROM app.current_price_offer AS offer
        JOIN app.retailer_product AS product
          ON product.id = offer.retailer_product_id
        WHERE product.retailer_id = target_retailer_id
          AND offer.scope_type = 'STORE_FORMAT'
    )
    SELECT ARRAY_AGG(list.name ORDER BY list.name)
    INTO newest_lists
    FROM (
        SELECT DISTINCT ON (LOWER(BTRIM(chain_offer.retailer_format_name)))
               BTRIM(chain_offer.retailer_format_name) AS name
        FROM chain_offer
        WHERE chain_offer.price_date = (SELECT MAX(price_date) FROM chain_offer)
        ORDER BY LOWER(BTRIM(chain_offer.retailer_format_name)),
                 BTRIM(chain_offer.retailer_format_name)
    ) AS list;

    -- Nothing imported yet: there is no list to follow.
    IF newest_lists IS NULL THEN
        RETURN;
    END IF;

    IF CARDINALITY(newest_lists) = 1 THEN
        WITH stranded AS (
            SELECT DISTINCT ON (mapping.store_external_code)
                   mapping.id,
                   mapping.retailer_format_name
            FROM app.store_price_format_mapping AS mapping
            JOIN app.store AS shop
              ON shop.retailer_id = mapping.retailer_id
             AND shop.external_code = mapping.store_external_code
            WHERE mapping.retailer_id = target_retailer_id
              AND mapping.active = FALSE
              AND mapping.verification_status = 'VERIFIED'
              AND mapping.mapping_method <> 'PRICE_LIST_WITHOUT_LOCATION'
              AND shop.active = TRUE
              AND shop.location IS NOT NULL
              AND LOWER(BTRIM(mapping.retailer_format_name)) <> LOWER(newest_lists[1])
              AND NOT EXISTS (
                  SELECT 1
                  FROM app.store_price_format_mapping AS linked
                  WHERE linked.retailer_id = mapping.retailer_id
                    AND linked.store_external_code = mapping.store_external_code
                    AND linked.active = TRUE
                    AND linked.verification_status = 'VERIFIED'
              )
            ORDER BY mapping.store_external_code,
                     mapping.updated_at DESC,
                     mapping.id DESC
        )
        UPDATE app.store_price_format_mapping AS mapping
        SET retailer_format_name = newest_lists[1],
            mapping_method = 'SINGLE_PRICE_LIST_RENAMED',
            active = TRUE,
            source_last_seen_at = NOW(),
            updated_at = NOW()
        FROM stranded
        WHERE mapping.id = stranded.id
          AND (
              SELECT COUNT(DISTINCT LOWER(BTRIM(old.retailer_format_name)))
              FROM stranded AS old
          ) = 1;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM app.retailer AS retailer
        JOIN app.store_format AS format
          ON format.retailer_id = retailer.id
         AND format.code = retailer.code || '_PRICE_LIST'
        WHERE retailer.id = target_retailer_id
    ) THEN
        RETURN;
    END IF;

    DROP TABLE IF EXISTS pg_temp.unplaced_list;
    CREATE TEMP TABLE unplaced_list ON COMMIT DROP AS
    SELECT list.name
    FROM UNNEST(newest_lists) AS list (name)
    WHERE NOT EXISTS (
        SELECT 1
        FROM app.store_price_format_mapping AS placed
        JOIN app.store AS shop
          ON shop.retailer_id = placed.retailer_id
         AND shop.external_code = placed.store_external_code
        WHERE placed.retailer_id = target_retailer_id
          AND placed.active = TRUE
          AND placed.verification_status = 'VERIFIED'
          AND placed.mapping_method <> 'PRICE_LIST_WITHOUT_LOCATION'
          AND shop.active = TRUE
          AND shop.location IS NOT NULL
          AND LOWER(BTRIM(placed.retailer_format_name)) = LOWER(list.name)
    );

    INSERT INTO app.store (
        retailer_id,
        external_code,
        name,
        store_format_id,
        active,
        location,
        geocoding_status,
        pricing_eligible,
        pricing_ineligibility_reason
    )
    SELECT retailer.id,
           LEFT('PRICE_LIST:' || unplaced_list.name, 100),
           LEFT(unplaced_list.name, 200),
           format.id,
           TRUE,
           NULL,
           'PENDING',
           FALSE,
           'LOCATION_NOT_VERIFIED'
    FROM unplaced_list
    JOIN app.retailer AS retailer
      ON retailer.id = target_retailer_id
    JOIN app.store_format AS format
      ON format.retailer_id = retailer.id
     AND format.code = retailer.code || '_PRICE_LIST'
    ON CONFLICT (retailer_id, external_code) DO UPDATE SET
        active = TRUE,
        updated_at = NOW()
    WHERE store.active = FALSE;

    INSERT INTO app.store_price_format_mapping (
        retailer_id,
        source_store_code,
        store_external_code,
        retailer_format_name,
        verification_status,
        mapping_method,
        source_url,
        source_last_seen_at,
        active
    )
    SELECT entry.retailer_id,
           entry.external_code,
           entry.external_code,
           entry.name,
           'VERIFIED',
           'PRICE_LIST_WITHOUT_LOCATION',
           'derived:published price list',
           NOW(),
           TRUE
    FROM unplaced_list
    JOIN app.store AS entry
      ON entry.retailer_id = target_retailer_id
     AND entry.external_code = LEFT('PRICE_LIST:' || unplaced_list.name, 100)
    ON CONFLICT (retailer_id, source_store_code) DO UPDATE SET
        retailer_format_name = EXCLUDED.retailer_format_name,
        verification_status = 'VERIFIED',
        mapping_method = EXCLUDED.mapping_method,
        source_last_seen_at = EXCLUDED.source_last_seen_at,
        active = TRUE,
        updated_at = NOW()
    WHERE store_price_format_mapping.active = FALSE
       OR store_price_format_mapping.verification_status <> 'VERIFIED';

    UPDATE app.store_price_format_mapping AS entry
    SET active = FALSE,
        updated_at = NOW()
    WHERE entry.retailer_id = target_retailer_id
      AND entry.active = TRUE
      AND entry.mapping_method = 'PRICE_LIST_WITHOUT_LOCATION'
      AND LOWER(BTRIM(entry.retailer_format_name)) = ANY (
          SELECT LOWER(list.name)
          FROM UNNEST(newest_lists) AS list (name)
      )
      AND NOT EXISTS (
          SELECT 1
          FROM unplaced_list
          WHERE LOWER(unplaced_list.name) = LOWER(BTRIM(entry.retailer_format_name))
      );

    DROP TABLE unplaced_list;
END;
$function$;

SELECT app.keep_price_list_links_current(retailer.id)
FROM app.retailer AS retailer;

-- A placed shop linked above can be priced again right away; every import
-- recomputes eligibility the same way.
UPDATE app.store AS shop
SET pricing_eligible = TRUE,
    pricing_ineligibility_reason = NULL,
    updated_at = NOW()
WHERE shop.pricing_eligible = FALSE
  AND shop.active = TRUE
  AND shop.location IS NOT NULL
  AND shop.geocoding_status IN ('AUTO_VERIFIED', 'MANUALLY_VERIFIED')
  AND EXISTS (
      SELECT 1
      FROM app.store_price_format_mapping AS mapping
      WHERE mapping.retailer_id = shop.retailer_id
        AND mapping.store_external_code = shop.external_code
        AND mapping.active = TRUE
        AND mapping.verification_status = 'VERIFIED'
        AND mapping.mapping_method = 'SINGLE_PRICE_LIST_RENAMED'
        AND EXISTS (
            SELECT 1
            FROM app.current_price_offer AS offer
            JOIN app.retailer_product AS product
              ON product.id = offer.retailer_product_id
             AND product.retailer_id = shop.retailer_id
            WHERE offer.scope_type = 'STORE_FORMAT'
              AND LOWER(BTRIM(offer.retailer_format_name))
                  = LOWER(BTRIM(mapping.retailer_format_name))
        )
  );
