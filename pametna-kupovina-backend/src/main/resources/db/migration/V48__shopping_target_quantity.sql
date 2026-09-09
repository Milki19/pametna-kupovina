-- Null preserves the existing number-of-packages contract. A target is per
-- requested group: target_quantity * quantity, in required_base_unit.
ALTER TABLE app.shopping_list_item ADD COLUMN target_quantity NUMERIC(14,4);
ALTER TABLE app.shopping_list_item ADD CONSTRAINT chk_shopping_target_quantity
CHECK (target_quantity IS NULL OR (target_quantity > 0 AND matching_rule='FLEXIBLE_CATEGORY'
    AND required_base_unit IS NOT NULL AND required_base_unit IN ('g','ml','piece')));
