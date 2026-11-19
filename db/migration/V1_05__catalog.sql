-- =============================================================================
-- 05: catalog — everything an organization sells, except event tickets (V1_07).
-- Prices are BIGINT minor units in an explicit currency.
-- =============================================================================

CREATE TABLE catalog.resources (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id uuid NOT NULL,
  name        text NOT NULL,
  kind        text NOT NULL CHECK (kind IN ('ROOM','CHAIR','BED','STATION','EQUIPMENT','OTHER')),
  -- Number of parallel units (a sauna with 4 places = 4). Each unit is reserved separately.
  units       smallint NOT NULL DEFAULT 1 CHECK (units BETWEEN 1 AND 500),
  is_active   boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id)
);

CREATE TABLE catalog.service_groups (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  name_i18n   jsonb NOT NULL,
  sort_order  smallint NOT NULL DEFAULT 0,
  UNIQUE (org_id, id)
);

CREATE TABLE catalog.services (
  id                       uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                   uuid NOT NULL REFERENCES tenant.organizations(id),
  group_id                 uuid,
  category_id              uuid NOT NULL REFERENCES ref.categories(id),
  name_i18n                jsonb NOT NULL,
  description_i18n         jsonb NOT NULL DEFAULT '{}'::jsonb,
  aftercare_i18n           jsonb NOT NULL DEFAULT '{}'::jsonb,
  delivery_modes           text[] NOT NULL DEFAULT '{ON_PREMISES}'
                             CHECK (delivery_modes <@ ARRAY['ON_PREMISES','AT_CUSTOMER','ONLINE']::text[] AND cardinality(delivery_modes) > 0),
  audience                 text NOT NULL DEFAULT 'EVERYONE' CHECK (audience IN ('EVERYONE','WOMEN','MEN','CHILDREN')),
  tax_category_code        text NOT NULL DEFAULT 'SERVICE' REFERENCES ref.tax_categories(code),
  requires_patch_test      boolean NOT NULL DEFAULT false,
  patch_test_validity_days smallint CHECK (patch_test_validity_days IS NULL OR patch_test_validity_days > 0),
  online_booking           boolean NOT NULL DEFAULT true,
  -- Commission eligibility and reporting bucket.
  commission_eligible      boolean NOT NULL DEFAULT true,
  status                   text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','HIDDEN','ARCHIVED')),
  sort_order               smallint NOT NULL DEFAULT 0,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, group_id) REFERENCES catalog.service_groups (org_id, id)
);

-- Bookable option of a service (short/long hair, 60/90 min). Every service has >= 1 variant.
CREATE TABLE catalog.service_variants (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  service_id         uuid NOT NULL,
  name_i18n          jsonb NOT NULL DEFAULT '{}'::jsonb,
  duration_minutes   smallint NOT NULL CHECK (duration_minutes BETWEEN 5 AND 1440),
  buffer_before_min  smallint NOT NULL DEFAULT 0 CHECK (buffer_before_min >= 0),
  buffer_after_min   smallint NOT NULL DEFAULT 0 CHECK (buffer_after_min >= 0),
  price_type         text NOT NULL DEFAULT 'FIXED' CHECK (price_type IN ('FIXED','FROM','FREE','ON_CONSULTATION')),
  price_minor        bigint NOT NULL DEFAULT 0 CHECK (price_minor >= 0),
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  is_default         boolean NOT NULL DEFAULT false,
  status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  sort_order         smallint NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id) ON DELETE CASCADE,
  CHECK (price_type <> 'FREE' OR price_minor = 0)
);
CREATE UNIQUE INDEX service_variants_one_default ON catalog.service_variants (service_id) WHERE is_default AND status = 'ACTIVE';

