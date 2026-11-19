-- =============================================================================
-- 20: fiscal (tax-authority e-invoicing / fiscal devices) and platform ops
-- =============================================================================

-- Country schemes behind one adapter interface, like payment providers:
--   ET_MOR_EINVOICE (Ministry of Revenue clearance e-invoicing / sales register),
--   KE_KRA_ETIMS, UG_URA_EFRIS, TZ_TRA_VFD, RW_RRA_EBM ...
CREATE TABLE fiscal.schemes (
  code         text PRIMARY KEY,
  country_code char(2) NOT NULL REFERENCES ref.countries(code),
  name         text NOT NULL,
  mode         text NOT NULL CHECK (mode IN ('CLEARANCE','REAL_TIME_REPORTING','DEVICE','BATCH_REPORTING')),
  adapter_key  text NOT NULL,
  status       text NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','SANDBOX','LIVE','SUSPENDED')),
  notes        text
);

CREATE TABLE fiscal.registrations (
  id                     uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                 uuid NOT NULL REFERENCES tenant.organizations(id),
  legal_entity_id        uuid NOT NULL,
  location_id            uuid,
  scheme_code            text NOT NULL REFERENCES fiscal.schemes(code),
  device_or_branch_ref   text NOT NULL,          -- authority-issued device serial / branch id / machine code
  credentials_secret_ref text,
  status                 text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','RETIRED')),
  activated_at           timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE (scheme_code, device_or_branch_ref),
  FOREIGN KEY (org_id, legal_entity_id) REFERENCES tenant.legal_entities (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id)
);

CREATE TABLE fiscal.documents (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL,
  registration_id    uuid NOT NULL,
  kind               text NOT NULL CHECK (kind IN ('RECEIPT','INVOICE','CREDIT_NOTE')),
  order_id           uuid,
  invoice_id         uuid,
  refund_id          uuid,
  payload            jsonb NOT NULL,             -- exactly what was submitted
  payload_sha256     text NOT NULL,
  status             text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SUBMITTED','ACCEPTED','REJECTED','FAILED')),
  authority_number   text,                       -- registration / control number
  authority_qr       text,                       -- QR content to print on the receipt
  attempts           smallint NOT NULL DEFAULT 0,
  next_attempt_at    timestamptz,
  last_error         text,
  submitted_at       timestamptz,
  accepted_at        timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, registration_id) REFERENCES fiscal.registrations (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, invoice_id) REFERENCES commerce.invoices (org_id, id),
  FOREIGN KEY (org_id, refund_id) REFERENCES payments.refunds (org_id, id),
  CHECK (num_nonnulls(order_id, invoice_id, refund_id) = 1),
  CHECK (status <> 'ACCEPTED' OR authority_number IS NOT NULL)
);
CREATE UNIQUE INDEX fiscal_documents_order_once ON fiscal.documents (kind, order_id) WHERE order_id IS NOT NULL AND status <> 'REJECTED';
CREATE UNIQUE INDEX fiscal_documents_refund_once ON fiscal.documents (refund_id) WHERE refund_id IS NOT NULL AND status <> 'REJECTED';
CREATE INDEX fiscal_documents_retry_idx ON fiscal.documents (next_attempt_at) WHERE status IN ('PENDING','FAILED');

-- =============================================================================
-- Platform operations
-- =============================================================================

-- Transactional outbox: written in the same transaction as the domain change,
-- published to RabbitMQ after commit by the relay. Consumers are idempotent.
CREATE TABLE platform.outbox (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid,
  aggregate_type text NOT NULL,
  aggregate_id   uuid NOT NULL,
  event_type     text NOT NULL,                  -- appointment.confirmed, order.paid, ticket.issued ...
  event_version  smallint NOT NULL DEFAULT 1,
  payload        jsonb NOT NULL,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  published_at   timestamptz,
  attempts       smallint NOT NULL DEFAULT 0,
  last_error     text
);
CREATE INDEX outbox_unpublished_idx ON platform.outbox (occurred_at) WHERE published_at IS NULL;

CREATE TABLE platform.processed_messages (
  consumer     text NOT NULL,
  message_id   uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, message_id)
);

