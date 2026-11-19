-- Core invariants: identifiers, scheduling conflicts, ticket inventory, order arithmetic.
\set ON_ERROR_STOP on
BEGIN;

-- ---------------------------------------------------------------------------
-- UUIDv7: version nibble and time ordering
-- ---------------------------------------------------------------------------
DO $$
DECLARE a uuid; b uuid;
BEGIN
  a := platform.uuid_v7(); PERFORM pg_sleep(0.002); b := platform.uuid_v7();
  PERFORM test.assert(substr(a::text, 15, 1) = '7', 'uuid_v7 version nibble');
  PERFORM test.assert(substr(a::text, 20, 1) IN ('8','9','a','b'), 'uuid_v7 variant bits');
  PERFORM test.assert(a < b, 'uuid_v7 is time ordered');
END $$;

-- ---------------------------------------------------------------------------
-- Scheduling: the reservation ledger rejects double-booking across verticals
-- ---------------------------------------------------------------------------
INSERT INTO scheduling.calendar_blocks (id, org_id, location_id, staff_id, during, reason) VALUES
  ('a0000000-0000-0000-0000-0000000000c1','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003',
   'a0000000-0000-0000-0000-000000000004','[2027-01-10 07:00+00,2027-01-10 08:00+00)','MEETING'),
  ('a0000000-0000-0000-0000-0000000000c2','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003',
   'a0000000-0000-0000-0000-000000000004','[2027-01-10 07:30+00,2027-01-10 08:30+00)','BREAK'),
  ('a0000000-0000-0000-0000-0000000000c3','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003',
   'a0000000-0000-0000-0000-000000000004','[2027-01-10 08:00+00,2027-01-10 09:00+00)','BREAK');

INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, calendar_block_id) VALUES
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','STAFF','a0000000-0000-0000-0000-000000000004',
   '[2027-01-10 07:00+00,2027-01-10 08:00+00)','BLOCKED','a0000000-0000-0000-0000-0000000000c1');

SELECT test.expect_error($$
  INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, calendar_block_id) VALUES
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','STAFF','a0000000-0000-0000-0000-000000000004',
   '[2027-01-10 07:30+00,2027-01-10 08:30+00)','BLOCKED','a0000000-0000-0000-0000-0000000000c2')$$,
  '23P01', 'overlapping staff reservations are rejected');

-- Back-to-back is fine (half-open ranges).
INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, calendar_block_id) VALUES
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','STAFF','a0000000-0000-0000-0000-000000000004',
   '[2027-01-10 08:00+00,2027-01-10 09:00+00)','BLOCKED','a0000000-0000-0000-0000-0000000000c3');

-- A class/event occurrence that assigns the same instructor conflicts with her calendar.
INSERT INTO events.events (id, org_id, legal_entity_id, slug, category_id, kind, source_language, currency_code, created_by, location_id)
SELECT 'a0000000-0000-0000-0000-0000000000e1','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000001',
       'braiding-masterclass', id, 'CLASS_SERIES', 'am', 'ETB', '11111111-1111-1111-1111-111111111111', 'a0000000-0000-0000-0000-000000000003'
FROM ref.categories WHERE kind = 'EVENT' AND code = 'EDUCATION';
INSERT INTO events.occurrences (id, org_id, event_id, starts_at, ends_at, timezone, place_id) VALUES
  ('a0000000-0000-0000-0000-0000000000e2','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000e1',
   '2027-01-10 08:30+00','2027-01-10 10:00+00','Africa/Addis_Ababa','a0000000-0000-0000-0000-000000000002');
INSERT INTO scheduling.occurrence_assignments (id, org_id, occurrence_id, staff_id) VALUES
  ('a0000000-0000-0000-0000-0000000000e3','a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-0000000000e2','a0000000-0000-0000-0000-000000000004');
SELECT test.expect_error($$
  INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, occurrence_assignment_id) VALUES
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','STAFF','a0000000-0000-0000-0000-000000000004',
   '[2027-01-10 08:30+00,2027-01-10 10:00+00)','CONFIRMED','a0000000-0000-0000-0000-0000000000e3')$$,
  '23P01', 'class instructor cannot be double-booked against appointments/blocks');

