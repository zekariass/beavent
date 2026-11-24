-- LOCAL DEVELOPMENT AND TESTS ONLY (Spring profile "local"; see application-local.properties).
-- Creates login users for the least-privilege group roles from V1_90__security.sql. In staging and production
-- these logins are provisioned by infrastructure-as-code with secrets from the secret manager.
-- BYPASSRLS is a role attribute and is not inherited through membership, so the identity and system logins
-- need it themselves.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beavent_app_login') THEN
    CREATE ROLE beavent_app_login LOGIN PASSWORD 'beavent_app' IN ROLE beavent_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beavent_identity_login') THEN
    CREATE ROLE beavent_identity_login LOGIN BYPASSRLS PASSWORD 'beavent_identity' IN ROLE beavent_identity;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beavent_system_login') THEN
    CREATE ROLE beavent_system_login LOGIN BYPASSRLS PASSWORD 'beavent_system' IN ROLE beavent_system;
  END IF;
END $$;