CREATE TABLE platform.idempotency_keys (
  scope           text NOT NULL,                 -- 'user:<id>' / 'org:<id>' / 'api-client:<id>'
  key             text NOT NULL,
  request_hash    text NOT NULL,
  status          text NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_status smallint,
  response_body   jsonb,
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL DEFAULT now() + interval '48 hours',
  PRIMARY KEY (scope, key)
);

CREATE TABLE platform.audit_log (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  occurred_at     timestamptz NOT NULL DEFAULT now(),
  actor_kind      text NOT NULL CHECK (actor_kind IN ('USER','PLATFORM_STAFF','SYSTEM','API_CLIENT','PROVIDER')),
  actor_user_id   uuid REFERENCES iam.users(id),
  impersonator_user_id uuid REFERENCES iam.users(id),   -- support acting "as" a partner, with consent
  org_id          uuid REFERENCES tenant.organizations(id),
  action          text NOT NULL,                 -- payout.approve, refund.create, member.role_change, customer.export ...
  target_type     text NOT NULL,
  target_id       text NOT NULL,
  before_state    jsonb,
  after_state     jsonb,
  reason          text,
  request_id      text,
  ip_address      inet,
  user_agent      text
);
CREATE INDEX audit_log_org_idx ON platform.audit_log (org_id, occurred_at DESC);
CREATE INDEX audit_log_target_idx ON platform.audit_log (target_type, target_id);
CREATE TRIGGER audit_log_immutable BEFORE UPDATE OR DELETE ON platform.audit_log
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE TABLE platform.feature_flags (
  code            text PRIMARY KEY,
  description     text NOT NULL,
  default_enabled boolean NOT NULL DEFAULT false,
  rollout         jsonb NOT NULL DEFAULT '{}'::jsonb,  -- percentage, markets, plans
  updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.feature_flag_overrides (
  id        uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  flag_code text NOT NULL REFERENCES platform.feature_flags(code) ON DELETE CASCADE,
  market_id uuid REFERENCES ref.markets(id),
  org_id    uuid REFERENCES tenant.organizations(id),
  user_id   uuid REFERENCES iam.users(id),
  enabled   boolean NOT NULL,
  CHECK (num_nonnulls(market_id, org_id, user_id) = 1),
  UNIQUE NULLS NOT DISTINCT (flag_code, market_id, org_id, user_id)
);

CREATE TABLE platform.settings (
  key        text PRIMARY KEY,
  value      jsonb NOT NULL,
  updated_by uuid REFERENCES iam.users(id),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Organization API keys for the public partner API and embeds.
CREATE TABLE platform.api_clients (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid NOT NULL REFERENCES tenant.organizations(id),
  name         text NOT NULL,
  key_prefix   text NOT NULL UNIQUE,             -- shown in UI: bv_live_ab12...
  key_hash     text NOT NULL,
  scopes       text[] NOT NULL,
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
  created_by   uuid NOT NULL REFERENCES iam.users(id),
  last_used_at timestamptz,
  expires_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE platform.webhook_endpoints (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL REFERENCES tenant.organizations(id),
  url           text NOT NULL CHECK (url ~ '^https://'),
  secret_ref    text NOT NULL,
  event_types   text[] NOT NULL,
  status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','DISABLED')),
  failure_count integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE platform.webhook_deliveries (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  endpoint_id     uuid NOT NULL,
  outbox_id       uuid NOT NULL REFERENCES platform.outbox(id),
  status          text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','FAILED','DEAD')),
  attempts        smallint NOT NULL DEFAULT 0,
  response_status smallint,
  next_attempt_at timestamptz,
  delivered_at    timestamptz,
  UNIQUE (endpoint_id, outbox_id),
  FOREIGN KEY (org_id, endpoint_id) REFERENCES platform.webhook_endpoints (org_id, id) ON DELETE CASCADE
);

CREATE TABLE platform.integrations (
  id                     uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                 uuid NOT NULL REFERENCES tenant.organizations(id),
  provider               text NOT NULL CHECK (provider IN ('GOOGLE_RESERVE','GOOGLE_CALENDAR','META_BOOKING','INSTAGRAM','TIKTOK','ZOOM','YOUTUBE',
                                                           'META_PIXEL','GOOGLE_ANALYTICS','TIKTOK_PIXEL','XERO','QUICKBOOKS','PEACHTREE_EXPORT','MAILCHIMP')),
  status                 text NOT NULL DEFAULT 'CONNECTED' CHECK (status IN ('CONNECTED','ERROR','DISCONNECTED')),
  config                 jsonb NOT NULL DEFAULT '{}'::jsonb,
  credentials_secret_ref text,
  connected_by           uuid REFERENCES iam.users(id),
  connected_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, provider)
);

CREATE TABLE platform.data_exports (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id       uuid REFERENCES tenant.organizations(id),
  requested_by uuid NOT NULL REFERENCES iam.users(id),
  kind         text NOT NULL,                    -- CUSTOMERS, SALES, ATTENDEES, APPOINTMENTS, PAYOUTS, ACCOUNTING_JOURNAL
  params       jsonb NOT NULL DEFAULT '{}'::jsonb,
  status       text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','READY','FAILED','EXPIRED')),
  file_id      uuid REFERENCES platform.files(id),
  expires_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);

-- Privacy rights requests (Ethiopia PDPP 1321/2024, Kenya DPA 2019, ...).
CREATE TABLE platform.data_subject_requests (
  id           uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  user_id      uuid NOT NULL REFERENCES iam.users(id),
  kind         text NOT NULL CHECK (kind IN ('ACCESS','PORTABILITY','RECTIFICATION','ERASURE','RESTRICTION','OBJECTION')),
  status       text NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','VERIFYING','IN_PROGRESS','COMPLETED','REJECTED')),
  due_at       timestamptz NOT NULL,
  handled_by   uuid REFERENCES iam.users(id),
  result_file_id uuid REFERENCES platform.files(id),
  notes        text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz
);

-- Support desk shared by consumers and partners; staff see both sides.
CREATE TABLE platform.support_cases (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  requester_user_id uuid REFERENCES iam.users(id),
  org_id            uuid REFERENCES tenant.organizations(id),
  market_id         uuid REFERENCES ref.markets(id),
  audience          text NOT NULL CHECK (audience IN ('CONSUMER','PARTNER')),
  category          text NOT NULL CHECK (category IN ('BOOKING','TICKETS','PAYMENT','REFUND','PAYOUT','ACCOUNT','VERIFICATION','TECHNICAL','SAFETY','OTHER')),
  subject           text NOT NULL,
  priority          text NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
  status            text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PENDING_REQUESTER','PENDING_PARTNER','ESCALATED','RESOLVED','CLOSED')),
  assigned_to       uuid REFERENCES iam.users(id),
  order_id          uuid REFERENCES commerce.orders(id),
  appointment_id    uuid REFERENCES scheduling.appointments(id),
  ticket_id         uuid REFERENCES events.tickets(id),
  payout_id         uuid REFERENCES payments.payouts(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  resolved_at       timestamptz
);
CREATE INDEX support_cases_queue_idx ON platform.support_cases (status, priority, created_at);

CREATE TABLE platform.support_messages (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  case_id        uuid NOT NULL REFERENCES platform.support_cases(id) ON DELETE CASCADE,
  author_user_id uuid REFERENCES iam.users(id),
  is_internal    boolean NOT NULL DEFAULT false,
  body           text NOT NULL,
  file_id        uuid REFERENCES platform.files(id),
  created_at     timestamptz NOT NULL DEFAULT now()
);

-- Marketplace content managed from the admin console (home hero, banners, help articles).
CREATE TABLE platform.cms_entries (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  market_id     uuid REFERENCES ref.markets(id),
  kind          text NOT NULL CHECK (kind IN ('HOME_HERO','BANNER','HELP_ARTICLE','LANDING_PAGE','ANNOUNCEMENT')),
  slug          citext NOT NULL,
  language_code text NOT NULL REFERENCES ref.languages(code),
  content       jsonb NOT NULL,
  status        text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
  visible_during tstzrange,
  updated_by    uuid REFERENCES iam.users(id),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (market_id, kind, slug, language_code)
);
