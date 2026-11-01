-- A list's number tells how many lists there are and travels in household
-- links. Newer clients call a list by a random id instead; the number keeps
-- working for Android up to 2.0.6, and access is checked the same either way.
ALTER TABLE app.shopping_list
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX shopping_list_public_id_key
    ON app.shopping_list (public_id);
