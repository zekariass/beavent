-- =============================================================================
-- 03: tenancy — one organization model for salons, spas, barbers, clinics,
-- gyms, freelancers, event organizers and venues. What an organization can do
-- is a set of capabilities, not a separate product account.
-- =============================================================================

CREATE TABLE tenant.organizations (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  slug                 citext NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$'),
  display_name         text NOT NULL,
  kind                 text NOT NULL CHECK (kind IN ('BUSINESS','INDEPENDENT_PROFESSIONAL','EVENT_ORGANIZER','VENUE_OPERATOR','NON_PROFIT','COMMUNITY_GROUP')),
  primary_category_id  uuid REFERENCES ref.categories(id),
  home_market_id       uuid NOT NULL REFERENCES ref.markets(id),
  status               text NOT NULL DEFAULT 'ONBOARDING' CHECK (status IN ('ONBOARDING','ACTIVE','RESTRICTED','SUSPENDED','CLOSED')),
  verification_status  text NOT NULL DEFAULT 'UNVERIFIED' CHECK (verification_status IN ('UNVERIFIED','PENDING','VERIFIED','REJECTED')),
  marketplace_status   text NOT NULL DEFAULT 'HIDDEN' CHECK (marketplace_status IN ('HIDDEN','PENDING_REVIEW','LISTED','DELISTED')),
  description_i18n     jsonb NOT NULL DEFAULT '{}'::jsonb,
  logo_file_id         uuid REFERENCES platform.files(id),
  cover_file_id        uuid REFERENCES platform.files(id),
  website_url          text,
  contact_email        citext,
  contact_phone        text,
  social_links         jsonb NOT NULL DEFAULT '{}'::jsonb,
  default_language     text NOT NULL REFERENCES ref.languages(code),
  created_by_user_id   uuid NOT NULL REFERENCES iam.users(id),
  settings             jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  closed_at            timestamptz
);

CREATE TABLE tenant.organization_capabilities (
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  capability  text NOT NULL CHECK (capability IN (
                'APPOINTMENTS','CLASSES','EVENTS','RESERVED_SEATING','RETAIL','ONLINE_STORE',
                'AT_HOME_SERVICES','GIFT_CARDS','MEMBERSHIPS','PACKAGES','LOYALTY','MARKETING',
                'TEAM_PAY','AI_CONCIERGE','BOX_OFFICE','PUBLIC_API')),
  status      text NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED','DISABLED','PENDING_APPROVAL')),
  enabled_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, capability)
);

-- The registered business/person that sells, invoices, pays tax and gets paid
-- in one country. Multi-country organizations have one per country.
CREATE TABLE tenant.legal_entities (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL REFERENCES tenant.organizations(id),
  country_code         char(2) NOT NULL REFERENCES ref.countries(code),
  entity_type          text NOT NULL CHECK (entity_type IN ('SOLE_PROPRIETOR','PLC','SHARE_COMPANY','PARTNERSHIP','NGO','INDIVIDUAL','OTHER')),
  registered_name      text NOT NULL,
  trade_license_number text,
  tin                  text,                     -- taxpayer identification number
  vat_number           text,
  tax_regime           text NOT NULL DEFAULT 'TOT' CHECK (tax_regime IN ('VAT','TOT','PRESUMPTIVE','EXEMPT')),
  functional_currency  char(3) NOT NULL REFERENCES ref.currencies(code),
  registered_address   jsonb NOT NULL DEFAULT '{}'::jsonb,
  verification_status  text NOT NULL DEFAULT 'UNVERIFIED' CHECK (verification_status IN ('UNVERIFIED','PENDING','VERIFIED','REJECTED')),
  is_default           boolean NOT NULL DEFAULT false,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  CHECK (tax_regime <> 'VAT' OR vat_number IS NOT NULL)
);
CREATE UNIQUE INDEX legal_entities_one_default_per_country ON tenant.legal_entities (org_id, country_code) WHERE is_default;

-- A physical (or virtual) place. Organization premises and event venues are
-- both places. Platform-curated public venues have owner_org_id NULL so many
-- organizers can host at the same venue without duplicating it.
CREATE TABLE tenant.places (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  owner_org_id    uuid REFERENCES tenant.organizations(id),
  name            text NOT NULL,
  kind            text NOT NULL CHECK (kind IN ('PREMISES','VENUE','OUTDOOR','ONLINE')),
  is_public_venue boolean NOT NULL DEFAULT false,
  country_code    char(2) NOT NULL REFERENCES ref.countries(code),
  admin_area_id   uuid REFERENCES ref.administrative_areas(id),
  address_line    text,
  landmark        text,
  city            text,
  postal_code     text,
  geo             geography(Point, 4326),
  timezone        text NOT NULL,
  capacity        integer CHECK (capacity IS NULL OR capacity > 0),
  amenities       text[] NOT NULL DEFAULT '{}',  -- PARKING, WHEELCHAIR_ACCESS, WIFI, GENERATOR ...
  external_refs   jsonb NOT NULL DEFAULT '{}'::jsonb, -- google place id etc.
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','MERGED','ARCHIVED')),
  merged_into_id  uuid REFERENCES tenant.places(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CHECK (kind = 'ONLINE' OR geo IS NOT NULL OR address_line IS NOT NULL OR landmark IS NOT NULL),
  CHECK (NOT is_public_venue OR owner_org_id IS NULL)
);
CREATE INDEX places_geo_gist ON tenant.places USING gist (geo);
CREATE INDEX places_name_trgm ON tenant.places USING gin (name gin_trgm_ops);

-- A site the organization operates from: a salon branch, a studio, a box office.
CREATE TABLE tenant.locations (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  legal_entity_id    uuid NOT NULL,
  place_id           uuid NOT NULL REFERENCES tenant.places(id),
  name               text NOT NULL,
  slug               citext NOT NULL,
  phone              text,
  email              citext,
  timezone           text NOT NULL,
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  service_mode       text NOT NULL DEFAULT 'ON_PREMISES' CHECK (service_mode IN ('ON_PREMISES','MOBILE_ONLY','BOTH')),
  online_booking     boolean NOT NULL DEFAULT true,
  marketplace_listed boolean NOT NULL DEFAULT false,
  status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, slug),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id)
);

