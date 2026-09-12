CREATE TABLE app.price_refresh_cycle (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cycle_date DATE NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status TEXT NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','WARNING','FAILED'))
);
CREATE UNIQUE INDEX price_refresh_one_running ON app.price_refresh_cycle(status) WHERE status='RUNNING';
CREATE TABLE app.price_refresh_result (
    cycle_id BIGINT NOT NULL REFERENCES app.price_refresh_cycle(id),
    retailer_code TEXT NOT NULL,
    snapshot_date DATE,
    rows_saved INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED','WARNING','FAILED')),
    detail TEXT NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (cycle_id, retailer_code)
);
