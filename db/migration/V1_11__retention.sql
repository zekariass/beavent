-- =============================================================================
-- 11: retention — gift cards, customer balances (store credit), memberships,
-- package/membership entitlements, loyalty. Each balance has an append-only
-- entry ledger; a trigger maintains the cached balance and CHECKs keep it sane.
-- Stored value is scoped to ONE organization (no platform-wide wallet, which
-- would be a regulated e-money product in most of the target markets).
-- =============================================================================

CREATE TABLE retention.gift_cards (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  program_id         uuid NOT NULL,
  code_hash          text NOT NULL UNIQUE,       -- the redeemable code is shown once; only its hash is stored
  code_last4         text NOT NULL,
  currency_code      char(3) NOT NULL REFERENCES ref.currencies(code),
  initial_minor      bigint NOT NULL CHECK (initial_minor > 0),
  balance_minor      bigint NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
  purchaser_customer_id uuid,
  issued_order_line_id  uuid,
  recipient_name     text,
  recipient_phone_e164 text,
  recipient_email    citext,
  message            text,
  deliver_at         timestamptz,
  expires_at         timestamptz,
  status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING_DELIVERY','ACTIVE','DEPLETED','EXPIRED','VOID')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, program_id) REFERENCES catalog.gift_card_programs (org_id, id),
  FOREIGN KEY (org_id, purchaser_customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, issued_order_line_id) REFERENCES commerce.order_lines (org_id, id),
  CHECK (balance_minor <= initial_minor)
);

CREATE TABLE retention.gift_card_entries (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  gift_card_id  uuid NOT NULL,
  kind          text NOT NULL CHECK (kind IN ('ISSUE','REDEEM','REDEEM_REVERSAL','ADJUST','EXPIRE','VOID')),
  amount_minor  bigint NOT NULL CHECK (amount_minor <> 0),  -- signed: + credit, - debit
  order_id      uuid,
  payment_attempt_id uuid,                      -- the tender that used this card (FK in V1_12)
  actor_user_id uuid REFERENCES iam.users(id),
  note          text,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, gift_card_id) REFERENCES retention.gift_cards (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  CHECK (kind NOT IN ('ISSUE','REDEEM_REVERSAL') OR amount_minor > 0),
  CHECK (kind NOT IN ('REDEEM','EXPIRE','VOID') OR amount_minor < 0)
);
CREATE TRIGGER gift_card_entries_immutable BEFORE UPDATE OR DELETE ON retention.gift_card_entries
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE FUNCTION retention.apply_gift_card_entry() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE retention.gift_cards
     SET balance_minor = balance_minor + NEW.amount_minor,
         status = CASE WHEN balance_minor + NEW.amount_minor = 0 AND status = 'ACTIVE' THEN 'DEPLETED'
                       WHEN balance_minor + NEW.amount_minor > 0 AND status = 'DEPLETED' THEN 'ACTIVE'
                       ELSE status END
   WHERE id = NEW.gift_card_id AND org_id = NEW.org_id;
  RETURN NEW;
END $$;
CREATE TRIGGER gift_card_entries_apply AFTER INSERT ON retention.gift_card_entries
  FOR EACH ROW EXECUTE FUNCTION retention.apply_gift_card_entry();

-- Store credit (e.g. refunds as credit, goodwill). One balance per customer and currency.
CREATE TABLE retention.customer_balances (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  customer_id   uuid NOT NULL,
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  balance_minor bigint NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (customer_id, currency_code),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id)
);

CREATE TABLE retention.customer_balance_entries (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  balance_id    uuid NOT NULL,
  kind          text NOT NULL CHECK (kind IN ('REFUND_CREDIT','GOODWILL','SPEND','SPEND_REVERSAL','ADJUST','EXPIRE')),
  amount_minor  bigint NOT NULL CHECK (amount_minor <> 0),
  order_id      uuid,
  refund_id     uuid,                            -- FK in V1_12
  payment_attempt_id uuid,                       -- FK in V1_12
  actor_user_id uuid REFERENCES iam.users(id),
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, balance_id) REFERENCES retention.customer_balances (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id)
);
CREATE TRIGGER customer_balance_entries_immutable BEFORE UPDATE OR DELETE ON retention.customer_balance_entries
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE FUNCTION retention.apply_customer_balance_entry() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE retention.customer_balances
     SET balance_minor = balance_minor + NEW.amount_minor, updated_at = now()
   WHERE id = NEW.balance_id AND org_id = NEW.org_id;
  RETURN NEW;
