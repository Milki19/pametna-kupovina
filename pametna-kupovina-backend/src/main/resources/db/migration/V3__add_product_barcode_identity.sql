CREATE UNIQUE INDEX uq_retailer_product_retailer_barcode
    ON app.retailer_product (retailer_id, barcode)
    WHERE barcode IS NOT NULL AND barcode <> '';