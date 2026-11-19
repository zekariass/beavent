-- =============================================================================
-- 12: payments — provider-agnostic collection, refunds, payouts, reconciliation.
--
-- Model (see docs/04-payments-and-money.md):
--   providers ── capabilities (per country × currency × method)
--      │
--   provider_accounts (owned by PLATFORM or by an ORGANIZATION; sandbox/live)
--      │
--   routes (market × currency × method × channel → account, priority/failover)
--
--   payment_intent  = "this much must be collected for this purpose"
--   payment_attempt = one try with one provider/method (push prompt, redirect,
--                     QR, card, cash, gift card, manual bank transfer ...)
--   refunds, payouts, disputes, provider statements → reconciliation.
--
-- Adding a provider = implementing the adapter interface + inserting catalog
-- rows. No schema change, no product-module change.
-- =============================================================================

CREATE TABLE payments.providers (
  code         text PRIMARY KEY,                 -- CHAPA, TELEBIRR, MPESA_KE, PAWAPAY, FLUTTERWAVE, CASH ...
  name         text NOT NULL,
  kind         text NOT NULL CHECK (kind IN ('GATEWAY','MOBILE_MONEY_OPERATOR','AGGREGATOR','BANK','CARD_ACQUIRER','INTERNAL')),
  adapter_key  text NOT NULL,                    -- backend adapter implementation id
  website      text,
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEPRECATED','DISABLED')),
  notes        text
);

-- What the customer pays with (brand level). Internal tenders (cash, gift card) are methods too,
-- so split tender at POS is just several intents on one order.
CREATE TABLE payments.methods (
  code       text PRIMARY KEY,                   -- TELEBIRR, CBE_BIRR, MPESA, MTN_MOMO, AIRTEL_MONEY, CARD, BANK_TRANSFER, CASH, GIFT_CARD ...
  family     text NOT NULL CHECK (family IN ('MOBILE_MONEY','CARD','BANK_TRANSFER','BANK_APP','QR','USSD','CASH','INTERNAL_TENDER','BNPL','WALLET')),
  name       text NOT NULL,
  icon_key   text,
  is_internal boolean NOT NULL DEFAULT false     -- cash, gift card, store credit, package credit
);

CREATE TABLE payments.provider_capabilities (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  provider_code      text NOT NULL REFERENCES payments.providers(code),
  country_code       char(2) NOT NULL REFERENCES ref.countries(code),
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  method_code        text NOT NULL REFERENCES payments.methods(code),
  can_collect        boolean NOT NULL DEFAULT true,
  can_refund_api     boolean NOT NULL DEFAULT false, -- false => refunds go out as payouts
  can_partial_refund boolean NOT NULL DEFAULT false,
  can_payout         boolean NOT NULL DEFAULT false,
  can_split          boolean NOT NULL DEFAULT false, -- provider-native split / sub-accounts
  can_tokenize       boolean NOT NULL DEFAULT false, -- saved instrument for renewals
  can_preauthorize   boolean NOT NULL DEFAULT false,
  interaction        text NOT NULL CHECK (interaction IN ('REDIRECT','PUSH_PROMPT','OTP','QR','USSD_DIAL','CARD_FORM','IN_APP_SDK','MANUAL_REFERENCE','IN_PERSON')),
  confirmation       text NOT NULL CHECK (confirmation IN ('SYNC','WEBHOOK','POLL','WEBHOOK_AND_POLL','MANUAL')),
  min_amount_minor   bigint,
  max_amount_minor   bigint,
  settlement_days    smallint,
  status             text NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','SANDBOX','LIVE','SUSPENDED')),
  verified_at        timestamptz,                -- when we last proved this works live
  notes              text,
  -- One row per way of paying: e.g. Chapa telebirr via hosted checkout (REDIRECT) and via direct charge (PUSH_PROMPT).
  UNIQUE (provider_code, country_code, currency_code, method_code, interaction),
  CHECK (status <> 'LIVE' OR verified_at IS NOT NULL)
);

