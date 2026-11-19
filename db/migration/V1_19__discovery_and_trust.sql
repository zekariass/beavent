-- =============================================================================
-- 19: discovery (marketplace read model, reviews, social graph, collections)
--     trust (KYB/KYC, risk, moderation, fraud signals)
-- =============================================================================

-- Denormalised, public-only projection of everything discoverable. Rebuilt from
-- domain events by the discovery projector; never written by request handlers.
-- "Places to book" and "things to do" are searched together.
CREATE TABLE discovery.listings (
  id                uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  kind              text NOT NULL CHECK (kind IN ('LOCATION','EVENT')),
  org_id            uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id       uuid REFERENCES tenant.locations(id),
  event_id          uuid REFERENCES events.events(id),
  market_id         uuid NOT NULL REFERENCES ref.markets(id),
  slug_path         text NOT NULL UNIQUE,        -- /addis-ababa/salon/abeba-beauty-bole, /events/addis-jazz-night
  title             text NOT NULL,
  title_i18n        jsonb NOT NULL DEFAULT '{}'::jsonb,
  summary_i18n      jsonb NOT NULL DEFAULT '{}'::jsonb,
  image_url         text,
  category_ids      uuid[] NOT NULL DEFAULT '{}',
  tags              text[] NOT NULL DEFAULT '{}',
  geo               geography(Point, 4326),
  admin_area_path   ltree,
  is_online         boolean NOT NULL DEFAULT false,
  price_from_minor  bigint,
  currency_code     char(3) REFERENCES ref.currencies(code),
  rating_avg        numeric(3,2),
  rating_count      integer NOT NULL DEFAULT 0,
  next_available_at timestamptz,                 -- LOCATION: earliest bookable slot (refreshed periodically)
  next_starts_at    timestamptz,                 -- EVENT: next upcoming occurrence
  last_ends_at      timestamptz,
  attributes        jsonb NOT NULL DEFAULT '{}'::jsonb, -- audience, delivery modes, languages spoken, amenities, accepts mobile money ...
  quality_score     numeric(6,3) NOT NULL DEFAULT 0,
  is_listed         boolean NOT NULL DEFAULT true,
  search_document   tsvector,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CHECK ((kind = 'LOCATION') = (location_id IS NOT NULL)),
  CHECK ((kind = 'EVENT') = (event_id IS NOT NULL))
);
CREATE INDEX listings_geo_gist ON discovery.listings USING gist (geo) WHERE is_listed;
CREATE INDEX listings_search_gin ON discovery.listings USING gin (search_document) WHERE is_listed;
CREATE INDEX listings_title_trgm ON discovery.listings USING gin (title gin_trgm_ops);
CREATE INDEX listings_categories_gin ON discovery.listings USING gin (category_ids);
CREATE INDEX listings_event_time_idx ON discovery.listings (market_id, next_starts_at) WHERE kind = 'EVENT' AND is_listed;
CREATE INDEX listings_area_gist ON discovery.listings USING gist (admin_area_path);

-- Verified reviews only: a review must reference a completed appointment or a ticket order.
CREATE TABLE discovery.reviews (
  id                  uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id              uuid NOT NULL REFERENCES tenant.organizations(id),
  location_id         uuid,
  staff_id            uuid,
  event_id            uuid,
  author_user_id      uuid NOT NULL REFERENCES iam.users(id),
  appointment_id      uuid,
  order_id            uuid,
  rating              smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
  sub_ratings         jsonb NOT NULL DEFAULT '{}'::jsonb,  -- cleanliness, value, punctuality, organisation, sound ...
  body                text,
  language_code       text REFERENCES ref.languages(code),
  status              text NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PENDING_MODERATION','PUBLISHED','HIDDEN','REMOVED')),
  response_body       text,
  responded_by        uuid REFERENCES iam.users(id),
  responded_at        timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  FOREIGN KEY (org_id, staff_id) REFERENCES team.staff (org_id, id),
  FOREIGN KEY (org_id, event_id) REFERENCES events.events (org_id, id),
  FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id),
  FOREIGN KEY (org_id, order_id) REFERENCES commerce.orders (org_id, id),
  CHECK (num_nonnulls(appointment_id, order_id) = 1),
  CHECK (appointment_id IS NULL OR location_id IS NOT NULL),
  CHECK (order_id IS NULL OR event_id IS NOT NULL)
);
CREATE UNIQUE INDEX reviews_one_per_appointment ON discovery.reviews (author_user_id, appointment_id) WHERE appointment_id IS NOT NULL;
CREATE UNIQUE INDEX reviews_one_per_order ON discovery.reviews (author_user_id, order_id) WHERE order_id IS NOT NULL;
CREATE INDEX reviews_location_idx ON discovery.reviews (location_id, created_at DESC) WHERE status = 'PUBLISHED';
CREATE INDEX reviews_event_idx ON discovery.reviews (event_id, created_at DESC) WHERE status = 'PUBLISHED';

