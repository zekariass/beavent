-- =============================================================================
-- 09: scheduling — appointments and the staff/resource reservation ledger.
-- schedule_reservations is the single authority on staff and resource time.
-- Appointment stages, temporary checkout holds, calendar blocks AND staff or
-- rooms assigned to event/class occurrences all write rows here, so a GiST
-- exclusion constraint makes double-booking impossible across both verticals.
-- =============================================================================

CREATE TABLE scheduling.booking_policies (
  id                       uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                   uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id              uuid,                 -- NULL = organization default
  service_id               uuid,                 -- most specific policy wins
  min_notice_minutes       integer NOT NULL DEFAULT 60 CHECK (min_notice_minutes >= 0),
  max_advance_days         integer NOT NULL DEFAULT 90 CHECK (max_advance_days > 0),
  slot_interval_minutes    smallint NOT NULL DEFAULT 15 CHECK (slot_interval_minutes IN (5,10,15,20,30,60)),
  requires_approval        boolean NOT NULL DEFAULT false, -- request-to-book
  allow_pay_at_venue       boolean NOT NULL DEFAULT true,
  deposit_mode             text NOT NULL DEFAULT 'NONE' CHECK (deposit_mode IN ('NONE','PERCENT','FIXED','FULL')),
  deposit_percent          numeric(5,2) CHECK (deposit_percent IS NULL OR (deposit_percent > 0 AND deposit_percent <= 100)),
  deposit_fixed_minor      bigint CHECK (deposit_fixed_minor IS NULL OR deposit_fixed_minor > 0),
  deposit_applies_to       text NOT NULL DEFAULT 'EVERYONE' CHECK (deposit_applies_to IN ('EVERYONE','NEW_CUSTOMERS','MARKETPLACE_CUSTOMERS','FLAGGED_CUSTOMERS')),
  cancellation_window_minutes integer NOT NULL DEFAULT 1440 CHECK (cancellation_window_minutes >= 0),
  late_cancel_fee_percent  numeric(5,2) NOT NULL DEFAULT 0 CHECK (late_cancel_fee_percent BETWEEN 0 AND 100),
  no_show_fee_percent      numeric(5,2) NOT NULL DEFAULT 0 CHECK (no_show_fee_percent BETWEEN 0 AND 100),
  currency_code            char(3) REFERENCES ref.currencies(code),
  policy_text_i18n         jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  UNIQUE NULLS NOT DISTINCT (org_id, location_id, service_id),
  CHECK ((deposit_mode = 'PERCENT') = (deposit_percent IS NOT NULL)),
  CHECK (deposit_mode <> 'FIXED' OR (deposit_fixed_minor IS NOT NULL AND currency_code IS NOT NULL))
);

CREATE TABLE scheduling.appointment_groups (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL REFERENCES tenant.organizations(id),
  lead_customer_id  uuid NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, lead_customer_id) REFERENCES crm.customers (org_id, id)
);

CREATE TABLE scheduling.appointment_series (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  customer_id uuid NOT NULL,
  rrule       text NOT NULL,                     -- e.g. FREQ=WEEKLY;INTERVAL=2;COUNT=6
  timezone    text NOT NULL,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED','CANCELLED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id)
);

CREATE TABLE scheduling.appointments (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id      uuid NOT NULL,
  customer_id      uuid NOT NULL,
  booked_by_user_id uuid REFERENCES iam.users(id),
  order_id         uuid,
  group_id         uuid,
  series_id        uuid,
  reference        text NOT NULL,
  source           text NOT NULL CHECK (source IN ('MARKETPLACE','DIRECT_LINK','WIDGET','SOCIAL','GOOGLE','PARTNER_APP','PARTNER_PORTAL','WALK_IN','PHONE','AI_CONCIERGE','WAITLIST','PUBLIC_API')),
  status           text NOT NULL CHECK (status IN ('PENDING_PAYMENT','REQUESTED','CONFIRMED','ARRIVED','IN_PROGRESS','COMPLETED','CANCELLED','NO_SHOW','EXPIRED','DECLINED')),
  delivery_mode    text NOT NULL DEFAULT 'ON_PREMISES' CHECK (delivery_mode IN ('ON_PREMISES','AT_CUSTOMER','ONLINE')),
  service_address  jsonb,                        -- snapshot of the customer's address for AT_CUSTOMER
  service_geo      geography(Point, 4326),
  starts_at        timestamptz NOT NULL,
  ends_at          timestamptz NOT NULL,
  timezone         text NOT NULL,
  -- Cancellation/deposit/no-show terms the customer agreed to. Never re-derived from current policy.
  policy_snapshot  jsonb NOT NULL,
  customer_note    text,
  internal_note    text,
  cancelled_at     timestamptz,
  cancelled_by     text CHECK (cancelled_by IN ('CUSTOMER','ORGANIZATION','SYSTEM')),
  cancel_reason    text,
  version          integer NOT NULL DEFAULT 0,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, reference),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, group_id) REFERENCES scheduling.appointment_groups (org_id, id),
  FOREIGN KEY (org_id, series_id) REFERENCES scheduling.appointment_series (org_id, id),
  CHECK (ends_at > starts_at),
  CHECK (delivery_mode <> 'AT_CUSTOMER' OR service_address IS NOT NULL),
  CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);
