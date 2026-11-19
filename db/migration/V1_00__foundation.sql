-- =============================================================================
-- Beavent platform — V1 baseline
-- 00: extensions, schemas, shared helper functions, request context, files
--
-- Target: PostgreSQL 17+ with PostGIS 3.5+.
-- Conventions (see docs/05-data-model.md):
--   * Every table has a UUIDv7 primary key `id` (time ordered) unless it is a
--     pure association table or a code-keyed reference table.
--   * Every tenant-owned row carries `org_id` directly (no "derive the tenant
--     through three joins"). Parents expose UNIQUE (org_id, id) and children use
--     composite foreign keys so a row can never point at another tenant's data.
--   * Money is always BIGINT minor units (`*_minor`) + `currency_code`.
--   * Instants are TIMESTAMPTZ (UTC). Local wall-clock rules keep an IANA zone.
--   * Status columns are TEXT + CHECK so they are readable in SQL and evolvable.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS postgis;

-- One schema per backend module. A module writes only to its own schema.
CREATE SCHEMA platform;    -- cross-cutting infrastructure: files, outbox, audit, flags, webhooks
CREATE SCHEMA ref;         -- reference data and market configuration
CREATE SCHEMA iam;         -- identity, authentication, platform staff roles
CREATE SCHEMA tenant;      -- organizations, legal entities, members, roles, places, locations
CREATE SCHEMA team;        -- staff, schedules, time tracking, commissions, pay runs
CREATE SCHEMA catalog;     -- services, products, resources, sellable plan definitions
CREATE SCHEMA crm;         -- per-organization customer records, forms, consents
CREATE SCHEMA events;      -- events, occurrences, ticket types, inventory, seating, tickets, check-in
CREATE SCHEMA commerce;    -- orders, lines, taxes, promotions, invoices, cash drawers, waitlists
CREATE SCHEMA scheduling;  -- appointments and the staff/resource reservation ledger
CREATE SCHEMA retention;   -- gift cards, memberships, packages, loyalty, customer balances
CREATE SCHEMA stock;       -- retail inventory, suppliers, purchase orders, fulfilment
CREATE SCHEMA payments;    -- provider-agnostic payments, refunds, payouts, reconciliation
CREATE SCHEMA ledger;      -- double-entry money ledger
CREATE SCHEMA billing;     -- platform plans, subscriptions, fees, usage, platform invoices
CREATE SCHEMA fiscal;      -- tax-authority e-invoicing / fiscal device integration
CREATE SCHEMA marketing;   -- segments, campaigns, automations, referrals, ads, tracking links
CREATE SCHEMA comms;       -- notifications, messaging, AI concierge
CREATE SCHEMA discovery;   -- marketplace read models, reviews, follows, collections
CREATE SCHEMA trust;       -- KYB/KYC, risk, moderation

-- -----------------------------------------------------------------------------
-- UUIDv7 (RFC 9562). Time-ordered keys keep B-tree inserts local.
-- PostgreSQL 18 ships uuidv7(); this function keeps PG17 compatibility.
-- -----------------------------------------------------------------------------
CREATE FUNCTION platform.uuid_v7() RETURNS uuid
LANGUAGE sql VOLATILE PARALLEL SAFE AS $$
  SELECT encode(
           set_bit(
             set_bit(
               overlay(uuid_send(gen_random_uuid())
                       PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                       FROM 1 FOR 6),
             52, 1),
           53, 1),
         'hex')::uuid;
$$;

-- -----------------------------------------------------------------------------
-- Request context. The API sets these per transaction (SET LOCAL) after it has
-- authenticated the caller and verified organization membership.
--   app.user_id : the authenticated person (consumer or staff)
--   app.org_id  : the organization whose workspace is being used (partner calls)
-- RLS policies read them through these STABLE functions.
-- -----------------------------------------------------------------------------
CREATE FUNCTION platform.current_user_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('app.user_id', true), '')::uuid
$$;

CREATE FUNCTION platform.current_org_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('app.org_id', true), '')::uuid
$$;

CREATE FUNCTION platform.set_request_context(p_user_id uuid, p_org_id uuid) RETURNS void
LANGUAGE sql VOLATILE AS $$
  SELECT set_config('app.user_id', coalesce(p_user_id::text, ''), true),
         set_config('app.org_id',  coalesce(p_org_id::text,  ''), true);
$$;

-- Generic updated_at maintenance (attached to every table with updated_at in V1_99).
CREATE FUNCTION platform.touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- Append-only guard for ledgers, histories and immutable snapshots.
CREATE FUNCTION platform.reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on %.% is not allowed: table is append-only',
    TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END $$;

-- -----------------------------------------------------------------------------
-- Files: one metadata table for every stored object (R2/S3). Visibility and
-- owner scope decide who may obtain a signed URL. The org FK is added in
-- V1_03 once tenant.organizations exists.
-- -----------------------------------------------------------------------------
CREATE TABLE platform.files (
  id               uuid PRIMARY KEY DEFAULT platform.uuid_v7(),
  org_id           uuid,
  owner_user_id    uuid,
  purpose          text NOT NULL CHECK (purpose IN (
                     'AVATAR','LOGO','COVER','GALLERY','EVENT_MEDIA','SEAT_MAP','DOCUMENT',
                     'KYC','CUSTOMER_PHOTO','FORM_SIGNATURE','RECEIPT','INVOICE','EXPORT',
                     'ATTACHMENT','PAYMENT_PROOF','IMPORT')),
  visibility       text NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PUBLIC','PRIVATE','SENSITIVE')),
  bucket           text NOT NULL,
  storage_key      text NOT NULL UNIQUE,
  content_type     text NOT NULL,
  size_bytes       bigint NOT NULL CHECK (size_bytes >= 0),
  checksum_sha256  text,
  width_px         integer,
  height_px        integer,
  scan_status      text NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','FAILED')),
  retention_until  timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  CHECK (org_id IS NOT NULL OR owner_user_id IS NOT NULL),
  UNIQUE (org_id, id)
);
COMMENT ON TABLE platform.files IS
  'Object-storage metadata. SENSITIVE (KYC, customer photos, signatures) is never served publicly and requires an audited, purpose-checked signed URL.';
