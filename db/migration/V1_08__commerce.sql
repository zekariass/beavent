-- =============================================================================
-- 08: commerce — one order model for everything sold on the platform.
-- An order belongs to exactly one organization (seller of record = one legal
-- entity) and one currency. Lines are typed and point at the catalog item they
-- sell with real foreign keys (no polymorphic source_type/source_id).
-- Fulfilment records (appointments, tickets, gift cards, memberships,
-- shipments) point back at the order/line.
-- =============================================================================

CREATE TABLE commerce.orders (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL REFERENCES tenant.organizations(id),
  legal_entity_id     uuid NOT NULL,
  location_id         uuid,
  number              text NOT NULL,             -- human reference, unique per org
  customer_id         uuid,                      -- NULL only for anonymous cash sales
  buyer_user_id       uuid REFERENCES iam.users(id),
  buyer_name          text,
  buyer_phone_e164    text,
  buyer_email         citext,
  channel             text NOT NULL CHECK (channel IN ('MARKETPLACE_APP','MARKETPLACE_WEB','DIRECT_LINK','WIDGET','PARTNER_POS','BOX_OFFICE','PARTNER_PORTAL','AI_CONCIERGE','PUBLIC_API','ADMIN')),
  status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_PAYMENT','CONFIRMED','COMPLETED','CANCELLED','EXPIRED')),
  payment_status      text NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID','PARTIALLY_PAID','PAID','PARTIALLY_REFUNDED','REFUNDED','NOT_REQUIRED')),
  currency_code       char(3) NOT NULL REFERENCES ref.currencies(code),
  prices_include_tax  boolean NOT NULL,
  subtotal_minor      bigint NOT NULL DEFAULT 0 CHECK (subtotal_minor >= 0),
  discount_minor      bigint NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
  tax_minor           bigint NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
  total_minor         bigint NOT NULL DEFAULT 0 CHECK (total_minor >= 0),
  paid_minor          bigint NOT NULL DEFAULT 0 CHECK (paid_minor >= 0),
  refunded_minor      bigint NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
  -- Pending orders hold inventory/time until this instant, then expire.
  expires_at          timestamptz,
  is_marketplace_new_client boolean NOT NULL DEFAULT false,
  attribution         jsonb NOT NULL DEFAULT '{}'::jsonb,  -- tracking link, utm, referrer, ad campaign
  buyer_note          text,
  placed_at           timestamptz,
  confirmed_at        timestamptz,
  completed_at        timestamptz,
  cancelled_at        timestamptz,
  cancel_reason       text,
  version             integer NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (org_id, number),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  CHECK (refunded_minor <= paid_minor),
  CHECK (status <> 'PENDING_PAYMENT' OR expires_at IS NOT NULL),
  CHECK (customer_id IS NOT NULL OR channel IN ('PARTNER_POS','BOX_OFFICE'))
);
CREATE INDEX orders_buyer_idx ON commerce.orders (buyer_user_id, created_at DESC) WHERE buyer_user_id IS NOT NULL;
CREATE INDEX orders_customer_idx ON commerce.orders (org_id, customer_id, created_at DESC);
CREATE INDEX orders_expiry_idx ON commerce.orders (expires_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE commerce.order_lines (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL,
  order_id             uuid NOT NULL,
  seq                  smallint NOT NULL CHECK (seq > 0),
  line_type            text NOT NULL CHECK (line_type IN (
                         'SERVICE','ADDON','TICKET','PRODUCT','GIFT_CARD','MEMBERSHIP','PACKAGE',
                         'DONATION','TIP','BUYER_FEE','DELIVERY_FEE','TRAVEL_FEE',
                         'CANCELLATION_FEE','NO_SHOW_FEE','CUSTOM')),
  parent_line_id       uuid,                     -- add-on -> service, fee -> ticket line
  service_variant_id   uuid,
  addon_id             uuid,
  ticket_type_id       uuid,
  occurrence_id        uuid,                     -- ticket occurrence, or event where a product is collected
  product_variant_id   uuid,
  gift_card_program_id uuid,
  membership_plan_id   uuid,
  package_plan_id      uuid,
  bundle_id            uuid,                     -- set on every line expanded from a bundle
  staff_id             uuid,                     -- performer (service), recipient (tip), seller (retail)
  description          text NOT NULL,            -- immutable snapshot of what was sold
  quantity             integer NOT NULL CHECK (quantity > 0),
  unit_price_minor     bigint NOT NULL CHECK (unit_price_minor >= 0),
  discount_minor       bigint NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
  tax_minor            bigint NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
  total_minor          bigint NOT NULL CHECK (total_minor >= 0),
  currency_code        char(3) NOT NULL REFERENCES ref.currencies(code),
  tax_category_code    text NOT NULL REFERENCES ref.tax_categories(code),
  tax_inclusive        boolean NOT NULL,
  status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
  refunded_quantity    integer NOT NULL DEFAULT 0 CHECK (refunded_quantity >= 0),
  refunded_minor       bigint NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
  metadata             jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (order_id, seq),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, parent_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, service_variant_id) REFERENCES catalog.service_variants (org_id, id),
  FOREIGN KEY (org_id, addon_id) REFERENCES catalog.addons (org_id, id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, product_variant_id) REFERENCES catalog.product_variants (org_id, id),
  FOREIGN KEY (org_id, gift_card_program_id) REFERENCES catalog.gift_card_programs (org_id, id),
  FOREIGN KEY (org_id, membership_plan_id) REFERENCES catalog.membership_plans (org_id, id),
  FOREIGN KEY (org_id, package_plan_id) REFERENCES catalog.package_plans (org_id, id),
  FOREIGN KEY (org_id, bundle_id) REFERENCES catalog.bundles (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  -- Arithmetic is enforced, not trusted.
  CHECK (discount_minor <= unit_price_minor * quantity),
  CHECK (total_minor = unit_price_minor * quantity - discount_minor + CASE WHEN tax_inclusive THEN 0 ELSE tax_minor END),
  CHECK (refunded_quantity <= quantity AND refunded_minor <= total_minor),
  -- At most one catalog reference, and the one required by the line type.
  CHECK (num_nonnulls(service_variant_id, addon_id, ticket_type_id, product_variant_id,
                      gift_card_program_id, membership_plan_id, package_plan_id) <= 1),
  CHECK (CASE line_type
           WHEN 'SERVICE'    THEN service_variant_id IS NOT NULL
           WHEN 'ADDON'      THEN addon_id IS NOT NULL AND parent_line_id IS NOT NULL
           WHEN 'TICKET'     THEN ticket_type_id IS NOT NULL
           WHEN 'PRODUCT'    THEN product_variant_id IS NOT NULL
           WHEN 'GIFT_CARD'  THEN gift_card_program_id IS NOT NULL
           WHEN 'MEMBERSHIP' THEN membership_plan_id IS NOT NULL
           WHEN 'PACKAGE'    THEN package_plan_id IS NOT NULL
           ELSE true END)
);
CREATE INDEX order_lines_order_idx ON commerce.order_lines (order_id);
CREATE INDEX order_lines_occurrence_idx ON commerce.order_lines (occurrence_id) WHERE occurrence_id IS NOT NULL;

-- Immutable tax snapshot per line (never recalculated from current rates).
CREATE TABLE commerce.order_line_taxes (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  order_line_id uuid NOT NULL,
  tax_rate_id   uuid REFERENCES ref.tax_rates(id),
  tax_code      text NOT NULL,
  rate          numeric(7,4) NOT NULL,
  taxable_minor bigint NOT NULL CHECK (taxable_minor >= 0),
  tax_minor     bigint NOT NULL CHECK (tax_minor >= 0),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id)
);
CREATE TRIGGER order_line_taxes_immutable BEFORE UPDATE OR DELETE ON commerce.order_line_taxes
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE TABLE commerce.order_status_history (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  order_id    uuid NOT NULL,
  from_status text,
  to_status   text NOT NULL,
  actor_user_id uuid REFERENCES iam.users(id),
  actor_kind  text NOT NULL CHECK (actor_kind IN ('CUSTOMER','STAFF','SYSTEM','PLATFORM_ADMIN','PROVIDER_WEBHOOK')),
  reason      text,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id)
);
CREATE TRIGGER order_status_history_immutable BEFORE UPDATE OR DELETE ON commerce.order_status_history
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