CREATE INDEX appointments_location_time_idx ON scheduling.appointments (location_id, starts_at);
CREATE INDEX appointments_customer_idx ON scheduling.appointments (org_id, customer_id, starts_at DESC);

CREATE TABLE scheduling.appointment_items (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  appointment_id     uuid NOT NULL,
  seq                smallint NOT NULL CHECK (seq > 0),
  guest_name         text,                       -- group bookings / booking for someone else
  service_variant_id uuid NOT NULL,
  order_line_id      uuid,
  staff_id           uuid,                       -- NULL only while REQUESTED and unassigned
  staff_requested    boolean NOT NULL DEFAULT false, -- customer chose this person (affects commission rules)
  service_name       text NOT NULL,              -- snapshot
  variant_name       text,
  starts_at          timestamptz NOT NULL,
  ends_at            timestamptz NOT NULL,
  quoted_price_minor bigint NOT NULL CHECK (quoted_price_minor >= 0),
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  status             text NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED','STARTED','DONE','CANCELLED','NO_SHOW')),
  UNIQUE (org_id, id),
  UNIQUE (appointment_id, seq),
  FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id),
  FOREIGN KEY (org_id, service_variant_id) REFERENCES catalog.service_variants (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  CHECK (ends_at > starts_at)
);

CREATE TABLE scheduling.appointment_item_addons (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  appointment_item_id uuid NOT NULL,
  addon_id            uuid NOT NULL,
  name                text NOT NULL,
  duration_minutes    smallint NOT NULL DEFAULT 0,
  price_minor         bigint NOT NULL CHECK (price_minor >= 0),
  FOREIGN KEY (org_id, appointment_item_id) REFERENCES scheduling.appointment_items (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, addon_id) REFERENCES catalog.addons (org_id, id)
);

-- Concrete stages of a booked item (copied from the variant's stage template).
CREATE TABLE scheduling.appointment_item_stages (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  appointment_item_id uuid NOT NULL,
  seq                 smallint NOT NULL CHECK (seq > 0),
  kind                text NOT NULL CHECK (kind IN ('SERVICE','PROCESSING','FINISHING','CONSULTATION','TRAVEL')),
  staff_busy          boolean NOT NULL,
  during              tstzrange NOT NULL,
  UNIQUE (org_id, id),
  UNIQUE (appointment_item_id, seq),
  FOREIGN KEY (org_id, appointment_item_id) REFERENCES scheduling.appointment_items (org_id, id) ON DELETE CASCADE,
  CHECK (NOT isempty(during))
);

CREATE TABLE scheduling.appointment_item_resources (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  appointment_item_id uuid NOT NULL,
  resource_id         uuid NOT NULL,
  resource_unit       smallint NOT NULL DEFAULT 1 CHECK (resource_unit > 0),
  during              tstzrange NOT NULL,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, appointment_item_id) REFERENCES scheduling.appointment_items (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, resource_id) REFERENCES catalog.resources (org_id, id),
  CHECK (NOT isempty(during))
);

CREATE TABLE scheduling.appointment_status_history (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  appointment_id uuid NOT NULL,
  from_status    text,
  to_status      text NOT NULL,
  actor_user_id  uuid REFERENCES iam.users(id),
  reason         text,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id)
);
CREATE TRIGGER appointment_status_history_immutable BEFORE UPDATE OR DELETE ON scheduling.appointment_status_history
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

-- Short-lived hold while a customer completes checkout (and pays a deposit by mobile money, which can take minutes).
CREATE TABLE scheduling.holds (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  order_id    uuid,
  holder_key  text NOT NULL,                     -- user id or anonymous checkout key
  expires_at  timestamptz NOT NULL,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CONVERTED','RELEASED','EXPIRED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id)
);

CREATE TABLE scheduling.calendar_blocks (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id uuid NOT NULL,
  staff_id    uuid,
  resource_id uuid,
  during      tstzrange NOT NULL,
  reason      text NOT NULL CHECK (reason IN ('BREAK','MEETING','TRAINING','TIME_OFF','PERSONAL','MAINTENANCE','OTHER')),
  title       text,
  time_off_id uuid REFERENCES team.time_off(id),
  created_by  uuid REFERENCES iam.users(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, resource_id) REFERENCES catalog.resources (org_id, id),
  CHECK (num_nonnulls(staff_id, resource_id) = 1),
  CHECK (NOT isempty(during))
);

