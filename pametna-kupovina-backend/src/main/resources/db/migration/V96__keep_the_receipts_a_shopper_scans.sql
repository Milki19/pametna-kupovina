-- Plan kaže šta je aplikacija predložila; račun kaže šta je čovek stvarno
-- platio. Tek to drugo može da nauči aplikaciju koji brend zaista kupuje i u
-- koji lanac zaista ide.
--
-- Polja su ona koja fiskalni račun po Pravilniku mora da ima, pa se model ne
-- menja ni kad se stranica Poreske uprave promeni.

CREATE TABLE app.receipt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,

    -- Ono po čemu je račun jedinstven kod Poreske uprave. Isti račun
    -- skeniran dvaput ne sme da uđe dvaput.
    verification_key VARCHAR(200) NOT NULL,

    retailer_id BIGINT,
    -- Prodavnica kako je napisana na računu; ime lanca kod nas je posao
    -- spajanja, a ovo je ono što je čovek dobio u ruke.
    shop_name VARCHAR(300) NOT NULL,
    tax_identification_number VARCHAR(20),
    issued_at TIMESTAMPTZ NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_receipt_account
        FOREIGN KEY (account_id) REFERENCES app.account (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_receipt_retailer
        FOREIGN KEY (retailer_id) REFERENCES app.retailer (id)
            ON DELETE SET NULL,
    CONSTRAINT uq_receipt_per_account
        UNIQUE (account_id, verification_key),
    CONSTRAINT chk_receipt_shop_name
        CHECK (BTRIM(shop_name) <> ''),
    CONSTRAINT chk_receipt_total
        CHECK (total_amount >= 0)
);

CREATE INDEX idx_receipt_account_issued
    ON app.receipt (account_id, issued_at DESC, id DESC);

CREATE TABLE app.receipt_item (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    line_number INTEGER NOT NULL,

    name VARCHAR(500) NOT NULL,
    quantity NUMERIC(12,3) NOT NULL,
    unit_of_measure VARCHAR(20),
    unit_price NUMERIC(12,2),
    total_price NUMERIC(12,2) NOT NULL,
    vat_label VARCHAR(10),

    -- Popunjava se kad se ime sa računa prepozna u katalogu. Prazno nije
    -- greška: račun ume da napiše artikal kako nijedan cenovnik ne piše.
    product_family_id BIGINT,

    CONSTRAINT fk_receipt_item_receipt
        FOREIGN KEY (receipt_id) REFERENCES app.receipt (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_receipt_item_family
        FOREIGN KEY (product_family_id) REFERENCES app.product_family (id)
            ON DELETE SET NULL,
    CONSTRAINT uq_receipt_item_line
        UNIQUE (receipt_id, line_number),
    CONSTRAINT chk_receipt_item_name
        CHECK (BTRIM(name) <> ''),
    CONSTRAINT chk_receipt_item_quantity
        CHECK (quantity > 0)
);

CREATE INDEX idx_receipt_item_receipt
    ON app.receipt_item (receipt_id, line_number);

CREATE INDEX idx_receipt_item_family
    ON app.receipt_item (product_family_id)
    WHERE product_family_id IS NOT NULL;