CREATE TABLE discovery.follows (
  user_id    uuid NOT NULL REFERENCES iam.users(id) ON DELETE CASCADE,
  org_id     uuid NOT NULL REFERENCES tenant.organizations(id),
  source     text NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','AFTER_PURCHASE','AFTER_VISIT')),
  notify     boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, org_id)
);
CREATE INDEX follows_org_idx ON discovery.follows (org_id);

CREATE TABLE discovery.saved_items (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  user_id     uuid NOT NULL REFERENCES iam.users(id) ON DELETE CASCADE,
  listing_id  uuid REFERENCES discovery.listings(id) ON DELETE CASCADE,
  staff_id    uuid REFERENCES team.staff(id) ON DELETE CASCADE,  -- favourite professional
  created_at  timestamptz NOT NULL DEFAULT now(),
  CHECK (num_nonnulls(listing_id, staff_id) = 1),
  UNIQUE NULLS NOT DISTINCT (user_id, listing_id, staff_id)
);

CREATE TABLE discovery.collections (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  owner       text NOT NULL CHECK (owner IN ('PLATFORM','ORGANIZATION','USER')),
  org_id      uuid REFERENCES tenant.organizations(id),
  user_id     uuid REFERENCES iam.users(id),
  market_id   uuid REFERENCES ref.markets(id),
  slug        citext NOT NULL,
  title_i18n  jsonb NOT NULL,
  description_i18n jsonb NOT NULL DEFAULT '{}'::jsonb,
  cover_file_id uuid REFERENCES platform.files(id),
  visibility  text NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PUBLIC','UNLISTED','PRIVATE')),
  featured_during tstzrange,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (owner, org_id, user_id, slug),
  CHECK ((owner = 'ORGANIZATION') = (org_id IS NOT NULL)),
  CHECK ((owner = 'USER') = (user_id IS NOT NULL))
);

CREATE TABLE discovery.collection_items (
  collection_id uuid NOT NULL REFERENCES discovery.collections(id) ON DELETE CASCADE,
  listing_id    uuid NOT NULL REFERENCES discovery.listings(id) ON DELETE CASCADE,
  sort_order    smallint NOT NULL DEFAULT 0,
  note          text,
  PRIMARY KEY (collection_id, listing_id)
);

-- Search analytics (high volume; partition by month in production).
CREATE TABLE discovery.search_events (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  user_id          uuid REFERENCES iam.users(id) ON DELETE SET NULL,
  anonymous_id     text,
  market_id        uuid REFERENCES ref.markets(id),
  query            text,
  filters          jsonb NOT NULL DEFAULT '{}'::jsonb,
  result_count     integer,
  clicked_listing_id uuid,
  occurred_at      timestamptz NOT NULL DEFAULT now()
);

-- =============================================================================
-- Trust & safety
-- =============================================================================
CREATE TABLE trust.verification_cases (
  id                 uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  subject            text NOT NULL CHECK (subject IN ('ORGANIZATION_KYB','OWNER_KYC','PAYOUT_ACCOUNT','EVENT_REVIEW','PROFESSIONAL_LICENSE')),
  org_id             uuid REFERENCES tenant.organizations(id),
  user_id            uuid REFERENCES iam.users(id),
  legal_entity_id    uuid REFERENCES tenant.legal_entities(id),
  payout_account_id  uuid REFERENCES payments.payout_accounts(id),
  event_id           uuid REFERENCES events.events(id),
  provider           text NOT NULL DEFAULT 'MANUAL' CHECK (provider IN ('MANUAL','FAYDA','SMILE_ID','BANK_NAME_ENQUIRY')),
  provider_reference text,
  status             text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','AWAITING_INFO','IN_REVIEW','APPROVED','REJECTED','EXPIRED')),
  risk_level         text CHECK (risk_level IN ('LOW','MEDIUM','HIGH')),
  assigned_to        uuid REFERENCES iam.users(id),
  decided_by         uuid REFERENCES iam.users(id),
  decided_at         timestamptz,
  decision_reason    text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (num_nonnulls(org_id, user_id) >= 1),
  CHECK (status NOT IN ('APPROVED','REJECTED') OR decided_by IS NOT NULL OR provider <> 'MANUAL')
);
CREATE INDEX verification_cases_queue_idx ON trust.verification_cases (status, created_at) WHERE status IN ('OPEN','IN_REVIEW');

