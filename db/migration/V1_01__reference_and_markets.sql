-- =============================================================================
-- 01: reference data and market configuration (single source of truth)
-- A country is geography. A market is a country where the platform is
-- commercially active. Nothing is "enabled" just because a country row exists.
-- =============================================================================

CREATE TABLE ref.currencies (
  code         char(3) PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'),
  name         text NOT NULL,
  symbol       text,
  minor_units  smallint NOT NULL CHECK (minor_units BETWEEN 0 AND 4),
  -- Smallest cash denomination in minor units, for cash rounding at POS (e.g. ETB 0.05 -> 5).
  cash_rounding_minor bigint NOT NULL DEFAULT 1 CHECK (cash_rounding_minor > 0),
  is_active    boolean NOT NULL DEFAULT true
);

CREATE TABLE ref.languages (
  code         text PRIMARY KEY,                 -- BCP 47 (am, om, ti, so, sw, en, fr, ar, rw)
  english_name text NOT NULL,
  native_name  text NOT NULL,
  script       text NOT NULL,                    -- ISO 15924 (Ethi, Latn, Arab)
  direction    text NOT NULL DEFAULT 'LTR' CHECK (direction IN ('LTR','RTL')),
  is_active    boolean NOT NULL DEFAULT true
);

CREATE TABLE ref.countries (
  code              char(2) PRIMARY KEY CHECK (code ~ '^[A-Z]{2}$'),
  iso3              char(3) NOT NULL UNIQUE,
  name              text NOT NULL,
  region            text NOT NULL CHECK (region IN ('EAST_AFRICA','WEST_AFRICA','CENTRAL_AFRICA','SOUTHERN_AFRICA','NORTH_AFRICA','REST_OF_WORLD')),
  calling_code      text NOT NULL,               -- '+251'
  default_currency  char(3) NOT NULL REFERENCES ref.currencies(code),
  default_language  text NOT NULL REFERENCES ref.languages(code),
  default_timezone  text NOT NULL,               -- IANA, e.g. Africa/Addis_Ababa
  -- Calendar used for display and for business documents. Storage is always UTC/Gregorian.
  display_calendar  text NOT NULL DEFAULT 'GREGORIAN' CHECK (display_calendar IN ('GREGORIAN','ETHIOPIAN')),
  -- Ethiopia commonly tells time from 06:00 ("1 o'clock in the morning" = 07:00).
  local_time_convention text NOT NULL DEFAULT 'STANDARD' CHECK (local_time_convention IN ('STANDARD','ETHIOPIAN')),
  address_format    jsonb NOT NULL DEFAULT '{}'::jsonb,  -- which fields are shown/required; landmark-first where postal codes are not used
  uses_postal_codes boolean NOT NULL DEFAULT false
);

CREATE TABLE ref.country_languages (
  country_code  char(2) NOT NULL REFERENCES ref.countries(code),
  language_code text NOT NULL REFERENCES ref.languages(code),
  is_official   boolean NOT NULL DEFAULT false,
  sort_order    smallint NOT NULL DEFAULT 0,
  PRIMARY KEY (country_code, language_code)
);

-- Country-configured administrative hierarchy (ET: region > zone/sub-city > woreda > kebele; KE: county > sub-county > ward).
CREATE TABLE ref.administrative_areas (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  country_code char(2) NOT NULL REFERENCES ref.countries(code),
  parent_id    uuid REFERENCES ref.administrative_areas(id),
  level        smallint NOT NULL CHECK (level BETWEEN 1 AND 6),
  level_name   text NOT NULL,                    -- REGION, CITY_ADMINISTRATION, SUB_CITY, ZONE, WOREDA, KEBELE, COUNTY ...
  code         text,
  name         text NOT NULL,
  name_i18n    jsonb NOT NULL DEFAULT '{}'::jsonb,
  path         ltree NOT NULL,
  centroid     geography(Point, 4326),
  is_active    boolean NOT NULL DEFAULT true,
  UNIQUE (country_code, path)
);
CREATE INDEX administrative_areas_path_gist ON ref.administrative_areas USING gist (path);
CREATE INDEX administrative_areas_parent_idx ON ref.administrative_areas (parent_id);

CREATE TABLE ref.public_holidays (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  country_code  char(2) NOT NULL REFERENCES ref.countries(code),
  admin_area_id uuid REFERENCES ref.administrative_areas(id),
  holiday_date  date NOT NULL,
  name_i18n     jsonb NOT NULL,
  is_estimated  boolean NOT NULL DEFAULT false,  -- lunar holidays are confirmed close to the date
  UNIQUE NULLS NOT DISTINCT (country_code, admin_area_id, holiday_date)
);

CREATE TABLE ref.fx_rates (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  base_currency  char(3) NOT NULL REFERENCES ref.currencies(code),
  quote_currency char(3) NOT NULL REFERENCES ref.currencies(code),
  rate          numeric(24,12) NOT NULL CHECK (rate > 0),
  source        text NOT NULL,                   -- NBE, CBK, provider quote id ...
  observed_at   timestamptz NOT NULL,
  valid_until   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  CHECK (base_currency <> quote_currency)
);
CREATE INDEX fx_rates_lookup_idx ON ref.fx_rates (base_currency, quote_currency, observed_at DESC);