-- What must be paid when: deposit now, balance at the appointment; or instalments for an event invoice.
CREATE TABLE commerce.payment_schedule (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  order_id        uuid NOT NULL,
  seq             smallint NOT NULL CHECK (seq > 0),
  kind            text NOT NULL CHECK (kind IN ('FULL','DEPOSIT','BALANCE','INSTALMENT')),
  amount_minor    bigint NOT NULL CHECK (amount_minor > 0),
  currency_code   char(3) NOT NULL REFERENCES ref.currencies(code),
  due_at          timestamptz,
  collect_at      text NOT NULL DEFAULT 'ONLINE' CHECK (collect_at IN ('ONLINE','AT_VENUE','ON_INVOICE')),
  collected_minor bigint NOT NULL DEFAULT 0 CHECK (collected_minor >= 0),
  status          text NOT NULL DEFAULT 'DUE' CHECK (status IN ('DUE','PAID','WAIVED','FORFEITED','CANCELLED')),
  UNIQUE (org_id, id),
  UNIQUE (order_id, seq),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  CHECK (collected_minor <= amount_minor)
);

-- -----------------------------------------------------------------------------
-- Order total integrity: header totals must equal the sum of active lines.
-- Deferred so the application can write header and lines in any order within
-- the transaction. Drafts are exempt while being edited.
-- -----------------------------------------------------------------------------
CREATE FUNCTION commerce.assert_order_totals() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_order_id uuid;
  o record;
  s record;
