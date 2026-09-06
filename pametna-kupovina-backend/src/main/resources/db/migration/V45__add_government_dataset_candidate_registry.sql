-- API kataloga služi kao control plane: automatski otkriva nove skupove,
-- ali ih ne uključuje u produkcioni import bez eksplicitnog pregleda.
CREATE TABLE app.government_dataset_candidate (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    portal_dataset_id VARCHAR(100) NOT NULL UNIQUE,
    slug VARCHAR(500) NOT NULL,
    title VARCHAR(1000) NOT NULL,
    organization_name VARCHAR(500),
    dataset_page_url TEXT NOT NULL,
    resource_id VARCHAR(100),
    resource_title VARCHAR(1000),
    resource_url TEXT NOT NULL,
    resource_format VARCHAR(50) NOT NULL,
    resource_last_modified TIMESTAMPTZ,
    review_status VARCHAR(30) NOT NULL DEFAULT 'DISCOVERED',
    first_discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_government_candidate_dataset_id
        CHECK (BTRIM(portal_dataset_id) <> ''),
    CONSTRAINT chk_government_candidate_slug
        CHECK (BTRIM(slug) <> ''),
    CONSTRAINT chk_government_candidate_title
        CHECK (BTRIM(title) <> ''),
    CONSTRAINT chk_government_candidate_page_url
        CHECK (BTRIM(dataset_page_url) <> ''),
    CONSTRAINT chk_government_candidate_resource_url
        CHECK (BTRIM(resource_url) <> ''),
    CONSTRAINT chk_government_candidate_format
        CHECK (BTRIM(resource_format) <> ''),
    CONSTRAINT chk_government_candidate_status
        CHECK (review_status IN (
            'DISCOVERED',
            'APPROVED',
            'IGNORED'
        )),
    CONSTRAINT chk_government_candidate_discovery_time
        CHECK (last_discovered_at >= first_discovered_at)
);

CREATE INDEX idx_government_candidate_review
    ON app.government_dataset_candidate (
        review_status,
        last_discovered_at DESC,
        id
    );

CREATE INDEX idx_government_candidate_organization
    ON app.government_dataset_candidate (organization_name, id);