-- -----------------------------------------------------------------------------
-- Markets
-- -----------------------------------------------------------------------------
CREATE TABLE ref.markets (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  code             text NOT NULL UNIQUE,         -- 'ET', 'KE' ...
  country_code     char(2) NOT NULL UNIQUE REFERENCES ref.countries(code),
  name             text NOT NULL,
  status           text NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','PILOT','LIVE','PAUSED','RETIRED')),
  default_currency char(3) NOT NULL REFERENCES ref.currencies(code),
  default_language text NOT NULL REFERENCES ref.languages(code),
  default_timezone text NOT NULL,
  -- Whether consumer prices are shown tax-inclusive by default in this market.
  prices_include_tax boolean NOT NULL DEFAULT true,
  support_contacts jsonb NOT NULL DEFAULT '{}'::jsonb,
  launched_at      timestamptz,
  settings         jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ref.market_currencies (
  market_id     uuid NOT NULL REFERENCES ref.markets(id),
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  is_default    boolean NOT NULL DEFAULT false,
  can_price     boolean NOT NULL DEFAULT true,   -- sellers may price in it
  can_settle    boolean NOT NULL DEFAULT true,   -- sellers may be paid out in it
  can_present   boolean NOT NULL DEFAULT true,   -- buyers may be shown / charged it (diaspora presentment)
  PRIMARY KEY (market_id, currency_code)
);
CREATE UNIQUE INDEX market_currencies_one_default ON ref.market_currencies (market_id) WHERE is_default;

CREATE TABLE ref.market_languages (
  market_id     uuid NOT NULL REFERENCES ref.markets(id),
  language_code text NOT NULL REFERENCES ref.languages(code),
  is_default    boolean NOT NULL DEFAULT false,
  status        text NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED','BETA','DISABLED')),
  PRIMARY KEY (market_id, language_code)
);
CREATE UNIQUE INDEX market_languages_one_default ON ref.market_languages (market_id) WHERE is_default;

-- Feature availability per market (RESERVED_SEATING, AT_HOME_SERVICES, PLATFORM_COLLECTION, INSTANT_PAYOUT, ...).
CREATE TABLE ref.market_features (
  market_id    uuid NOT NULL REFERENCES ref.markets(id),
  feature_code text NOT NULL,
  status       text NOT NULL DEFAULT 'DISABLED' CHECK (status IN ('DISABLED','BETA','ENABLED')),
  config       jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (market_id, feature_code)
);

-- -----------------------------------------------------------------------------
-- One taxonomy for businesses, services, events and products.
-- -----------------------------------------------------------------------------
CREATE TABLE ref.categories (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  parent_id   uuid REFERENCES ref.categories(id),
  kind        text NOT NULL CHECK (kind IN ('BUSINESS','SERVICE','EVENT','PRODUCT')),
  vertical    text NOT NULL CHECK (vertical IN ('BEAUTY','WELLNESS','FITNESS','HEALTH','EVENTS','EXPERIENCES','RETAIL')),
  code        text NOT NULL,
  name_i18n   jsonb NOT NULL,                    -- {"en": "Hair", "am": "ፀጉር"}
  icon        text,
  sort_order  smallint NOT NULL DEFAULT 0,
  is_active   boolean NOT NULL DEFAULT true,
  UNIQUE (kind, code)
);

-- -----------------------------------------------------------------------------
-- Tax: platform-maintained rates; organizations declare their regime on the
-- legal entity; order lines keep immutable tax snapshots.
-- -----------------------------------------------------------------------------
CREATE TABLE ref.tax_categories (
  code        text PRIMARY KEY,                  -- SERVICE, GOODS, EVENT_ADMISSION, VOUCHER_MULTI_PURPOSE, PLATFORM_FEE, EXEMPT
  description text NOT NULL
);

CREATE TABLE ref.tax_rates (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  country_code      char(2) NOT NULL REFERENCES ref.countries(code),
  tax_code          text NOT NULL CHECK (tax_code IN ('VAT','TOT','EXCISE','SERVICE_CHARGE','ENTERTAINMENT','WITHHOLDING')),
  tax_category_code text NOT NULL REFERENCES ref.tax_categories(code),
  -- Which seller regime the rate applies to (a VAT-registered seller charges VAT; a TOT seller charges TOT).
  seller_regime     text NOT NULL CHECK (seller_regime IN ('VAT','TOT','ANY')),
  rate              numeric(7,4) NOT NULL CHECK (rate >= 0 AND rate < 1),
  valid_during      daterange NOT NULL,
  source_reference  text NOT NULL,               -- law / directive reference
  is_verified       boolean NOT NULL DEFAULT false, -- must be confirmed by local tax adviser before LIVE
  created_at        timestamptz NOT NULL DEFAULT now(),
  EXCLUDE USING gist (country_code WITH =, tax_code WITH =, tax_category_code WITH =, seller_regime WITH =, valid_during WITH &&)
);

-- -----------------------------------------------------------------------------
-- Legal documents (terms, privacy, merchant agreement) per market & language.
-- -----------------------------------------------------------------------------
CREATE TABLE ref.legal_documents (
  id        uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  market_id uuid REFERENCES ref.markets(id),     -- NULL = global
  code      text NOT NULL CHECK (code IN ('CONSUMER_TERMS','PARTNER_TERMS','PRIVACY_NOTICE','COOKIE_NOTICE','REFUND_POLICY','MERCHANT_AGREEMENT','ACCEPTABLE_USE','DATA_PROCESSING_AGREEMENT')),
  audience  text NOT NULL CHECK (audience IN ('CONSUMER','PARTNER','ALL')),
  UNIQUE NULLS NOT DISTINCT (market_id, code)
);

CREATE TABLE ref.legal_document_versions (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  document_id          uuid NOT NULL REFERENCES ref.legal_documents(id),
  version              text NOT NULL,
  language_code        text NOT NULL REFERENCES ref.languages(code),
  body_file_id         uuid REFERENCES platform.files(id),
  summary_of_changes   text,
  published_at         timestamptz,
  effective_at         timestamptz NOT NULL,
  requires_reacceptance boolean NOT NULL DEFAULT false,
  UNIQUE (document_id, version, language_code)
);