END $$;
CREATE TRIGGER customer_balance_entries_apply AFTER INSERT ON retention.customer_balance_entries
  FOR EACH ROW EXECUTE FUNCTION retention.apply_customer_balance_entry();

-- -----------------------------------------------------------------------------
-- Memberships and entitlements
-- -----------------------------------------------------------------------------
CREATE TABLE retention.customer_memberships (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  customer_id        uuid NOT NULL,
  plan_id            uuid NOT NULL,
  order_line_id      uuid,                       -- the purchase
  status             text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','PAST_DUE','PAUSED','CANCELLED','EXPIRED')),
  current_period     tstzrange,
  started_at         timestamptz,
  cancel_at          timestamptz,
  cancelled_at       timestamptz,
  -- Recurring collection: saved instrument where the provider supports tokens,
  -- otherwise a payment link/push prompt is sent each cycle.
  renewal_method     text NOT NULL DEFAULT 'PAYMENT_LINK' CHECK (renewal_method IN ('SAVED_INSTRUMENT','PAYMENT_LINK','PAY_AT_VENUE')),
  instrument_id      uuid,                       -- FK in V1_12
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, plan_id) REFERENCES catalog.membership_plans (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  CHECK (status NOT IN ('ACTIVE','PAST_DUE') OR current_period IS NOT NULL)
);

-- Countable rights: "3 blow-dries left", "8 class credits this month".
CREATE TABLE retention.entitlements (
  id                     uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                 uuid NOT NULL,
  customer_id            uuid NOT NULL,
  source                 text NOT NULL CHECK (source IN ('PACKAGE','MEMBERSHIP','REFERRAL','COMPENSATION','PROMOTION')),
  package_order_line_id  uuid,
  customer_membership_id uuid,
  service_id             uuid,
  category_id            uuid REFERENCES ref.categories(id),
  product_variant_id     uuid,
  event_id               uuid,                   -- class credits for a class series
  quantity_total         integer NOT NULL CHECK (quantity_total > 0),
  quantity_used          integer NOT NULL DEFAULT 0 CHECK (quantity_used >= 0),
  valid_during           tstzrange NOT NULL,
  status                 text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXHAUSTED','EXPIRED','VOID')),
  created_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, package_order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, customer_membership_id) REFERENCES retention.customer_memberships (org_id, id),
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  CHECK (quantity_used <= quantity_total),
  CHECK (num_nonnulls(service_id, category_id, product_variant_id, event_id) = 1),
  CHECK (source <> 'PACKAGE' OR package_order_line_id IS NOT NULL),
  CHECK (source <> 'MEMBERSHIP' OR customer_membership_id IS NOT NULL)
);

CREATE TABLE retention.entitlement_usages (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  entitlement_id      uuid NOT NULL,
  kind                text NOT NULL CHECK (kind IN ('USE','RESTORE')),
  quantity            integer NOT NULL CHECK (quantity > 0),
  appointment_item_id uuid,
  ticket_id           uuid,
  order_line_id       uuid,
  occurred_at         timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, entitlement_id) REFERENCES retention.entitlements (org_id, id),
  FOREIGN KEY (org_id, appointment_item_id) REFERENCES scheduling.appointment_items (org_id, id),
  FOREIGN KEY (org_id, ticket_id) REFERENCES events.tickets (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id)
);
CREATE TRIGGER entitlement_usages_immutable BEFORE UPDATE OR DELETE ON retention.entitlement_usages
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE FUNCTION retention.apply_entitlement_usage() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE retention.entitlements
     SET quantity_used = quantity_used + CASE NEW.kind WHEN 'USE' THEN NEW.quantity ELSE -NEW.quantity END,
         status = CASE
                    WHEN quantity_used + CASE NEW.kind WHEN 'USE' THEN NEW.quantity ELSE -NEW.quantity END = quantity_total THEN 'EXHAUSTED'
                    WHEN status = 'EXHAUSTED' THEN 'ACTIVE'
                    ELSE status END
   WHERE id = NEW.entitlement_id AND org_id = NEW.org_id;
  RETURN NEW;
END $$;
CREATE TRIGGER entitlement_usages_apply AFTER INSERT ON retention.entitlement_usages
  FOR EACH ROW EXECUTE FUNCTION retention.apply_entitlement_usage();

