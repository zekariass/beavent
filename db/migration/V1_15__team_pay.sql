-- =============================================================================
-- 15: team pay — tips, commissions, pay runs.
-- This computes and records what staff EARNED (wages, commission, tips) and how
-- it was paid. Statutory payroll (income tax, pension) is exported to local
-- payroll/accounting software rather than re-implemented per country.
-- =============================================================================

CREATE TABLE team.tip_allocations (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  order_line_id uuid NOT NULL,                   -- the TIP line
  staff_id      uuid NOT NULL,
  amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  pay_run_item_id uuid,
  UNIQUE (order_line_id, staff_id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id)
);

CREATE TABLE team.commission_rules (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL REFERENCES tenant.organizations(id),
  staff_id       uuid,                           -- NULL = default for everyone
  applies_to     text NOT NULL CHECK (applies_to IN ('SERVICE','PRODUCT','TICKET','GIFT_CARD','MEMBERSHIP','PACKAGE','ALL')),
  service_id     uuid,
  category_id    uuid REFERENCES ref.categories(id),
  basis          text NOT NULL DEFAULT 'NET_OF_DISCOUNT_EX_TAX' CHECK (basis IN ('NET_OF_DISCOUNT_EX_TAX','GROSS_EX_TAX')),
  calculation    text NOT NULL CHECK (calculation IN ('PERCENT','FIXED_PER_UNIT','TIERED_PERCENT')),
  percent        numeric(5,2) CHECK (percent IS NULL OR percent BETWEEN 0 AND 100),
  fixed_minor    bigint CHECK (fixed_minor IS NULL OR fixed_minor >= 0),
  currency_code  char(3) REFERENCES ref.currencies(code),
  tiers          jsonb,                          -- [{"from_minor":0,"percent":30},{"from_minor":5000000,"percent":40}]
  requested_staff_bonus_percent numeric(5,2),    -- extra when the customer requested this professional
  priority       smallint NOT NULL DEFAULT 100,
  valid_during   daterange NOT NULL DEFAULT daterange(current_date, NULL),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  CHECK (calculation <> 'PERCENT' OR percent IS NOT NULL),
  CHECK (calculation <> 'FIXED_PER_UNIT' OR (fixed_minor IS NOT NULL AND currency_code IS NOT NULL)),
  CHECK (calculation <> 'TIERED_PERCENT' OR tiers IS NOT NULL)
);

CREATE TABLE team.pay_runs (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL REFERENCES tenant.organizations(id),
  legal_entity_id uuid NOT NULL,
  period          daterange NOT NULL,
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','CALCULATED','APPROVED','PAID','VOID')),
  total_gross_minor bigint NOT NULL DEFAULT 0 CHECK (total_gross_minor >= 0),
  total_net_minor   bigint NOT NULL DEFAULT 0,
  approved_by     uuid REFERENCES iam.users(id),
  approved_at     timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id),
  EXCLUDE USING gist (legal_entity_id WITH =, period WITH &&) WHERE (status <> 'VOID'),
  CHECK (status NOT IN ('APPROVED','PAID') OR approved_by IS NOT NULL)
);

CREATE TABLE team.pay_run_items (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  pay_run_id         uuid NOT NULL,
  staff_id           uuid NOT NULL,
  wages_minor        bigint NOT NULL DEFAULT 0 CHECK (wages_minor >= 0),
  commission_minor   bigint NOT NULL DEFAULT 0 CHECK (commission_minor >= 0),
  tips_minor         bigint NOT NULL DEFAULT 0 CHECK (tips_minor >= 0),
  adjustments_minor  bigint NOT NULL DEFAULT 0,  -- signed; detail in pay_run_adjustments
  deductions_minor   bigint NOT NULL DEFAULT 0 CHECK (deductions_minor >= 0), -- chair rent, advances
  net_minor          bigint NOT NULL,
  paid_minor         bigint NOT NULL DEFAULT 0 CHECK (paid_minor >= 0),
  UNIQUE (org_id, id),
  UNIQUE (pay_run_id, staff_id),
  FOREIGN KEY (org_id, pay_run_id) REFERENCES team.pay_runs (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  CHECK (net_minor = wages_minor + commission_minor + tips_minor + adjustments_minor - deductions_minor),
  CHECK (paid_minor <= GREATEST(net_minor, 0))
);

CREATE TABLE team.pay_run_adjustments (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  pay_run_item_id  uuid NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('BONUS','ALLOWANCE','REIMBURSEMENT','ADVANCE_RECOVERY','CORRECTION','OTHER')),
  amount_minor     bigint NOT NULL CHECK (amount_minor <> 0),
  note             text NOT NULL,
  created_by       uuid REFERENCES iam.users(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, pay_run_item_id) REFERENCES team.pay_run_items (org_id, id) ON DELETE CASCADE
);

CREATE TABLE team.pay_run_payments (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  pay_run_item_id uuid NOT NULL,
  method          text NOT NULL CHECK (method IN ('CASH','BANK_TRANSFER','MOBILE_MONEY','PLATFORM_PAYOUT')),
  amount_minor    bigint NOT NULL CHECK (amount_minor > 0),
  payout_id       uuid,
  reference       text,
  paid_at         timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, pay_run_item_id) REFERENCES team.pay_run_items (org_id, id),
  FOREIGN KEY (org_id, payout_id) REFERENCES payments.payouts (org_id, id),
  CHECK ((method = 'PLATFORM_PAYOUT') = (payout_id IS NOT NULL))
);

-- Commission ledger: one entry per earning line, reversed (not edited) on refund.
CREATE TABLE team.commission_entries (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  staff_id        uuid NOT NULL,
  order_line_id   uuid NOT NULL,
  rule_id         uuid REFERENCES team.commission_rules(id),
  base_minor      bigint NOT NULL,
  amount_minor    bigint NOT NULL,               -- negative for reversals
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  kind            text NOT NULL DEFAULT 'EARNED' CHECK (kind IN ('EARNED','REVERSED','ADJUSTED')),
  reverses_entry_id uuid UNIQUE,
  pay_run_item_id uuid,
  earned_at       timestamptz NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, reverses_entry_id) REFERENCES team.commission_entries (org_id, id),
  FOREIGN KEY (org_id, pay_run_item_id) REFERENCES team.pay_run_items (org_id, id),
  CHECK ((kind = 'REVERSED') = (reverses_entry_id IS NOT NULL))
);
CREATE UNIQUE INDEX commission_entries_earned_once ON team.commission_entries (order_line_id, staff_id) WHERE kind = 'EARNED';

ALTER TABLE team.tip_allocations
  ADD CONSTRAINT tip_allocations_pay_item_fk FOREIGN KEY (org_id, pay_run_item_id) REFERENCES team.pay_run_items (org_id, id);
