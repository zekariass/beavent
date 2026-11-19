-- =============================================================================
-- 07: events — definitions, schedule, ticket types, capacity, seating.
-- Covers concerts, conferences, workshops, festivals, timed-entry attractions,
-- multi-day passes and recurring classes (a yoga class series at a spa is an
-- event of kind CLASS_SERIES whose instructor is reserved in the same staff
-- ledger as appointments — see scheduling.occurrence_assignments).
-- Ticket issuance, check-in and inventory movements: V1_10 (after commerce).
-- =============================================================================

CREATE TABLE events.events (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL REFERENCES tenant.organizations(id),
  legal_entity_id     uuid NOT NULL,             -- seller of record: currency, tax, payouts
  slug                citext NOT NULL,
  category_id         uuid NOT NULL REFERENCES ref.categories(id),
  kind                text NOT NULL DEFAULT 'SINGLE' CHECK (kind IN ('SINGLE','RECURRING','TIMED_ENTRY','MULTI_DAY','CLASS_SERIES')),
  format              text NOT NULL DEFAULT 'IN_PERSON' CHECK (format IN ('IN_PERSON','ONLINE','HYBRID')),
  visibility          text NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PUBLIC','UNLISTED','PRIVATE')),
  access_password_hash text,                     -- PRIVATE events may require a password
  status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_REVIEW','SCHEDULED','PUBLISHED','SALES_PAUSED','POSTPONED','CANCELLED','COMPLETED','ARCHIVED')),
  publish_at          timestamptz,
  published_at        timestamptz,
  source_language     text NOT NULL REFERENCES ref.languages(code),
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  location_id         uuid,                      -- when held at the organization's own premises
  cover_file_id       uuid REFERENCES platform.files(id),
  min_age             smallint CHECK (min_age IS NULL OR min_age BETWEEN 0 AND 25),
  refund_policy       text NOT NULL DEFAULT 'NO_REFUNDS' CHECK (refund_policy IN ('NO_REFUNDS','UNTIL_1_DAY','UNTIL_7_DAYS','UNTIL_30_DAYS','CUSTOM')),
  refund_policy_text  text,
  -- Structured page content: agenda, speakers, sponsors, FAQs, highlights, links.
  -- Presentation-only, validated against a JSON schema by the application.
  content             jsonb NOT NULL DEFAULT '{}'::jsonb,
  max_tickets_per_order smallint NOT NULL DEFAULT 20 CHECK (max_tickets_per_order BETWEEN 1 AND 500),
  waitlist_enabled    boolean NOT NULL DEFAULT false,
  transfers_enabled   boolean NOT NULL DEFAULT true,
  -- The organizer receives buyer contact details for marketing only with consent.
  ask_marketing_consent boolean NOT NULL DEFAULT true,
  created_by          uuid NOT NULL REFERENCES iam.users(id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, slug),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (refund_policy <> 'CUSTOM' OR refund_policy_text IS NOT NULL),
  CHECK (status NOT IN ('PUBLISHED','SALES_PAUSED','COMPLETED') OR published_at IS NOT NULL)
);
CREATE INDEX events_status_idx ON events.events (status, visibility);

CREATE TABLE events.event_translations (
  org_id        uuid NOT NULL,
  event_id      uuid NOT NULL,
  language_code text NOT NULL REFERENCES ref.languages(code),
  title         text NOT NULL,
  summary       text,
  description   jsonb NOT NULL DEFAULT '{}'::jsonb,
  is_machine_translated boolean NOT NULL DEFAULT false,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, language_code),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id) ON DELETE CASCADE
);

CREATE TABLE events.event_tags (
  org_id   uuid NOT NULL,
  event_id uuid NOT NULL,
  tag      citext NOT NULL,
  PRIMARY KEY (event_id, tag),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id) ON DELETE CASCADE
);

