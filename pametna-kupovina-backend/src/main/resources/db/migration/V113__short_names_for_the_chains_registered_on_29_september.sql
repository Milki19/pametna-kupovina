-- 29.09. su sa portala uključena još dva prehrambena lanca. Portal jedan od
-- njih vodi punim pravnim nazivom („Metalac Proleter društvo sa ograničenom
-- odgovornošću Gornji Milanovac"), a kupac treba da vidi ime sa table.

UPDATE app.retailer AS retailer
   SET name = short.name
  FROM (VALUES
      ('TRANSKOM_94', 'Transkom 94'),
      ('METALAC_PROLETER_DRUSTVO_SA_OG', 'Metalac Proleter')
  ) AS short (code, name)
 WHERE retailer.code = short.code;
