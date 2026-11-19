-- =============================================================================
-- 18: comms — notifications (transactional + marketing delivery), two-way
-- conversations, AI concierge. Channels are provider-agnostic the same way as
-- payments: SMS (Ethio telecom, Africa's Talking, Twilio ...), WhatsApp Cloud
-- API, Telegram bot, email, push.
-- =============================================================================

CREATE TABLE comms.provider_accounts (
  id                     uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  channel                text NOT NULL CHECK (channel IN ('SMS','WHATSAPP','TELEGRAM','EMAIL','PUSH','VOICE')),
  provider_code          text NOT NULL,          -- ETHIO_TELECOM_SMS, AFRICAS_TALKING, TWILIO, META_WHATSAPP, TELEGRAM_BOT, AWS_SES, FCM, APNS
  market_id              uuid REFERENCES ref.markets(id),
  sender_id              text,                   -- alphanumeric sender / short code / from address / bot username
  credentials_secret_ref text NOT NULL,
  priority               smallint NOT NULL DEFAULT 100,
  unit_cost_minor        bigint,
  cost_currency          char(3) REFERENCES ref.currencies(code),
  status                 text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEGRADED','DISABLED')),
  created_at             timestamptz NOT NULL DEFAULT now()
);

-- Platform default templates (org_id NULL) with optional organization overrides.
CREATE TABLE comms.templates (
  id                    uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                uuid REFERENCES tenant.organizations(id),
  code                  text NOT NULL,           -- APPOINTMENT_CONFIRMED, APPOINTMENT_REMINDER_24H, TICKETS_ISSUED, OTP ...
  channel               text NOT NULL CHECK (channel IN ('SMS','WHATSAPP','TELEGRAM','EMAIL','PUSH','IN_APP')),
  language_code         text NOT NULL REFERENCES ref.languages(code),
  version               integer NOT NULL DEFAULT 1,
  subject               text,
  body                  text NOT NULL,
  provider_template_ref text,                    -- pre-approved WhatsApp template name
  status                text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (org_id, code, channel, language_code, version)
);

CREATE TABLE comms.notifications (
  id                    uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id                uuid REFERENCES tenant.organizations(id),
  recipient_user_id     uuid REFERENCES iam.users(id),
  recipient_customer_id uuid,
  category              text NOT NULL CHECK (category IN ('TRANSACTIONAL','REMINDER','SECURITY','MARKETING','OPERATIONAL')),
  template_code         text NOT NULL,
  data                  jsonb NOT NULL DEFAULT '{}'::jsonb,
  show_in_app           boolean NOT NULL DEFAULT true,
  read_at               timestamptz,
  dedupe_key            text UNIQUE,             -- e.g. 'reminder24h:<appointment id>'
  send_after            timestamptz NOT NULL DEFAULT now(),  -- quiet hours / scheduled reminders
  created_at            timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, recipient_customer_id) REFERENCES crm.customers (org_id, id),
  CHECK (recipient_user_id IS NOT NULL OR recipient_customer_id IS NOT NULL)
);
CREATE INDEX notifications_inbox_idx ON comms.notifications (recipient_user_id, created_at DESC) WHERE show_in_app;

CREATE TABLE comms.deliveries (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid,
  notification_id     uuid NOT NULL REFERENCES comms.notifications(id),
  channel             text NOT NULL CHECK (channel IN ('SMS','WHATSAPP','TELEGRAM','EMAIL','PUSH','VOICE')),
  provider_account_id uuid REFERENCES comms.provider_accounts(id),
  destination_masked  text NOT NULL,
  status              text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','SENT','DELIVERED','READ','FAILED','BOUNCED','SUPPRESSED')),
  provider_message_id text,
  attempts            smallint NOT NULL DEFAULT 0,
  next_attempt_at     timestamptz,
  cost_minor          bigint,
  cost_currency       char(3) REFERENCES ref.currencies(code),
  error               text,
  sent_at             timestamptz,
  delivered_at        timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (notification_id, channel)
);
CREATE INDEX deliveries_retry_idx ON comms.deliveries (next_attempt_at) WHERE status = 'QUEUED';

ALTER TABLE marketing.campaign_recipients
  ADD CONSTRAINT campaign_recipients_delivery_fk FOREIGN KEY (delivery_id) REFERENCES comms.deliveries(id);

CREATE TABLE comms.preferences (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  user_id     uuid NOT NULL REFERENCES iam.users(id) ON DELETE CASCADE,
  org_id      uuid REFERENCES tenant.organizations(id),  -- NULL = platform-wide
  category    text NOT NULL CHECK (category IN ('TRANSACTIONAL','REMINDER','MARKETING','RECOMMENDATIONS')),
  channel     text NOT NULL CHECK (channel IN ('SMS','WHATSAPP','TELEGRAM','EMAIL','PUSH')),
  enabled     boolean NOT NULL,
  quiet_hours jsonb,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (user_id, org_id, category, channel)
);

