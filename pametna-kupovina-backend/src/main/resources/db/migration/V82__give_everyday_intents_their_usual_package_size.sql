-- Someone who writes only "pirinač" or "voda" means the usual pack, not the
-- cheapest small one. Half of the intents had no usual size, so "pirinač"
-- became a 500 g bag and "voda" a 0.5 l bottle. The ranges follow the sizes
-- most chains list for each intent (15.09.). Like the existing defaults they
-- rank offers and never drop a store; a stated amount still wins. Loose meat,
-- fish, produce and bakery rolls have no usual size. Intents that already
-- have one are left alone.

UPDATE app.shopping_intent AS intent
SET default_base_unit = usual.base_unit,
    default_min_package_quantity = usual.min_quantity,
    default_max_package_quantity = usual.max_quantity,
    updated_at = NOW()
FROM (
    VALUES
        ('AYRAN', 'g', 180, 1000),
        ('BAKING_POWDER', 'g', 10, 15),
        ('BREADCRUMBS', 'g', 200, 500),
        ('CEVAPI', 'g', 400, 1000),
        ('CHICKEN_DRUMSTICK', 'g', 450, 1000),
        ('CHICKEN_FILLET', 'g', 350, 1000),
        ('CHICKEN_WINGS', 'g', 500, 1000),
        ('FLAVORED_MILK', 'ml', 200, 1000),
        ('FRUIT_YOGURT', 'g', 125, 400),
        ('GRILL_SAUSAGE', 'g', 280, 520),
        ('MAYONNAISE', 'ml', 180, 300),
        ('PASTA', 'g', 400, 500),
        ('PLANT_DRINK', 'ml', 900, 1100),
        ('PLJESKAVICA', 'g', 400, 1000),
        ('PORK_NECK_FRESH', 'g', 400, 1000),
        ('POWDERED_MILK', 'g', 200, 800),
        ('RICE', 'g', 800, 1000),
        ('SALT', 'g', 500, 1000),
        ('TONIC_WATER', 'ml', 1000, 1500),
        ('VINEGAR', 'ml', 900, 1000),
        ('WATER', 'ml', 1500, 2000),
        ('YEAST', 'g', 7, 42)
) AS usual (code, base_unit, min_quantity, max_quantity)
WHERE intent.code = usual.code
  AND intent.default_min_package_quantity IS NULL
  AND intent.default_max_package_quantity IS NULL
  AND (intent.default_base_unit IS NULL OR intent.default_base_unit = usual.base_unit);
