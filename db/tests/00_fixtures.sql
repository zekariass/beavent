-- Shared fixtures for the invariant tests (committed into the throwaway validation DB).
-- Two organizations A and B, an owner for each, and a consumer who is a customer of both.
\set ON_ERROR_STOP on

CREATE SCHEMA test;

-- Runs a statement in a subtransaction and asserts it fails with the given SQLSTATE.
CREATE FUNCTION test.expect_error(p_sql text, p_sqlstate text, p_label text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE p_sql;
  EXCEPTION WHEN OTHERS THEN
    IF SQLSTATE <> p_sqlstate THEN
      RAISE EXCEPTION 'FAIL [%]: expected SQLSTATE %, got % (%)', p_label, p_sqlstate, SQLSTATE, SQLERRM;
    END IF;
    RETURN;
  END;
  RAISE EXCEPTION 'FAIL [%]: statement succeeded but should have failed with %', p_label, p_sqlstate;
END $$;

CREATE FUNCTION test.assert(p_ok boolean, p_label text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  IF p_ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL [%]', p_label; END IF;
END $$;

GRANT USAGE ON SCHEMA test TO beavent_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA test TO beavent_app;

INSERT INTO iam.users (id, phone_e164, status, country_code, preferred_language) VALUES
  ('11111111-1111-1111-1111-111111111111', '+251911000001', 'ACTIVE', 'ET', 'am'),
  ('22222222-2222-2222-2222-222222222222', '+251911000002', 'ACTIVE', 'ET', 'am'),
  ('33333333-3333-3333-3333-333333333333', '+251911000003', 'ACTIVE', 'ET', 'en');

INSERT INTO tenant.organizations (id, slug, display_name, kind, home_market_id, status, default_language, created_by_user_id)
SELECT o.id::uuid, o.slug, o.name, o.kind, m.id, 'ACTIVE', 'am', o.owner::uuid
FROM (VALUES ('a0000000-0000-0000-0000-000000000000','abeba-beauty','Abeba Beauty','BUSINESS','11111111-1111-1111-1111-111111111111'),
             ('b0000000-0000-0000-0000-000000000000','addis-live','Addis Live Events','EVENT_ORGANIZER','22222222-2222-2222-2222-222222222222'))
     AS o(id, slug, name, kind, owner)
CROSS JOIN ref.markets m WHERE m.code = 'ET';

INSERT INTO tenant.members (id, org_id, user_id, role_id, is_owner)
SELECT x.id::uuid, x.org::uuid, x.usr::uuid, r.id, true
FROM (VALUES ('a0000000-0000-0000-0000-00000000000e','a0000000-0000-0000-0000-000000000000','11111111-1111-1111-1111-111111111111'),
             ('b0000000-0000-0000-0000-00000000000e','b0000000-0000-0000-0000-000000000000','22222222-2222-2222-2222-222222222222')) AS x(id, org, usr)
CROSS JOIN tenant.roles r WHERE r.org_id IS NULL AND r.code = 'OWNER';

INSERT INTO tenant.legal_entities (id, org_id, country_code, entity_type, registered_name, tin, tax_regime, functional_currency, is_default) VALUES
  ('a0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000000','ET','SOLE_PROPRIETOR','Abeba Beauty','0012345678','TOT','ETB',true),
  ('b0000000-0000-0000-0000-000000000001','b0000000-0000-0000-0000-000000000000','ET','PLC','Addis Live PLC','0087654321','TOT','ETB',true);

INSERT INTO tenant.places (id, owner_org_id, name, kind, country_code, landmark, timezone) VALUES
  ('a0000000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-000000000000','Abeba Bole','PREMISES','ET','Bole, near Edna Mall','Africa/Addis_Ababa'),
  ('b0000000-0000-0000-0000-000000000002', NULL,'Millennium Hall','VENUE','ET','Bole Road','Africa/Addis_Ababa');
UPDATE tenant.places SET is_public_venue = true WHERE id = 'b0000000-0000-0000-0000-000000000002';

INSERT INTO tenant.locations (id, org_id, legal_entity_id, place_id, name, slug, timezone, currency_code) VALUES
  ('a0000000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000001',
   'a0000000-0000-0000-0000-000000000002','Bole branch','bole','Africa/Addis_Ababa','ETB');

INSERT INTO team.staff (id, org_id, member_id, display_name) VALUES
  ('a0000000-0000-0000-0000-000000000004','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-00000000000e','Hana');
INSERT INTO catalog.resources (id, org_id, location_id, name, kind, units) VALUES
  ('a0000000-0000-0000-0000-000000000005','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','Steam room','ROOM',2);

INSERT INTO catalog.services (id, org_id, category_id, name_i18n)
SELECT 'a0000000-0000-0000-0000-000000000006','a0000000-0000-0000-0000-000000000000', id, '{"en":"Silk press"}'
FROM ref.categories WHERE kind = 'SERVICE' AND code = 'HAIR';
INSERT INTO catalog.service_variants (id, org_id, service_id, duration_minutes, price_minor, currency_code, is_default) VALUES
  ('a0000000-0000-0000-0000-000000000007','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000006',60,150000,'ETB',true);

INSERT INTO crm.customers (id, org_id, user_id, display_name, phone_e164, source) VALUES
  ('a0000000-0000-0000-0000-000000000008','a0000000-0000-0000-0000-000000000000','33333333-3333-3333-3333-333333333333','Selam','+251911000003','MARKETPLACE'),
  ('b0000000-0000-0000-0000-000000000008','b0000000-0000-0000-0000-000000000000','33333333-3333-3333-3333-333333333333','Selam','+251911000003','EVENT_ORDER');

-- Org B: an event with one occurrence, one ticket type, capacity 100 overall / 2 for the type.
INSERT INTO events.events (id, org_id, legal_entity_id, slug, category_id, source_language, currency_code, created_by)
SELECT 'b0000000-0000-0000-0000-000000000009','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000001',
       'jazz-night', id, 'en', 'ETB', '22222222-2222-2222-2222-222222222222'
FROM ref.categories WHERE kind = 'EVENT' AND code = 'MUSIC';
INSERT INTO events.occurrences (id, org_id, event_id, starts_at, ends_at, timezone, place_id) VALUES
  ('b0000000-0000-0000-0000-00000000000a','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000009',
   '2027-01-15 17:00+00','2027-01-15 21:00+00','Africa/Addis_Ababa','b0000000-0000-0000-0000-000000000002');
INSERT INTO events.ticket_types (id, org_id, event_id, name_i18n, currency_code, price_minor) VALUES
  ('b0000000-0000-0000-0000-00000000000b','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000009','{"en":"VIP"}','ETB',500000);
INSERT INTO events.inventory_buckets (id, org_id, event_id, occurrence_id, scope, ticket_type_id, capacity) VALUES
  ('b0000000-0000-0000-0000-00000000000c','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000009',
   'b0000000-0000-0000-0000-00000000000a','OCCURRENCE',NULL,100),
  ('b0000000-0000-0000-0000-00000000000d','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000009',
   'b0000000-0000-0000-0000-00000000000a','TICKET_TYPE','b0000000-0000-0000-0000-00000000000b',2);
