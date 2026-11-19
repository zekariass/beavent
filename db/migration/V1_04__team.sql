-- =============================================================================
-- 04: team — staff, pricing levels, working patterns, shifts, time off,
-- timesheets. (Commissions, tips and pay runs: V1_15 once order lines exist.)
-- A staff profile may exist without a login (e.g. a junior stylist who never
-- uses the app); member_id links it to a login when there is one.
-- =============================================================================

CREATE TABLE team.pricing_levels (
  id         uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id     uuid NOT NULL REFERENCES tenant.organizations(id),
  name       text NOT NULL,                      -- Junior, Senior, Master, Director
  rank       smallint NOT NULL DEFAULT 0,
  UNIQUE (org_id, id),
  UNIQUE (org_id, name)
);

CREATE TABLE team.staff (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL REFERENCES tenant.organizations(id),
  member_id        uuid,
  display_name     text NOT NULL,
  job_title        text,
  bio_i18n         jsonb NOT NULL DEFAULT '{}'::jsonb,
  avatar_file_id   uuid REFERENCES platform.files(id),
  phone_e164       text,
  email            citext,
  employment_type  text NOT NULL DEFAULT 'EMPLOYEE' CHECK (employment_type IN ('EMPLOYEE','CONTRACTOR','CHAIR_RENTER','APPRENTICE','VOLUNTEER')),
  pricing_level_id uuid,
  is_bookable      boolean NOT NULL DEFAULT true,
  bookable_online  boolean NOT NULL DEFAULT true,
  languages        text[] NOT NULL DEFAULT '{}', -- languages spoken; shown on marketplace
  calendar_color   text,
  sort_order       smallint NOT NULL DEFAULT 0,
  started_on       date,
  ended_on         date,
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, member_id) REFERENCES tenant.members (org_id, id),
  FOREIGN KEY (org_id, pricing_level_id) REFERENCES team.pricing_levels (org_id, id)
);
CREATE UNIQUE INDEX staff_member_uniq ON team.staff (org_id, member_id) WHERE member_id IS NOT NULL;

CREATE TABLE team.staff_locations (
  org_id      uuid NOT NULL,
  staff_id    uuid NOT NULL,
  location_id uuid NOT NULL,
  PRIMARY KEY (staff_id, location_id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE
);

-- Recurring weekly pattern (Fresha "regular shifts"). Effective-dated so history is kept.
CREATE TABLE team.working_patterns (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  staff_id       uuid NOT NULL,
  location_id    uuid NOT NULL,
  iso_weekday    smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
  starts_at      time NOT NULL,
  ends_at        time NOT NULL,
  -- Repeat every N weeks counted from effective_from (supports fortnightly rotas).
  repeat_weeks   smallint NOT NULL DEFAULT 1 CHECK (repeat_weeks BETWEEN 1 AND 8),
  effective_from date NOT NULL,
  effective_to   date,
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (ends_at > starts_at),
  CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- Concrete shifts override the pattern for a date (published rota, swaps, overtime).
CREATE TABLE team.shifts (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  staff_id    uuid NOT NULL,
  location_id uuid NOT NULL,
  during      tstzrange NOT NULL,
  status      text NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT','PUBLISHED','CANCELLED')),
  note        text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (NOT isempty(during)),
  EXCLUDE USING gist (staff_id WITH =, during WITH &&) WHERE (status = 'PUBLISHED')
);

CREATE TABLE team.time_off (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL,
  staff_id     uuid NOT NULL,
  during       tstzrange NOT NULL,
  kind         text NOT NULL CHECK (kind IN ('ANNUAL','SICK','UNPAID','TRAINING','PUBLIC_HOLIDAY','OTHER')),
  status       text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','REJECTED','CANCELLED')),
  reason       text,
  decided_by   uuid REFERENCES iam.users(id),
  decided_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  CHECK (NOT isempty(during))
);

CREATE TABLE team.time_entries (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  staff_id      uuid NOT NULL,
  location_id   uuid NOT NULL,
  clock_in_at   timestamptz NOT NULL,
  clock_out_at  timestamptz,
  break_minutes smallint NOT NULL DEFAULT 0 CHECK (break_minutes >= 0),
  source        text NOT NULL DEFAULT 'APP' CHECK (source IN ('APP','PORTAL','KIOSK','MANUAL')),
  status        text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SUBMITTED','APPROVED','REJECTED')),
  approved_by   uuid REFERENCES iam.users(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (clock_out_at IS NULL OR clock_out_at > clock_in_at),
  EXCLUDE USING gist (staff_id WITH =, tstzrange(clock_in_at, coalesce(clock_out_at, 'infinity'::timestamptz)) WITH &&)
);

CREATE TABLE team.compensation (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  staff_id       uuid NOT NULL,
  basis          text NOT NULL CHECK (basis IN ('HOURLY','DAILY','MONTHLY_SALARY','COMMISSION_ONLY','CHAIR_RENT')),
  rate_minor     bigint NOT NULL CHECK (rate_minor >= 0), -- for CHAIR_RENT: rent the staff pays the business
  currency_code  char(3) NOT NULL REFERENCES ref.currencies(code),
  effective_from date NOT NULL,
  effective_to   date,
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  CHECK (effective_to IS NULL OR effective_to >= effective_from),
  EXCLUDE USING gist (staff_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
);

CREATE TABLE team.targets (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL REFERENCES tenant.organizations(id),
  staff_id     uuid,
  location_id  uuid,
  metric       text NOT NULL CHECK (metric IN ('SALES','SERVICE_SALES','RETAIL_SALES','APPOINTMENTS','REBOOK_RATE','NEW_CLIENTS','TICKETS_SOLD')),
  period       daterange NOT NULL,
  target_value numeric(18,2) NOT NULL CHECK (target_value > 0),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id)
);
