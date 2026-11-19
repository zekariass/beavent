-- =============================================================================
-- 17: marketing — segments, campaigns (SMS/WhatsApp/Telegram/email/push),
-- automations, referrals, tracking links, marketplace ads.
-- Transactional messages (confirmations, reminders, tickets) are NOT marketing;
-- they live in comms and do not require marketing consent.
-- =============================================================================

CREATE TABLE marketing.segments (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid NOT NULL REFERENCES tenant.organizations(id),
  name             text NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('DYNAMIC','STATIC')),
  definition       jsonb,                        -- rule tree for DYNAMIC (last visit, spend, tags, events attended ...)
  member_count     integer,
  computed_at      timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  CHECK (kind <> 'DYNAMIC' OR definition IS NOT NULL)
);

CREATE TABLE marketing.segment_members (
  org_id      uuid NOT NULL,
  segment_id  uuid NOT NULL,
  customer_id uuid NOT NULL,
  added_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (segment_id, customer_id),
  FOREIGN KEY (org_id, segment_id) REFERENCES marketing.segments (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id) ON DELETE CASCADE
);

ALTER TABLE commerce.promotions
  ADD CONSTRAINT promotions_segment_fk FOREIGN KEY (org_id, segment_id) REFERENCES marketing.segments (org_id, id);