-- Released rows no longer block.
UPDATE scheduling.schedule_reservations SET status = 'RELEASED' WHERE calendar_block_id = 'a0000000-0000-0000-0000-0000000000c3';
INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, occurrence_assignment_id) VALUES
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','STAFF','a0000000-0000-0000-0000-000000000004',
   '[2027-01-10 08:30+00,2027-01-10 10:00+00)','CONFIRMED','a0000000-0000-0000-0000-0000000000e3');

-- Resource units: two units of the steam room can be used in parallel, not the same unit twice.
INSERT INTO scheduling.calendar_blocks (id, org_id, location_id, resource_id, during, reason)
SELECT ('a0000000-0000-0000-0000-0000000000d' || n)::uuid, 'a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003',
       'a0000000-0000-0000-0000-000000000005','[2027-01-11 07:00+00,2027-01-11 08:00+00)','MAINTENANCE'
FROM generate_series(1,3) n;
INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, resource_id, resource_unit, during, status, calendar_block_id) VALUES
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','RESOURCE','a0000000-0000-0000-0000-000000000005',1,
   '[2027-01-11 07:00+00,2027-01-11 08:00+00)','BLOCKED','a0000000-0000-0000-0000-0000000000d1'),
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','RESOURCE','a0000000-0000-0000-0000-000000000005',2,
   '[2027-01-11 07:00+00,2027-01-11 08:00+00)','BLOCKED','a0000000-0000-0000-0000-0000000000d2');
SELECT test.expect_error($$
  INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, resource_id, resource_unit, during, status, calendar_block_id) VALUES
  ('a0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','RESOURCE','a0000000-0000-0000-0000-000000000005',1,
   '[2027-01-11 07:30+00,2027-01-11 08:00+00)','BLOCKED','a0000000-0000-0000-0000-0000000000d3')$$,
  '23P01', 'same resource unit cannot be double-booked');

-- Cross-tenant FK substitution is impossible: org A row pointing at org B's location.
SELECT test.expect_error($$
  INSERT INTO scheduling.calendar_blocks (org_id, location_id, staff_id, during, reason) VALUES
  ('b0000000-0000-0000-0000-000000000000','a0000000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-000000000004',
   '[2027-01-12 07:00+00,2027-01-12 08:00+00)','BREAK')$$,
  '23503', 'composite tenant FK blocks cross-organization references');

-- ---------------------------------------------------------------------------
-- Ticket inventory: movements drive counters; no oversell; no double release
-- ---------------------------------------------------------------------------
INSERT INTO commerce.orders (id, org_id, legal_entity_id, number, customer_id, channel, status, currency_code, prices_include_tax) VALUES
  ('b0000000-0000-0000-0000-0000000000f1','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000001','T-1',
   'b0000000-0000-0000-0000-000000000008','MARKETPLACE_APP','DRAFT','ETB',true),
  ('b0000000-0000-0000-0000-0000000000f2','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-000000000001','T-2',
   'b0000000-0000-0000-0000-000000000008','MARKETPLACE_APP','DRAFT','ETB',true);
INSERT INTO commerce.order_lines (id, org_id, order_id, seq, line_type, ticket_type_id, occurrence_id, description, quantity,
                                  unit_price_minor, total_minor, currency_code, tax_category_code, tax_inclusive) VALUES
  ('b0000000-0000-0000-0000-0000000000a1','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-0000000000f1',1,'TICKET',
   'b0000000-0000-0000-0000-00000000000b','b0000000-0000-0000-0000-00000000000a','VIP',2,500000,1000000,'ETB','EVENT_ADMISSION',true),
  ('b0000000-0000-0000-0000-0000000000a2','b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-0000000000f2',1,'TICKET',
   'b0000000-0000-0000-0000-00000000000b','b0000000-0000-0000-0000-00000000000a','VIP',1,500000,500000,'ETB','EVENT_ADMISSION',true);

INSERT INTO events.inventory_movements (org_id, bucket_id, kind, quantity, order_id, order_line_id) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-00000000000c','RESERVE',2,'b0000000-0000-0000-0000-0000000000f1','b0000000-0000-0000-0000-0000000000a1'),
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-00000000000d','RESERVE',2,'b0000000-0000-0000-0000-0000000000f1','b0000000-0000-0000-0000-0000000000a1');
DO $$ BEGIN
  PERFORM test.assert((SELECT reserved FROM events.inventory_buckets WHERE id = 'b0000000-0000-0000-0000-00000000000d') = 2, 'reserve increments bucket');
END $$;

