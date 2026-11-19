-- =============================================================================
-- 06: CRM — the organization's view of a person, whether they booked a facial,
-- bought a concert ticket, or walked in. One record per (org, person), linked
-- to a platform user when known. Guests and walk-ins have no user_id.
-- =============================================================================

CREATE TABLE crm.customers (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL REFERENCES tenant.organizations(id),
  user_id              uuid REFERENCES iam.users(id),
  first_name           text,
  last_name            text,
  display_name         text NOT NULL,
  phone_e164           text,
  email                citext,
  date_of_birth        date,
  gender               text CHECK (gender IN ('FEMALE','MALE','OTHER','UNDISCLOSED')),
  preferred_language   text REFERENCES ref.languages(code),
  source               text NOT NULL CHECK (source IN ('MARKETPLACE','DIRECT_LINK','WIDGET','WALK_IN','PHONE','IMPORT','EVENT_ORDER','REFERRAL','SOCIAL','AI_CONCIERGE','BOX_OFFICE')),
  -- Set once, when the customer was first acquired through the marketplace.
  -- Drives the marketplace new-client commission and attribution reports.
  marketplace_acquired_at timestamptz,
  first_seen_at        timestamptz NOT NULL DEFAULT now(),
  last_visit_at        timestamptz,
  is_blocked           boolean NOT NULL DEFAULT false,
  block_reason         text,
  status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','MERGED','ERASED')),
  merged_into_id       uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, merged_into_id) REFERENCES crm.customers (org_id, id),
  CHECK ((status = 'MERGED') = (merged_into_id IS NOT NULL))
);
CREATE UNIQUE INDEX customers_user_uniq ON crm.customers (org_id, user_id) WHERE user_id IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX customers_phone_idx ON crm.customers (org_id, phone_e164);
CREATE INDEX customers_name_trgm ON crm.customers USING gin (display_name gin_trgm_ops);
CREATE INDEX customers_user_idx ON crm.customers (user_id) WHERE user_id IS NOT NULL;

CREATE TABLE crm.customer_addresses (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  customer_id   uuid NOT NULL,
  label         text,
  admin_area_id uuid REFERENCES ref.administrative_areas(id),
  address_line  text,
  landmark      text,
  instructions  text,
  geo           geography(Point, 4326),
  created_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE
);

CREATE TABLE crm.customer_notes (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  customer_id uuid NOT NULL,
  author_id   uuid REFERENCES iam.users(id),
  kind        text NOT NULL DEFAULT 'GENERAL' CHECK (kind IN ('GENERAL','ALERT','ALLERGY','MEDICAL','PREFERENCE')),
  body        text NOT NULL,
  is_pinned   boolean NOT NULL DEFAULT false,
  visibility  text NOT NULL DEFAULT 'TEAM' CHECK (visibility IN ('TEAM','MANAGERS','AUTHOR')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE
);

CREATE TABLE crm.tags (
  id     uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id uuid NOT NULL REFERENCES tenant.organizations(id),
  name   text NOT NULL,
  color  text,
  UNIQUE (org_id, id),
  UNIQUE (org_id, name)
);

CREATE TABLE crm.customer_tags (
  org_id      uuid NOT NULL,
  customer_id uuid NOT NULL,
  tag_id      uuid NOT NULL,
  tagged_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (customer_id, tag_id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, tag_id) REFERENCES crm.tags (org_id, id) ON DELETE CASCADE
);

-- Photos (before/after), documents, IDs. appointment link added in V1_09.
CREATE TABLE crm.customer_files (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  customer_id    uuid NOT NULL,
  file_id        uuid NOT NULL,
  kind           text NOT NULL CHECK (kind IN ('PHOTO_BEFORE','PHOTO_AFTER','PHOTO','DOCUMENT','PRESCRIPTION')),
  appointment_id uuid,
  caption        text,
  shared_with_customer boolean NOT NULL DEFAULT false,
  created_by     uuid REFERENCES iam.users(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, file_id) REFERENCES platform.files (org_id, id)
);

CREATE TABLE crm.treatment_records (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  customer_id         uuid NOT NULL,
  appointment_item_id uuid,                      -- FK added in V1_09
  staff_id            uuid,
  kind                text NOT NULL CHECK (kind IN ('COLOUR_FORMULA','TREATMENT_NOTE','BODY_CHART','SKIN_ANALYSIS','PRESCRIPTION','PROGRESS')),
  data                jsonb NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id)
);

