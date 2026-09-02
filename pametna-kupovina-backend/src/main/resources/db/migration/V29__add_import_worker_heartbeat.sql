CREATE TABLE app.import_worker_heartbeat (
    instance_id VARCHAR(200) PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_import_worker_instance_not_blank
        CHECK (BTRIM(instance_id) <> ''),

    CONSTRAINT chk_import_worker_heartbeat_order
        CHECK (heartbeat_at >= started_at)
);

CREATE INDEX idx_import_worker_latest_heartbeat
    ON app.import_worker_heartbeat (heartbeat_at DESC);
