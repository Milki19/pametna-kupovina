CREATE TABLE app.shopping_list (
                                   id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                   name VARCHAR(200) NOT NULL,
                                   created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                   updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                   CONSTRAINT chk_shopping_list_name
                                       CHECK (BTRIM(name) <> '')
);

CREATE TABLE app.shopping_list_item (
                                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                        shopping_list_id BIGINT NOT NULL,
                                        name VARCHAR(500) NOT NULL,
                                        barcode VARCHAR(32),
                                        quantity NUMERIC(10,3) NOT NULL DEFAULT 1,
                                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                        CONSTRAINT fk_shopping_list_item_list
                                            FOREIGN KEY (shopping_list_id)
                                                REFERENCES app.shopping_list(id)
                                                ON DELETE CASCADE,

                                        CONSTRAINT chk_shopping_list_item_name
                                            CHECK (BTRIM(name) <> ''),

                                        CONSTRAINT chk_shopping_list_item_quantity
                                            CHECK (quantity > 0)
);

CREATE INDEX idx_shopping_list_item_list
    ON app.shopping_list_item(shopping_list_id);

CREATE INDEX idx_shopping_list_item_barcode
    ON app.shopping_list_item(barcode)
    WHERE barcode IS NOT NULL;