-- Co-hosts and partners displayed on the event page. Revenue stays with the seller of record.
CREATE TABLE events.event_cohosts (
  org_id        uuid NOT NULL,
  event_id      uuid NOT NULL,
  cohost_org_id uuid NOT NULL REFERENCES tenant.organizations(id),
  role          text NOT NULL CHECK (role IN ('COHOST','PARTNER','SPONSOR','PROMOTER')),
  status        text NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED','ACCEPTED','DECLINED','REMOVED')),
  PRIMARY KEY (event_id, cohost_org_id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id) ON DELETE CASCADE,
  CHECK (cohost_org_id <> org_id)
);

CREATE TABLE events.recurrence_rules (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  event_id    uuid NOT NULL,
  rrule       text NOT NULL,                     -- RFC 5545 RRULE, evaluated in `timezone`
  timezone    text NOT NULL,
  starts_on   date NOT NULL,
  until_date  date,
  start_time  time NOT NULL,
  duration_minutes integer NOT NULL CHECK (duration_minutes > 0),
  exceptions  date[] NOT NULL DEFAULT '{}',
  generated_through date,                        -- occurrences materialised up to this date
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id) ON DELETE CASCADE
);

-- A dated instance. Timed-entry slots are occurrences too (slot_label = '10:00–10:30').
CREATE TABLE events.occurrences (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  event_id           uuid NOT NULL,
  recurrence_rule_id uuid,
  starts_at          timestamptz NOT NULL,
  ends_at            timestamptz NOT NULL,
  timezone           text NOT NULL,
  doors_open_at      timestamptz,
  place_id           uuid REFERENCES tenant.places(id),
  slot_label         text,
  status             text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','ON_SALE','SALES_ENDED','SOLD_OUT','POSTPONED','CANCELLED','COMPLETED')),
  sales_start_at     timestamptz,
  sales_end_at       timestamptz,
  seat_map_version_id uuid,                      -- FK below once seat maps exist
  allow_reentry      boolean NOT NULL DEFAULT false,
  postponed_from     tstzrange,                  -- original window when POSTPONED/rescheduled
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, recurrence_rule_id) REFERENCES events.recurrence_rules (org_id, id),
  CHECK (ends_at > starts_at),
  CHECK (doors_open_at IS NULL OR doors_open_at <= starts_at),
  CHECK (sales_end_at IS NULL OR sales_start_at IS NULL OR sales_end_at > sales_start_at)
);
CREATE INDEX occurrences_event_start_idx ON events.occurrences (event_id, starts_at);
CREATE INDEX occurrences_start_idx ON events.occurrences (starts_at) WHERE status IN ('SCHEDULED','ON_SALE');

-- Online join details are only revealed to valid ticket holders, never on public pages.
CREATE TABLE events.online_access (
  org_id          uuid NOT NULL,
  occurrence_id   uuid PRIMARY KEY,
  provider        text NOT NULL CHECK (provider IN ('ZOOM','GOOGLE_MEET','YOUTUBE_LIVE','FACEBOOK_LIVE','TELEGRAM','CUSTOM')),
  join_url_secret_ref text NOT NULL,
  instructions    text,
  reveal_at       timestamptz,
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id) ON DELETE CASCADE
);

-- Conference tracks / workshops inside an occurrence; may have their own capacity and check-in.
CREATE TABLE events.sessions (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  occurrence_id uuid NOT NULL,
  title_i18n    jsonb NOT NULL,
  starts_at     timestamptz NOT NULL,
  ends_at       timestamptz NOT NULL,
  room          text,
  requires_registration boolean NOT NULL DEFAULT false,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id) ON DELETE CASCADE,
  CHECK (ends_at > starts_at)
);

