-- Tenant isolation under the real runtime role (beavent_app), not as the owner.
\set ON_ERROR_STOP on
BEGIN;

-- Consumer order at org B for the consumer, and one at org A for someone else.
INSERT INTO commerce.orders (id, org_id, legal_entity_id, number, customer_id, buyer_user_id, channel, status, currency_code, prices_include_tax) VALUES
  ('b0000000-0000-0000-0000-0000000000f9','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000001','B-9',
   'b0000000-0000-0000-0000-000000000008','33333333-3333-3333-3333-333333333333','MARKETPLACE_WEB','DRAFT','ETB',true),
  ('a0000000-0000-0000-0000-0000000000f9','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000001','A-9',
   'a0000000-0000-0000-0000-000000000008','11111111-1111-1111-1111-111111111111','PARTNER_POS','DRAFT','ETB',true);

SET LOCAL ROLE beavent_app;

-- No context: nothing tenant-owned is visible.
SELECT platform.set_request_context(NULL, NULL);
DO $$ BEGIN
  PERFORM test.assert((SELECT count(*) FROM crm.customers) = 0, 'no context sees no customers');
  PERFORM test.assert((SELECT count(*) FROM commerce.orders) = 0, 'no context sees no orders');
END $$;

-- Partner workspace A (owner of A).
SELECT platform.set_request_context('11111111-1111-1111-1111-111111111111', 'a0000000-0000-0000-0000-000000000000');
DO $$ BEGIN
  PERFORM test.assert((SELECT count(*) FROM crm.customers) = 1, 'org A sees only its customer');
  PERFORM test.assert((SELECT bool_and(org_id = 'a0000000-0000-0000-0000-000000000000') FROM commerce.orders), 'org A sees only its orders');
  PERFORM test.assert((SELECT count(*) FROM events.inventory_buckets) = 0, 'org A cannot see org B inventory');
  PERFORM test.assert((SELECT count(*) FROM tenant.organizations) = 1, 'org A sees its organization only');
  PERFORM test.assert((SELECT count(*) FROM tenant.roles WHERE org_id IS NULL) > 0, 'system role templates are readable');
  PERFORM test.assert((SELECT count(*) FROM tenant.places WHERE is_public_venue) = 1, 'public venues are readable');
END $$;
SELECT test.expect_error($$
  INSERT INTO crm.customers (org_id, display_name, source) VALUES ('b0000000-0000-0000-0000-000000000000','Intruder','WALK_IN')$$,
  '42501', 'cannot write into another organization');
DO $$ DECLARE n int; BEGIN
  UPDATE tenant.roles SET name = 'Hacked' WHERE org_id IS NULL AND code = 'OWNER';
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM test.assert(n = 0, 'system role templates cannot be modified');
  -- Silent no-op updates across tenants (RLS filters rows rather than erroring).
  UPDATE crm.customers SET display_name = 'x' WHERE org_id = 'b0000000-0000-0000-0000-000000000000';
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM test.assert(n = 0, 'cross-tenant update touches zero rows');
  DELETE FROM tenant.role_permissions WHERE role_id IN (SELECT id FROM tenant.roles WHERE org_id IS NULL);
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM test.assert(n = 0, 'system role permissions cannot be deleted');
END $$;
SELECT test.expect_error($$ SELECT * FROM iam.auth_challenges $$, '42501', 'app role has no access to one-time codes');
SELECT test.expect_error($$ SELECT * FROM iam.sessions $$, '42501', 'app role has no access to sessions');
SELECT test.expect_error($$ SELECT * FROM payments.webhook_events $$, '42501', 'app role has no access to raw webhooks');
SELECT test.expect_error($$ UPDATE events.inventory_buckets SET sold = 0 $$, '42501', 'app cannot write inventory counters directly');
SELECT test.expect_error($$ INSERT INTO discovery.listings (kind, org_id, market_id, slug_path, title) SELECT 'LOCATION', 'a0000000-0000-0000-0000-000000000000', id, '/x', 'x' FROM ref.markets LIMIT 1 $$,
  '42501', 'app cannot write the marketplace read model');

-- Consumer context: own customer records and orders across organizations, nothing else.
SELECT platform.set_request_context('33333333-3333-3333-3333-333333333333', NULL);
DO $$ BEGIN
  PERFORM test.assert((SELECT count(*) FROM crm.customers) = 2, 'consumer sees own customer record at each org');
  PERFORM test.assert((SELECT count(*) FROM commerce.orders) = 1, 'consumer sees only orders they bought');
  PERFORM test.assert((SELECT count(*) FROM iam.users) = 1, 'consumer sees only own user row');
  PERFORM test.assert((SELECT count(*) FROM team.staff) = 0, 'consumer sees no staff records');
END $$;
SELECT test.expect_error($$
  INSERT INTO commerce.orders (org_id, legal_entity_id, number, customer_id, channel, currency_code, prices_include_tax)
  VALUES ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000001','X-1','b0000000-0000-0000-0000-000000000008','MARKETPLACE_WEB','ETB',true)$$,
  '42501', 'consumer context cannot create orders without the checkout org context');

RESET ROLE;
ROLLBACK;
