-- =============================================================================
-- 10: ticketing — inventory movements, ticket issuance, transfers, check-in.
-- =============================================================================

-- Append-only movement log. The trigger below is the ONLY writer of bucket
-- counters, and the bucket CHECK (sold + reserved + held <= capacity) is the
-- final oversell guard. Insert movements for several buckets in bucket-id order
-- to avoid deadlocks between concurrent checkouts.
CREATE TABLE events.inventory_movements (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  bucket_id     uuid NOT NULL,
  kind          text NOT NULL CHECK (kind IN (
                  'RESERVE',    -- checkout started: reserved += q
                  'RELEASE',    -- checkout expired/cancelled: reserved -= q
                  'CONFIRM',    -- paid: reserved -= q, sold += q
                  'SELL',       -- direct sale without reservation (box office, comp): sold += q
                  'RETURN',     -- refund/cancel of sold tickets: sold -= q
                  'HOLD',       -- organizer hold / waitlist offer: held += q
                  'UNHOLD',     -- hold released back to sale: held -= q
                  'HOLD_SELL')),-- sold out of a hold: held -= q, sold += q
  quantity      integer NOT NULL CHECK (quantity > 0),
  order_id      uuid,
  order_line_id uuid,
  hold_id       uuid,
  waitlist_offer_id uuid,
  reason        text,
  actor_user_id uuid REFERENCES iam.users(id),
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, bucket_id) REFERENCES events.inventory_buckets (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, hold_id) REFERENCES events.inventory_holds (org_id, id),
  FOREIGN KEY (org_id, waitlist_offer_id) REFERENCES commerce.waitlist_offers (org_id, id),
  CHECK (kind NOT IN ('RESERVE','RELEASE','CONFIRM','SELL','RETURN') OR order_line_id IS NOT NULL),
  CHECK (kind NOT IN ('HOLD','UNHOLD','HOLD_SELL') OR num_nonnulls(hold_id, waitlist_offer_id) = 1)
);
-- A line reserves, releases and confirms a bucket at most once each.
CREATE UNIQUE INDEX inventory_movements_line_once
  ON events.inventory_movements (order_line_id, bucket_id, kind) WHERE kind IN ('RESERVE','RELEASE','CONFIRM');
CREATE INDEX inventory_movements_bucket_idx ON events.inventory_movements (bucket_id, occurred_at);
CREATE TRIGGER inventory_movements_immutable BEFORE UPDATE OR DELETE ON events.inventory_movements
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE FUNCTION events.apply_inventory_movement() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  q integer := NEW.quantity;
  v_reserved integer;
  v_sold integer;
BEGIN
  -- Lifecycle rules for reservation-based movements. (AFTER trigger: NEW is already
  -- visible to these queries, so it is excluded explicitly.)
  IF NEW.kind IN ('RELEASE','CONFIRM') THEN
    SELECT quantity INTO v_reserved FROM events.inventory_movements
     WHERE order_line_id = NEW.order_line_id AND bucket_id = NEW.bucket_id AND kind = 'RESERVE';
    IF v_reserved IS NULL OR v_reserved <> q THEN
      RAISE EXCEPTION '% of % without a matching RESERVE of the same quantity', NEW.kind, NEW.order_line_id
        USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM events.inventory_movements
                WHERE order_line_id = NEW.order_line_id AND bucket_id = NEW.bucket_id
                  AND kind IN ('RELEASE','CONFIRM') AND id <> NEW.id) THEN
      RAISE EXCEPTION 'reservation for line % on bucket % is already settled', NEW.order_line_id, NEW.bucket_id
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.kind = 'RETURN' THEN
    SELECT coalesce(sum(CASE WHEN kind IN ('CONFIRM','SELL','HOLD_SELL') THEN quantity ELSE -quantity END), 0)
      INTO v_sold FROM events.inventory_movements
     WHERE order_line_id = NEW.order_line_id AND bucket_id = NEW.bucket_id
       AND kind IN ('CONFIRM','SELL','HOLD_SELL','RETURN') AND id <> NEW.id;
    IF q > v_sold THEN
      RAISE EXCEPTION 'cannot return % units for line %: only % sold', q, NEW.order_line_id, v_sold
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  UPDATE events.inventory_buckets SET
    reserved = reserved + CASE NEW.kind WHEN 'RESERVE' THEN q WHEN 'RELEASE' THEN -q WHEN 'CONFIRM' THEN -q ELSE 0 END,
    sold     = sold     + CASE NEW.kind WHEN 'CONFIRM' THEN q WHEN 'SELL' THEN q WHEN 'HOLD_SELL' THEN q WHEN 'RETURN' THEN -q ELSE 0 END,
    held     = held     + CASE NEW.kind WHEN 'HOLD' THEN q WHEN 'UNHOLD' THEN -q WHEN 'HOLD_SELL' THEN -q ELSE 0 END,
    updated_at = now()
  WHERE id = NEW.bucket_id AND org_id = NEW.org_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'inventory bucket % not found', NEW.bucket_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER inventory_movements_apply AFTER INSERT ON events.inventory_movements
  FOR EACH ROW EXECUTE FUNCTION events.apply_inventory_movement();

