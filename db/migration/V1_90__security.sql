-- =============================================================================
-- 90: database security — roles, row-level security, grants.
--
-- Roles (NOLOGIN groups; login users are provisioned by infrastructure-as-code
-- and granted membership):
--   beavent_app      API request handling. RLS enforced. No access to secrets,
--                    credentials, sessions, raw webhooks or trust internals.
--   beavent_identity Identity module (login, OTP, sessions). iam.* only.
--   beavent_system   Background workers and the admin-console backend for
--                    cross-tenant jobs (payout runs, reconciliation, search
--                    projection). BYPASSRLS; every admin action is audited.
--   beavent_readonly Analytics/BI on a read replica (cross-tenant, BYPASSRLS).
--                    SELECT only, and no identity/CRM/messaging/KYC tables.
--
-- Request contexts (set with platform.set_request_context):
--   Partner workspace: app.user_id + app.org_id (membership verified first).
--   Consumer:          app.user_id only — sees own orders/tickets/appointments.
--   Checkout / public page for organization X: app.org_id = X in a dedicated
--   transaction (READ ONLY for public pages). RLS confines the transaction to X.
-- =============================================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beavent_app')      THEN CREATE ROLE beavent_app NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beavent_identity') THEN CREATE ROLE beavent_identity NOLOGIN BYPASSRLS; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beavent_system')   THEN CREATE ROLE beavent_system NOLOGIN BYPASSRLS; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'beavent_readonly') THEN CREATE ROLE beavent_readonly NOLOGIN BYPASSRLS; END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 1. Generic tenant isolation on every table with an org_id column in the
--    tenant-owned schemas. Later migrations call platform.apply_tenant_rls()
--    for each new org-owned table; db/tests/04_schema_lint.sql fails the build
--    if any org_id table is left without RLS.
-- -----------------------------------------------------------------------------
CREATE FUNCTION platform.apply_tenant_rls(p_table regclass, p_shared_reference boolean DEFAULT false) RETURNS void
LANGUAGE plpgsql AS $fn$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
  IF p_shared_reference THEN
    -- org_id NULL rows are platform-provided and readable by every tenant; writes stay tenant-only.
    EXECUTE format($p$CREATE POLICY tenant_read ON %s FOR SELECT
                      USING (org_id IS NULL OR org_id = (SELECT platform.current_org_id()))$p$, p_table);
    EXECUTE format($p$CREATE POLICY tenant_insert ON %s FOR INSERT
                      WITH CHECK (org_id = (SELECT platform.current_org_id()))$p$, p_table);
    EXECUTE format($p$CREATE POLICY tenant_update ON %s FOR UPDATE
                      USING (org_id = (SELECT platform.current_org_id())) WITH CHECK (org_id = (SELECT platform.current_org_id()))$p$, p_table);
    EXECUTE format($p$CREATE POLICY tenant_delete ON %s FOR DELETE
                      USING (org_id = (SELECT platform.current_org_id()))$p$, p_table);
  ELSE
    EXECUTE format($p$CREATE POLICY tenant_isolation ON %s
                      USING (org_id = (SELECT platform.current_org_id()))
                      WITH CHECK (org_id = (SELECT platform.current_org_id()))$p$, p_table);
  END IF;
END $fn$;

DO $$
DECLARE
  r record;
  -- Tables whose org_id may be NULL for platform-provided rows that tenants must be able to READ.
  shared_reference text[] := ARRAY[
    'tenant.roles','commerce.promotions','commerce.promotion_targets','commerce.promo_codes',
    'payments.provider_accounts','payments.routes','billing.fee_rules','comms.templates'];
  -- Tables with bespoke policies below, or deliberately without tenant RLS because the
  -- app role has no (or insert-only / read-only public) grants on them. Kept in sync with
  -- the allow-list in db/tests/04_schema_lint.sql.
  excluded text[] := ARRAY[
    'tenant.members','platform.files','platform.outbox','platform.audit_log','platform.support_cases',
    'platform.data_exports','platform.feature_flag_overrides','discovery.listings','discovery.follows',
    'discovery.collections','discovery.reviews','discovery.saved_items','trust.content_reports','trust.moderation_actions',
    'trust.fraud_signals','trust.risk_profiles','trust.verification_cases','ledger.scheduled_releases',
    'comms.notifications','comms.deliveries','comms.preferences','comms.suppressions','comms.provider_accounts'];
