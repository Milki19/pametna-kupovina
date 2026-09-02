-- Zvanični cenovnici po Uredbi. IDEA i Roda dele jedan izvor i zato su
-- jedan retailer u bazi, ali imaju odvojene formate i fizičke objekte.
INSERT INTO app.retailer (
    code,
    name,
    dataset_url
)
VALUES
    (
        'LIDL',
        'Lidl',
        'https://data.gov.rs/s/resources/cenovnici-proizvoda-po-uredbi-o-obaveznoj-evidenciji-i-dostavljanju-cena-13/20260302-111532/cene-proizvoda-lidl.csv'
    ),
    (
        'IDEA_RODA',
        'IDEA / Roda',
        'https://data.gov.rs/s/resources/cenovnici-proizvoda-po-uredbi-o-obaveznoj-evidenciji-i-dostavljanju-cena-7/20260309-181722/cene-proizvoda-idea-marketi.csv'
    ),
    (
        'UNIVEREXPORT',
        'Univerexport',
        'https://data.gov.rs/s/resources/cenovnici-proizvoda-po-uredbi-o-obaveznoj-evidenciji-i-dostavljanju-cena-12/20260316-092331/cene-proizvoda-univerexport.csv'
    ),
    (
        'DIS',
        'DIS',
        'https://data.gov.rs/s/resources/cenovnici-proizvoda-po-uredbi-o-obaveznoj-evidenciji-i-dostavljanju-cena-8/20260223-090736/cene-proizvoda-ptpdisdoo.csv'
    )
ON CONFLICT (code)
DO UPDATE SET
    name = EXCLUDED.name,
    dataset_url = EXCLUDED.dataset_url;


INSERT INTO app.store_format (
    retailer_id,
    code,
    name,
    active
)
SELECT retailer.id,
       seed.format_code,
       seed.format_name,
       TRUE
FROM (
    VALUES
        ('LIDL', 'LIDL_KD', 'Lidl Srbija KD'),
        ('IDEA_RODA', 'IDEA_I0', 'IDEA MARKETI _ Cenovnik I0'),
        ('IDEA_RODA', 'RODA_RPLUS', 'IDEA MARKETI _ Cenovnik Rplus'),
        ('DIS', 'DIS_STANDARD', 'Dis Standard'),
        ('DIS', 'DIS_STANDARD_PLUS', 'Dis Standard +'),
        ('DIS', 'DIS_SUPER', 'Dis Super')
) AS seed(retailer_code, format_code, format_name)
JOIN app.retailer AS retailer
  ON retailer.code = seed.retailer_code
ON CONFLICT (retailer_id, code)
DO UPDATE SET
    name = EXCLUDED.name,
    active = TRUE,
    updated_at = NOW();


-- Adrese su potvrđene na zvaničnim stranicama lanaca. Koordinate su
-- geokodirane i zadržavamo poreklo rezultata radi kasnije revizije.
INSERT INTO app.store (
    retailer_id,
    external_code,
    name,
    address,
    city,
    location,
    active,
    store_format_id,
    geocoding_candidate,
    geocoding_status,
    geocoding_query,
    geocoding_source,
    geocoding_source_reference,
    geocoding_matched_address,
    geocoding_confidence,
    geocoded_at,
    geocoding_review_note,
    geocoding_reviewed_at
)
SELECT retailer.id,
       seed.external_code,
       seed.store_name,
       seed.address,
       seed.city,
       ST_SetSRID(
           ST_MakePoint(seed.longitude, seed.latitude),
           4326
       )::geography,
       TRUE,
       format.id,
       ST_SetSRID(
           ST_MakePoint(seed.longitude, seed.latitude),
           4326
       )::geography,
       'MANUALLY_VERIFIED',
       LOWER(seed.address || ', ' || seed.city),
       seed.geocoding_source,
       seed.source_reference,
       seed.address || ', ' || seed.city,
       seed.confidence,
       NOW(),
       seed.review_note,
       NOW()
FROM (
    VALUES
        (
            'LIDL',
            'LIDL_KD',
            'valjevo-pop-lukina-45',
            'Lidl Valjevo',
            'Pop Lukina 45',
            'Valjevo',
            44.2744152::DOUBLE PRECISION,
            19.8808274::DOUBLE PRECISION,
            'OPENSTREETMAP_NOMINATIM',
            'https://www.lidl.rs/s/sr-RS/pretraga-prodavnice/valjevo/pop-lukina-45/',
            1.0000::NUMERIC,
            'Zvanična Lidl adresa i OSM objekat supermarketa se poklapaju.'
        ),
        (
            'IDEA_RODA',
            'IDEA_I0',
            'idea-valjevo-karadjordjeva-62',
            'IDEA Valjevo',
            'Karađorđeva 62',
            'Valjevo',
            44.2705505::DOUBLE PRECISION,
            19.8868233::DOUBLE PRECISION,
            'OPENSTREETMAP_NOMINATIM',
            'https://www.idea.rs/O-Idei/Novosti/Sada-nas-potrazite-i-na-Wolt-aplikaciji/spisak',
            0.9500::NUMERIC,
            'Zvanična IDEA adresa i OSM komercijalni objekat se poklapaju.'
        ),
        (
            'IDEA_RODA',
            'RODA_RPLUS',
            'roda-valjevo-bulevar-palih-boraca',
            'Roda Market Valjevo',
            'Bulevar palih boraca 91-92 br. 1',
            'Valjevo',
            44.2696800::DOUBLE PRECISION,
            19.8985800::DOUBLE PRECISION,
            'CROSS_CHECKED_PUBLIC_MAP',
            'https://www.idea.rs/O-Idei/Novosti/Sada-nas-potrazite-i-na-Wolt-aplikaciji/spisak',
            0.9000::NUMERIC,
            'Adresa je zvanična; koordinata je proverena u javnim mapama.'
        )
) AS seed(
    retailer_code,
    format_code,
    external_code,
    store_name,
    address,
    city,
    latitude,
    longitude,
    geocoding_source,
    source_reference,
    confidence,
    review_note
)
JOIN app.retailer AS retailer
  ON retailer.code = seed.retailer_code