CREATE TABLE payments.provider_accounts (
  id                    uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  provider_code         text NOT NULL REFERENCES payments.providers(code),
  owner                 text NOT NULL CHECK (owner IN ('PLATFORM','ORGANIZATION')),
  org_id                uuid REFERENCES tenant.organizations(id),
  market_id             uuid NOT NULL REFERENCES ref.markets(id),
  environment           text NOT NULL CHECK (environment IN ('SANDBOX','LIVE')),
  display_name          text NOT NULL,
  merchant_reference    text,                    -- provider-side merchant / short code / till
  -- For PROVIDER_SPLIT collection: the seller's sub-account under the platform master account.
  parent_account_id     uuid REFERENCES payments.provider_accounts(id),
  sub_account_reference text,
  credentials_secret_ref text,                   -- secret-manager path; never the secret itself
  webhook_secret_ref    text,
  settlement_currency   char(3) NOT NULL REFERENCES ref.currencies(code),
  status                text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','DISABLED')),
  health                text NOT NULL DEFAULT 'UNKNOWN' CHECK (health IN ('UNKNOWN','HEALTHY','DEGRADED','DOWN')),
  health_checked_at     timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  CHECK ((owner = 'ORGANIZATION') = (org_id IS NOT NULL)),
  CHECK (parent_account_id IS NULL OR (sub_account_reference IS NOT NULL AND org_id IS NOT NULL)),
  CHECK (parent_account_id IS NOT NULL OR provider_code IN ('CASH','INTERNAL','MANUAL_BANK') OR credentials_secret_ref IS NOT NULL)
);

-- Routing table. The router picks active routes for (market, currency, method, channel) by
-- priority, skips accounts whose health is DOWN, respects amount limits, and fails over.
-- An org-specific route (seller's own merchant account) overrides platform routes.
CREATE TABLE payments.routes (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  market_id           uuid NOT NULL REFERENCES ref.markets(id),
  org_id              uuid REFERENCES tenant.organizations(id),
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  method_code         text NOT NULL REFERENCES payments.methods(code),
  channel             text NOT NULL DEFAULT 'ANY' CHECK (channel IN ('ANY','ONLINE','IN_PERSON')),
  provider_account_id uuid NOT NULL REFERENCES payments.provider_accounts(id),
  priority            smallint NOT NULL DEFAULT 100,
  traffic_weight      smallint NOT NULL DEFAULT 100 CHECK (traffic_weight BETWEEN 0 AND 100),
  min_amount_minor    bigint,
  max_amount_minor    bigint,
  is_active           boolean NOT NULL DEFAULT true,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (market_id, org_id, currency_code, method_code, channel, provider_account_id)
);

-- How money flows for a seller in a market.
--   PLATFORM_COLLECTS : platform's licensed/partner account collects; seller paid by payout.
--   PROVIDER_SPLIT    : provider splits at source into seller sub-account + platform fee.
--   SELLER_DIRECT     : seller's own merchant account collects; platform fees invoiced/netted.
CREATE TABLE payments.org_settings (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL REFERENCES tenant.organizations(id),
  market_id            uuid NOT NULL REFERENCES ref.markets(id),
  collection_mode      text NOT NULL CHECK (collection_mode IN ('PLATFORM_COLLECTS','PROVIDER_SPLIT','SELLER_DIRECT')),
  accepted_methods     text[] NOT NULL DEFAULT '{}',
  accept_pay_at_venue  boolean NOT NULL DEFAULT true,
  pass_processing_fee  boolean NOT NULL DEFAULT false,
  payout_schedule      text NOT NULL DEFAULT 'WEEKLY' CHECK (payout_schedule IN ('DAILY','WEEKLY','MONTHLY','MANUAL')),
  payout_weekday       smallint CHECK (payout_weekday BETWEEN 1 AND 7),
  min_payout_minor     bigint NOT NULL DEFAULT 0 CHECK (min_payout_minor >= 0),
  statement_descriptor text,
  -- PROVIDER_SPLIT: the seller is paid at source, but refunds and disputes are debited from the
  -- platform's provider balance. The seller share becomes a SELLER_RECEIVABLE that is recovered
  -- either by adding a capped amount to the split of the seller's next transactions, or by invoice.
  recovery_mode        text NOT NULL DEFAULT 'SPLIT_TOP_UP' CHECK (recovery_mode IN ('SPLIT_TOP_UP','INVOICE')),
  recovery_cap_bps     integer NOT NULL DEFAULT 2000 CHECK (recovery_cap_bps BETWEEN 0 AND 10000),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, market_id)
);