-- -----------------------------------------------------------------------------
-- Ticket types
-- -----------------------------------------------------------------------------
CREATE TABLE events.ticket_types (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL,
  event_id             uuid NOT NULL,
  name_i18n            jsonb NOT NULL,
  description_i18n     jsonb NOT NULL DEFAULT '{}'::jsonb,
  pricing              text NOT NULL DEFAULT 'PAID' CHECK (pricing IN ('PAID','FREE','DONATION','PAY_WHAT_YOU_WANT')),
  admission            text NOT NULL DEFAULT 'GENERAL' CHECK (admission IN ('GENERAL','RESERVED_SEAT')),
  visibility           text NOT NULL DEFAULT 'VISIBLE' CHECK (visibility IN ('VISIBLE','HIDDEN','HIDDEN_WHEN_UNAVAILABLE','ACCESS_CODE_ONLY','BOX_OFFICE_ONLY','INVITE_ONLY')),
  -- Which occurrences one ticket admits to. ALL/SELECTED = series or multi-day pass.
  validity             text NOT NULL DEFAULT 'SINGLE_OCCURRENCE' CHECK (validity IN ('SINGLE_OCCURRENCE','ALL_OCCURRENCES','SELECTED_OCCURRENCES')),
  -- A "table for 8" sells one unit and issues 8 admissions.
  admissions_per_unit  smallint NOT NULL DEFAULT 1 CHECK (admissions_per_unit BETWEEN 1 AND 100),
  currency_code        char(3) NOT NULL REFERENCES ref.currencies(code),
  price_minor          bigint NOT NULL DEFAULT 0 CHECK (price_minor >= 0),
  min_price_minor      bigint CHECK (min_price_minor IS NULL OR min_price_minor >= 0),
  fee_handling         text NOT NULL DEFAULT 'PASS_TO_BUYER' CHECK (fee_handling IN ('PASS_TO_BUYER','ABSORB')),
  tax_category_code    text NOT NULL DEFAULT 'EVENT_ADMISSION' REFERENCES ref.tax_categories(code),
  min_per_order        smallint NOT NULL DEFAULT 1 CHECK (min_per_order >= 1),
  max_per_order        smallint NOT NULL DEFAULT 10,
  sales_start_at       timestamptz,
  sales_end_at         timestamptz,
  -- Sequential release: goes on sale when the referenced type sells out or ends.
  release_after_ticket_type_id uuid,
  is_transferable      boolean NOT NULL DEFAULT true,
  requires_attendee_details boolean NOT NULL DEFAULT false,
  status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','ARCHIVED')),
  sort_order           smallint NOT NULL DEFAULT 0,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, release_after_ticket_type_id) REFERENCES events.ticket_types (org_id, id),
  CHECK (max_per_order >= min_per_order),
  CHECK (pricing <> 'FREE' OR price_minor = 0),
  CHECK (pricing NOT IN ('DONATION','PAY_WHAT_YOU_WANT') OR min_price_minor IS NOT NULL),
  CHECK (sales_end_at IS NULL OR sales_start_at IS NULL OR sales_end_at > sales_start_at)
);

-- For SELECTED_OCCURRENCES passes, and to restrict a type to some occurrences of a recurring event.
CREATE TABLE events.ticket_type_occurrences (
  org_id         uuid NOT NULL,
  ticket_type_id uuid NOT NULL,
  occurrence_id  uuid NOT NULL,
  PRIMARY KEY (ticket_type_id, occurrence_id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id) ON DELETE CASCADE
);

-- Early-bird / tiered pricing that changes automatically by time or quantity sold.
CREATE TABLE events.price_phases (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  ticket_type_id uuid NOT NULL,
  seq            smallint NOT NULL CHECK (seq > 0),
  name_i18n      jsonb NOT NULL,
  price_minor    bigint NOT NULL CHECK (price_minor >= 0),
  starts_at      timestamptz,
  ends_at        timestamptz,
  quantity_limit integer CHECK (quantity_limit IS NULL OR quantity_limit > 0),
  UNIQUE (ticket_type_id, seq),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id) ON DELETE CASCADE,
  CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

