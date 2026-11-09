-- Šesti test (6.11.2026.): „riba 4“ je u planu Jedna stanica postala četiri
-- sveža brancina po 2.504 RSD/kg (10.016 RSD). Pravilo „najviše 3× medijana“
-- (2.11.) gleda samo ponude radnji oko korisnika, a tamo su bile tri skupe
-- ribe, pa je brancin bio baš medijana.
--
-- Red koji kaže samo osnovnu vrstu („riba“) sada se poredi sa onim što ta
-- vrsta obično košta po kilogramu u celom tržištu: medijana lanaca, a u
-- lancu medijana njegovih proizvoda (ShoppingRecommendationService,
-- DatabaseBaseKindPrices). Ponuda skuplja od 2× toga nije izbor za taj red,
-- a uz stavku piše da ovde nema obične vrste. Red koji kaže više („brancin“,
-- „riba Frikom“, „sveža riba“ sa svojim izrazom) bira kao i do sada.
--
-- „riba“ (namera FISH, V89) uzima svežu ribu, a smrznutu gde radnja nema
-- svežu. Zato je osnovna vrsta sveža riba (FISH), a smrznuta riba je vrsta
-- pod njom: medijana „ribe“ je medijana obe. Riblja konzerva nije riba sa
-- spiska (V88) i ostaje van.
--
-- Ništa ovde nije vezano za jednu zemlju: šifre vrsta su iste na svim
-- tržištima, a cene i medijana dolaze iz lanaca svakog tržišta.

UPDATE app.product_type AS kind
SET base_type_id = base.id,
    updated_at = NOW()
FROM app.product_type AS base
WHERE base.code = 'FISH'
  AND kind.code = 'FROZEN_FISH'
  AND kind.base_type_id IS DISTINCT FROM base.id;

