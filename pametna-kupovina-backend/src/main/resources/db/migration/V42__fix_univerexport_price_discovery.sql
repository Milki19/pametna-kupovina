-- data.gov.rs je aktuelni Univerexport skup objavio sa novim slugom (-1),
-- dok sam izdavač sada nudi stabilan URL za tekući CSV.
UPDATE app.retailer
SET dataset_url = 'https://ucloud.univerexport.rs/opendata/univer.csv'
WHERE code = 'UNIVEREXPORT';

UPDATE app.retailer_data_source AS source
SET source_url = 'https://ucloud.univerexport.rs/opendata/univer.csv',
    discovery_url = 'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-univerexport-export-import-doo-1/',
    updated_at = NOW()
FROM app.retailer AS retailer
WHERE source.retailer_id = retailer.id
  AND retailer.code = 'UNIVEREXPORT'
  AND source.source_type = 'PRICE_CATALOG'
  AND source.price_scope <> 'STORE';
