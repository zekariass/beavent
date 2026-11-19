-- =============================================================================
-- 99: finalize — attach updated_at maintenance to every table that has it.
-- (Future migrations that add tables with updated_at must attach the trigger.)
-- =============================================================================
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT c.table_schema, c.table_name
      FROM information_schema.columns c
      JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
     WHERE c.column_name = 'updated_at' AND t.table_type = 'BASE TABLE'
       AND c.table_schema IN ('platform','ref','iam','tenant','team','catalog','crm','events','commerce','scheduling',
                              'retention','stock','payments','ledger','billing','fiscal','marketing','comms','discovery','trust')
  LOOP
    EXECUTE format('CREATE TRIGGER touch_updated_at BEFORE UPDATE ON %I.%I FOR EACH ROW EXECUTE FUNCTION platform.touch_updated_at()',
                   r.table_schema, r.table_name);
  END LOOP;
END $$;