BEGIN
  -- IF (not CASE): PL/pgSQL resolves every NEW.field in an expression, and orders has no order_id.
  IF TG_TABLE_NAME = 'orders' THEN v_order_id := NEW.id; ELSE v_order_id := NEW.order_id; END IF;
  SELECT * INTO o FROM commerce.orders WHERE id = v_order_id;
  IF o.status = 'DRAFT' THEN RETURN NULL; END IF;
  SELECT coalesce(sum(total_minor), 0)    AS total,
         coalesce(sum(tax_minor), 0)      AS tax,
         coalesce(sum(discount_minor), 0) AS discount,
         count(*) FILTER (WHERE currency_code <> o.currency_code) AS foreign_ccy
    INTO s
    FROM commerce.order_lines WHERE order_id = v_order_id AND status = 'ACTIVE';
  IF s.foreign_ccy > 0 THEN
    RAISE EXCEPTION 'order % has lines in a currency other than %', v_order_id, o.currency_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF o.total_minor <> s.total OR o.tax_minor <> s.tax OR o.discount_minor <> s.discount THEN
    RAISE EXCEPTION 'order % totals (total %, tax %, discount %) do not match lines (total %, tax %, discount %)',
      v_order_id, o.total_minor, o.tax_minor, o.discount_minor, s.total, s.tax, s.discount
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER orders_totals_check AFTER INSERT OR UPDATE ON commerce.orders
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION commerce.assert_order_totals();
CREATE CONSTRAINT TRIGGER order_lines_totals_check AFTER INSERT OR UPDATE ON commerce.order_lines
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION commerce.assert_order_totals();

