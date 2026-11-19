-- =============================================================================
-- 13: ledger — one double-entry ledger for all money the platform touches.
-- Amounts are signed minor units: positive = debit, negative = credit.
-- Every transaction balances to zero per currency (deferred check at commit).
-- Entries are immutable; corrections are reversal transactions.
--
-- Typical accounts (per currency):
--   PROVIDER_CLEARING (per provider account) — money held at a PSP/MNO for us
--   SELLER_PENDING / SELLER_AVAILABLE / SELLER_RESERVE (per organization)
--   SELLER_FEES_RECEIVABLE (per organization, SELLER_DIRECT mode)
--   SELLER_RECEIVABLE (per organization: refunds/disputes the platform funded for a
--     PROVIDER_SPLIT seller, and platform fees on cash sales; recovered via split top-up or invoice)
--   PLATFORM_FEE_REVENUE, PLATFORM_PROCESSING_COST, PLATFORM_PROMO_EXPENSE
--   TAX_PAYABLE (VAT on platform fees), PAYOUTS_IN_TRANSIT, REFUNDS_PAYABLE,
--   CUSTOMER_REFUND_PAYABLE, SUSPENSE (unmatched statement lines)
-- =============================================================================

CREATE TABLE ledger.accounts (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  owner_type          text NOT NULL CHECK (owner_type IN ('PLATFORM','ORGANIZATION','PROVIDER_ACCOUNT')),
  org_id              uuid REFERENCES tenant.organizations(id),
  provider_account_id uuid REFERENCES payments.provider_accounts(id),
  code                text NOT NULL CHECK (code IN (
                        'PROVIDER_CLEARING','SELLER_PENDING','SELLER_AVAILABLE','SELLER_RESERVE',
                        'SELLER_FEES_RECEIVABLE','PLATFORM_FEE_REVENUE','PLATFORM_PROCESSING_COST',
                        'PLATFORM_PROMO_EXPENSE','TAX_PAYABLE','PAYOUTS_IN_TRANSIT','REFUNDS_PAYABLE',
                        'CUSTOMER_REFUND_PAYABLE','CASH_AT_SELLER','SELLER_RECEIVABLE','SUSPENSE')),
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  normal_side         text NOT NULL CHECK (normal_side IN ('DEBIT','CREDIT')),
  status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (owner_type, org_id, provider_account_id, code, currency_code),
  CHECK ((owner_type = 'ORGANIZATION') = (org_id IS NOT NULL)),
  CHECK ((owner_type = 'PROVIDER_ACCOUNT') = (provider_account_id IS NOT NULL))
);

CREATE TABLE ledger.transactions (
  id                      uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  kind                    text NOT NULL CHECK (kind IN (
                            'PAYMENT_CAPTURED','FEES_ASSESSED','FUNDS_RELEASED','RESERVE_HELD','RESERVE_RELEASED',
                            'REFUND_INITIATED','REFUND_SETTLED','PAYOUT_INITIATED','PAYOUT_SETTLED','PAYOUT_FAILED',
                            'DISPUTE_OPENED','DISPUTE_RESOLVED','PROVIDER_FEE','PROMO_FUNDED','FEE_INVOICED',
                            'FEE_COLLECTED','CASH_COLLECTED_BY_SELLER','REFUND_FUNDED_BY_PLATFORM','SELLER_RECOVERY',
                            'ADJUSTMENT','REVERSAL')),
  org_id                  uuid REFERENCES tenant.organizations(id),
  occurred_at             timestamptz NOT NULL DEFAULT now(),
  effective_date          date NOT NULL DEFAULT current_date,
  payment_attempt_id      uuid REFERENCES payments.payment_attempts(id),
  refund_id               uuid REFERENCES payments.refunds(id),
  payout_id               uuid REFERENCES payments.payouts(id),
  dispute_id              uuid REFERENCES payments.disputes(id),
  order_id                uuid REFERENCES commerce.orders(id),
  reverses_transaction_id uuid UNIQUE REFERENCES ledger.transactions(id),
  idempotency_key         text NOT NULL UNIQUE,
  description             text,
  created_by              uuid REFERENCES iam.users(id),
  CHECK ((kind = 'REVERSAL') = (reverses_transaction_id IS NOT NULL))
);
CREATE INDEX ledger_transactions_org_idx ON ledger.transactions (org_id, occurred_at);

