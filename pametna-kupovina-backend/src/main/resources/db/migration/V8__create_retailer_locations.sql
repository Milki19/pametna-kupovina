CREATE TABLE app.retailer_location (
                                       id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                       retailer_id BIGINT NOT NULL,
                                       external_code VARCHAR(100) NOT NULL,
                                       name VARCHAR(200) NOT NULL,
                                       address VARCHAR(300),
                                       city VARCHAR(100),
                                       location GEOGRAPHY(POINT, 4326) NOT NULL,
                                       active BOOLEAN NOT NULL DEFAULT TRUE,
                                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                       CONSTRAINT fk_retailer_location_retailer
                                           FOREIGN KEY (retailer_id)
                                               REFERENCES app.retailer(id),

                                       CONSTRAINT uq_retailer_location_code
                                           UNIQUE (retailer_id, external_code)
);

CREATE INDEX idx_retailer_location_location
    ON app.retailer_location
    USING GIST(location);

CREATE INDEX idx_retailer_location_retailer
    ON app.retailer_location(retailer_id);