CREATE TABLE comms.suppressions (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  channel          text NOT NULL,
  destination_hash text NOT NULL,
  org_id           uuid REFERENCES tenant.organizations(id),  -- NULL = global (hard bounce, STOP to platform sender)
  reason           text NOT NULL CHECK (reason IN ('UNSUBSCRIBED','STOP_KEYWORD','HARD_BOUNCE','COMPLAINT','INVALID_NUMBER')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (channel, destination_hash, org_id)
);

-- -----------------------------------------------------------------------------
-- Conversations: customer <-> business chat (in-app, or bridged from WhatsApp/
-- Telegram/SMS), event inquiries, and internal team chat.
-- -----------------------------------------------------------------------------
CREATE TABLE comms.conversations (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id             uuid NOT NULL REFERENCES tenant.organizations(id),
  kind               text NOT NULL CHECK (kind IN ('CUSTOMER','EVENT_INQUIRY','TEAM')),
  customer_id        uuid,
  appointment_id     uuid,
  order_id           uuid,
  event_id           uuid,
  origin_channel     text NOT NULL DEFAULT 'APP' CHECK (origin_channel IN ('APP','WEB','WHATSAPP','TELEGRAM','SMS')),
  subject            text,
  status             text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SNOOZED','CLOSED')),
  assigned_member_id uuid,
  last_message_at    timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, assigned_member_id) REFERENCES tenant.members (org_id, id),
  CHECK (kind = 'TEAM' OR customer_id IS NOT NULL)
);

CREATE TABLE comms.participants (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL,
  conversation_id      uuid NOT NULL,
  user_id              uuid REFERENCES iam.users(id),
  role                 text NOT NULL CHECK (role IN ('CUSTOMER','STAFF','AI_AGENT')),
  last_read_at         timestamptz,
  muted_until          timestamptz,
  joined_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  UNIQUE NULLS NOT DISTINCT (conversation_id, user_id, role),
  FOREIGN KEY (org_id, conversation_id) REFERENCES comms.conversations (org_id, id) ON DELETE CASCADE,
  CHECK (role <> 'STAFF' OR user_id IS NOT NULL)  -- customers bridged from WhatsApp/SMS may have no account
);

CREATE TABLE comms.messages (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL,
  conversation_id     uuid NOT NULL,
  sender_participant_id uuid NOT NULL,
  kind                text NOT NULL DEFAULT 'TEXT' CHECK (kind IN ('TEXT','IMAGE','FILE','SYSTEM','PAYMENT_LINK','BOOKING_CARD')),
  body                text,
  file_id             uuid,
  reply_to_id         uuid,
  external_message_id text,                      -- WhatsApp/Telegram id when bridged
  created_at          timestamptz NOT NULL DEFAULT now(),
  edited_at           timestamptz,
  deleted_at          timestamptz,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, conversation_id) REFERENCES comms.conversations (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, sender_participant_id) REFERENCES comms.participants (org_id, id),
  FOREIGN KEY (org_id, file_id) REFERENCES platform.files (org_id, id),
  FOREIGN KEY (org_id, reply_to_id) REFERENCES comms.messages (org_id, id),
  CHECK (body IS NOT NULL OR file_id IS NOT NULL OR deleted_at IS NOT NULL)
);
CREATE INDEX messages_conversation_idx ON comms.messages (conversation_id, created_at);

-- -----------------------------------------------------------------------------
-- AI concierge (answers questions, checks availability, books, sends payment links)
-- -----------------------------------------------------------------------------
CREATE TABLE comms.ai_agents (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL REFERENCES tenant.organizations(id),
  channels       text[] NOT NULL DEFAULT '{APP,WEB}',
  languages      text[] NOT NULL DEFAULT '{en}',
  persona        jsonb NOT NULL DEFAULT '{}'::jsonb,
  allowed_actions text[] NOT NULL DEFAULT '{ANSWER,CHECK_AVAILABILITY}',
  handoff_rules  jsonb NOT NULL DEFAULT '{}'::jsonb,
  status         text NOT NULL DEFAULT 'DISABLED' CHECK (status IN ('ENABLED','DISABLED')),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE comms.ai_knowledge (
  id         uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id     uuid NOT NULL,
  agent_id   uuid NOT NULL,
  title      text NOT NULL,
  content    text NOT NULL,
  language_code text REFERENCES ref.languages(code),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, agent_id) REFERENCES comms.ai_agents (org_id, id) ON DELETE CASCADE
);

CREATE TABLE comms.ai_sessions (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL,
  agent_id        uuid NOT NULL,
  conversation_id uuid,
  channel         text NOT NULL,
  outcome         text CHECK (outcome IN ('ANSWERED','BOOKED','PAYMENT_LINK_SENT','HANDED_OFF','ABANDONED')),
  input_tokens    integer NOT NULL DEFAULT 0,
  output_tokens   integer NOT NULL DEFAULT 0,
  voice_seconds   integer NOT NULL DEFAULT 0,
  started_at      timestamptz NOT NULL DEFAULT now(),
  ended_at        timestamptz,
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, agent_id) REFERENCES comms.ai_agents (org_id, id),
  FOREIGN KEY (org_id, conversation_id) REFERENCES comms.conversations (org_id, id)
);

-- Every action the AI takes is logged with input/output for audit and dispute handling.
CREATE TABLE comms.ai_actions (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL,
  session_id  uuid NOT NULL,
  action      text NOT NULL CHECK (action IN ('CHECK_AVAILABILITY','CREATE_APPOINTMENT','RESCHEDULE','CANCEL','SEND_PAYMENT_LINK','LOOKUP_ORDER','HANDOFF')),
  request     jsonb NOT NULL,
  result      jsonb,
  status      text NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','REJECTED_BY_POLICY')),
  appointment_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, session_id) REFERENCES comms.ai_sessions (org_id, id),
  FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id)
);
CREATE TRIGGER ai_actions_immutable BEFORE UPDATE OR DELETE ON comms.ai_actions
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();