-- -----------------------------------------------------------------------------
-- Promotions (discounts, deals, off-peak pricing) and codes (discount codes and
-- Eventbrite-style access codes that unlock hidden tickets). org_id NULL =
-- platform-funded marketplace promotion.
-- -----------------------------------------------------------------------------
CREATE TABLE commerce.promotions (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid REFERENCES tenant.organizations(id),
  market_id          uuid REFERENCES ref.markets(id),
  name               text NOT NULL,
  kind               text NOT NULL CHECK (kind IN ('PERCENT_OFF','AMOUNT_OFF','FIXED_PRICE','FREE_ITEM')),
  percent_off        numeric(5,2) CHECK (percent_off IS NULL OR (percent_off > 0 AND percent_off <= 100)),
  amount_minor       bigint CHECK (amount_minor IS NULL OR amount_minor > 0),
  currency_code      char(3) REFERENCES ref.currencies(code),
  eligibility        text NOT NULL DEFAULT 'EVERYONE' CHECK (eligibility IN ('EVERYONE','NEW_CUSTOMERS','RETURNING_CUSTOMERS','MEMBERS','FOLLOWERS','SEGMENT')),
  segment_id         uuid,                       -- FK added in V1_17
  min_order_minor    bigint CHECK (min_order_minor IS NULL OR min_order_minor > 0),
  max_discount_minor bigint CHECK (max_discount_minor IS NULL OR max_discount_minor > 0),
  valid_during       tstzrange NOT NULL,
  -- Off-peak / happy-hour windows: [{"iso_weekdays":[1,2,3],"from":"10:00","to":"14:00"}]
  time_windows       jsonb NOT NULL DEFAULT '[]'::jsonb,
  auto_apply         boolean NOT NULL DEFAULT false,
  stackable          boolean NOT NULL DEFAULT false,
  max_redemptions    integer CHECK (max_redemptions IS NULL OR max_redemptions > 0),
  per_customer_limit integer CHECK (per_customer_limit IS NULL OR per_customer_limit > 0),
  redemption_count   integer NOT NULL DEFAULT 0 CHECK (redemption_count >= 0),
  funded_by          text NOT NULL DEFAULT 'ORGANIZATION' CHECK (funded_by IN ('ORGANIZATION','PLATFORM','SHARED')),
  platform_share_bps integer NOT NULL DEFAULT 0 CHECK (platform_share_bps BETWEEN 0 AND 10000),
  status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT','ACTIVE','PAUSED','ENDED')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  CHECK (max_redemptions IS NULL OR redemption_count <= max_redemptions),
  CHECK ((kind = 'PERCENT_OFF') = (percent_off IS NOT NULL)),
  CHECK (kind NOT IN ('AMOUNT_OFF','FIXED_PRICE') OR (amount_minor IS NOT NULL AND currency_code IS NOT NULL)),
  CHECK (org_id IS NOT NULL OR funded_by = 'PLATFORM'),
  CHECK ((funded_by = 'SHARED') = (platform_share_bps BETWEEN 1 AND 9999))
);

