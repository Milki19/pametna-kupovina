-- Valjevski Lidl je prvobitno ručno zasejan pre dostupnosti zvaničnog API-ja.
-- Prebacivanje na stabilan zvanični objectNumber sprečava dupliranje objekta
-- pri prvoj kompletnoj sinhronizaciji Lidl lokatora.
UPDATE app.store AS store
SET external_code = 'RS00152',
    updated_at = NOW()
FROM app.retailer AS retailer
WHERE store.retailer_id = retailer.id
  AND retailer.code = 'LIDL'
  AND store.external_code = 'valjevo-pop-lukina-45'
  AND NOT EXISTS (
      SELECT 1
      FROM app.store AS official_store
      WHERE official_store.retailer_id = retailer.id
        AND official_store.external_code = 'RS00152'
  );
