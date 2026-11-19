-- =============================================================================
-- 14: billing — how the platform earns: subscriptions, transaction fees,
-- marketplace commission, metered usage (SMS/WhatsApp/AI), and platform
-- invoices to organizations. One engine for appointment and event businesses.
-- =============================================================================

CREATE TABLE billing.plans (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  code          text NOT NULL UNIQUE,            -- FREE, SOLO, TEAM, EVENTS_PRO, ENTERPRISE
  name_i18n     jsonb NOT NULL,
  pricing_unit  text NOT NULL CHECK (pricing_unit IN ('FREE','FLAT','PER_BOOKABLE_STAFF','PER_LOCATION')),
  is_public     boolean NOT NULL DEFAULT true,
  status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LEGACY','RETIRED')),
  sort_order    smallint NOT NULL DEFAULT 0
);

CREATE TABLE billing.plan_prices (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  plan_id       uuid NOT NULL REFERENCES billing.plans(id),
  market_id     uuid NOT NULL REFERENCES ref.markets(id),
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  interval_unit text NOT NULL CHECK (interval_unit IN ('MONTH','YEAR')),
  unit_amount_minor bigint NOT NULL CHECK (unit_amount_minor >= 0),
  valid_during  tstzrange NOT NULL DEFAULT tstzrange(now(), NULL),
  EXCLUDE USING gist (plan_id WITH =, market_id WITH =, currency_code WITH =, interval_unit WITH =, valid_during WITH &&)
);

-- Feature gates and included quotas: CAPABILITY:EVENTS, MAX_LOCATIONS=3, INCLUDED_SMS=50 ...
CREATE TABLE billing.plan_entitlements (
  plan_id       uuid NOT NULL REFERENCES billing.plans(id) ON DELETE CASCADE,
  feature_code  text NOT NULL,
  limit_value   integer,                         -- NULL = unlimited / boolean feature
  PRIMARY KEY (plan_id, feature_code)
);

CREATE TABLE billing.subscriptions (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL REFERENCES tenant.organizations(id),
  plan_id              uuid NOT NULL REFERENCES billing.plans(id),
  plan_price_id        uuid REFERENCES billing.plan_prices(id),
  quantity             integer NOT NULL DEFAULT 1 CHECK (quantity > 0),
  status               text NOT NULL DEFAULT 'TRIALING' CHECK (status IN ('TRIALING','ACTIVE','PAST_DUE','PAUSED','CANCELLED','EXPIRED')),
  current_period       tstzrange NOT NULL,
  trial_ends_at        timestamptz,
  cancel_at_period_end boolean NOT NULL DEFAULT false,
  collection           text NOT NULL DEFAULT 'NET_FROM_PAYOUTS' CHECK (collection IN ('NET_FROM_PAYOUTS','AUTO_CHARGE','INVOICE')),
  started_at           timestamptz NOT NULL DEFAULT now(),
  ended_at             timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);
CREATE UNIQUE INDEX subscriptions_one_live ON billing.subscriptions (org_id) WHERE status IN ('TRIALING','ACTIVE','PAST_DUE','PAUSED');

CREATE TABLE billing.subscription_addons (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  subscription_id uuid NOT NULL,
  addon_code      text NOT NULL,                 -- AI_CONCIERGE, ADVANCED_REPORTS, CUSTOM_DOMAIN, RESERVED_SEATING ...
  quantity        integer NOT NULL DEFAULT 1 CHECK (quantity > 0),
  unit_amount_minor bigint NOT NULL CHECK (unit_amount_minor >= 0),
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
  started_at      timestamptz NOT NULL DEFAULT now(),
  ended_at        timestamptz,
  FOREIGN KEY (org_id, subscription_id) REFERENCES billing.subscriptions (org_id, id)
);

