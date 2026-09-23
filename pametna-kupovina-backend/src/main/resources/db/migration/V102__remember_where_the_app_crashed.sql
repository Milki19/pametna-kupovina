-- Izveštaj o padu aplikacije: šta je puklo, u kojoj verziji i na kom modelu
-- telefona. Bez broja uređaja i bez ikakvog ličnog podatka, pa se ne zna ni
-- čiji je telefon; čuva se 90 dana.

CREATE TABLE app.crash_report (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    app_version VARCHAR(20) NOT NULL,
    android_version VARCHAR(20) NOT NULL,
    device VARCHAR(100) NOT NULL,
    stack_trace TEXT NOT NULL,

    CONSTRAINT chk_crash_report_stack_trace
        CHECK (BTRIM(stack_trace) <> '' AND LENGTH(stack_trace) <= 20000)
);

CREATE INDEX idx_crash_report_created ON app.crash_report (created_at);