-- Optional stage template: e.g. colour = apply 30 (staff busy) + process 35 (staff free) + rinse/style 25 (staff busy).
-- When present, the sum of stage durations must equal the variant duration (checked by the application on save).
CREATE TABLE catalog.service_variant_stages (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  variant_id       uuid NOT NULL,
  seq              smallint NOT NULL CHECK (seq > 0),
  kind             text NOT NULL CHECK (kind IN ('SERVICE','PROCESSING','FINISHING','CONSULTATION')),
  duration_minutes smallint NOT NULL CHECK (duration_minutes > 0),
  staff_busy       boolean NOT NULL DEFAULT true,
  UNIQUE (variant_id, seq),
  FOREIGN KEY (org_id, variant_id) REFERENCES catalog.service_variants (org_id, id) ON DELETE CASCADE
);

-- Price/duration overrides. Resolution order (most specific wins):
--   staff+location > staff > pricing_level+location > pricing_level > location > variant default.
CREATE TABLE catalog.service_prices (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  variant_id       uuid NOT NULL,
  location_id      uuid,
  staff_id         uuid,
  pricing_level_id uuid,
  price_minor      bigint NOT NULL CHECK (price_minor >= 0),
  currency_code    char(3) NOT NULL REFERENCES ref.currencies(code),
  duration_minutes smallint CHECK (duration_minutes IS NULL OR duration_minutes BETWEEN 5 AND 1440),
  valid_from       timestamptz NOT NULL DEFAULT '-infinity',
  valid_to         timestamptz NOT NULL DEFAULT 'infinity',
  FOREIGN KEY (org_id, variant_id) REFERENCES catalog.service_variants (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, pricing_level_id) REFERENCES team.pricing_levels (org_id, id),
  CHECK (staff_id IS NULL OR pricing_level_id IS NULL),
  CHECK (valid_to > valid_from),
  EXCLUDE USING gist (
    variant_id WITH =,
    (coalesce(location_id,      '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
    (coalesce(staff_id,         '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
    (coalesce(pricing_level_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
    tstzrange(valid_from, valid_to) WITH &&)
);

CREATE TABLE catalog.location_services (
  org_id      uuid NOT NULL,
  location_id uuid NOT NULL,
  service_id  uuid NOT NULL,
  PRIMARY KEY (location_id, service_id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id) ON DELETE CASCADE
);

CREATE TABLE catalog.staff_services (
  org_id     uuid NOT NULL,
  staff_id   uuid NOT NULL,
  service_id uuid NOT NULL,
  PRIMARY KEY (staff_id, service_id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id) ON DELETE CASCADE
);

-- A service needs either a specific resource or "any resource of kind X at the location".
CREATE TABLE catalog.service_resource_requirements (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  variant_id    uuid NOT NULL,
  resource_id   uuid,
  resource_kind text CHECK (resource_kind IN ('ROOM','CHAIR','BED','STATION','EQUIPMENT','OTHER')),
  units         smallint NOT NULL DEFAULT 1 CHECK (units > 0),
  stage_seq     smallint,                        -- NULL = whole appointment item
  FOREIGN KEY (org_id, variant_id) REFERENCES catalog.service_variants (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, resource_id) REFERENCES catalog.resources (org_id, id),
  CHECK (num_nonnulls(resource_id, resource_kind) = 1)
);

CREATE TABLE catalog.addons (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL REFERENCES tenant.organizations(id),
  name_i18n         jsonb NOT NULL,
  duration_minutes  smallint NOT NULL DEFAULT 0 CHECK (duration_minutes >= 0),
  price_minor       bigint NOT NULL CHECK (price_minor >= 0),
  currency_code     char(3) NOT NULL REFERENCES ref.currencies(code),
  tax_category_code text NOT NULL DEFAULT 'SERVICE' REFERENCES ref.tax_categories(code),
  status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE catalog.service_addons (
  org_id     uuid NOT NULL,
  service_id uuid NOT NULL,
  addon_id   uuid NOT NULL,
  PRIMARY KEY (service_id, addon_id),
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, addon_id) REFERENCES catalog.addons (org_id, id) ON DELETE CASCADE
);

-- Bundles: several services (and optionally products) booked and sold together at one price.
CREATE TABLE catalog.bundles (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL REFERENCES tenant.organizations(id),
  name_i18n        jsonb NOT NULL,
  description_i18n jsonb NOT NULL DEFAULT '{}'::jsonb,
  pricing          text NOT NULL CHECK (pricing IN ('SUM_OF_ITEMS','FIXED_PRICE','PERCENT_OFF')),
  price_minor      bigint CHECK (price_minor IS NULL OR price_minor >= 0),
  percent_off      numeric(5,2) CHECK (percent_off IS NULL OR (percent_off > 0 AND percent_off < 100)),
  currency_code    char(3) NOT NULL REFERENCES ref.currencies(code),
  scheduling       text NOT NULL DEFAULT 'SEQUENTIAL' CHECK (scheduling IN ('SEQUENTIAL','PARALLEL')),
  online_booking   boolean NOT NULL DEFAULT true,
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  CHECK ((pricing = 'FIXED_PRICE') = (price_minor IS NOT NULL)),
  CHECK ((pricing = 'PERCENT_OFF') = (percent_off IS NOT NULL))
);

-- -----------------------------------------------------------------------------
-- Retail products
-- -----------------------------------------------------------------------------
CREATE TABLE catalog.products (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  category_id        uuid REFERENCES ref.categories(id),
  brand              text,
  name_i18n          jsonb NOT NULL,
  description_i18n   jsonb NOT NULL DEFAULT '{}'::jsonb,
  tax_category_code  text NOT NULL DEFAULT 'GOODS' REFERENCES ref.tax_categories(code),
  usage              text NOT NULL DEFAULT 'RETAIL' CHECK (usage IN ('RETAIL','PROFESSIONAL','BOTH')),
  track_inventory    boolean NOT NULL DEFAULT true,
  sell_online        boolean NOT NULL DEFAULT false,
  status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE catalog.product_variants (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  product_id    uuid NOT NULL,
  sku           text,
  barcode       text,
  option_values jsonb NOT NULL DEFAULT '{}'::jsonb,   -- {"size":"250ml"}
  unit          text NOT NULL DEFAULT 'EACH' CHECK (unit IN ('EACH','ML','L','G','KG')),
  price_minor   bigint NOT NULL CHECK (price_minor >= 0),
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  cost_minor    bigint CHECK (cost_minor IS NULL OR cost_minor >= 0),
  status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, product_id) REFERENCES catalog.products (org_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX product_variants_sku_uniq ON catalog.product_variants (org_id, sku) WHERE sku IS NOT NULL;
CREATE UNIQUE INDEX product_variants_barcode_uniq ON catalog.product_variants (org_id, barcode) WHERE barcode IS NOT NULL;

CREATE TABLE catalog.product_location_prices (
  org_id        uuid NOT NULL,
  variant_id    uuid NOT NULL,
  location_id   uuid NOT NULL,
  price_minor   bigint NOT NULL CHECK (price_minor >= 0),
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  PRIMARY KEY (variant_id, location_id),
  FOREIGN KEY (org_id, variant_id) REFERENCES catalog.product_variants (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id) ON DELETE CASCADE
);

CREATE TABLE catalog.bundle_items (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  bundle_id          uuid NOT NULL,
  seq                smallint NOT NULL,
  service_variant_id uuid,
  product_variant_id uuid,
  quantity           smallint NOT NULL DEFAULT 1 CHECK (quantity > 0),
  UNIQUE (bundle_id, seq),
  FOREIGN KEY (org_id, bundle_id) REFERENCES catalog.bundles (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_variant_id) REFERENCES catalog.service_variants (org_id, id),
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id),
  CHECK (num_nonnulls(service_variant_id, product_variant_id) = 1)
);

-- -----------------------------------------------------------------------------
-- Definitions of stored-value / entitlement products (instances: V1_11 retention)
-- -----------------------------------------------------------------------------
CREATE TABLE catalog.gift_card_programs (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL REFERENCES tenant.organizations(id),
  name             text NOT NULL,
  currency_code    char(3) NOT NULL REFERENCES ref.currencies(code),
  preset_amounts_minor bigint[] NOT NULL DEFAULT '{}',
  min_amount_minor bigint NOT NULL CHECK (min_amount_minor > 0),
  max_amount_minor bigint NOT NULL,
  validity_months  smallint CHECK (validity_months IS NULL OR validity_months > 0),
  redeemable_on    text[] NOT NULL DEFAULT '{SERVICE,PRODUCT,TICKET}'
                     CHECK (redeemable_on <@ ARRAY['SERVICE','PRODUCT','TICKET','MEMBERSHIP','PACKAGE']::text[]),
  design_file_id   uuid REFERENCES platform.files(id),
  sell_online      boolean NOT NULL DEFAULT true,
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  CHECK (max_amount_minor >= min_amount_minor)
);

CREATE TABLE catalog.membership_plans (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL REFERENCES tenant.organizations(id),
  name_i18n         jsonb NOT NULL,
  description_i18n  jsonb NOT NULL DEFAULT '{}'::jsonb,
  billing           text NOT NULL CHECK (billing IN ('ONE_TIME','RECURRING')),
  interval_unit     text CHECK (interval_unit IN ('WEEK','MONTH','YEAR')),
  interval_count    smallint CHECK (interval_count IS NULL OR interval_count > 0),
  term_cycles       smallint CHECK (term_cycles IS NULL OR term_cycles > 0), -- minimum commitment
  price_minor       bigint NOT NULL CHECK (price_minor >= 0),
  currency_code     char(3) NOT NULL REFERENCES ref.currencies(code),
  tax_category_code text NOT NULL DEFAULT 'SERVICE' REFERENCES ref.tax_categories(code),
  sell_online       boolean NOT NULL DEFAULT true,
  status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  CHECK ((billing = 'RECURRING') = (interval_unit IS NOT NULL AND interval_count IS NOT NULL))
);

CREATE TABLE catalog.membership_benefits (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  plan_id            uuid NOT NULL,
  kind               text NOT NULL CHECK (kind IN ('SERVICE_CREDITS','PERCENT_DISCOUNT','FREE_ADDON','PRIORITY_BOOKING','EVENT_ACCESS','CLASS_CREDITS')),
  service_id         uuid,
  category_id        uuid REFERENCES ref.categories(id),
  quantity_per_cycle smallint CHECK (quantity_per_cycle IS NULL OR quantity_per_cycle > 0),
  percent_off        numeric(5,2) CHECK (percent_off IS NULL OR (percent_off > 0 AND percent_off <= 100)),
  rollover           boolean NOT NULL DEFAULT false,
  FOREIGN KEY (org_id, plan_id) REFERENCES catalog.membership_plans (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  CHECK (kind NOT IN ('SERVICE_CREDITS','CLASS_CREDITS') OR quantity_per_cycle IS NOT NULL),
  CHECK (kind <> 'PERCENT_DISCOUNT' OR percent_off IS NOT NULL)
);

-- Prepaid packages (e.g. "5 massages for the price of 4"), redeemed over time.
CREATE TABLE catalog.package_plans (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL REFERENCES tenant.organizations(id),
  name_i18n         jsonb NOT NULL,
  price_minor       bigint NOT NULL CHECK (price_minor >= 0),
  currency_code     char(3) NOT NULL REFERENCES ref.currencies(code),
  validity_days     smallint CHECK (validity_days IS NULL OR validity_days > 0),
  tax_category_code text NOT NULL DEFAULT 'SERVICE' REFERENCES ref.tax_categories(code),
  sell_online       boolean NOT NULL DEFAULT true,
  status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE catalog.package_plan_items (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  package_plan_id    uuid NOT NULL,
  service_id         uuid,
  product_variant_id uuid,
  quantity           smallint NOT NULL CHECK (quantity > 0),
  FOREIGN KEY (org_id, package_plan_id) REFERENCES catalog.package_plans (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id),
  CHECK (num_nonnulls(service_id, product_variant_id) = 1)
);