-- What a promotion applies to. No rows = whole order.
CREATE TABLE commerce.promotion_targets (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid,
  promotion_id       uuid NOT NULL REFERENCES commerce.promotions(id) ON DELETE CASCADE,
  category_id        uuid REFERENCES ref.categories(id),
  service_id         uuid,
  product_id         uuid,
  event_id           uuid,
  ticket_type_id     uuid,
  location_id        uuid,
  FOREIGN KEY (org_id, service_id) REFERENCES catalog.services (org_id, id),
  FOREIGN KEY (org_id, product_id) REFERENCES catalog.products (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (num_nonnulls(category_id, service_id, product_id, event_id, ticket_type_id, location_id) = 1)
);

CREATE TABLE commerce.promo_codes (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid REFERENCES tenant.organizations(id),
  promotion_id uuid REFERENCES commerce.promotions(id),
  event_id     uuid,                             -- scope an access code to one event
  code         citext NOT NULL CHECK (code ~ '^[A-Za-z0-9_-]{3,40}$'),
  kind         text NOT NULL CHECK (kind IN ('DISCOUNT','ACCESS','DISCOUNT_AND_ACCESS')),
  max_uses     integer CHECK (max_uses IS NULL OR max_uses > 0),
  use_count    integer NOT NULL DEFAULT 0 CHECK (use_count >= 0),
  valid_during tstzrange,
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','ENDED')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  CHECK (max_uses IS NULL OR use_count <= max_uses),
  CHECK ((kind = 'ACCESS') = (promotion_id IS NULL))
);
CREATE UNIQUE INDEX promo_codes_org_code_uniq ON commerce.promo_codes (org_id, code) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX promo_codes_platform_code_uniq ON commerce.promo_codes (code) WHERE org_id IS NULL;

CREATE TABLE commerce.promo_code_unlocks (
  org_id         uuid NOT NULL,
  promo_code_id  uuid NOT NULL,
  ticket_type_id uuid NOT NULL,
  PRIMARY KEY (promo_code_id, ticket_type_id),
  FOREIGN KEY (org_id, promo_code_id) REFERENCES commerce.promo_codes (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id) ON DELETE CASCADE
);

CREATE TABLE commerce.redemptions (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL,
  promotion_id   uuid REFERENCES commerce.promotions(id),
  promo_code_id  uuid REFERENCES commerce.promo_codes(id),
  order_id       uuid NOT NULL,
  order_line_id  uuid,
  customer_id    uuid,
  discount_minor bigint NOT NULL CHECK (discount_minor >= 0),
  platform_funded_minor bigint NOT NULL DEFAULT 0 CHECK (platform_funded_minor >= 0),
  currency_code  char(3) NOT NULL REFERENCES ref.currencies(code),
  status         text NOT NULL DEFAULT 'APPLIED' CHECK (status IN ('APPLIED','REVERSED')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  CHECK (num_nonnulls(promotion_id, promo_code_id) >= 1),
  CHECK (platform_funded_minor <= discount_minor)
);

-- -----------------------------------------------------------------------------
-- Document numbering (gapless per legal entity and document type, as most
-- tax authorities in the region require for invoices and receipts).
-- -----------------------------------------------------------------------------
CREATE TABLE commerce.document_sequences (
  org_id          uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  doc_type        text NOT NULL CHECK (doc_type IN ('ORDER','INVOICE','QUOTE','CREDIT_NOTE','RECEIPT')),
  prefix          text NOT NULL DEFAULT '',
  next_value      bigint NOT NULL DEFAULT 1 CHECK (next_value > 0),
  PRIMARY KEY (legal_entity_id, doc_type),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id)
);

CREATE FUNCTION commerce.next_document_number(p_legal_entity_id uuid, p_doc_type text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE v_prefix text; v_value bigint;
BEGIN
  UPDATE commerce.document_sequences
     SET next_value = next_value + 1
   WHERE legal_entity_id = p_legal_entity_id AND doc_type = p_doc_type
   RETURNING prefix, next_value - 1 INTO v_prefix, v_value;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'no % sequence for legal entity %', p_doc_type, p_legal_entity_id;
  END IF;
  RETURN v_prefix || lpad(v_value::text, 7, '0');
END $$;

-- Quotes, invoices and credit notes issued by the organization to its customers.
CREATE TABLE commerce.invoices (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  legal_entity_id  uuid NOT NULL,
  order_id         uuid,
  customer_id      uuid NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('QUOTE','INVOICE','CREDIT_NOTE')),
  number           text NOT NULL,
  corrects_invoice_id uuid,
  status           text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ISSUED','ACCEPTED','PARTIALLY_PAID','PAID','VOID','EXPIRED')),
  issue_date       date,
  due_date         date,
  currency_code    char(3) NOT NULL REFERENCES ref.currencies(code),
  subtotal_minor   bigint NOT NULL DEFAULT 0 CHECK (subtotal_minor >= 0),
  tax_minor        bigint NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
  total_minor      bigint NOT NULL DEFAULT 0 CHECK (total_minor >= 0),
  buyer_tax_id     text,                         -- B2B buyers' TIN printed on the invoice
  pdf_file_id      uuid,
  notes            text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (legal_entity_id, kind, number),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, corrects_invoice_id) REFERENCES commerce.invoices (org_id, id),
  FOREIGN KEY (org_id, pdf_file_id) REFERENCES platform.files (org_id, id),
  CHECK ((kind = 'CREDIT_NOTE') = (corrects_invoice_id IS NOT NULL))
);

CREATE TABLE commerce.invoice_lines (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL,
  invoice_id        uuid NOT NULL,
  seq               smallint NOT NULL CHECK (seq > 0),
  order_line_id     uuid,
  description       text NOT NULL,
  quantity          numeric(12,3) NOT NULL CHECK (quantity > 0),
  unit_price_minor  bigint NOT NULL CHECK (unit_price_minor >= 0),
  tax_category_code text NOT NULL REFERENCES ref.tax_categories(code),
  tax_minor         bigint NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
  total_minor       bigint NOT NULL CHECK (total_minor >= 0),
  UNIQUE (invoice_id, seq),
  FOREIGN KEY (org_id, invoice_id) REFERENCES commerce.invoices (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, order_line_id) REFERENCES commerce.order_lines (org_id, id)
);

CREATE TABLE commerce.receipts (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  order_id        uuid NOT NULL,
  number          text NOT NULL,
  issued_at       timestamptz NOT NULL DEFAULT now(),
  file_id         uuid,
  delivered_via   text[] NOT NULL DEFAULT '{}',
  UNIQUE (org_id, id),
  UNIQUE (legal_entity_id, number),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, file_id) REFERENCES platform.files (org_id, id)
);

-- -----------------------------------------------------------------------------
-- Cash handling for salon front desks and event box offices alike.
-- -----------------------------------------------------------------------------
CREATE TABLE commerce.cash_drawers (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id   uuid,
  name          text NOT NULL,
  currency_code char(3) NOT NULL REFERENCES ref.currencies(code),
  is_active     boolean NOT NULL DEFAULT true,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id)
);