-- -----------------------------------------------------------------------------
-- Collection
-- -----------------------------------------------------------------------------
CREATE TABLE payments.payment_intents (
  id                    uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                uuid NOT NULL REFERENCES tenant.organizations(id),
  purpose               text NOT NULL CHECK (purpose IN ('ORDER','CUSTOMER_INVOICE','PAYMENT_LINK','MEMBERSHIP_RENEWAL','PLATFORM_BILLING','NO_SHOW_FEE')),
  order_id              uuid,
  payment_schedule_id   uuid,
  customer_invoice_id   uuid,
  billing_invoice_id    uuid,                    -- FK in V1_14
  customer_membership_id uuid,
  collection_mode       text NOT NULL CHECK (collection_mode IN ('PLATFORM_COLLECTS','PROVIDER_SPLIT','SELLER_DIRECT','INTERNAL')),
  amount_minor          bigint NOT NULL CHECK (amount_minor > 0),
  currency_code         char(3) NOT NULL REFERENCES ref.currencies(code),
  captured_minor        bigint NOT NULL DEFAULT 0 CHECK (captured_minor >= 0),
  refunded_minor        bigint NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
  -- Presentment (e.g. diaspora buyer charged in USD for an ETB-priced order). Snapshot only.
  presentment_currency  char(3) REFERENCES ref.currencies(code),
  presentment_amount_minor bigint,
  fx_rate_id            uuid REFERENCES ref.fx_rates(id),
  status                text NOT NULL DEFAULT 'REQUIRES_METHOD' CHECK (status IN ('REQUIRES_METHOD','PROCESSING','REQUIRES_ACTION','AUTHORIZED','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),
  payer_user_id         uuid REFERENCES iam.users(id),
  payer_customer_id     uuid,
  idempotency_key       text NOT NULL,
  expires_at            timestamptz,
  succeeded_at          timestamptz,
  metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
  version               integer NOT NULL DEFAULT 0,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, idempotency_key),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, payment_schedule_id) REFERENCES commerce.payment_schedule (org_id, id),
  FOREIGN KEY (org_id, customer_invoice_id) REFERENCES commerce.invoices (org_id, id),
  FOREIGN KEY (org_id, customer_membership_id) REFERENCES retention.customer_memberships (org_id, id),
  FOREIGN KEY (org_id, payer_customer_id) REFERENCES crm.customers (org_id, id),
  CHECK (captured_minor <= amount_minor),
  CHECK (refunded_minor <= captured_minor),
  CHECK (status <> 'SUCCEEDED' OR (captured_minor > 0 AND succeeded_at IS NOT NULL)),
  CHECK ((presentment_currency IS NULL) = (presentment_amount_minor IS NULL)),
  CHECK (presentment_currency IS NULL OR fx_rate_id IS NOT NULL),
  CHECK (CASE purpose
           WHEN 'ORDER'              THEN order_id IS NOT NULL
           WHEN 'NO_SHOW_FEE'        THEN order_id IS NOT NULL
           WHEN 'CUSTOMER_INVOICE'   THEN customer_invoice_id IS NOT NULL
           WHEN 'PLATFORM_BILLING'   THEN billing_invoice_id IS NOT NULL
           WHEN 'MEMBERSHIP_RENEWAL' THEN customer_membership_id IS NOT NULL
           ELSE true END)
);
CREATE INDEX payment_intents_order_idx ON payments.payment_intents (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX payment_intents_pending_idx ON payments.payment_intents (expires_at) WHERE status IN ('PROCESSING','REQUIRES_ACTION');

CREATE TABLE payments.instruments (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  user_id             uuid NOT NULL REFERENCES iam.users(id) ON DELETE CASCADE,
  provider_account_id uuid NOT NULL REFERENCES payments.provider_accounts(id),
  method_code         text NOT NULL REFERENCES payments.methods(code),
  token_secret_ref    text NOT NULL,             -- provider token in the vault; never PAN/PIN
  display_label       text NOT NULL,             -- 'Visa •••• 4242', 'Telebirr •••• 1234'
  expires_on          date,
  is_default          boolean NOT NULL DEFAULT false,
  status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
  created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payments.payment_attempts (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  intent_id           uuid NOT NULL,
  provider_account_id uuid REFERENCES payments.provider_accounts(id),
  method_code         text NOT NULL REFERENCES payments.methods(code),
  interaction         text NOT NULL CHECK (interaction IN ('REDIRECT','PUSH_PROMPT','OTP','QR','USSD_DIAL','CARD_FORM','IN_APP_SDK','MANUAL_REFERENCE','IN_PERSON','INTERNAL')),
  amount_minor        bigint NOT NULL CHECK (amount_minor > 0),
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  status              text NOT NULL DEFAULT 'CREATED' CHECK (status IN (
                        'CREATED','AWAITING_CUSTOMER','PROCESSING','AWAITING_VERIFICATION',
                        'SUCCEEDED','SUCCEEDED_DUPLICATE','FAILED','CANCELLED','EXPIRED','UNKNOWN')),
  provider_reference  text,
  provider_status_raw text,
  next_action         jsonb,                     -- redirect URL / USSD string / QR payload / instructions
  payer_account_masked text,                     -- '+2519•••••234'
  instrument_id       uuid REFERENCES payments.instruments(id),
  provider_fee_minor  bigint CHECK (provider_fee_minor IS NULL OR provider_fee_minor >= 0),
  failure_code        text,                      -- normalized: INSUFFICIENT_FUNDS, TIMEOUT, CUSTOMER_DECLINED, LIMIT_EXCEEDED ...
  failure_message     text,
  -- Internal tenders
  cash_session_id     uuid,
  gift_card_id        uuid,
  -- Manual bank transfer: payer uploads proof, staff/finance verifies against the bank statement.
  manual_reference    text,
  proof_file_id       uuid,
  verified_by         uuid REFERENCES iam.users(id),
  verified_at         timestamptz,
  idempotency_key     text NOT NULL UNIQUE,
  poll_count          smallint NOT NULL DEFAULT 0,
  next_poll_at        timestamptz,
  initiated_at        timestamptz NOT NULL DEFAULT now(),
  completed_at        timestamptz,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, intent_id) REFERENCES payments.payment_intents (org_id, id),
  FOREIGN KEY (org_id, cash_session_id) REFERENCES commerce.cash_drawer_sessions (org_id, id),
  FOREIGN KEY (org_id, gift_card_id) REFERENCES retention.gift_cards (org_id, id),
  FOREIGN KEY (org_id, proof_file_id) REFERENCES platform.files (org_id, id),
  UNIQUE NULLS DISTINCT (provider_account_id, provider_reference),
  CHECK (method_code <> 'CASH' OR cash_session_id IS NOT NULL),
  CHECK (method_code <> 'GIFT_CARD' OR gift_card_id IS NOT NULL),
  CHECK (status <> 'AWAITING_VERIFICATION' OR interaction = 'MANUAL_REFERENCE'),
  CHECK (interaction <> 'MANUAL_REFERENCE' OR status <> 'SUCCEEDED' OR verified_at IS NOT NULL),
  CHECK (provider_account_id IS NOT NULL OR interaction IN ('INTERNAL','IN_PERSON','MANUAL_REFERENCE'))
);
-- An intent is satisfied by exactly one attempt. A second success (customer approved two
-- mobile-money prompts) is recorded as SUCCEEDED_DUPLICATE and auto-refunded.
CREATE UNIQUE INDEX payment_attempts_one_success ON payments.payment_attempts (intent_id) WHERE status = 'SUCCEEDED';
CREATE INDEX payment_attempts_poll_idx ON payments.payment_attempts (next_poll_at) WHERE status IN ('AWAITING_CUSTOMER','PROCESSING','UNKNOWN');

-- Raw provider callbacks, persisted before processing. Deduplicated per account.
CREATE TABLE payments.webhook_events (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  provider_code       text NOT NULL REFERENCES payments.providers(code),
  provider_account_id uuid REFERENCES payments.provider_accounts(id),
  dedupe_key          text NOT NULL,             -- provider event id, or hash of the canonical payload when none exists
  event_type          text,
  signature_valid     boolean NOT NULL,
  payload             jsonb NOT NULL,
  headers             jsonb NOT NULL DEFAULT '{}'::jsonb, -- redacted
  received_at         timestamptz NOT NULL DEFAULT now(),
  status              text NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','FAILED','DEAD')),
  attempts            smallint NOT NULL DEFAULT 0,
  processed_at        timestamptz,
  last_error          text,
  UNIQUE NULLS NOT DISTINCT (provider_code, provider_account_id, dedupe_key)
);
CREATE INDEX webhook_events_pending_idx ON payments.webhook_events (received_at) WHERE status IN ('RECEIVED','FAILED');

CREATE TABLE payments.payment_links (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  intent_id           uuid NOT NULL,
  token_hash          text NOT NULL UNIQUE,
  sent_via            text[] NOT NULL DEFAULT '{}',
  expires_at          timestamptz NOT NULL,
  opened_at           timestamptz,
  status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAID','EXPIRED','REVOKED')),
  created_by          uuid REFERENCES iam.users(id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, intent_id) REFERENCES payments.payment_intents (org_id, id)
);

-- -----------------------------------------------------------------------------
-- Payouts (to sellers, and to customers when a refund cannot go back to source)
-- -----------------------------------------------------------------------------
CREATE TABLE payments.payout_accounts (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  legal_entity_id     uuid NOT NULL,
  kind                text NOT NULL CHECK (kind IN ('BANK_ACCOUNT','MOBILE_MONEY')),
  country_code        char(2) NOT NULL REFERENCES ref.countries(code),
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  institution_code    text NOT NULL,             -- bank code / MNO code
  institution_name    text NOT NULL,
  account_name        text NOT NULL,
  account_number_masked text NOT NULL,
  details_secret_ref  text NOT NULL,             -- full account number lives in the vault
  verification_status text NOT NULL DEFAULT 'UNVERIFIED' CHECK (verification_status IN ('UNVERIFIED','PENDING','VERIFIED','FAILED')),
  verified_via        text CHECK (verified_via IN ('NAME_ENQUIRY','MICRO_DEPOSIT','BANK_LETTER','MANUAL')),
  -- Fraud control: payouts to a newly added/changed account wait for a cooling-off period.
  payouts_allowed_after timestamptz NOT NULL DEFAULT now() + interval '72 hours',
  is_default          boolean NOT NULL DEFAULT false,
  status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id)
);
CREATE UNIQUE INDEX payout_accounts_one_default ON payments.payout_accounts (org_id, currency_code) WHERE is_default AND status = 'ACTIVE';

