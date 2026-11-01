-- Izraz za ono što red traži preko vrste (V140) sad zna i reči koje naziv
-- čine nečim drugim („Parmezan salama“, „tortilja sa ukusom kečapa“), pa za
-- duži red ne staje u 600 znakova. Duži izraz je bio odbačen, a red se
-- tražio kao jedan proizvod. Povećanje dužine ne prepisuje tabelu.
ALTER TABLE app.shopping_list_item
    ALTER COLUMN wanted_name_pattern TYPE VARCHAR(1000);