BEGIN
  FOR r IN
    SELECT c.table_schema, c.table_name
      FROM information_schema.columns c
      JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
     WHERE c.column_name = 'org_id' AND t.table_type = 'BASE TABLE'
       AND c.table_schema IN ('tenant','team','catalog','crm','events','commerce','scheduling','retention','stock',
                              'payments','ledger','billing','fiscal','marketing','comms','discovery','trust','platform')
  LOOP
    CONTINUE WHEN (r.table_schema || '.' || r.table_name) = ANY (excluded);
    PERFORM platform.apply_tenant_rls(format('%I.%I', r.table_schema, r.table_name)::regclass,
                                      (r.table_schema || '.' || r.table_name) = ANY (shared_reference));
  END LOOP;
END $$;

-- Note: foreign-key checks bypass RLS, which is why every tenant table also carries org_id and
-- uses composite (org_id, parent_id) FKs: a row can only reference parents of its own org.

-- -----------------------------------------------------------------------------
-- 2. Tables with explicit policies
-- -----------------------------------------------------------------------------

-- Organizations: the workspace in context, plus every org the user is an active member of.
ALTER TABLE tenant.organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY org_read ON tenant.organizations FOR SELECT USING (
  id = (SELECT platform.current_org_id())
  OR id IN (SELECT m.org_id FROM tenant.members m
             WHERE m.user_id = (SELECT platform.current_user_id()) AND m.status = 'ACTIVE'));
CREATE POLICY org_create ON tenant.organizations FOR INSERT
  WITH CHECK (created_by_user_id = (SELECT platform.current_user_id()));
CREATE POLICY org_update ON tenant.organizations FOR UPDATE
  USING (id = (SELECT platform.current_org_id())) WITH CHECK (id = (SELECT platform.current_org_id()));

ALTER TABLE tenant.members ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.members FORCE ROW LEVEL SECURITY;
CREATE POLICY members_tenant ON tenant.members
  USING (org_id = (SELECT platform.current_org_id())) WITH CHECK (org_id = (SELECT platform.current_org_id()));
CREATE POLICY members_self_read ON tenant.members FOR SELECT
  USING (user_id = (SELECT platform.current_user_id()));

-- Places: public venues readable by everyone; private premises only by the owner org.
ALTER TABLE tenant.places ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.places FORCE ROW LEVEL SECURITY;
CREATE POLICY places_read ON tenant.places FOR SELECT
  USING (owner_org_id IS NULL OR owner_org_id = (SELECT platform.current_org_id()));
CREATE POLICY places_write ON tenant.places FOR INSERT
  WITH CHECK (owner_org_id = (SELECT platform.current_org_id()));
CREATE POLICY places_update ON tenant.places FOR UPDATE
  USING (owner_org_id = (SELECT platform.current_org_id())) WITH CHECK (owner_org_id = (SELECT platform.current_org_id()));

ALTER TABLE platform.files ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.files FORCE ROW LEVEL SECURITY;
CREATE POLICY files_access ON platform.files
  USING (org_id = (SELECT platform.current_org_id())
         OR (org_id IS NULL AND owner_user_id = (SELECT platform.current_user_id())))
  WITH CHECK (org_id = (SELECT platform.current_org_id())
         OR (org_id IS NULL AND owner_user_id = (SELECT platform.current_user_id())));