CREATE TABLE payments.payouts (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL REFERENCES tenant.organizations(id),
  recipient_kind      text NOT NULL CHECK (recipient_kind IN ('ORGANIZATION','CUSTOMER','STAFF')),
  payout_account_id   uuid,
  recipient_details_secret_ref text,             -- customer/staff wallet for refund-by-payout / pay runs
  recipient_masked    text,
  provider_account_id uuid REFERENCES payments.provider_accounts(id),
  purpose             text NOT NULL CHECK (purpose IN ('SETTLEMENT','INSTANT_SETTLEMENT','REFUND_DISBURSEMENT','STAFF_PAY')),
  amount_minor        bigint NOT NULL CHECK (amount_minor > 0),
  fee_minor           bigint NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  status              text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','PENDING_APPROVAL','APPROVED','PROCESSING','PAID','FAILED','CANCELLED','RETURNED')),
  scheduled_for       timestamptz NOT NULL DEFAULT now(),
  requires_approval   boolean NOT NULL DEFAULT false,
  approved_by         uuid REFERENCES iam.users(id),
  approved_at         timestamptz,
  provider_reference  text,
  failure_code        text,
  failure_message     text,
  idempotency_key     text NOT NULL UNIQUE,
  paid_at             timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, payout_account_id) REFERENCES payments.payout_accounts (org_id, id),
  CHECK ((recipient_kind = 'ORGANIZATION') = (payout_account_id IS NOT NULL)),
  CHECK (recipient_kind = 'ORGANIZATION' OR recipient_details_secret_ref IS NOT NULL),
  CHECK (fee_minor < amount_minor),
  CHECK (NOT requires_approval OR status NOT IN ('APPROVED','PROCESSING','PAID') OR approved_by IS NOT NULL)
);
CREATE INDEX payouts_due_idx ON payments.payouts (scheduled_for) WHERE status IN ('SCHEDULED','APPROVED');

