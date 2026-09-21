-- Račun se zavodi čim se skenira, iz samog QR koda: prodavnica, vreme i
-- iznos. Stavke stižu sa stranice Poreske uprave, a do nje se dolazi jedino
-- ovom adresom — zato se ona pamti, inače se stavke posle više ne mogu
-- dovući ni kad bude signala.
ALTER TABLE app.receipt
    ADD COLUMN verification_url VARCHAR(4000),
    -- Prazno znači „stavke još nisu pročitane"; račun je i bez njih račun.
    ADD COLUMN items_read_at TIMESTAMPTZ;