-- -----------------------------------------------------------------------------
-- Tickets: one row per admission credential.
-- -----------------------------------------------------------------------------
CREATE TABLE events.tickets (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  order_id           uuid NOT NULL,
  order_line_id      uuid NOT NULL,
  unit_index         smallint NOT NULL CHECK (unit_index > 0),      -- 1..line quantity
  admission_index    smallint NOT NULL DEFAULT 1 CHECK (admission_index > 0), -- 1..admissions_per_unit
  event_id           uuid NOT NULL,
  occurrence_id      uuid,                       -- NULL for passes valid across occurrences
  ticket_type_id     uuid NOT NULL,
  occurrence_seat_id uuid,
  serial             text NOT NULL UNIQUE,       -- short human code for SMS/manual lookup, not a credential
  -- QR carries an opaque random secret; only its hash is stored. Rotated on transfer.
  credential_hash    text NOT NULL UNIQUE,
  credential_version smallint NOT NULL DEFAULT 1,
  holder_user_id     uuid REFERENCES iam.users(id),
  holder_name        text,
  holder_email       citext,
  holder_phone_e164  text,
  status             text NOT NULL DEFAULT 'VALID' CHECK (status IN ('VALID','VOID','CANCELLED','REFUNDED')),
  issued_at          timestamptz NOT NULL DEFAULT now(),
  voided_at          timestamptz,
  void_reason        text,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  -- A paid line can never be issued twice.
  UNIQUE (order_line_id, unit_index, admission_index),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id),
  FOREIGN KEY (org_id, occurrence_seat_id) REFERENCES events.occurrence_seats (org_id, id),
  CHECK ((status = 'VALID') = (voided_at IS NULL))
);
CREATE UNIQUE INDEX tickets_seat_uniq ON events.tickets (occurrence_seat_id) WHERE occurrence_seat_id IS NOT NULL AND status = 'VALID';
CREATE INDEX tickets_holder_idx ON events.tickets (holder_user_id) WHERE holder_user_id IS NOT NULL;
CREATE INDEX tickets_occurrence_idx ON events.tickets (occurrence_id, status);

ALTER TABLE events.occurrence_seats
  ADD CONSTRAINT occurrence_seats_order_fk  FOREIGN KEY (org_id, order_id)  REFERENCES commerce.orders (org_id, id),
  ADD CONSTRAINT occurrence_seats_ticket_fk FOREIGN KEY (org_id, ticket_id) REFERENCES events.tickets (org_id, id);
ALTER TABLE crm.form_submissions
  ADD CONSTRAINT form_submissions_ticket_fk FOREIGN KEY (org_id, ticket_id) REFERENCES events.tickets (org_id, id);