JOIN app.store_format AS format
  ON format.retailer_id = retailer.id
 AND format.code = seed.format_code
ON CONFLICT (retailer_id, external_code)
DO UPDATE SET
    name = EXCLUDED.name,
    address = EXCLUDED.address,
    city = EXCLUDED.city,
    location = EXCLUDED.location,
    active = TRUE,
    store_format_id = EXCLUDED.store_format_id,
    geocoding_candidate = EXCLUDED.geocoding_candidate,
    geocoding_status = EXCLUDED.geocoding_status,
    geocoding_query = EXCLUDED.geocoding_query,
    geocoding_source = EXCLUDED.geocoding_source,
    geocoding_source_reference = EXCLUDED.geocoding_source_reference,
    geocoding_matched_address = EXCLUDED.geocoding_matched_address,
    geocoding_confidence = EXCLUDED.geocoding_confidence,
    geocoded_at = EXCLUDED.geocoded_at,
    geocoding_suspicious_reason = NULL,
    geocoding_review_note = EXCLUDED.geocoding_review_note,
    geocoding_reviewed_at = EXCLUDED.geocoding_reviewed_at,
    updated_at = NOW();


-- Današnji Maxi cenovnik API potvrđuje ove dodatne store-level fajlove.
INSERT INTO app.store (
    retailer_id,
    external_code,
    name,
    address,
    city,
    location,
    active,
    store_format_id,
    geocoding_candidate,
    geocoding_status,
    geocoding_query,
    geocoding_source,
    geocoding_source_reference,
    geocoding_matched_address,
    geocoding_confidence,
    geocoded_at,
    geocoding_review_note,
    geocoding_reviewed_at
)
SELECT retailer.id,
       seed.external_code,
       seed.store_name,
       seed.address,
       seed.city,
       ST_SetSRID(
           ST_MakePoint(seed.longitude, seed.latitude),
           4326
       )::geography,
       TRUE,
       format.id,
       ST_SetSRID(
           ST_MakePoint(seed.longitude, seed.latitude),
           4326
       )::geography,
       'MANUALLY_VERIFIED',
       LOWER(seed.address || ', ' || seed.city),
       'MAXI_PRICE_FEED_AND_OPENSTREETMAP',
       'https://www.maxi.rs/cenovnici',
       seed.address || ', ' || seed.city,
       seed.confidence,
       NOW(),
       seed.review_note,
       NOW()
FROM app.retailer AS retailer
JOIN app.store_format AS format
  ON format.retailer_id = retailer.id
 AND format.code = 'MAXI'
CROSS JOIN (
    VALUES
        (
            '512',
            'Prodavnica 512',
            'Obrena Nikolića 2',
            'Valjevo',
            44.2761196::DOUBLE PRECISION,
            19.8941945::DOUBLE PRECISION,
            0.9000::NUMERIC,
            'Zvanični store locator i cenovnik potvrđuju objekat; OSM potvrđuje adresu.'
        ),
        (
            '513',
            'Prodavnica 513',
            'Naselje Zbratimljeni gradovi bb',
            'Valjevo',
            44.2750768::DOUBLE PRECISION,
            19.8994821::DOUBLE PRECISION,
            0.8000::NUMERIC,
            'Zvanični store locator i cenovnik potvrđuju objekat; koordinata je na pripadajućoj ulici.'
        ),
        (
            '541',
            'Maxi 541 Divčibare',
            'Gođevačka 2',
            'Divčibare',
            44.1119684::DOUBLE PRECISION,
            19.9942374::DOUBLE PRECISION,
            1.0000::NUMERIC,
            'Zvanični cenovnik i OSM supermarket se poklapaju.'
        ),
        (
            '544',
            'Maxi 544',
            'Karađorđeva 2',
            'Valjevo',
            44.2704048::DOUBLE PRECISION,
            19.8787018::DOUBLE PRECISION,
            0.9000::NUMERIC,
            'Današnji zvanični cenovnik navodi ovu adresu; OSM potvrđuje objekat na adresi.'
        )
) AS seed(
    external_code,
    store_name,
    address,
    city,
    latitude,
    longitude,
    confidence,
    review_note
)
WHERE retailer.code = 'MAXI'
ON CONFLICT (retailer_id, external_code)
DO UPDATE SET
    name = EXCLUDED.name,
    address = EXCLUDED.address,
    city = EXCLUDED.city,
    location = EXCLUDED.location,
    active = TRUE,
    store_format_id = EXCLUDED.store_format_id,
    geocoding_candidate = EXCLUDED.geocoding_candidate,
    geocoding_status = EXCLUDED.geocoding_status,
    geocoding_query = EXCLUDED.geocoding_query,
    geocoding_source = EXCLUDED.geocoding_source,
    geocoding_source_reference = EXCLUDED.geocoding_source_reference,
    geocoding_matched_address = EXCLUDED.geocoding_matched_address,
    geocoding_confidence = EXCLUDED.geocoding_confidence,
    geocoded_at = EXCLUDED.geocoded_at,
    geocoding_suspicious_reason = NULL,
    geocoding_review_note = EXCLUDED.geocoding_review_note,
    geocoding_reviewed_at = EXCLUDED.geocoding_reviewed_at,
    updated_at = NOW();