-- -----------------------------------------------------------------------------
-- Inventory buckets: every capacity constraint is a bucket with counters.
--   OCCURRENCE  : total capacity of an occurrence (venue/fire limit)
--   TICKET_TYPE : quantity of one ticket type in one occurrence
--   POOL        : custom shared pool (e.g. "all VIP types share 200")
--   SESSION     : capacity of a session inside an occurrence
-- A purchase draws from every bucket that applies; counters move only via
-- events.inventory_movements (trigger in V1_10), and the CHECK below is the
-- final guard against overselling.
-- -----------------------------------------------------------------------------
CREATE TABLE events.inventory_buckets (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  event_id       uuid NOT NULL,
  occurrence_id  uuid NOT NULL,
  scope          text NOT NULL CHECK (scope IN ('OCCURRENCE','TICKET_TYPE','POOL','SESSION')),
  ticket_type_id uuid,
  session_id     uuid,
  pool_name      text,
  capacity       integer NOT NULL CHECK (capacity >= 0),
  sold           integer NOT NULL DEFAULT 0 CHECK (sold >= 0),
  reserved       integer NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  held           integer NOT NULL DEFAULT 0 CHECK (held >= 0),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id),
  FOREIGN KEY (org_id, session_id) REFERENCES events.sessions (org_id, id),
  CHECK (sold + reserved + held <= capacity),
  CHECK ((scope = 'TICKET_TYPE') = (ticket_type_id IS NOT NULL)),
  CHECK ((scope = 'SESSION') = (session_id IS NOT NULL)),
  CHECK ((scope = 'POOL') = (pool_name IS NOT NULL))
);
CREATE UNIQUE INDEX inventory_buckets_occurrence_uniq ON events.inventory_buckets (occurrence_id) WHERE scope = 'OCCURRENCE';
CREATE UNIQUE INDEX inventory_buckets_type_uniq ON events.inventory_buckets (occurrence_id, ticket_type_id) WHERE scope = 'TICKET_TYPE';
CREATE UNIQUE INDEX inventory_buckets_pool_uniq ON events.inventory_buckets (occurrence_id, pool_name) WHERE scope = 'POOL';
CREATE UNIQUE INDEX inventory_buckets_session_uniq ON events.inventory_buckets (session_id) WHERE scope = 'SESSION';

CREATE TABLE events.pool_ticket_types (
  org_id         uuid NOT NULL,
  bucket_id      uuid NOT NULL,
  ticket_type_id uuid NOT NULL,
  PRIMARY KEY (bucket_id, ticket_type_id),
  FOREIGN KEY (org_id, bucket_id) REFERENCES events.inventory_buckets (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id) ON DELETE CASCADE
);

-- Organizer holds (sponsors, artist guests, press): quantity taken out of sale.
CREATE TABLE events.inventory_holds (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  occurrence_id  uuid NOT NULL,
  ticket_type_id uuid,
  name           text NOT NULL,
  quantity       integer NOT NULL CHECK (quantity >= 0),
  status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
  created_by     uuid NOT NULL REFERENCES iam.users(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id)
);

