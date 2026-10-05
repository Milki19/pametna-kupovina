-- Since 03.10.2026 METRO publishes one chain-wide list ("Metro CashCarry")
-- instead of one list per store (ST ZEMUN, ST Vidikovac, ... nine lists). V91
-- moves a shop to a chain's one renamed list only when every stranded shop was
-- on one and the same old list, so METRO's nine stores, each on its own old
-- list, were left without prices and the chain-wide list was shown only as an
-- entry without an address (seen on the database copy, 05.10).
--
-- When a chain's newest snapshot holds exactly one list, that list is the only
-- price any of its shops can quote, however many lists the shops followed
-- before. So every placed shop left without a list now follows it. With
-- several new lists nothing changes: nothing tells which list a shop quotes.
-- The import that brings such a change is still held for a format review
-- (fewer lists than acknowledged), so a person sees it.

CREATE OR REPLACE FUNCTION app.keep_price_list_links_current(target_retailer_id BIGINT)
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
        WHERE mapping.id = stranded.id;
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