-- Instructor / host / room assigned to an event or class occurrence.
CREATE TABLE scheduling.occurrence_assignments (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  occurrence_id uuid NOT NULL,
  staff_id      uuid,
  resource_id   uuid,
  resource_unit smallint,
  role          text NOT NULL DEFAULT 'INSTRUCTOR' CHECK (role IN ('INSTRUCTOR','HOST','PERFORMER','ASSISTANT','ROOM')),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, resource_id) REFERENCES catalog.resources (org_id, id),
  CHECK (num_nonnulls(staff_id, resource_id) = 1),
  CHECK ((resource_id IS NULL) = (resource_unit IS NULL))
);

-- -----------------------------------------------------------------------------
-- The reservation ledger
-- -----------------------------------------------------------------------------
CREATE TABLE scheduling.schedule_reservations (
  id                      uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                  uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id             uuid NOT NULL,
  kind                    text NOT NULL CHECK (kind IN ('STAFF','RESOURCE')),
  staff_id                uuid,
  resource_id             uuid,
  resource_unit           smallint,
  during                  tstzrange NOT NULL,
  status                  text NOT NULL CHECK (status IN ('HELD','CONFIRMED','BLOCKED','RELEASED')),
  -- Staff-forced overlap (front desk deliberately double-books). Excluded from
  -- the DB guard; online booking never sets it.
  allow_overlap           boolean NOT NULL DEFAULT false,
  -- Exactly one source:
  appointment_stage_id    uuid,
  appointment_resource_id uuid,
  hold_id                 uuid,
  calendar_block_id       uuid,
  occurrence_assignment_id uuid,
  hold_expires_at         timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, resource_id) REFERENCES catalog.resources (org_id, id),
  FOREIGN KEY (org_id, appointment_stage_id) REFERENCES scheduling.appointment_item_stages (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, appointment_resource_id) REFERENCES scheduling.appointment_item_resources (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, hold_id) REFERENCES scheduling.holds (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, calendar_block_id) REFERENCES scheduling.calendar_blocks (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, occurrence_assignment_id) REFERENCES scheduling.occurrence_assignments (org_id, id) ON DELETE CASCADE,
  CHECK (NOT isempty(during)),
  CHECK (lower_inc(during) AND NOT upper_inc(during)),
  CHECK (num_nonnulls(appointment_stage_id, appointment_resource_id, hold_id, calendar_block_id, occurrence_assignment_id) = 1),
  CHECK ((kind = 'STAFF' AND staff_id IS NOT NULL AND resource_id IS NULL AND resource_unit IS NULL)
      OR (kind = 'RESOURCE' AND resource_id IS NOT NULL AND staff_id IS NULL AND resource_unit > 0)),
  CHECK (status <> 'HELD' OR (hold_id IS NOT NULL AND hold_expires_at IS NOT NULL)),
  CHECK (hold_id IS NULL OR status IN ('HELD','RELEASED')),
  CHECK (status <> 'BLOCKED' OR calendar_block_id IS NOT NULL),
  CHECK (NOT allow_overlap OR hold_id IS NULL),
  EXCLUDE USING gist (staff_id WITH =, during WITH &&)
    WHERE (kind = 'STAFF' AND status IN ('HELD','CONFIRMED','BLOCKED') AND NOT allow_overlap),
  EXCLUDE USING gist (resource_id WITH =, resource_unit WITH =, during WITH &&)
    WHERE (kind = 'RESOURCE' AND status IN ('HELD','CONFIRMED','BLOCKED') AND NOT allow_overlap)
);
CREATE INDEX schedule_reservations_hold_expiry_idx ON scheduling.schedule_reservations (hold_expires_at) WHERE status = 'HELD';
CREATE INDEX schedule_reservations_location_time_idx ON scheduling.schedule_reservations USING gist (location_id, during) WHERE status <> 'RELEASED';

-- Releases expired holds; called by the scheduler every minute (idempotent).
CREATE FUNCTION scheduling.expire_holds(p_now timestamptz DEFAULT now()) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE v_count integer;
BEGIN
  UPDATE scheduling.schedule_reservations
     SET status = 'RELEASED'
   WHERE status = 'HELD' AND hold_expires_at <= p_now;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  UPDATE scheduling.holds SET status = 'EXPIRED'
   WHERE status = 'ACTIVE' AND expires_at <= p_now;
  RETURN v_count;
END $$;

-- Late FKs from CRM into scheduling.
ALTER TABLE crm.customer_files
  ADD CONSTRAINT customer_files_appointment_fk FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id);
ALTER TABLE crm.treatment_records
  ADD CONSTRAINT treatment_records_item_fk FOREIGN KEY (org_id, appointment_item_id) REFERENCES scheduling.appointment_items (org_id, id);
ALTER TABLE crm.form_submissions
  ADD CONSTRAINT form_submissions_order_fk FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  ADD CONSTRAINT form_submissions_appointment_fk FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id);