CREATE TABLE tenant.location_hours (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  location_id uuid NOT NULL,
  iso_weekday smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
  opens_at    time NOT NULL,
  closes_at   time NOT NULL,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE,
  CHECK (closes_at > opens_at)
);

CREATE TABLE tenant.location_closures (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL,
  location_id  uuid NOT NULL,
  closed_during tstzrange NOT NULL,
  reason       text,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE,
  CHECK (NOT isempty(closed_during))
);

-- Coverage for at-home / mobile services (very common for beauty in the launch markets).
CREATE TABLE tenant.service_areas (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  location_id     uuid NOT NULL,
  name            text NOT NULL,
  admin_area_id   uuid REFERENCES ref.administrative_areas(id),
  center          geography(Point, 4326),
  radius_m        integer CHECK (radius_m IS NULL OR radius_m > 0),
  travel_fee_minor bigint NOT NULL DEFAULT 0 CHECK (travel_fee_minor >= 0),
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  travel_minutes  smallint NOT NULL DEFAULT 0 CHECK (travel_minutes >= 0),
  is_active       boolean NOT NULL DEFAULT true,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE,
  CHECK (admin_area_id IS NOT NULL OR (center IS NOT NULL AND radius_m IS NOT NULL))
);

CREATE TABLE tenant.custom_domains (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  hostname           citext NOT NULL UNIQUE,
  verification_token text NOT NULL,
  status             text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','FAILED','REMOVED')),
  verified_at        timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Roles & membership. System role templates have org_id NULL; organizations
-- can clone and customise them. One RBAC model for appointments, events, retail.
-- -----------------------------------------------------------------------------
CREATE TABLE tenant.roles (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid REFERENCES tenant.organizations(id),
  code        text NOT NULL,
  name        text NOT NULL,
  description text,
  is_system   boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (org_id, code),
  CHECK (is_system = (org_id IS NULL))
);

CREATE TABLE tenant.role_permissions (
  role_id         uuid NOT NULL REFERENCES tenant.roles(id) ON DELETE CASCADE,
  permission_code text NOT NULL REFERENCES iam.permissions(code),
  PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE tenant.members (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL REFERENCES tenant.organizations(id),
  user_id           uuid NOT NULL REFERENCES iam.users(id),
  role_id           uuid NOT NULL REFERENCES tenant.roles(id),
  status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','REMOVED')),
  all_locations     boolean NOT NULL DEFAULT true,
  is_owner          boolean NOT NULL DEFAULT false,
  joined_at         timestamptz NOT NULL DEFAULT now(),
  removed_at        timestamptz,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, user_id)
);
CREATE UNIQUE INDEX members_one_owner ON tenant.members (org_id) WHERE is_owner AND status = 'ACTIVE';
CREATE INDEX members_user_idx ON tenant.members (user_id) WHERE status = 'ACTIVE';

CREATE TABLE tenant.member_locations (
  org_id      uuid NOT NULL,
  member_id   uuid NOT NULL,
  location_id uuid NOT NULL,
  PRIMARY KEY (member_id, location_id),
  FOREIGN KEY (org_id, member_id) REFERENCES tenant.members (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE
);

CREATE TABLE tenant.invitations (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL REFERENCES tenant.organizations(id),
  phone_e164    text,
  email         citext,
  role_id       uuid NOT NULL REFERENCES tenant.roles(id),
  location_ids  uuid[] NOT NULL DEFAULT '{}',
  token_hash    text NOT NULL UNIQUE,
  invited_by    uuid NOT NULL REFERENCES iam.users(id),
  status        text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REVOKED','EXPIRED')),
  expires_at    timestamptz NOT NULL,
  accepted_by   uuid REFERENCES iam.users(id),
  accepted_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  CHECK (phone_e164 IS NOT NULL OR email IS NOT NULL)
);

ALTER TABLE platform.files
  ADD CONSTRAINT files_org_fk FOREIGN KEY (org_id) REFERENCES tenant.organizations(id);