-- Identity: self-service only for the app role; the identity role bypasses RLS.
ALTER TABLE iam.users ENABLE ROW LEVEL SECURITY;
CREATE POLICY users_self ON iam.users USING (id = (SELECT platform.current_user_id()));
ALTER TABLE iam.user_profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY profiles_self ON iam.user_profiles USING (user_id = (SELECT platform.current_user_id()));
ALTER TABLE iam.user_addresses ENABLE ROW LEVEL SECURITY;
CREATE POLICY addresses_self ON iam.user_addresses USING (user_id = (SELECT platform.current_user_id()));
ALTER TABLE iam.devices ENABLE ROW LEVEL SECURITY;
CREATE POLICY devices_self ON iam.devices USING (user_id = (SELECT platform.current_user_id()));
ALTER TABLE payments.instruments ENABLE ROW LEVEL SECURITY;
CREATE POLICY instruments_self ON payments.instruments USING (user_id = (SELECT platform.current_user_id()));

-- Consumer self-access (permissive policies are OR-ed with tenant_isolation).
CREATE POLICY consumer_own_customer_records ON crm.customers FOR SELECT
  USING (user_id = (SELECT platform.current_user_id()));
CREATE POLICY consumer_own_orders ON commerce.orders FOR SELECT
  USING (buyer_user_id = (SELECT platform.current_user_id()));
CREATE POLICY consumer_own_order_lines ON commerce.order_lines FOR SELECT
  USING (EXISTS (SELECT 1 FROM commerce.orders o WHERE o.id = order_id AND o.buyer_user_id = (SELECT platform.current_user_id())));
CREATE POLICY consumer_own_tickets ON events.tickets FOR SELECT
  USING (holder_user_id = (SELECT platform.current_user_id())
         OR EXISTS (SELECT 1 FROM commerce.orders o WHERE o.id = order_id AND o.buyer_user_id = (SELECT platform.current_user_id())));
CREATE POLICY consumer_own_appointments ON scheduling.appointments FOR SELECT
  USING (booked_by_user_id = (SELECT platform.current_user_id())
         OR EXISTS (SELECT 1 FROM crm.customers c WHERE c.id = customer_id AND c.user_id = (SELECT platform.current_user_id())));
CREATE POLICY consumer_own_appointment_items ON scheduling.appointment_items FOR SELECT
  USING (EXISTS (SELECT 1 FROM scheduling.appointments a WHERE a.id = appointment_id));  -- visibility of the parent decides
CREATE POLICY consumer_own_intents ON payments.payment_intents FOR SELECT
  USING (payer_user_id = (SELECT platform.current_user_id()));
CREATE POLICY consumer_own_transfers ON events.ticket_transfers FOR SELECT
  USING (from_user_id = (SELECT platform.current_user_id()) OR to_user_id = (SELECT platform.current_user_id()));

-- Discovery & social
ALTER TABLE discovery.reviews ENABLE ROW LEVEL SECURITY;
CREATE POLICY reviews_public_read ON discovery.reviews FOR SELECT
  USING (status = 'PUBLISHED' OR author_user_id = (SELECT platform.current_user_id()) OR org_id = (SELECT platform.current_org_id()));
CREATE POLICY reviews_author_write ON discovery.reviews FOR INSERT
  WITH CHECK (author_user_id = (SELECT platform.current_user_id()));
CREATE POLICY reviews_author_or_org_update ON discovery.reviews FOR UPDATE
  USING (author_user_id = (SELECT platform.current_user_id()) OR org_id = (SELECT platform.current_org_id()));

ALTER TABLE discovery.follows ENABLE ROW LEVEL SECURITY;
CREATE POLICY follows_self ON discovery.follows
  USING (user_id = (SELECT platform.current_user_id())) WITH CHECK (user_id = (SELECT platform.current_user_id()));
CREATE POLICY follows_org_read ON discovery.follows FOR SELECT USING (org_id = (SELECT platform.current_org_id()));

ALTER TABLE discovery.saved_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY saved_items_self ON discovery.saved_items
  USING (user_id = (SELECT platform.current_user_id())) WITH CHECK (user_id = (SELECT platform.current_user_id()));