-- -----------------------------------------------------------------------------
-- Refunds
-- -----------------------------------------------------------------------------
CREATE TABLE payments.refunds (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  intent_id          uuid NOT NULL,
  attempt_id         uuid NOT NULL,              -- the successful attempt being reversed
  order_id           uuid,
  amount_minor       bigint NOT NULL CHECK (amount_minor > 0),
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  reason             text NOT NULL CHECK (reason IN ('CUSTOMER_REQUEST','EVENT_CANCELLED','EVENT_POSTPONED','APPOINTMENT_CANCELLED','DUPLICATE_PAYMENT','FRAUD','GOODWILL','DISPUTE_LOST','ORDER_ADJUSTMENT')),
  method             text NOT NULL CHECK (method IN ('ORIGINAL_METHOD','PAYOUT','STORE_CREDIT','GIFT_CARD','CASH','MANUAL_BANK_TRANSFER')),
  status             text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','PROCESSING','SUCCEEDED','FAILED','REJECTED','CANCELLED')),
  requested_by       text NOT NULL CHECK (requested_by IN ('CUSTOMER','ORGANIZATION','PLATFORM','SYSTEM')),
  requester_user_id  uuid REFERENCES iam.users(id),
  decided_by         uuid REFERENCES iam.users(id),
  decided_at         timestamptz,
  decision_note      text,
  payout_id          uuid,
  cash_session_id    uuid,
  provider_reference text,
  failure_message    text,
  idempotency_key    text NOT NULL UNIQUE,
  succeeded_at       timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, intent_id) REFERENCES payments.payment_intents (org_id, id),
  FOREIGN KEY (org_id, attempt_id) REFERENCES payments.payment_attempts (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, payout_id) REFERENCES payments.payouts (org_id, id),
  FOREIGN KEY (org_id, cash_session_id) REFERENCES commerce.cash_drawer_sessions (org_id, id),
  CHECK (method <> 'PAYOUT' OR status NOT IN ('PROCESSING','SUCCEEDED') OR payout_id IS NOT NULL),
  CHECK (method <> 'CASH' OR status <> 'SUCCEEDED' OR cash_session_id IS NOT NULL),
  CHECK ((status = 'SUCCEEDED') = (succeeded_at IS NOT NULL))
);