CREATE TABLE crm.patch_tests (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL,
  customer_id  uuid NOT NULL,
  service_id   uuid NOT NULL,
  performed_at timestamptz NOT NULL,
  performed_by uuid,
  result       text NOT NULL DEFAULT 'PENDING' CHECK (result IN ('PENDING','PASS','FAIL')),
  valid_until  timestamptz,
  notes        text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  FOREIGN KEY (org_id, performed_by) REFERENCES team.staff (org_id, id),
  CHECK (result <> 'PASS' OR valid_until IS NOT NULL)
);

-- Append-only consent evidence; the latest row per (customer, purpose) is the current state.
CREATE TABLE crm.customer_consents (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  customer_id uuid NOT NULL,
  purpose     text NOT NULL CHECK (purpose IN ('MARKETING_SMS','MARKETING_EMAIL','MARKETING_WHATSAPP','MARKETING_TELEGRAM','MARKETING_PUSH','PHOTO_USE','TREATMENT','DATA_SHARING_WITH_ORGANIZER')),
  granted     boolean NOT NULL,
  source      text NOT NULL CHECK (source IN ('CHECKOUT','FORM','PORTAL','IMPORT','UNSUBSCRIBE_LINK','STOP_KEYWORD','APP_SETTINGS')),
  evidence    jsonb NOT NULL DEFAULT '{}'::jsonb,
  captured_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE
);
CREATE INDEX customer_consents_latest_idx ON crm.customer_consents (customer_id, purpose, captured_at DESC);

-- -----------------------------------------------------------------------------
-- Forms: consultation/intake/consent forms (Fresha) and checkout/attendee
-- questions (Eventbrite) are the same thing — a versioned schema + submissions.
-- -----------------------------------------------------------------------------
CREATE TABLE crm.forms (
  id         uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id     uuid NOT NULL REFERENCES tenant.organizations(id),
  name       text NOT NULL,
  purpose    text NOT NULL CHECK (purpose IN ('CONSULTATION','INTAKE','CONSENT','AFTERCARE','ORDER_QUESTIONS','ATTENDEE_QUESTIONS','FEEDBACK')),
  status     text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE crm.form_versions (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL,
  form_id      uuid NOT NULL,
  version      integer NOT NULL CHECK (version > 0),
  schema       jsonb NOT NULL,                   -- fields, types, options, conditional logic, i18n labels
  published_at timestamptz,
  UNIQUE (org_id, id),
  UNIQUE (form_id, version),
  FOREIGN KEY (org_id, form_id) REFERENCES crm.forms (org_id, id)
);

-- Where a form is required. event_id / ticket_type_id FKs are added in V1_07.
CREATE TABLE crm.form_assignments (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  form_id        uuid NOT NULL,
  service_id     uuid,
  event_id       uuid,
  ticket_type_id uuid,
  timing         text NOT NULL CHECK (timing IN ('AT_CHECKOUT','PER_ATTENDEE','BEFORE_VISIT','AFTER_VISIT')),
  frequency      text NOT NULL DEFAULT 'EVERY_TIME' CHECK (frequency IN ('EVERY_TIME','ONCE','EVERY_N_DAYS')),
  frequency_days smallint CHECK (frequency_days IS NULL OR frequency_days > 0),
  is_required    boolean NOT NULL DEFAULT true,
  FOREIGN KEY (org_id, form_id) REFERENCES crm.forms (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id) ON DELETE CASCADE,
  CHECK (num_nonnulls(service_id, event_id, ticket_type_id) = 1),
  CHECK ((frequency = 'EVERY_N_DAYS') = (frequency_days IS NOT NULL))
);

-- order / appointment / ticket links are added in later migrations.
CREATE TABLE crm.form_submissions (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  form_version_id  uuid NOT NULL,
  customer_id      uuid,
  submitted_by_user_id uuid REFERENCES iam.users(id),
  order_id         uuid,
  appointment_id   uuid,
  ticket_id        uuid,
  answers          jsonb NOT NULL,
  signature_file_id uuid,
  status           text NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('DRAFT','SUBMITTED','REVIEWED','SUPERSEDED')),
  submitted_at     timestamptz NOT NULL DEFAULT now(),
  reviewed_by      uuid REFERENCES iam.users(id),
  reviewed_at      timestamptz,
  FOREIGN KEY (org_id, form_version_id) REFERENCES crm.form_versions (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, signature_file_id) REFERENCES platform.files (org_id, id)
);
