-- Plan Stage 4, slice 4.5 (products): retail goods sold at the counter. A sale, a receipt of stock or an adjustment is a
-- movement written by the application; the level is kept by the trigger below. The application role may not write
-- stock.levels directly (V1_90), so the trigger function runs with its owner's rights, as the movement's own fields
-- (organization and location) are all it uses.
ALTER FUNCTION stock.apply_movement() SECURITY DEFINER SET search_path = pg_catalog, stock;