CREATE TABLE payments.refund_lines (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  refund_id     uuid NOT NULL,
  order_line_id uuid NOT NULL,
  quantity      integer NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
  FOREIGN KEY (org_id, refund_id) REFERENCES payments.refunds (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  UNIQUE (refund_id, order_line_id)
);

-- Refund cap: committed refunds (approved/processing/succeeded) can never exceed what was captured.
CREATE FUNCTION payments.guard_refund_total() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_captured bigint;
  v_committed bigint;
BEGIN
  IF NEW.status NOT IN ('APPROVED','PROCESSING','SUCCEEDED') THEN
    RETURN NEW;
  END IF;
  SELECT captured_minor INTO v_captured
    FROM payments.payment_intents WHERE id = NEW.intent_id FOR UPDATE;
  SELECT coalesce(sum(amount_minor), 0) INTO v_committed
    FROM payments.refunds
   WHERE intent_id = NEW.intent_id AND id <> NEW.id
     AND status IN ('APPROVED','PROCESSING','SUCCEEDED');
  IF v_committed + NEW.amount_minor > v_captured THEN
    RAISE EXCEPTION 'refund % exceeds refundable amount for intent % (captured %, already committed %)',
      NEW.amount_minor, NEW.intent_id, v_captured, v_committed
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER refunds_10_guard_total BEFORE INSERT OR UPDATE OF status, amount_minor ON payments.refunds
  FOR EACH ROW EXECUTE FUNCTION payments.guard_refund_total();

-- (Triggers fire in name order: guard first, then apply.)
-- A succeeded refund is final: it updates the intent and can never change again.
CREATE FUNCTION payments.apply_refund_success() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.status = 'SUCCEEDED' THEN
    RAISE EXCEPTION 'refund % already succeeded and is immutable', OLD.id USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.status = 'SUCCEEDED' THEN
    UPDATE payments.payment_intents
       SET refunded_minor = refunded_minor + NEW.amount_minor
     WHERE id = NEW.intent_id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER refunds_20_apply_success BEFORE INSERT OR UPDATE ON payments.refunds
  FOR EACH ROW EXECUTE FUNCTION payments.apply_refund_success();

-- Keep order.paid_minor / refunded_minor / payment_status in step with its intents.
CREATE FUNCTION payments.sync_order_payment_totals() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  d_paid bigint := NEW.captured_minor - CASE WHEN TG_OP = 'UPDATE' THEN OLD.captured_minor ELSE 0 END;
  d_refunded bigint := NEW.refunded_minor - CASE WHEN TG_OP = 'UPDATE' THEN OLD.refunded_minor ELSE 0 END;
BEGIN
  IF NEW.order_id IS NULL OR (d_paid = 0 AND d_refunded = 0) THEN
    RETURN NEW;
  END IF;
  UPDATE commerce.orders o
     SET paid_minor = o.paid_minor + d_paid,
         refunded_minor = o.refunded_minor + d_refunded,
         payment_status = CASE
           WHEN o.refunded_minor + d_refunded > 0 AND o.refunded_minor + d_refunded >= o.paid_minor + d_paid THEN 'REFUNDED'
           WHEN o.refunded_minor + d_refunded > 0 THEN 'PARTIALLY_REFUNDED'
           WHEN o.paid_minor + d_paid >= o.total_minor THEN 'PAID'
           WHEN o.paid_minor + d_paid > 0 THEN 'PARTIALLY_PAID'
           ELSE 'UNPAID' END
   WHERE o.id = NEW.order_id AND o.org_id = NEW.org_id;
  RETURN NEW;
END $$;
CREATE TRIGGER payment_intents_sync_order AFTER INSERT OR UPDATE OF captured_minor, refunded_minor ON payments.payment_intents
  FOR EACH ROW EXECUTE FUNCTION payments.sync_order_payment_totals();

-- -----------------------------------------------------------------------------
-- Disputes and reconciliation
-- -----------------------------------------------------------------------------
CREATE TABLE payments.disputes (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  attempt_id         uuid NOT NULL,
  provider_reference text,
  amount_minor       bigint NOT NULL CHECK (amount_minor > 0),
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  reason             text,
  status             text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','EVIDENCE_REQUIRED','UNDER_REVIEW','WON','LOST','ACCEPTED')),
  evidence_due_at    timestamptz,
  evidence           jsonb NOT NULL DEFAULT '{}'::jsonb,
  opened_at          timestamptz NOT NULL DEFAULT now(),
  closed_at          timestamptz,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, attempt_id) REFERENCES payments.payment_attempts (org_id, id)
);

