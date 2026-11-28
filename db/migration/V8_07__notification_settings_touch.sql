-- Every table with updated_at keeps it current through the shared trigger (db/tests/04_schema_lint.sql); V8_04 added
-- comms.notification_settings without it.
CREATE TRIGGER touch_updated_at BEFORE UPDATE ON comms.notification_settings
  FOR EACH ROW EXECUTE FUNCTION platform.touch_updated_at();
