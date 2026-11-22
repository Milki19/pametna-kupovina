-- Neaktivna radnja ne daje cene (20.11.2026).
--
-- Uvoz proverenih lokacija je 15.11. dodao „Lidl Kragujevac - Avalska“
-- (RS00209) kao neaktivnu, ali sa pricing_eligible = TRUE, jer je pitao samo
-- izvor, ne i radnju. Kupci je nisu videli (blizina traži active), ali je M1
-- izveštaj od tada svakog sata bio CRITICAL (unsafePricingEligible = 1) i
-- tako krio prave alarme. Kod sad neaktivnu radnju upisuje kao INACTIVE;
-- ova migracija sređuje one koje su već upisane.

UPDATE app.store
SET pricing_eligible = FALSE,
    pricing_ineligibility_reason = 'INACTIVE',
    updated_at = NOW()
WHERE NOT active
  AND pricing_eligible;