CREATE TABLE commerce.cash_drawer_sessions (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL,
  drawer_id        uuid NOT NULL,
  occurrence_id    uuid,                         -- box office session for an event occurrence
  opened_by        uuid NOT NULL REFERENCES iam.users(id),
  opened_at        timestamptz NOT NULL DEFAULT now(),
  opening_float_minor bigint NOT NULL CHECK (opening_float_minor >= 0),
  closed_by        uuid REFERENCES iam.users(id),
  closed_at        timestamptz,
  counted_minor    bigint CHECK (counted_minor IS NULL OR counted_minor >= 0),
  expected_minor   bigint,
  status           text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED','RECONCILED')),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, drawer_id) REFERENCES commerce.cash_drawers (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  CHECK ((status = 'OPEN') = (closed_at IS NULL)),
  CHECK (status = 'OPEN' OR (counted_minor IS NOT NULL AND expected_minor IS NOT NULL))
);
CREATE UNIQUE INDEX cash_drawer_one_open_session ON commerce.cash_drawer_sessions (drawer_id) WHERE status = 'OPEN';

-- Sale/refund cash movements link to the payment attempt/refund (FKs in V1_12).
CREATE TABLE commerce.cash_movements (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  session_id         uuid NOT NULL,
  kind               text NOT NULL CHECK (kind IN ('SALE','REFUND','PAY_IN','PAY_OUT','DROP','CORRECTION')),
  amount_minor       bigint NOT NULL CHECK (amount_minor <> 0), -- signed: + into drawer, - out of drawer
  payment_attempt_id uuid,
  refund_id          uuid,
  note               text,
  actor_user_id      uuid NOT NULL REFERENCES iam.users(id),
  occurred_at        timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, session_id) REFERENCES commerce.cash_drawer_sessions (org_id, id),
  CHECK (kind <> 'SALE'   OR (amount_minor > 0 AND payment_attempt_id IS NOT NULL)),
  CHECK (kind <> 'REFUND' OR (amount_minor < 0 AND refund_id IS NOT NULL))
);
CREATE TRIGGER cash_movements_immutable BEFORE UPDATE OR DELETE ON commerce.cash_movements
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

-- -----------------------------------------------------------------------------
-- One waitlist for sold-out occurrences and fully booked appointment times.
-- -----------------------------------------------------------------------------
CREATE TABLE commerce.waitlist_entries (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  customer_id        uuid,
  user_id            uuid REFERENCES iam.users(id),
  contact_phone_e164 text,
  contact_email      citext,
  -- Event target
  occurrence_id      uuid,
  ticket_type_id     uuid,
  -- Appointment target
  location_id        uuid,
  service_variant_id uuid,
  staff_id           uuid,
  preferred_windows  tstzmultirange,
  quantity           smallint NOT NULL DEFAULT 1 CHECK (quantity > 0),
  status             text NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','OFFERED','CONVERTED','EXPIRED','CANCELLED')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, occurrence_id) REFERENCES events.occurrences (org_id, id),
  FOREIGN KEY (org_id, ticket_type_id) REFERENCES events.ticket_types (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, service_variant_id) REFERENCES catalog.service_variants (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  CHECK ((occurrence_id IS NOT NULL) <> (service_variant_id IS NOT NULL)),
  CHECK (service_variant_id IS NULL OR (location_id IS NOT NULL AND preferred_windows IS NOT NULL)),
  CHECK (user_id IS NOT NULL OR contact_phone_e164 IS NOT NULL OR contact_email IS NOT NULL)
);
CREATE INDEX waitlist_occurrence_idx ON commerce.waitlist_entries (occurrence_id, created_at) WHERE status = 'WAITING';

CREATE TABLE commerce.waitlist_offers (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  entry_id    uuid NOT NULL,
  offered_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  -- Inventory is held for the offer (events: inventory HOLD movement; appointments: scheduling hold).
  offered_slot tstzrange,
  order_id    uuid,
  status      text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACCEPTED','DECLINED','EXPIRED')),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, entry_id) REFERENCES commerce.waitlist_entries (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  CHECK (expires_at > offered_at),
  CHECK ((status = 'ACCEPTED') = (order_id IS NOT NULL))
);
