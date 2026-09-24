-- Cenovnik bez adrese dobija svoju „prodavnicu" sa oznakom
-- 'PRICE_LIST:' || naziv (do 100 znakova, V91), a mapiranje ju je prepisivalo u
-- source_store_code od 50. Tri lanca uključena 23.09. (Cash & Carry Plus Kula,
-- Euro Ša M, Matijević) imaju duže nazive, pa im je ceo uvoz padao.

ALTER TABLE app.store_price_format_mapping
    ALTER COLUMN source_store_code TYPE VARCHAR(100);
