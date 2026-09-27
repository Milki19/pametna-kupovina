-- Do sada je slučajan broj iz aplikacije (X-Client-Token) išao uz svaki
-- zahtev i nikad nije isticao: ko ga jednom vidi — u logu, na ekranu „O
-- aplikaciji“, u proxyju — ulazi u nalog zauvek, i nema načina da se to
-- prekine osim brisanja naloga.
--
-- Od sada telefon drži sesiju: kratak pristupni token (minuti) koji ide uz
-- zahteve i token za obnovu koji se menja pri svakoj upotrebi. Čuvaju se samo
-- otisci. Sesija se briše pri odjavi, pri uklanjanju telefona sa naloga i kad
-- se ne koristi duže od dozvoljenog; sa njom nestaje i pristup.

CREATE TABLE app.device_session (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id BIGINT NOT NULL,
    access_token_hash VARCHAR(64) NOT NULL UNIQUE,
    access_expires_at TIMESTAMPTZ NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    -- Token za obnovu pre poslednje zamene. Ako stigne ponovo dok novi par
    -- još niko nije upotrebio, odgovor na obnovu se izgubio na putu i par se
    -- izdaje ponovo; ako stigne posle toga, neko drugi ga ima i sesija pada.
    previous_refresh_token_hash VARCHAR(64) UNIQUE,
    current_pair_used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_device_session_device
        FOREIGN KEY (device_id) REFERENCES app.account_device (id)
            ON DELETE CASCADE,
    CONSTRAINT chk_device_session_access_hash
        CHECK (access_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_device_session_refresh_hash
        CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_device_session_previous_hash
        CHECK (previous_refresh_token_hash IS NULL
               OR previous_refresh_token_hash ~ '^[0-9a-f]{64}$')
);

-- Jedan telefon, jedna sesija: nova zamenjuje staru.
CREATE UNIQUE INDEX uq_device_session_device
    ON app.device_session (device_id);

CREATE INDEX idx_device_session_refresh_expires
    ON app.device_session (refresh_expires_at);

-- Kad telefon jednom pređe na sesiju, njegov stari broj više ne otvara
-- ništa — ni kao zaglavlje ni kao zamena za novu sesiju. Stare verzije
-- aplikacije (do 1.8) ga i dalje šalju dok se ne ažuriraju.
ALTER TABLE app.account_device
    ADD COLUMN legacy_token_retired_at TIMESTAMPTZ;

-- Da bi se na spisku telefona naloga znalo koji je koji („Samsung SM-A546B“).
-- Model telefona, ne ime vlasnika; aplikacija ga šalje pri otvaranju sesije.
ALTER TABLE app.account_device
    ADD COLUMN name VARCHAR(100);
