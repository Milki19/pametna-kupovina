-- Broj koji se vidi u nazivu Maxi cenovnika nije jedinstven na nivou cele
-- mreže. Zvanični lokator ima stabilan S-id, pa šest već podržanih fajlova
-- vezujemo za njihove tačne objekte. ID reda ostaje isti i postojeće cene
-- zato ne menjaju vlasnika.
DO $$
DECLARE
    mapping RECORD;
    retailer_id_value BIGINT;
    legacy_id BIGINT;
    official_id BIGINT;
BEGIN
    FOR mapping IN
        SELECT *
        FROM (
            VALUES
                ('MAXI', '508', 'S841'),
                ('MAXI', '538', 'S538'),
                ('MAXI', '512', 'S512'),
                ('MAXI', '513', 'S513'),
                ('MAXI', '541', 'S541'),
                ('MAXI', '544', 'S927'),
                (
                    'IDEA_RODA',
                    'idea-valjevo-karadjordjeva-62',
                    'IDEA_GEO_44_270271_19_886746'
                ),
                (
                    'IDEA_RODA',
                    'roda-valjevo-bulevar-palih-boraca',
                    'RODA_407'
                )
        ) AS mappings(retailer_code, legacy_code, official_code)
    LOOP
        SELECT id INTO retailer_id_value
        FROM app.retailer
        WHERE code = mapping.retailer_code;

        SELECT id INTO legacy_id
        FROM app.store
        WHERE retailer_id = retailer_id_value
          AND external_code = mapping.legacy_code;

        IF legacy_id IS NULL THEN
            CONTINUE;
        END IF;

        SELECT id INTO official_id
        FROM app.store
        WHERE retailer_id = retailer_id_value
          AND external_code = mapping.official_code;

        IF official_id IS NOT NULL AND official_id <> legacy_id THEN
            IF EXISTS (
                SELECT 1 FROM app.current_price_offer
                WHERE store_id = official_id
            ) OR EXISTS (
                SELECT 1 FROM app.price_observation
                WHERE store_id = official_id
            ) THEN
                RAISE EXCEPTION
                    'Zvanična lokacija %/% već ima cene; automatsko spajanje je zaustavljeno',
                    mapping.retailer_code,
                    mapping.official_code;
            END IF;

            DELETE FROM app.store WHERE id = official_id;
        END IF;

        UPDATE app.store
        SET external_code = mapping.official_code,
            updated_at = NOW()
        WHERE id = legacy_id;
    END LOOP;
END
$$;

-- Maxi lokacija sama po sebi nije dokaz da za nju imamo store-level
-- cenovnik. U preporuke ulaze samo objekti sa stvarno uvezenom ponudom.
UPDATE app.store AS store
SET pricing_eligible = EXISTS (
        SELECT 1
        FROM app.current_price_offer AS offer
        WHERE offer.store_id = store.id
          AND offer.scope_type = 'STORE'
    ),
    pricing_ineligibility_reason = CASE
        WHEN EXISTS (
            SELECT 1
            FROM app.current_price_offer AS offer
            WHERE offer.store_id = store.id
              AND offer.scope_type = 'STORE'
        ) THEN NULL
        ELSE 'NO_STORE_PRICE_FEED'
    END,
    updated_at = NOW()
FROM app.retailer AS retailer
WHERE retailer.id = store.retailer_id
  AND retailer.code = 'MAXI';