-- -----------------------------------------------------------------------------
-- Transaction fees. Resolution: org override > plan-specific > market default,
-- at the order's placed_at instant. Overlapping rules for the same scope are
-- impossible (exclusion constraint).
-- -----------------------------------------------------------------------------
CREATE TABLE billing.fee_rules (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  market_id       uuid NOT NULL REFERENCES ref.markets(id),
  plan_id         uuid REFERENCES billing.plans(id),
  org_id          uuid REFERENCES tenant.organizations(id),
  fee_code        text NOT NULL CHECK (fee_code IN (
                    'MARKETPLACE_NEW_CLIENT',  -- one-time commission on a customer acquired via marketplace
                    'TICKET_SERVICE_FEE',      -- per paid ticket
                    'BOOKING_FEE',             -- per online appointment (optional, market dependent)
                    'PAYMENT_PROCESSING',      -- pass-through/markup of PSP cost
                    'PAYOUT_FEE','INSTANT_PAYOUT_FEE',
                    'SMS','WHATSAPP','MARKETING_EMAIL','AI_MINUTE','AI_MESSAGE','FEATURED_PLACEMENT')),
  payer           text NOT NULL CHECK (payer IN ('BUYER','SELLER')),
  applies_to      text[] NOT NULL DEFAULT '{}',  -- order line types; empty = n/a (usage fees)
  percent_bps     integer NOT NULL DEFAULT 0 CHECK (percent_bps BETWEEN 0 AND 10000),
  fixed_minor     bigint NOT NULL DEFAULT 0 CHECK (fixed_minor >= 0),
  min_minor       bigint CHECK (min_minor IS NULL OR min_minor >= 0),
  max_minor       bigint CHECK (max_minor IS NULL OR max_minor >= 0),
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  vat_applies     boolean NOT NULL DEFAULT true,
  valid_during    tstzrange NOT NULL,
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
  created_by      uuid REFERENCES iam.users(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CHECK (max_minor IS NULL OR min_minor IS NULL OR max_minor >= min_minor),
  CHECK (percent_bps > 0 OR fixed_minor > 0),
  EXCLUDE USING gist (
    market_id WITH =, fee_code WITH =, payer WITH =,
    (coalesce(plan_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
    (coalesce(org_id,  '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
    valid_during WITH &&) WHERE (status = 'ACTIVE')
);

-- Immutable record of every fee charged (what, why, by which rule, on which order).
CREATE TABLE billing.fee_assessments (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  fee_rule_id        uuid NOT NULL REFERENCES billing.fee_rules(id),
  fee_code           text NOT NULL,
  payer              text NOT NULL CHECK (payer IN ('BUYER','SELLER')),
  order_id           uuid,
  order_line_id      uuid,                       -- for BUYER fees: the BUYER_FEE line on the order
  base_minor         bigint NOT NULL CHECK (base_minor >= 0),
  amount_minor       bigint NOT NULL CHECK (amount_minor >= 0),
  vat_minor          bigint NOT NULL DEFAULT 0 CHECK (vat_minor >= 0),
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  status             text NOT NULL DEFAULT 'ASSESSED' CHECK (status IN ('ASSESSED','DEFERRED','WAIVED','REVERSED','INVOICED','SETTLED')),
  billing_invoice_id uuid,
  ledger_transaction_id uuid REFERENCES ledger.transactions(id),
  assessed_at        timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id)
);
-- The marketplace new-client commission is charged at most once per order.
CREATE UNIQUE INDEX fee_assessments_new_client_once ON billing.fee_assessments (order_id)
  WHERE fee_code = 'MARKETPLACE_NEW_CLIENT' AND status NOT IN ('REVERSED','WAIVED');

CREATE TABLE billing.usage_records (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  meter              text NOT NULL CHECK (meter IN ('SMS','WHATSAPP','MARKETING_EMAIL','AI_MINUTES','AI_MESSAGES','PUSH','STORAGE_GB')),
  quantity           numeric(14,3) NOT NULL CHECK (quantity > 0),
  occurred_at        timestamptz NOT NULL,
  source_ref         text NOT NULL,              -- e.g. delivery id; unique per meter for idempotency
  billing_invoice_id uuid,
  UNIQUE (meter, source_ref)
);
CREATE INDEX usage_records_unbilled_idx ON billing.usage_records (org_id, meter, occurred_at) WHERE billing_invoice_id IS NULL;

CREATE TABLE billing.invoices (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL REFERENCES tenant.organizations(id),
  number          text NOT NULL UNIQUE,
  period          daterange NOT NULL,
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  subtotal_minor  bigint NOT NULL DEFAULT 0 CHECK (subtotal_minor >= 0),
  tax_minor       bigint NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
  total_minor     bigint NOT NULL DEFAULT 0 CHECK (total_minor >= 0),
  collection      text NOT NULL CHECK (collection IN ('NET_FROM_PAYOUTS','AUTO_CHARGE','INVOICE')),
  status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','OPEN','PAID','VOID','UNCOLLECTIBLE')),
  due_at          timestamptz,
  paid_at         timestamptz,
  pdf_file_id     uuid REFERENCES platform.files(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  CHECK (total_minor = subtotal_minor + tax_minor)
);

CREATE TABLE billing.invoice_lines (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL,
  invoice_id        uuid NOT NULL,
  kind              text NOT NULL CHECK (kind IN ('SUBSCRIPTION','ADDON','USAGE','FEE','CREDIT','ADJUSTMENT')),
  description       text NOT NULL,
  quantity          numeric(14,3) NOT NULL DEFAULT 1,
  unit_amount_minor bigint NOT NULL,
  amount_minor      bigint NOT NULL,             -- negative for credits
  subscription_id   uuid,
  FOREIGN KEY (org_id, invoice_id) REFERENCES billing.invoices (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, subscription_id) REFERENCES billing.subscriptions (org_id, id)
);

ALTER TABLE billing.fee_assessments
  ADD CONSTRAINT fee_assessments_invoice_fk FOREIGN KEY (org_id, billing_invoice_id) REFERENCES billing.invoices (org_id, id);
ALTER TABLE billing.usage_records
  ADD CONSTRAINT usage_records_invoice_fk FOREIGN KEY (org_id, billing_invoice_id) REFERENCES billing.invoices (org_id, id);
ALTER TABLE payments.payment_intents
  ADD CONSTRAINT payment_intents_billing_invoice_fk FOREIGN KEY (org_id, billing_invoice_id) REFERENCES billing.invoices (org_id, id);
