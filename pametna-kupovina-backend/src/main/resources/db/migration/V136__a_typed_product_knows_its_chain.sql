-- A typed product carries its chain, so a plan finds the products of a kind
-- in the chains asked for without opening every product of that kind. A plan
-- for 23 items opened 62,000 products to keep 6,000 (17.10., 0.4 s of 1.1 s).
-- The chain is filled in on every write, wherever the type comes from.

ALTER TABLE app.retailer_product_type ADD COLUMN retailer_id BIGINT;

UPDATE app.retailer_product_type AS assignment
SET retailer_id = product.retailer_id
FROM app.retailer_product AS product
WHERE product.id = assignment.retailer_product_id;

ALTER TABLE app.retailer_product_type ALTER COLUMN retailer_id SET NOT NULL;

CREATE FUNCTION app.retailer_product_type_chain()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT product.retailer_id
    INTO NEW.retailer_id
    FROM app.retailer_product AS product
    WHERE product.id = NEW.retailer_product_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_retailer_product_type_chain
    BEFORE INSERT OR UPDATE OF retailer_product_id, retailer_id
    ON app.retailer_product_type
    FOR EACH ROW EXECUTE FUNCTION app.retailer_product_type_chain();

CREATE FUNCTION app.retailer_product_chain_moved()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE app.retailer_product_type
    SET retailer_id = NEW.retailer_id
    WHERE retailer_product_id = NEW.id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_retailer_product_chain_moved
    AFTER UPDATE OF retailer_id ON app.retailer_product
    FOR EACH ROW WHEN (OLD.retailer_id IS DISTINCT FROM NEW.retailer_id)
    EXECUTE FUNCTION app.retailer_product_chain_moved();

CREATE INDEX idx_retailer_product_type_chain
    ON app.retailer_product_type (product_type_id, retailer_id, retailer_product_id);