-- -----------------------------------------------------------------------------
-- Reserved seating. Layouts are versioned per place; each occurrence gets its
-- own seat rows so "sold" is a per-occurrence fact with a uniqueness guarantee.
-- -----------------------------------------------------------------------------
CREATE TABLE events.seat_maps (
  id         uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id     uuid NOT NULL REFERENCES tenant.organizations(id),
  place_id   uuid NOT NULL REFERENCES tenant.places(id),
  name       text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE events.seat_map_versions (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL,
  seat_map_id  uuid NOT NULL,
  version      integer NOT NULL CHECK (version > 0),
  layout       jsonb NOT NULL,                   -- rendering geometry only; seats are rows below
  published_at timestamptz,
  UNIQUE (org_id, id),
  UNIQUE (seat_map_id, version),
  FOREIGN KEY (org_id, seat_map_id) REFERENCES events.seat_maps (org_id, id)
);

CREATE TABLE events.seats (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  seat_map_version_id uuid NOT NULL,
  section             text NOT NULL,
  row_label           text NOT NULL DEFAULT '',
  seat_label          text NOT NULL,
  table_label         text,
  kind                text NOT NULL DEFAULT 'STANDARD' CHECK (kind IN ('STANDARD','ACCESSIBLE','COMPANION','RESTRICTED_VIEW','STANDING_SPOT')),
  x                   numeric(9,2),
  y                   numeric(9,2),
  UNIQUE (org_id, id),
  UNIQUE (seat_map_version_id, section, row_label, seat_label),
  FOREIGN KEY (org_id, seat_map_version_id) REFERENCES events.seat_map_versions (org_id, id) ON DELETE CASCADE
);

ALTER TABLE events.occurrences
  ADD CONSTRAINT occurrences_seat_map_version_fk
  FOREIGN KEY (org_id, seat_map_version_id) REFERENCES events.seat_map_versions (org_id, id);

-- order_id / ticket_id FKs are added in V1_10.
CREATE TABLE events.occurrence_seats (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  occurrence_id  uuid NOT NULL,
  seat_id        uuid NOT NULL,
  ticket_type_id uuid NOT NULL,                  -- price zone for this seat in this occurrence
  status         text NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','HELD','RESERVED','SOLD','BLOCKED')),
  hold_id        uuid,
  order_id       uuid,
  ticket_id      uuid,
  reserved_until timestamptz,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (occurrence_id, seat_id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, seat_id) REFERENCES events.seats (org_id, id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id),
  FOREIGN KEY (org_id, hold_id) REFERENCES events.inventory_holds (org_id, id),
  CHECK ((status = 'HELD') = (hold_id IS NOT NULL)),
  CHECK (status <> 'RESERVED' OR (order_id IS NOT NULL AND reserved_until IS NOT NULL AND ticket_id IS NULL)),
  CHECK (status <> 'SOLD' OR (order_id IS NOT NULL AND ticket_id IS NOT NULL)),
  CHECK (status NOT IN ('AVAILABLE','BLOCKED','HELD') OR (order_id IS NULL AND ticket_id IS NULL))
);
CREATE INDEX occurrence_seats_available_idx ON events.occurrence_seats (occurrence_id, ticket_type_id) WHERE status = 'AVAILABLE';

-- -----------------------------------------------------------------------------
-- Event-scoped staff (door scanners, box office) without org-wide access.
-- -----------------------------------------------------------------------------
CREATE TABLE events.staff_assignments (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  event_id      uuid NOT NULL,
  occurrence_id uuid,                            -- NULL = all occurrences of the event
  member_id     uuid NOT NULL,
  role          text NOT NULL CHECK (role IN ('EVENT_MANAGER','CHECK_IN','BOX_OFFICE','SUPPORT','HOST')),
  valid_during  tstzrange,
  created_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, member_id) REFERENCES tenant.members (org_id, id) ON DELETE CASCADE,
  UNIQUE NULLS NOT DISTINCT (event_id, occurrence_id, member_id, role)
);

-- -----------------------------------------------------------------------------
-- Galleries for any public-facing entity (typed FKs, exactly one subject).
-- -----------------------------------------------------------------------------
CREATE TABLE catalog.gallery_items (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  file_id     uuid NOT NULL,
  location_id uuid,
  service_id  uuid,
  product_id  uuid,
  staff_id    uuid,
  event_id    uuid,
  alt_text_i18n jsonb NOT NULL DEFAULT '{}'::jsonb,
  sort_order  smallint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, file_id) REFERENCES platform.files (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, product_id) REFERENCES catalog.products (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id) ON DELETE CASCADE,
  CHECK (num_nonnulls(location_id, service_id, product_id, staff_id, event_id) <= 1)
);

ALTER TABLE crm.form_assignments
  ADD CONSTRAINT form_assignments_event_fk FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id) ON DELETE CASCADE,
  ADD CONSTRAINT form_assignments_ticket_type_fk FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id) ON DELETE CASCADE;
