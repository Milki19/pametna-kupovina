-- Someone who writes only "mleko" means a litre of milk, not the cheapest
-- 200ml cup, and "jogurt" means the 1l/1.5l bottle rather than a 180g cup.
-- Ranges are taken from the package sizes that actually occur in the imported
-- catalogues, so a range always has real products behind it. The offer query
-- ranks these first but does not filter on them, so a store that only stocks
-- small packs still offers the item.

UPDATE app.shopping_intent SET
    default_min_package_quantity = values.min_quantity,
    default_max_package_quantity = values.max_quantity,
    default_base_unit = values.base_unit,
    updated_at = NOW()
FROM (
    VALUES
        -- Dairy: a litre of milk, a litre-plus bottle of plain yogurt.
        ('MILK',        900,    1100,   'ml'),
        ('YOGURT',      900,    1600,   'g'),
        -- Kiselo mleko has no 1l pack in the feeds; 400-700g is the normal tub.
        ('SOUR_MILK',   350,    1000,   'g'),
        ('KEFIR',       350,    1600,   'g'),
        ('CREAM',       150,    800,    'g'),
        ('BUTTER',      100,    260,    'g'),
        -- "Sir" must not land on a 40g grated parmesan sachet.
        ('CHEESE',      200,    600,    'g'),
        -- A loaf, not a mini baguette.
        ('BREAD',       400,    800,    'g'),
        ('EGGS',        10,     12,     'piece'),
        ('FLOUR',       900,    1100,   'g'),
        ('SUGAR',       900,    1100,   'g'),
        ('OIL',         900,    1100,   'ml'),
        ('JUICE',       900,    1600,   'ml'),
        -- Half-litre can or bottle unless something specific was asked for.
        ('BEER',        450,    550,    'ml'),
        ('NON_ALCOHOLIC_BEER', 450, 550, 'ml'),
        -- Coffee: keep the 200g/500g packs ahead of single-serve sachets.
        ('COFFEE',      150,    550,    'g')
) AS values (code, min_quantity, max_quantity, base_unit)
WHERE app.shopping_intent.code = values.code;