-- -----------------------------------------------------------------------------
-- Loyalty
-- -----------------------------------------------------------------------------
CREATE TABLE retention.loyalty_programs (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL REFERENCES tenant.organizations(id),
  name             text NOT NULL,
  points_per_major_unit numeric(10,4) NOT NULL CHECK (points_per_major_unit > 0), -- e.g. 1 point per ETB 10 = 0.1
  earn_on          text[] NOT NULL DEFAULT '{SERVICE,PRODUCT,TICKET}',
  points_expiry_months smallint CHECK (points_expiry_months IS NULL OR points_expiry_months > 0),
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','ENDED')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);
CREATE UNIQUE INDEX loyalty_programs_one_active ON retention.loyalty_programs (org_id) WHERE status = 'ACTIVE';

CREATE TABLE retention.loyalty_tiers (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  program_id       uuid NOT NULL,
  name             text NOT NULL,
  threshold_points integer NOT NULL CHECK (threshold_points >= 0),
  benefits         jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (org_id, id),
  UNIQUE (program_id, threshold_points),
  FOREIGN KEY (org_id, program_id) REFERENCES retention.loyalty_programs (org_id, id) ON DELETE CASCADE
);

CREATE TABLE retention.loyalty_rewards (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  program_id    uuid NOT NULL,
  name_i18n     jsonb NOT NULL,
  points_cost   integer NOT NULL CHECK (points_cost > 0),
  kind          text NOT NULL CHECK (kind IN ('AMOUNT_OFF','PERCENT_OFF','FREE_SERVICE','FREE_PRODUCT')),
  amount_minor  bigint,
  currency_code char(3) REFERENCES ref.currencies(code),
  percent_off   numeric(5,2),
  service_id    uuid,
  product_variant_id uuid,
  status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, program_id) REFERENCES retention.loyalty_programs (org_id, id),
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id),
  CHECK (kind <> 'AMOUNT_OFF' OR (amount_minor > 0 AND currency_code IS NOT NULL)),
  CHECK (kind <> 'PERCENT_OFF' OR percent_off > 0),
  CHECK (kind <> 'FREE_SERVICE' OR service_id IS NOT NULL),
  CHECK (kind <> 'FREE_PRODUCT' OR product_variant_id IS NOT NULL)
);

CREATE TABLE retention.loyalty_accounts (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  program_id      uuid NOT NULL,
  customer_id     uuid NOT NULL,
  points_balance  integer NOT NULL DEFAULT 0 CHECK (points_balance >= 0),
  lifetime_points integer NOT NULL DEFAULT 0 CHECK (lifetime_points >= 0),
  tier_id         uuid,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (program_id, customer_id),
  FOREIGN KEY (org_id, program_id) REFERENCES retention.loyalty_programs (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, tier_id) REFERENCES retention.loyalty_tiers (org_id, id)
);

CREATE TABLE retention.loyalty_entries (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  account_id  uuid NOT NULL,
  kind        text NOT NULL CHECK (kind IN ('EARN','REDEEM','EXPIRE','ADJUST','REVERSE')),
  points      integer NOT NULL CHECK (points <> 0),
  order_id    uuid,
  reward_id   uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, account_id) REFERENCES retention.loyalty_accounts (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, reward_id) REFERENCES retention.loyalty_rewards (org_id, id),
  CHECK (kind <> 'EARN' OR points > 0),
  CHECK (kind NOT IN ('REDEEM','EXPIRE') OR points < 0),
  CHECK (kind <> 'REDEEM' OR reward_id IS NOT NULL)
);
CREATE TRIGGER loyalty_entries_immutable BEFORE UPDATE OR DELETE ON retention.loyalty_entries
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE FUNCTION retention.apply_loyalty_entry() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE retention.loyalty_accounts
     SET points_balance  = points_balance + NEW.points,
         lifetime_points = lifetime_points + GREATEST(NEW.points, 0) * (NEW.kind = 'EARN')::int,
         updated_at = now()
   WHERE id = NEW.account_id AND org_id = NEW.org_id;
  RETURN NEW;
END $$;
CREATE TRIGGER loyalty_entries_apply AFTER INSERT ON retention.loyalty_entries
  FOR EACH ROW EXECUTE FUNCTION retention.apply_loyalty_entry();