CREATE TABLE marketing.campaigns (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL REFERENCES tenant.organizations(id),
  name            text NOT NULL,
  channel         text NOT NULL CHECK (channel IN ('SMS','WHATSAPP','TELEGRAM','EMAIL','PUSH')),
  audience        text NOT NULL CHECK (audience IN ('SEGMENT','FOLLOWERS','EVENT_ATTENDEES','ALL_CUSTOMERS')),
  segment_id      uuid,
  event_id        uuid,
  content         jsonb NOT NULL,                -- per-language subject/body, media, CTA
  promotion_id    uuid REFERENCES commerce.promotions(id),
  status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SCHEDULED','SENDING','SENT','CANCELLED','FAILED')),
  scheduled_at    timestamptz,
  sent_at         timestamptz,
  stats           jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by      uuid REFERENCES iam.users(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, segment_id) REFERENCES marketing.segments (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  CHECK (audience <> 'SEGMENT' OR segment_id IS NOT NULL),
  CHECK (audience <> 'EVENT_ATTENDEES' OR event_id IS NOT NULL)
);

CREATE TABLE marketing.campaign_recipients (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id            uuid NOT NULL,
  campaign_id       uuid NOT NULL,
  customer_id       uuid,
  user_id           uuid REFERENCES iam.users(id),
  destination_hash  text NOT NULL,
  status            text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','SUPPRESSED_NO_CONSENT','SUPPRESSED_OPTED_OUT','SENT','DELIVERED','FAILED')),
  delivery_id       uuid,                        -- comms.deliveries (FK in V1_18)
  opened_at         timestamptz,
  clicked_at        timestamptz,
  converted_order_id uuid,
  UNIQUE (campaign_id, destination_hash),
  FOREIGN KEY (org_id, campaign_id) REFERENCES marketing.campaigns (org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, converted_order_id) REFERENCES commerce.orders (org_id, id)
);

CREATE TABLE marketing.automations (
  id         uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id     uuid NOT NULL REFERENCES tenant.organizations(id),
  name       text NOT NULL,
  trigger    text NOT NULL CHECK (trigger IN ('APPOINTMENT_COMPLETED','REBOOK_DUE','BIRTHDAY','LAPSED_CUSTOMER','FIRST_VISIT',
                                              'CHECKOUT_ABANDONED','TICKET_PURCHASED','EVENT_ENDED','MEMBERSHIP_EXPIRING',
                                              'PACKAGE_LOW_BALANCE','REVIEW_REQUEST','FOLLOWED_ORGANIZER')),
  config     jsonb NOT NULL DEFAULT '{}'::jsonb,
  status     text NOT NULL DEFAULT 'PAUSED' CHECK (status IN ('ACTIVE','PAUSED','ARCHIVED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE marketing.automation_steps (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  automation_id uuid NOT NULL,
  seq           smallint NOT NULL CHECK (seq > 0),
  action        text NOT NULL CHECK (action IN ('WAIT','SEND_MESSAGE','APPLY_TAG','OFFER_PROMOTION','NOTIFY_TEAM','BRANCH')),
  config        jsonb NOT NULL,
  UNIQUE (automation_id, seq),
  FOREIGN KEY (org_id, automation_id) REFERENCES marketing.automations (org_id, id) ON DELETE CASCADE
);

CREATE TABLE marketing.automation_runs (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id        uuid NOT NULL,
  automation_id uuid NOT NULL,
  customer_id   uuid NOT NULL,
  dedupe_key    text NOT NULL,                   -- e.g. appointment id, so one trigger fires once
  current_seq   smallint NOT NULL DEFAULT 1,
  status        text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','WAITING','COMPLETED','EXITED','FAILED')),
  next_run_at   timestamptz,
  context       jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (automation_id, customer_id, dedupe_key),
  FOREIGN KEY (org_id, automation_id) REFERENCES marketing.automations (org_id, id),
  FOREIGN KEY (org_id, customer_id) REFERENCES crm.customers (org_id, id)
);
CREATE INDEX automation_runs_due_idx ON marketing.automation_runs (next_run_at) WHERE status IN ('RUNNING','WAITING');

CREATE TABLE marketing.referral_programs (
  id              uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id          uuid NOT NULL REFERENCES tenant.organizations(id),
  referrer_reward jsonb NOT NULL,                -- {"kind":"AMOUNT_CREDIT","amount_minor":20000}
  referee_reward  jsonb NOT NULL,
  qualifying_rule jsonb NOT NULL DEFAULT '{"min_order_minor":0}'::jsonb,
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','ENDED')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id)
);

CREATE TABLE marketing.referrals (
  id                   uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id               uuid NOT NULL,
  program_id           uuid NOT NULL,
  referrer_customer_id uuid NOT NULL,
  referee_customer_id  uuid,
  code                 citext NOT NULL,
  status               text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','QUALIFIED','REWARDED','VOID')),
  qualifying_order_id  uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, code),
  UNIQUE (program_id, referee_customer_id),
  FOREIGN KEY (org_id, program_id) REFERENCES marketing.referral_programs (org_id, id),
  FOREIGN KEY (org_id, referrer_customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, referee_customer_id) REFERENCES crm.customers (org_id, id),
  FOREIGN KEY (org_id, qualifying_order_id) REFERENCES commerce.orders (org_id, id),
  CHECK (referee_customer_id IS NULL OR referee_customer_id <> referrer_customer_id)
);

CREATE TABLE marketing.tracking_links (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid NOT NULL REFERENCES tenant.organizations(id),
  code        citext NOT NULL UNIQUE,
  name        text NOT NULL,
  event_id    uuid,
  location_id uuid,
  utm         jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id)
);

CREATE TABLE marketing.tracking_link_daily (
  org_id        uuid NOT NULL,
  link_id       uuid NOT NULL,
  day           date NOT NULL,
  clicks        integer NOT NULL DEFAULT 0,
  orders        integer NOT NULL DEFAULT 0,
  revenue_minor bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (link_id, day),
  FOREIGN KEY (org_id, link_id) REFERENCES marketing.tracking_links (org_id, id) ON DELETE CASCADE
);

-- Marketplace promotion (Eventbrite Ads / featured listings).
CREATE TABLE marketing.ad_campaigns (
  id             uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id         uuid NOT NULL REFERENCES tenant.organizations(id),
  market_id      uuid NOT NULL REFERENCES ref.markets(id),
  event_id       uuid,
  location_id    uuid,
  placements     text[] NOT NULL CHECK (placements <@ ARRAY['HOME_FEED','SEARCH','CATEGORY','NEARBY','EVENT_RECOMMENDATIONS']::text[]),
  pricing        text NOT NULL CHECK (pricing IN ('CPC','CPM','FLAT_DAILY')),
  bid_minor      bigint NOT NULL CHECK (bid_minor > 0),
  budget_minor   bigint NOT NULL CHECK (budget_minor > 0),
  spent_minor    bigint NOT NULL DEFAULT 0 CHECK (spent_minor >= 0),
  currency_code  char(3) NOT NULL REFERENCES ref.currencies(code),
  targeting      jsonb NOT NULL DEFAULT '{}'::jsonb,
  runs_during    tstzrange NOT NULL,
  status         text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_REVIEW','ACTIVE','PAUSED','EXHAUSTED','ENDED','REJECTED')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  CHECK (num_nonnulls(event_id, location_id) = 1),
  CHECK (spent_minor <= budget_minor)
);

CREATE TABLE marketing.ad_daily_stats (
  org_id        uuid NOT NULL,
  campaign_id   uuid NOT NULL,
  day           date NOT NULL,
  impressions   integer NOT NULL DEFAULT 0,
  clicks        integer NOT NULL DEFAULT 0,
  spend_minor   bigint NOT NULL DEFAULT 0,
  orders        integer NOT NULL DEFAULT 0,
  revenue_minor bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (campaign_id, day),
  FOREIGN KEY (org_id, campaign_id) REFERENCES marketing.ad_campaigns (org_id, id) ON DELETE CASCADE
);