SELECT test.expect_error($$
  INSERT INTO events.inventory_movements (org_id, bucket_id, kind, quantity, order_id, order_line_id) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-00000000000d','RESERVE',1,'b0000000-0000-0000-0000-0000000000f2','b0000000-0000-0000-0000-0000000000a2')$$,
  '23514', 'cannot oversell a ticket type');

INSERT INTO events.inventory_movements (org_id, bucket_id, kind, quantity, order_id, order_line_id) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-00000000000d','CONFIRM',2,'b0000000-0000-0000-0000-0000000000f1','b0000000-0000-0000-0000-0000000000a1');
DO $$ BEGIN
  PERFORM test.assert((SELECT sold = 2 AND reserved = 0 FROM events.inventory_buckets WHERE id = 'b0000000-0000-0000-0000-00000000000d'), 'confirm moves reserved to sold');
END $$;
SELECT test.expect_error($$
  INSERT INTO events.inventory_movements (org_id, bucket_id, kind, quantity, order_id, order_line_id) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-00000000000d','RELEASE',2,'b0000000-0000-0000-0000-0000000000f1','b0000000-0000-0000-0000-0000000000a1')$$,
  '23514', 'cannot release a reservation that was already confirmed');
SELECT test.expect_error($$
  INSERT INTO events.inventory_movements (org_id, bucket_id, kind, quantity, order_line_id) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-00000000000d','RETURN',3,'b0000000-0000-0000-0000-0000000000a1')$$,
  '23514', 'cannot return more than was sold');
SELECT test.expect_error($$ UPDATE events.inventory_movements SET quantity = 1 $$, '23001', 'inventory movements are append-only');

-- Tickets: a paid line can be issued only once per unit/admission.
INSERT INTO events.tickets (org_id, order_id, order_line_id, unit_index, event_id, occurrence_id, ticket_type_id, serial, credential_hash) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-0000000000f1','b0000000-0000-0000-0000-0000000000a1',1,
   'b0000000-0000-0000-0000-000000000009','b0000000-0000-0000-0000-00000000000a','b0000000-0000-0000-0000-00000000000b','BV-AAAA','h1');
SELECT test.expect_error($$
  INSERT INTO events.tickets (org_id, order_id, order_line_id, unit_index, event_id, occurrence_id, ticket_type_id, serial, credential_hash) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-0000000000f1','b0000000-0000-0000-0000-0000000000a1',1,
   'b0000000-0000-0000-0000-000000000009','b0000000-0000-0000-0000-00000000000a','b0000000-0000-0000-0000-00000000000b','BV-BBBB','h2')$$,
  '23505', 'duplicate ticket issuance is impossible');

-- ---------------------------------------------------------------------------
-- Order arithmetic
-- ---------------------------------------------------------------------------
SELECT test.expect_error($$
  INSERT INTO commerce.order_lines (org_id, order_id, seq, line_type, ticket_type_id, description, quantity,
                                    unit_price_minor, total_minor, currency_code, tax_category_code, tax_inclusive) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-0000000000f2',2,'TICKET','b0000000-0000-0000-0000-00000000000b',
   'VIP',1,500000,400000,'ETB','EVENT_ADMISSION',true)$$,
  '23514', 'line total must equal price x qty - discount (+ tax if exclusive)');
SELECT test.expect_error($$
  INSERT INTO commerce.order_lines (org_id, order_id, seq, line_type, description, quantity,
                                    unit_price_minor, total_minor, currency_code, tax_category_code, tax_inclusive) VALUES
  ('b0000000-0000-0000-0000-000000000000','b0000000-0000-0000-0000-0000000000f2',3,'TICKET','VIP',1,500000,500000,'ETB','EVENT_ADMISSION',true)$$,
  '23514', 'a TICKET line must reference a ticket type');

-- Header totals must match lines once the order leaves DRAFT (deferred check).
SELECT test.expect_error($$
  UPDATE commerce.orders SET status = 'PENDING_PAYMENT', expires_at = now() + interval '10 minutes', total_minor = 999
   WHERE id = 'b0000000-0000-0000-0000-0000000000f1';
  SET CONSTRAINTS ALL IMMEDIATE$$,
  '23514', 'order totals must equal the sum of its lines');
UPDATE commerce.orders SET status = 'PENDING_PAYMENT', expires_at = now() + interval '10 minutes',
       subtotal_minor = 1000000, total_minor = 1000000
 WHERE id = 'b0000000-0000-0000-0000-0000000000f1';
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

ROLLBACK;