ALTER TABLE discovery.collections ENABLE ROW LEVEL SECURITY;
CREATE POLICY collections_read ON discovery.collections FOR SELECT
  USING (visibility <> 'PRIVATE' OR user_id = (SELECT platform.current_user_id()) OR org_id = (SELECT platform.current_org_id()));
CREATE POLICY collections_write ON discovery.collections FOR INSERT
  WITH CHECK ((owner = 'USER' AND user_id = (SELECT platform.current_user_id()))
           OR (owner = 'ORGANIZATION' AND org_id = (SELECT platform.current_org_id())));
CREATE POLICY collections_update ON discovery.collections FOR UPDATE
  USING ((owner = 'USER' AND user_id = (SELECT platform.current_user_id()))
      OR (owner = 'ORGANIZATION' AND org_id = (SELECT platform.current_org_id())));

-- Notifications: the recipient reads their inbox; the org sees notifications it sent.
ALTER TABLE comms.notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_recipient ON comms.notifications FOR SELECT
  USING (recipient_user_id = (SELECT platform.current_user_id()) OR org_id = (SELECT platform.current_org_id()));
CREATE POLICY notifications_mark_read ON comms.notifications FOR UPDATE
  USING (recipient_user_id = (SELECT platform.current_user_id()));
CREATE POLICY notifications_org_insert ON comms.notifications FOR INSERT
  WITH CHECK (org_id = (SELECT platform.current_org_id()));
ALTER TABLE comms.preferences ENABLE ROW LEVEL SECURITY;
CREATE POLICY preferences_self ON comms.preferences
  USING (user_id = (SELECT platform.current_user_id())) WITH CHECK (user_id = (SELECT platform.current_user_id()));

-- Conversations: org staff via tenant policy; customers via participation.
CREATE POLICY conversations_participant ON comms.conversations FOR SELECT
  USING (EXISTS (SELECT 1 FROM comms.participants p
                  WHERE p.conversation_id = comms.conversations.id AND p.user_id = (SELECT platform.current_user_id())));
CREATE POLICY participants_self ON comms.participants FOR SELECT
  USING (user_id = (SELECT platform.current_user_id()));
CREATE POLICY messages_participant ON comms.messages FOR SELECT
  USING (EXISTS (SELECT 1 FROM comms.participants p
                  WHERE p.conversation_id = comms.messages.conversation_id AND p.user_id = (SELECT platform.current_user_id())));

-- Trust: partners file KYB cases and read their own; reporters file reports.
ALTER TABLE trust.verification_cases ENABLE ROW LEVEL SECURITY;
CREATE POLICY verification_cases_org ON trust.verification_cases
  USING (org_id = (SELECT platform.current_org_id())) WITH CHECK (org_id = (SELECT platform.current_org_id()));
ALTER TABLE trust.verification_documents ENABLE ROW LEVEL SECURITY;
CREATE POLICY verification_documents_org ON trust.verification_documents
  USING (EXISTS (SELECT 1 FROM trust.verification_cases c WHERE c.id = case_id))
  WITH CHECK (EXISTS (SELECT 1 FROM trust.verification_cases c WHERE c.id = case_id));
ALTER TABLE trust.content_reports ENABLE ROW LEVEL SECURITY;
CREATE POLICY content_reports_file ON trust.content_reports FOR INSERT
  WITH CHECK (reporter_user_id = (SELECT platform.current_user_id()));

ALTER TABLE platform.support_cases ENABLE ROW LEVEL SECURITY;
CREATE POLICY support_cases_access ON platform.support_cases
  USING (requester_user_id = (SELECT platform.current_user_id()) OR org_id = (SELECT platform.current_org_id()))
  WITH CHECK (requester_user_id = (SELECT platform.current_user_id()));
ALTER TABLE platform.support_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY support_messages_access ON platform.support_messages
  USING (NOT is_internal AND EXISTS (SELECT 1 FROM platform.support_cases c WHERE c.id = case_id))
  WITH CHECK (NOT is_internal AND author_user_id = (SELECT platform.current_user_id()));