CREATE TABLE payments.provider_statements (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  provider_account_id uuid NOT NULL REFERENCES payments.provider_accounts(id),
  period              daterange NOT NULL,
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  source              text NOT NULL CHECK (source IN ('API','SFTP','UPLOAD','EMAIL')),
  file_id             uuid REFERENCES platform.files(id),
  opening_balance_minor bigint,
  closing_balance_minor bigint,
  status              text NOT NULL DEFAULT 'IMPORTED' CHECK (status IN ('IMPORTED','RECONCILING','RECONCILED','EXCEPTIONS')),
  imported_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider_account_id, period, currency_code)
);

CREATE TABLE payments.statement_lines (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  statement_id       uuid NOT NULL REFERENCES payments.provider_statements(id) ON DELETE CASCADE,
  provider_reference text,
  line_type          text NOT NULL CHECK (line_type IN ('COLLECTION','REFUND','FEE','PAYOUT','REVERSAL','CHARGEBACK','ADJUSTMENT','SETTLEMENT')),
  amount_minor       bigint NOT NULL,            -- signed as on the statement
  fee_minor          bigint NOT NULL DEFAULT 0,
  occurred_at        timestamptz NOT NULL,
  matched_attempt_id uuid REFERENCES payments.payment_attempts(id),
  matched_refund_id  uuid REFERENCES payments.refunds(id),
  matched_payout_id  uuid REFERENCES payments.payouts(id),
  match_status       text NOT NULL DEFAULT 'UNMATCHED' CHECK (match_status IN ('UNMATCHED','MATCHED','AMOUNT_MISMATCH','MISSING_INTERNALLY','IGNORED')),
  resolution_note    text,
  CHECK (num_nonnulls(matched_attempt_id, matched_refund_id, matched_payout_id) <= 1)
);
CREATE INDEX statement_lines_unmatched_idx ON payments.statement_lines (statement_id) WHERE match_status <> 'MATCHED';

-- Late FKs into payments.
ALTER TABLE commerce.cash_movements
  ADD CONSTRAINT cash_movements_attempt_fk FOREIGN KEY (org_id, payment_attempt_id) REFERENCES payments.payment_attempts (org_id, id),
  ADD CONSTRAINT cash_movements_refund_fk  FOREIGN KEY (org_id, refund_id) REFERENCES payments.refunds (org_id, id);
ALTER TABLE retention.gift_card_entries
  ADD CONSTRAINT gift_card_entries_attempt_fk FOREIGN KEY (org_id, payment_attempt_id) REFERENCES payments.payment_attempts (org_id, id);
ALTER TABLE retention.customer_balance_entries
  ADD CONSTRAINT customer_balance_entries_refund_fk  FOREIGN KEY (org_id, refund_id) REFERENCES payments.refunds (org_id, id),
  ADD CONSTRAINT customer_balance_entries_attempt_fk FOREIGN KEY (org_id, payment_attempt_id) REFERENCES payments.payment_attempts (org_id, id);
ALTER TABLE retention.customer_memberships
  ADD CONSTRAINT customer_memberships_instrument_fk FOREIGN KEY (instrument_id) REFERENCES payments.instruments(id);