CREATE TABLE trust.verification_documents (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  case_id       uuid NOT NULL REFERENCES trust.verification_cases(id) ON DELETE CASCADE,
  doc_type      text NOT NULL CHECK (doc_type IN ('TRADE_LICENSE','TIN_CERTIFICATE','VAT_CERTIFICATE','NATIONAL_ID','FAYDA_ID','PASSPORT','KEBELE_ID','BANK_LETTER','PROFESSIONAL_LICENSE','VENUE_PERMIT','MEMORANDUM_OF_ASSOCIATION')),
  file_id       uuid NOT NULL REFERENCES platform.files(id),
  number_masked text,
  expires_on    date,
  status        text NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','ACCEPTED','REJECTED')),
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- Payout risk controls per organization (set by risk team; defaults by market/plan).
CREATE TABLE trust.risk_profiles (
  org_id                    uuid PRIMARY KEY REFERENCES tenant.organizations(id),
  tier                      text NOT NULL DEFAULT 'NEW' CHECK (tier IN ('NEW','STANDARD','TRUSTED','HIGH_RISK','BLOCKED')),
  payout_delay_days         smallint NOT NULL DEFAULT 7 CHECK (payout_delay_days >= 0),
  reserve_bps               integer NOT NULL DEFAULT 0 CHECK (reserve_bps BETWEEN 0 AND 10000),
  -- Event organizers: share of not-yet-delivered ticket revenue that may be paid out before the event.
  pre_event_payout_bps      integer NOT NULL DEFAULT 0 CHECK (pre_event_payout_bps BETWEEN 0 AND 10000),
  manual_payout_review      boolean NOT NULL DEFAULT true,
  max_single_payout_minor   bigint,
  -- PROVIDER_SPLIT: above this outstanding SELLER_RECEIVABLE, refunds need seller pre-funding or admin approval.
  max_outstanding_receivable_minor bigint CHECK (max_outstanding_receivable_minor IS NULL OR max_outstanding_receivable_minor >= 0),
  currency_code             char(3) REFERENCES ref.currencies(code),
  notes                     text,
  reviewed_by               uuid REFERENCES iam.users(id),
  reviewed_at               timestamptz,
  updated_at                timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trust.content_reports (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  reporter_user_id uuid REFERENCES iam.users(id),
  org_id           uuid REFERENCES tenant.organizations(id),
  event_id         uuid REFERENCES events.events(id),
  review_id        uuid REFERENCES discovery.reviews(id),
  message_id       uuid REFERENCES comms.messages(id),
  reason           text NOT NULL CHECK (reason IN ('SCAM','FAKE_EVENT','INAPPROPRIATE','HARASSMENT','IP_INFRINGEMENT','UNSAFE','MISLEADING_PRICE','OTHER')),
  details          text,
  status           text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','ACTIONED','DISMISSED')),
  resolved_by      uuid REFERENCES iam.users(id),
  resolved_at      timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (num_nonnulls(org_id, event_id, review_id, message_id) >= 1)
);

CREATE TABLE trust.moderation_actions (
  id            uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  report_id     uuid REFERENCES trust.content_reports(id),
  org_id        uuid REFERENCES tenant.organizations(id),
  event_id      uuid REFERENCES events.events(id),
  review_id     uuid REFERENCES discovery.reviews(id),
  user_id       uuid REFERENCES iam.users(id),
  action        text NOT NULL CHECK (action IN ('UNPUBLISH','HIDE','WARN','RESTRICT_PAYOUTS','SUSPEND','RESTORE','REQUIRE_REVIEW','BAN_USER')),
  reason        text NOT NULL,
  actor_user_id uuid NOT NULL REFERENCES iam.users(id),
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (num_nonnulls(org_id, event_id, review_id, user_id) >= 1)
);
CREATE TRIGGER moderation_actions_immutable BEFORE UPDATE OR DELETE ON trust.moderation_actions
  FOR EACH ROW EXECUTE FUNCTION platform.reject_mutation();

CREATE TABLE trust.fraud_signals (
  id          uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id      uuid REFERENCES tenant.organizations(id),
  user_id     uuid REFERENCES iam.users(id),
  order_id    uuid REFERENCES commerce.orders(id),
  attempt_id  uuid REFERENCES payments.payment_attempts(id),
  signal      text NOT NULL CHECK (signal IN ('VELOCITY','DEVICE_REUSE','MANY_FAILED_PAYMENTS','HIGH_RISK_MSISDN','BOT_TRAFFIC','CARD_COUNTRY_MISMATCH','DISPUTE_HISTORY','PAYOUT_ACCOUNT_CHANGE','BULK_TICKET_BUYING')),
  score       numeric(5,2) NOT NULL CHECK (score BETWEEN 0 AND 100),
  details     jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
);