-- Role permissions: everyone reads the templates + own org roles; only own (non-system) roles are editable.
ALTER TABLE tenant.role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY role_permissions_read ON tenant.role_permissions FOR SELECT
  USING (EXISTS (SELECT 1 FROM tenant.roles r WHERE r.id = role_id));
CREATE POLICY role_permissions_write ON tenant.role_permissions FOR ALL
  USING (EXISTS (SELECT 1 FROM tenant.roles r WHERE r.id = role_id AND r.org_id = (SELECT platform.current_org_id())))
  WITH CHECK (EXISTS (SELECT 1 FROM tenant.roles r WHERE r.id = role_id AND r.org_id = (SELECT platform.current_org_id())));

-- Collection items follow their collection's visibility and ownership.
ALTER TABLE discovery.collection_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY collection_items_read ON discovery.collection_items FOR SELECT
  USING (EXISTS (SELECT 1 FROM discovery.collections c WHERE c.id = collection_id));
CREATE POLICY collection_items_write ON discovery.collection_items FOR ALL
  USING (EXISTS (SELECT 1 FROM discovery.collections c WHERE c.id = collection_id
                  AND ((c.owner = 'USER' AND c.user_id = (SELECT platform.current_user_id()))
                    OR (c.owner = 'ORGANIZATION' AND c.org_id = (SELECT platform.current_org_id())))))
  WITH CHECK (EXISTS (SELECT 1 FROM discovery.collections c WHERE c.id = collection_id
                  AND ((c.owner = 'USER' AND c.user_id = (SELECT platform.current_user_id()))
                    OR (c.owner = 'ORGANIZATION' AND c.org_id = (SELECT platform.current_org_id())))));

ALTER TABLE platform.data_exports ENABLE ROW LEVEL SECURITY;
CREATE POLICY data_exports_org ON platform.data_exports
  USING (org_id = (SELECT platform.current_org_id())) WITH CHECK (org_id = (SELECT platform.current_org_id()));

-- -----------------------------------------------------------------------------
-- 3. Grants
-- -----------------------------------------------------------------------------
GRANT USAGE ON SCHEMA platform, ref, iam, tenant, team, catalog, crm, events, commerce, scheduling, retention,
                      stock, payments, ledger, billing, fiscal, marketing, comms, discovery, trust
  TO beavent_app, beavent_system, beavent_readonly;
GRANT USAGE ON SCHEMA platform, ref, iam TO beavent_identity;

-- System role: everything (BYPASSRLS). Identity role: iam + reference reads.
GRANT ALL ON ALL TABLES IN SCHEMA platform, ref, iam, tenant, team, catalog, crm, events, commerce, scheduling, retention,
                                  stock, payments, ledger, billing, fiscal, marketing, comms, discovery, trust TO beavent_system;
GRANT ALL ON ALL TABLES IN SCHEMA iam TO beavent_identity;
GRANT SELECT ON ALL TABLES IN SCHEMA ref TO beavent_identity;
GRANT SELECT, INSERT ON platform.audit_log, platform.outbox TO beavent_identity;
GRANT SELECT, INSERT, UPDATE ON comms.notifications, comms.deliveries TO beavent_identity;

-- App role: full DML on tenant-owned schemas (RLS confines it).
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenant, team, catalog, crm, events, commerce, scheduling,
                                                          retention, stock, marketing, discovery TO beavent_app;
GRANT SELECT ON ALL TABLES IN SCHEMA ref TO beavent_app;
GRANT SELECT, UPDATE ON iam.users, iam.user_profiles TO beavent_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.user_addresses, iam.devices TO beavent_app;
GRANT SELECT ON iam.permissions, iam.staff_roles, iam.staff_role_permissions TO beavent_app;
-- (no grants on iam.user_credentials, sessions, auth_challenges, mfa_factors, user_identities, user_staff_roles)

GRANT SELECT ON payments.providers, payments.methods, payments.provider_capabilities,
                payments.provider_accounts, payments.routes TO beavent_app;
