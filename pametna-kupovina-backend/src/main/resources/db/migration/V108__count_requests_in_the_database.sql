-- Brojač zahteva po adresi i po nalogu živeo je u memoriji jednog procesa:
-- restart ga je brisao, a druga instanca backend-a bi imala svoj, pa bi
-- svaka pustila punu kvotu. Sada se broji ovde, zajedno za sve instance.
--
-- UNLOGGED: bez WAL-a, jer je upis po svakom zahtevu. Posle pada servera
-- tabela ostaje prazna, a to znači samo da se tekući minut broji od nule.

CREATE UNLOGGED TABLE app.request_count (
    counter_key VARCHAR(100) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    uses INTEGER NOT NULL,

    PRIMARY KEY (counter_key, window_start)
);

CREATE INDEX idx_request_count_window_start
    ON app.request_count (window_start);