CREATE TABLE events.ticket_transfers (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL,
  ticket_id         uuid NOT NULL,
  from_user_id      uuid REFERENCES iam.users(id),
  to_phone_e164     text,
  to_email          citext,
  to_user_id        uuid REFERENCES iam.users(id),
  initiated_by      text NOT NULL CHECK (initiated_by IN ('HOLDER','ORGANIZATION')),
  status            text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DECLINED','CANCELLED','EXPIRED')),
  expires_at        timestamptz NOT NULL,
  completed_at      timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, ticket_id) REFERENCES events.tickets (org_id, id),
  CHECK (num_nonnulls(to_phone_e164, to_email, to_user_id) >= 1)
);
CREATE UNIQUE INDEX ticket_transfers_one_pending ON events.ticket_transfers (ticket_id) WHERE status = 'PENDING';

-- -----------------------------------------------------------------------------
-- Check-in
-- -----------------------------------------------------------------------------
CREATE TABLE events.checkin_devices (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  name               text NOT NULL,
  installation_id    text NOT NULL,
  event_id           uuid,                       -- scope; NULL = any event of the org
  occurrence_id      uuid,
  registered_by      uuid NOT NULL REFERENCES iam.users(id),
  -- Offline mode: device downloads a signed manifest of credential hashes for its scope.
  offline_enabled    boolean NOT NULL DEFAULT true,
  manifest_version   integer NOT NULL DEFAULT 0,
  last_sync_at       timestamptz,
  status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
  revoked_at         timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, installation_id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id)
);

-- Every scan attempt, online or synced from offline. Not the admission authority.
CREATE TABLE events.scans (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  device_id      uuid NOT NULL,
  client_scan_id uuid NOT NULL,                  -- generated on device; makes offline sync idempotent
  occurrence_id  uuid NOT NULL,
  session_id     uuid,
  ticket_id      uuid,
  scanned_by     uuid REFERENCES iam.users(id),
  direction      text NOT NULL DEFAULT 'IN' CHECK (direction IN ('IN','OUT')),
  gate           text,
  scanned_at     timestamptz NOT NULL,           -- device clock
  received_at    timestamptz NOT NULL DEFAULT now(),
  was_offline    boolean NOT NULL DEFAULT false,
  result         text NOT NULL CHECK (result IN ('ADMITTED','READMITTED','EXITED','ALREADY_INSIDE','NOT_FOUND','WRONG_OCCURRENCE','VOID','NOT_YET_OPEN','DEVICE_REVOKED','CONFLICT')),
  UNIQUE (device_id, client_scan_id),
  FOREIGN KEY (org_id, device_id) REFERENCES events.checkin_devices (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, session_id) REFERENCES events.sessions (org_id, id),
  FOREIGN KEY (org_id, ticket_id) REFERENCES events.tickets (org_id, id)
);
CREATE INDEX scans_ticket_idx ON events.scans (ticket_id, scanned_at);
CREATE TRIGGER scans_immutable BEFORE UPDATE OR DELETE ON events.scans
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

-- Authoritative admission state: one row per ticket per occurrence (and session).
-- The unique key makes a second concurrent "first admission" impossible.
CREATE TABLE events.admissions (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL,
  ticket_id         uuid NOT NULL,
  occurrence_id     uuid NOT NULL,
  session_id        uuid,
  state             text NOT NULL DEFAULT 'INSIDE' CHECK (state IN ('INSIDE','OUTSIDE')),
  first_admitted_at timestamptz NOT NULL,
  last_scan_id      uuid NOT NULL REFERENCES events.scans(id),
  entry_count       smallint NOT NULL DEFAULT 1 CHECK (entry_count > 0),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (ticket_id, occurrence_id, session_id),
  FOREIGN KEY (org_id, ticket_id) REFERENCES events.tickets (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, session_id) REFERENCES events.sessions (org_id, id)
);
