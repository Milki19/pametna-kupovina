-- 23.09. je sa portala uključeno 22 lanca van Beograda. Portal ih vodi punim
-- pravnim nazivom („ДРУШТВО ЗА ПРОИЗВОДЊУ ТРГОВИНУ И УСЛУГЕ ЗАМ ДОО ИНЂИЈА"),
-- a kupac u aplikaciji treba da vidi ime sa table nad radnjom.

UPDATE app.retailer AS retailer
   SET name = short.name
  FROM (VALUES
      ('BB_TRADE', 'BB Trade'),
      ('CASH_CARRY_PLUS_KULA', 'Cash & Carry Plus Kula'),
      ('DESHODES_ZEMUN', 'Deshodes Zemun'),
      ('DRUSTVO_ZA_PROIZVODNJU_PROMET', 'Vum'),
      ('FORTUNA_MARKET', 'Fortuna Market'),
      ('INDUSTRIJA_MESA_MATIJEVIC', 'Matijević'),
      ('LEON_CONDITORS', 'Leon Conditors'),
      ('MEDIUS_NOVA_PAZOVA', 'Medius Nova Pazova'),
      ('PODUNAVLJE', 'Podunavlje'),
      ('PRIMA_NOVA', 'Prima Nova'),
      ('PROIZVODNO_TRGOVINSKO_PREDUZEC', 'Euro Ša M'),
      ('RIC_PROKUPLJE', 'RIČ Prokuplje'),
      ('ROS_PRODUKT_SERVIS', 'ROS Produkt'),
      ('SENTA_PROMET', 'Senta-Promet'),
      ('TRGOVINSKO_PREDUZECE_MORAVA_KR', 'Morava Kragujevac'),
      ('TEKIJANKA_TEKIJA', 'Tekijanka'),
      ('VP_DIMA', 'VP Dima'),
      ('SUMADIJA_MARKET', 'Šumadija market'),
      ('DRUSTVO_ZA_PROIZVODNJU_TRGOVIN', 'ZAM Inđija'),
      ('ZEMLJORADNICKA_ZADRUGA_TRGOVIN', 'Trlić'),
      ('MIKROMARKET_NS', 'Mikromarket NS'),
      ('PRIVREDNO_DRUSTVO_ZA_PROMET_I', 'Čutura Plus')
  ) AS short (code, name)
 WHERE retailer.code = short.code;