CREATE TABLE ledger.entries (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  transaction_id uuid NOT NULL REFERENCES ledger.transactions(id),
  account_id     uuid NOT NULL REFERENCES ledger.accounts(id),
  org_id         uuid,                           -- copied from the account (for tenant reads)
  amount_minor   bigint NOT NULL CHECK (amount_minor <> 0),
  currency_code  char(3) NOT NULL REFERENCES ref.currencies(code)
);
CREATE INDEX ledger_entries_tx_idx ON ledger.entries (transaction_id);
CREATE INDEX ledger_entries_account_idx ON ledger.entries (account_id);

CREATE TABLE ledger.balances (
  account_id    uuid PRIMARY KEY REFERENCES ledger.accounts(id),
  org_id        uuid,
  balance_minor bigint NOT NULL DEFAULT 0,       -- signed (debit positive)
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER ledger_transactions_immutable BEFORE UPDATE OR DELETE ON ledger.transactions
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();
CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger.entries
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

-- Stamp org/currency from the account and refuse currency mismatches.
CREATE FUNCTION ledger.prepare_entry() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE a record;
BEGIN
  SELECT org_id, currency_code, status INTO a FROM ledger.accounts WHERE id = NEW.account_id;
  IF a.currency_code <> NEW.currency_code THEN
    RAISE EXCEPTION 'entry currency % does not match account currency %', NEW.currency_code, a.currency_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF a.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'ledger account % is %', NEW.account_id, a.status USING ERRCODE = 'check_violation';
  END IF;
  NEW.org_id := a.org_id;
  RETURN NEW;
END $$;
CREATE TRIGGER ledger_entries_prepare BEFORE INSERT ON ledger.entries
  FOR EACH ROW EXECUTE FUNCTION ledger.prepare_entry();

CREATE FUNCTION ledger.apply_entry_to_balance() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO ledger.balances (account_id, org_id, balance_minor, updated_at)
  VALUES (NEW.account_id, NEW.org_id, NEW.amount_minor, now())
  ON CONFLICT (account_id) DO UPDATE
    SET balance_minor = ledger.balances.balance_minor + EXCLUDED.balance_minor,
        updated_at = now();
  RETURN NULL;
END $$;
CREATE TRIGGER ledger_entries_balance AFTER INSERT ON ledger.entries
  FOR EACH ROW EXECUTE FUNCTION ledger.apply_entry_to_balance();

-- Double-entry invariant, checked once per transaction at commit.
CREATE FUNCTION ledger.assert_transaction_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_bad record;
  v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM ledger.entries WHERE transaction_id = NEW.transaction_id;
  IF v_count < 2 THEN
    RAISE EXCEPTION 'ledger transaction % has fewer than two entries', NEW.transaction_id
      USING ERRCODE = 'check_violation';
  END IF;
  SELECT currency_code, sum(amount_minor) AS total INTO v_bad
    FROM ledger.entries WHERE transaction_id = NEW.transaction_id
   GROUP BY currency_code HAVING sum(amount_minor) <> 0 LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'ledger transaction % is unbalanced in % by %', NEW.transaction_id, v_bad.currency_code, v_bad.total
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger.entries
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.assert_transaction_balanced();

-- Seller funds move PENDING -> AVAILABLE when the service is delivered
-- (appointment completed, event ended + risk delay). The release job posts a
-- FUNDS_RELEASED transaction and marks the row RELEASED.
CREATE TABLE ledger.scheduled_releases (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL REFERENCES tenant.organizations(id),
  order_id       uuid REFERENCES commerce.orders(id),
  occurrence_id  uuid REFERENCES events.occurrences(id),
  amount_minor   bigint NOT NULL CHECK (amount_minor > 0),
  currency_code  char(3) NOT NULL REFERENCES ref.currencies(code),
  release_at     timestamptz NOT NULL,
  status         text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','RELEASED','CANCELLED','ON_HOLD')),
  transaction_id uuid REFERENCES ledger.transactions(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'RELEASED') = (transaction_id IS NOT NULL))
);
CREATE INDEX scheduled_releases_due_idx ON ledger.scheduled_releases (release_at) WHERE status = 'SCHEDULED';
