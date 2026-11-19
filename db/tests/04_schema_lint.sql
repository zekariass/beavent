-- Schema lint: conventions that must hold for every current AND future migration.
\set ON_ERROR_STOP on
BEGIN;

DO $$
DECLARE
  v text;
  app_schemas text[] := ARRAY['platform','ref','iam','tenant','team','catalog','crm','events','commerce','scheduling',
                              'retention','stock','payments','ledger','billing','fiscal','marketing','comms','discovery','trust'];
  -- org_id tables intentionally without tenant RLS (the app role has no write access, or they have bespoke policies
  -- defined on other columns). Must match the `excluded` list in V1_90__security.sql minus those with bespoke RLS.
  rls_exempt text[] := ARRAY['platform.outbox','platform.audit_log','platform.feature_flag_overrides','discovery.listings',
                             'trust.moderation_actions','trust.fraud_signals','trust.risk_profiles','ledger.scheduled_releases',
                             'comms.deliveries','comms.suppressions','comms.provider_accounts'];
BEGIN
  -- 1. Every org-owned table has RLS enabled (or is explicitly exempt).
  SELECT string_agg(n.nspname || '.' || c.relname, ', ') INTO v
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE c.relkind = 'r' AND n.nspname = ANY (app_schemas) AND NOT c.relrowsecurity
     AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'org_id' AND NOT a.attisdropped)
     AND (n.nspname || '.' || c.relname) <> ALL (rls_exempt);
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [org_id tables without RLS]: %', v; END IF;

  -- 2. The app role cannot write to any RLS-less table outside the known infrastructure tables.
  SELECT string_agg(DISTINCT g.table_schema || '.' || g.table_name, ', ') INTO v
    FROM information_schema.role_table_grants g
    JOIN pg_namespace n ON n.nspname = g.table_schema
    JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = g.table_name
   WHERE g.grantee = 'beavent_app' AND g.privilege_type IN ('UPDATE','DELETE')
     AND c.relkind = 'r' AND NOT c.relrowsecurity
     AND (g.table_schema || '.' || g.table_name) NOT IN ('platform.idempotency_keys','platform.processed_messages');
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [app can update/delete RLS-less tables]: %', v; END IF;

  -- 3. No floating point anywhere; money columns are bigint.
  SELECT string_agg(table_schema || '.' || table_name || '.' || column_name, ', ') INTO v
    FROM information_schema.columns
   WHERE table_schema = ANY (app_schemas) AND data_type IN ('real','double precision','money');
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [floating point / money type columns]: %', v; END IF;
  SELECT string_agg(table_schema || '.' || table_name || '.' || column_name, ', ') INTO v
    FROM information_schema.columns
   WHERE table_schema = ANY (app_schemas) AND column_name LIKE '%\_minor' AND data_type <> 'bigint'
     AND NOT (data_type = 'ARRAY' AND column_name = 'preset_amounts_minor');
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [*_minor columns must be bigint]: %', v; END IF;

  -- 4. Every *_minor amount on a table has a currency column on that table (or is a documented exception).
  SELECT string_agg(DISTINCT c.table_schema || '.' || c.table_name, ', ') INTO v
    FROM information_schema.columns c
   WHERE c.table_schema = ANY (app_schemas) AND c.column_name LIKE '%\_minor'
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns x
                      WHERE x.table_schema = c.table_schema AND x.table_name = c.table_name
                        AND x.column_name IN ('currency_code','cost_currency','base_currency','settlement_currency'))
     AND (c.table_schema || '.' || c.table_name) NOT IN (
       -- currency is carried by the parent row (documented in docs/05-data-model.md)
       'commerce.order_line_taxes','commerce.invoice_lines','commerce.cash_drawer_sessions','commerce.cash_movements',
       'commerce.redemptions','retention.gift_card_entries','retention.customer_balance_entries','team.pay_run_items',
       'team.pay_run_adjustments','team.pay_run_payments','stock.purchase_order_lines','stock.movements',
       'payments.refund_lines','payments.provider_capabilities','payments.routes','payments.org_settings','payments.statement_lines',
       'payments.provider_statements','billing.invoice_lines','events.price_phases','scheduling.appointment_item_addons',
       'marketing.ad_daily_stats','marketing.tracking_link_daily','discovery.listings','trust.risk_profiles',
       'payments.payment_intents','catalog.bundles','ledger.balances','ref.currencies');
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [*_minor without currency on table or allow-list]: %', v; END IF;

  -- 5. Every table with updated_at has the touch trigger.
  SELECT string_agg(c.table_schema || '.' || c.table_name, ', ') INTO v
    FROM information_schema.columns c
    JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
   WHERE c.table_schema = ANY (app_schemas) AND c.column_name = 'updated_at'
     AND NOT EXISTS (SELECT 1 FROM information_schema.triggers tr
                      WHERE tr.event_object_schema = c.table_schema AND tr.event_object_table = c.table_name
                        AND tr.trigger_name = 'touch_updated_at');
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [updated_at without touch trigger]: %', v; END IF;

  -- 6. Every timestamp is timestamptz.
  SELECT string_agg(table_schema || '.' || table_name || '.' || column_name, ', ') INTO v
    FROM information_schema.columns
   WHERE table_schema = ANY (app_schemas) AND data_type = 'timestamp without time zone';
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [timestamp without time zone]: %', v; END IF;

  -- 7. Every table has a primary key.
  SELECT string_agg(n.nspname || '.' || c.relname, ', ') INTO v
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE c.relkind = 'r' AND n.nspname = ANY (app_schemas)
     AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype = 'p');
  IF v IS NOT NULL THEN RAISE EXCEPTION 'FAIL [tables without primary key]: %', v; END IF;
END $$;

ROLLBACK;
