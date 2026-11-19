#!/usr/bin/env bash
# Race test against a validated database (called by validate.sh after the tests).
#  1. 40 concurrent buyers each try to RESERVE 1 ticket from a bucket of 5.
#     Expect exactly 5 successes and bucket.reserved = 5.
#  2. 40 concurrent sessions each try to book the same staff member for
#     overlapping times. Expect exactly 1 success.
set -euo pipefail
DB="$1"
N=40
ORG=b0000000-0000-0000-0000-000000000000

psql -X -q -v ON_ERROR_STOP=1 -d "$DB" <<SQL >/dev/null
INSERT INTO events.ticket_types (id, org_id, event_id, name_i18n, currency_code, price_minor)
VALUES ('b0000000-0000-0000-0000-0000000000ee','$ORG','b0000000-0000-0000-0000-000000000009','{"en":"Race"}','ETB',1000);
INSERT INTO events.inventory_buckets (id, org_id, event_id, occurrence_id, scope, ticket_type_id, capacity)
VALUES ('b0000000-0000-0000-0000-0000000000ef','$ORG','b0000000-0000-0000-0000-000000000009','b0000000-0000-0000-0000-00000000000a','TICKET_TYPE','b0000000-0000-0000-0000-0000000000ee',5);
INSERT INTO commerce.orders (id, org_id, legal_entity_id, number, customer_id, channel, status, currency_code, prices_include_tax)
SELECT ('b1000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, '$ORG', 'b0000000-0000-0000-0000-000000000001', 'RACE-' || g,
       'b0000000-0000-0000-0000-000000000008', 'MARKETPLACE_APP', 'DRAFT', 'ETB', true
FROM generate_series(1, $N) g;
INSERT INTO commerce.order_lines (id, org_id, order_id, seq, line_type, ticket_type_id, occurrence_id, description, quantity,
                                  unit_price_minor, total_minor, currency_code, tax_category_code, tax_inclusive)
SELECT ('b2000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, '$ORG', ('b1000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       1, 'TICKET', 'b0000000-0000-0000-0000-0000000000ee', 'b0000000-0000-0000-0000-00000000000a', 'Race', 1, 1000, 1000, 'ETB', 'EVENT_ADMISSION', true
FROM generate_series(1, $N) g;
INSERT INTO scheduling.calendar_blocks (id, org_id, location_id, staff_id, during, reason)
SELECT ('a1000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'a0000000-0000-0000-0000-000000000000',
       'a0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000004',
       tstzrange('2027-02-01 07:00+00'::timestamptz + (g || ' minutes')::interval, '2027-02-01 08:00+00'::timestamptz + (g || ' minutes')::interval), 'OTHER'
FROM generate_series(1, $N) g;
SQL

pids=()
for g in $(seq 1 $N); do
  id=$(printf '%012d' "$g")
  (
    psql -X -q -d "$DB" -c "INSERT INTO events.inventory_movements (org_id, bucket_id, kind, quantity, order_id, order_line_id)
      VALUES ('$ORG','b0000000-0000-0000-0000-0000000000ef','RESERVE',1,'b1000000-0000-0000-0000-$id','b2000000-0000-0000-0000-$id')" >/dev/null 2>&1 || true
    psql -X -q -d "$DB" -c "INSERT INTO scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, calendar_block_id)
      SELECT org_id, location_id, 'STAFF', staff_id, during, 'BLOCKED', id FROM scheduling.calendar_blocks
       WHERE id = 'a1000000-0000-0000-0000-$id'" >/dev/null 2>&1 || true
  ) &
  pids+=($!)
done
wait "${pids[@]}"

read -r reserved movements bookings < <(psql -X -At -F ' ' -d "$DB" -c "
  SELECT (SELECT reserved FROM events.inventory_buckets WHERE id = 'b0000000-0000-0000-0000-0000000000ef'),
         (SELECT count(*) FROM events.inventory_movements WHERE bucket_id = 'b0000000-0000-0000-0000-0000000000ef'),
         (SELECT count(*) FROM scheduling.schedule_reservations r JOIN scheduling.calendar_blocks b ON b.id = r.calendar_block_id
           WHERE b.id::text LIKE 'a1000000-%')")
echo "  race: $N buyers for 5 tickets -> reserved=$reserved movements=$movements; $N overlapping bookings -> accepted=$bookings"
[[ "$reserved" == "5" && "$movements" == "5" && "$bookings" == "1" ]] || { echo "  RACE TEST FAILED"; exit 1; }
echo "  race: pass"