GRANT SELECT, INSERT, UPDATE ON payments.org_settings, payments.payment_intents, payments.payment_attempts,
                payments.payment_links, payments.refunds, payments.refund_lines, payments.payout_accounts,
                payments.payouts, payments.disputes, payments.instruments TO beavent_app;
-- (no grants on webhook_events, provider_statements, statement_lines — system only)

GRANT SELECT ON ledger.accounts, ledger.transactions, ledger.entries, ledger.balances TO beavent_app;
GRANT SELECT ON billing.plans, billing.plan_prices, billing.plan_entitlements, billing.fee_rules,
                billing.subscriptions, billing.subscription_addons, billing.invoices, billing.invoice_lines,
                billing.fee_assessments, billing.usage_records TO beavent_app;
GRANT INSERT ON billing.fee_assessments, billing.usage_records TO beavent_app;
GRANT SELECT ON fiscal.schemes TO beavent_app;
GRANT SELECT, INSERT, UPDATE ON fiscal.registrations, fiscal.documents TO beavent_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON comms.templates, comms.conversations, comms.participants, comms.messages,
                comms.ai_agents, comms.ai_knowledge, comms.ai_sessions, comms.ai_actions, comms.preferences TO beavent_app;
GRANT SELECT, INSERT, UPDATE ON comms.notifications TO beavent_app;
GRANT SELECT, INSERT ON trust.verification_cases, trust.verification_documents TO beavent_app;
GRANT INSERT ON trust.content_reports TO beavent_app;
GRANT SELECT, INSERT, UPDATE ON platform.files, platform.idempotency_keys, platform.processed_messages,
                platform.support_cases, platform.support_messages, platform.data_exports,
                platform.api_clients, platform.webhook_endpoints, platform.integrations TO beavent_app;
GRANT INSERT ON platform.outbox, platform.audit_log TO beavent_app;
GRANT SELECT ON platform.feature_flags, platform.feature_flag_overrides, platform.settings TO beavent_app;
GRANT DELETE ON platform.idempotency_keys TO beavent_app;

-- Append-only tables: no UPDATE/DELETE for the app even where a schema-wide grant gave it.
REVOKE UPDATE, DELETE ON commerce.order_line_taxes, commerce.order_status_history, commerce.cash_movements,
                         events.inventory_movements, events.scans, scheduling.appointment_status_history,
                         retention.gift_card_entries, retention.customer_balance_entries, retention.entitlement_usages,
                         retention.loyalty_entries, stock.movements, comms.ai_actions
  FROM beavent_app;
-- The marketplace read model is public to read and written only by the projector (system role);
-- search analytics are write-only for the app.
REVOKE INSERT, UPDATE, DELETE ON discovery.listings FROM beavent_app;
REVOKE SELECT, UPDATE, DELETE ON discovery.search_events FROM beavent_app;

-- Counters maintained by triggers must not be written directly by the app.
REVOKE UPDATE ON events.inventory_buckets FROM beavent_app;
GRANT UPDATE (capacity) ON events.inventory_buckets TO beavent_app;
REVOKE INSERT, UPDATE, DELETE ON stock.levels FROM beavent_app;
GRANT UPDATE (reorder_point, reorder_quantity) ON stock.levels TO beavent_app;

-- Read-only analytics: no identity, CRM free-text, messaging or KYC tables.
GRANT SELECT ON ALL TABLES IN SCHEMA ref, catalog, events, commerce, scheduling, payments, ledger, billing, discovery TO beavent_readonly;
REVOKE SELECT ON payments.webhook_events, payments.instruments FROM beavent_readonly;

-- Functions: revoke the PUBLIC default, then grant to the roles that use them.
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA platform, commerce, scheduling, events, retention, payments, ledger, stock FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA platform, commerce, scheduling, events, retention, payments, ledger, stock
  TO beavent_app, beavent_system, beavent_identity, beavent_readonly;
-- Migration-only helper.
REVOKE EXECUTE ON FUNCTION platform.apply_tenant_rls(regclass, boolean) FROM beavent_app, beavent_identity, beavent_readonly;
