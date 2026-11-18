-- =============================================================================
-- UNIFIED BEAUTY + EVENT PLATFORM DATABASE BASELINE v1.0 (INTEGRATION CANDIDATE)
-- One database; shared identity; independently owned product schemas.
-- Target: PostgreSQL 17+, PostGIS 3.5+, pgcrypto, btree_gist, ltree.
-- CLEAN INSTALL ONLY. This is not a migration for existing product databases.
-- Runtime/security role grants must be provisioned separately as documented.
-- IMPORTANT: static integration candidate; execute validation on a disposable
-- PostgreSQL 17/PostGIS instance and complete the release gates before production.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS ltree;

CREATE SCHEMA identity;
CREATE SCHEMA beauty_platform;
CREATE SCHEMA event_platform;


-- ---------------------------------------------------------------------------
-- SHARED IDENTITY SCHEMA
-- One global account and authentication identity across Beauty and Events.
-- Product memberships and product business records remain in their own schemas.
-- ---------------------------------------------------------------------------
SET search_path TO identity, public;

CREATE TABLE platform_roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    label text NOT NULL,
    is_system boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text,
    email_normalized text GENERATED ALWAYS AS (lower(btrim(email))) STORED,
    phone_e164 text,
    email_verified_at timestamptz,
    phone_verified_at timestamptz,
    account_status text NOT NULL DEFAULT 'PENDING'
        CHECK (account_status IN ('PENDING','ACTIVE','SUSPENDED','CLOSED')),
    preferred_language varchar(16),
    locale text NOT NULL DEFAULT 'en',
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (email_normalized),
    UNIQUE (phone_e164),
    CHECK (email IS NOT NULL OR phone_e164 IS NOT NULL)
);
COMMENT ON TABLE users IS 'Canonical global account identity shared by Beauty and Events. Product roles and memberships are stored in product schemas.';

CREATE TABLE user_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL UNIQUE REFERENCES identity.users(id) ON DELETE CASCADE,
    display_name text,
    first_name text,
    last_name text,
    username text UNIQUE,
    bio text,
    date_of_birth date,
    avatar_storage_key text,
    preferred_language text NOT NULL DEFAULT 'en',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE user_profiles IS 'Shared global display profile. Product-specific profile, business-customer and attendee details remain in their product domains.';

CREATE TABLE user_credentials (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL UNIQUE REFERENCES identity.users(id) ON DELETE CASCADE,
    password_hash text NOT NULL,
    password_changed_at timestamptz,
    must_change_password boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_oauth_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    provider text NOT NULL,
    provider_subject text NOT NULL,
    provider_email text,
    linked_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject),
    UNIQUE (user_id, provider)
);

CREATE TABLE user_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    client_code text NOT NULL CHECK (client_code IN ('BEAUTY_CUSTOMER_MOBILE','BEAUTY_BUSINESS_MOBILE','BEAUTY_WEB','EVENT_CUSTOMER_MOBILE','EVENT_ORGANISER_MOBILE','EVENT_WEB','PLATFORM_ADMIN_WEB')),
    refresh_token_hash text NOT NULL UNIQUE,
    device_id text,
    device_name text,
    ip_address inet,
    user_agent text,
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz,
    replaced_by_session_id uuid REFERENCES identity.user_sessions(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE user_sessions IS 'Rotating refresh-token sessions. client_code records the originating first-party client; it is not an authorization grant.';

CREATE TABLE auth_challenges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES identity.users(id) ON DELETE CASCADE,
    destination text,
    challenge_type text NOT NULL CHECK (challenge_type IN ('EMAIL_VERIFY','PHONE_VERIFY','PASSWORD_RESET','LOGIN_OTP','MFA')),
    token_hash text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_consents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    product_code text NOT NULL CHECK (product_code IN ('PLATFORM','BEAUTY','EVENT')),
    document_code text NOT NULL,
    document_version text NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT now(),
    source text,
    UNIQUE (user_id, product_code, document_code, document_version)
);

CREATE TABLE user_platform_roles (
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES identity.platform_roles(id) ON DELETE RESTRICT,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    assigned_by_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
    PRIMARY KEY (user_id, role_id)
);

INSERT INTO platform_roles (code, label, is_system) VALUES
 ('SUPER_ADMIN','Super administrator',true),
 ('ADMIN','Platform administrator',true),
 ('SUPPORT','Support agent',true),
 ('FINANCE','Platform finance',true)
ON CONFLICT (code) DO NOTHING;

CREATE OR REPLACE FUNCTION set_identity_updated_at() RETURNS trigger
LANGUAGE plpgsql SET search_path = identity, pg_temp AS $function$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$function$;
DO $identity_updated_at$
DECLARE r record;
BEGIN
    FOR r IN SELECT table_name FROM information_schema.columns
             WHERE table_schema='identity' AND column_name='updated_at' GROUP BY table_name
    LOOP
        EXECUTE format('CREATE TRIGGER trg_%I_updated_at BEFORE UPDATE ON identity.%I FOR EACH ROW EXECUTE FUNCTION identity.set_identity_updated_at()', r.table_name, r.table_name);
    END LOOP;
END;
$identity_updated_at$;

-- Self-service RLS. Authentication and administrative operations must use
-- narrowly granted identity-service functions/roles; product runtime code
-- must not receive broad direct access to credentials or token hashes.
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_user_self_policy ON users
    USING (id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);
ALTER TABLE user_profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_profile_self_policy ON user_profiles
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);
ALTER TABLE user_credentials ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_credentials_self_policy ON user_credentials
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);
ALTER TABLE user_oauth_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_oauth_self_policy ON user_oauth_accounts
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);
ALTER TABLE user_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_sessions_self_policy ON user_sessions
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);
ALTER TABLE auth_challenges ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_challenges_self_policy ON auth_challenges
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);
ALTER TABLE user_consents ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_consents_self_policy ON user_consents
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);
ALTER TABLE user_platform_roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_platform_roles_self_policy ON user_platform_roles
    FOR SELECT USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);

CREATE OR REPLACE FUNCTION auth_find_account(p_login text)
RETURNS TABLE (user_id uuid, email text, phone_e164 text, account_status text,
               email_verified_at timestamptz, phone_verified_at timestamptz, password_hash text)
LANGUAGE sql SECURITY DEFINER STABLE
SET search_path = pg_catalog, identity, pg_temp
AS $function$
    SELECT u.id, u.email, u.phone_e164, u.account_status,
           u.email_verified_at, u.phone_verified_at, uc.password_hash
      FROM identity.users u
      LEFT JOIN identity.user_credentials uc ON uc.user_id = u.id
     WHERE u.email_normalized = lower(btrim(p_login)) OR u.phone_e164 = p_login
     ORDER BY u.created_at DESC
     LIMIT 2
$function$;
REVOKE ALL ON FUNCTION auth_find_account(text) FROM PUBLIC;
COMMENT ON FUNCTION auth_find_account(text) IS 'Authentication-only normalized email/phone lookup. Grant EXECUTE only to the identity authentication service role.';


-- =============================================================================
-- BEAUTY PRODUCT SCHEMA (source baseline: Ethiopian Beauty & Wellness v1.2.1)
-- Shared identity tables resolve from the identity schema. All other beauty
-- marketplace and operational tables remain owned by beauty_platform.
-- =============================================================================
-- INSTALLABLE CLEAN-INSTALL SCRIPT - VERSION 1.2.1
-- Run only against a new empty PostgreSQL database.
-- BEAUTY & WELLNESS PLATFORM - VERSION 1.2.1 (MULTI-COUNTRY)
-- Clean-install PostgreSQL schema baseline: 228 tables.
-- Requirements: PostgreSQL 17+, PostGIS, btree_gist, pgcrypto.
-- Target schema: beauty_platform. Monetary amounts use NUMERIC; instants use TIMESTAMPTZ.
-- 228 tables: 214 from v1.1 plus 14 multi-country additions.
-- Apply this to a new/empty database. This is not an incremental migration from v1.0 or v1.1.
-- Before production: grant the app role access only to approved schemas/tables/functions; never use a table-owner or BYPASSRLS role for normal API queries.

CREATE SCHEMA IF NOT EXISTS beauty_platform;
SET search_path TO beauty_platform, identity, public;
CREATE TABLE business_roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    label text NOT NULL,
    is_system boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_roles IS 'Beauty-product business membership role definitions.';

CREATE TABLE businesses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid NOT NULL REFERENCES identity.users(id),
    legal_name text NOT NULL,
    display_name text NOT NULL,
    slug text NOT NULL UNIQUE,
    business_type text NOT NULL DEFAULT 'SALON',
    description text,
    status text NOT NULL DEFAULT 'DRAFT',
    verification_status text NOT NULL DEFAULT 'UNVERIFIED',

    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE businesses IS 'Business tenant root and public business identity.';

CREATE TABLE business_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id uuid REFERENCES business_categories(id),
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    name_i18n jsonb NOT NULL DEFAULT '{}'::jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_categories IS 'Platform-maintained business categories.';

CREATE TABLE business_category_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    category_id uuid NOT NULL REFERENCES business_categories(id),
    is_primary boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, category_id)
);
COMMENT ON TABLE business_category_assignments IS 'Many-to-many business/category mapping.';
CREATE INDEX ix_business_category_assignments_business_created ON business_category_assignments (business_id, created_at DESC);

CREATE TABLE business_memberships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users(id),
    role_id uuid NOT NULL REFERENCES business_roles(id),
    membership_status text NOT NULL DEFAULT 'ACTIVE',
    joined_at timestamptz NOT NULL DEFAULT now(),
    invited_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, user_id)
);
COMMENT ON TABLE business_memberships IS 'User membership and one role in a business.';
CREATE INDEX ix_business_memberships_business_created ON business_memberships (business_id, created_at DESC);

CREATE TABLE business_invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    email text NOT NULL,
    role_id uuid NOT NULL REFERENCES business_roles(id),
    token_hash text NOT NULL UNIQUE,
    invited_by_user_id uuid NOT NULL REFERENCES identity.users(id),
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_invitations IS 'Invitations to join a business.';
CREATE INDEX ix_business_invitations_business_created ON business_invitations (business_id, created_at DESC);

CREATE TABLE branches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    code text,
    slug text,
    phone text,
    email text,
    address_line1 text,
    address_line2 text,
    administrative_area_id uuid,
    city text,
    country_code char(2) NOT NULL ,
    location geography(Point,4326),
    timezone text NOT NULL ,
    currency char(3) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, code)
);
COMMENT ON TABLE branches IS 'Branch address, map point, currency and time zone.';
CREATE INDEX ix_branches_business_created ON branches (business_id, created_at DESC);

CREATE TABLE branch_hours (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    weekday smallint NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    opens_at time,
    closes_at time,
    is_closed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (branch_id, weekday, opens_at)
);
COMMENT ON TABLE branch_hours IS 'Weekly branch opening hours.';
CREATE INDEX ix_branch_hours_business_created ON branch_hours (business_id, created_at DESC);

CREATE TABLE branch_schedule_exceptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    exception_date date NOT NULL,
    opens_at time,
    closes_at time,
    is_closed boolean NOT NULL DEFAULT false,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (branch_id, exception_date, opens_at)
);
COMMENT ON TABLE branch_schedule_exceptions IS 'Branch closures and exceptional hours.';
CREATE INDEX ix_branch_schedule_exceptions_business_created ON branch_schedule_exceptions (business_id, created_at DESC);

CREATE TABLE business_settings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    setting_key text NOT NULL,
    setting_value jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, setting_key)
);
COMMENT ON TABLE business_settings IS 'Business configuration stored as structured JSONB.';
CREATE INDEX ix_business_settings_business_created ON business_settings (business_id, created_at DESC);

CREATE TABLE business_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL,
    media_type text NOT NULL,
    caption text,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_media IS 'Logos, cover images and business gallery metadata.';
CREATE INDEX ix_business_media_business_created ON business_media (business_id, created_at DESC);

CREATE TABLE business_verification_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL,
    document_type text NOT NULL,
    review_status text NOT NULL DEFAULT 'PENDING',
    reviewed_by_user_id uuid REFERENCES identity.users(id),
    reviewed_at timestamptz,
    rejection_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_verification_documents IS 'Verification file references and review state.';
CREATE INDEX ix_business_verification_documents_business_created ON business_verification_documents (business_id, created_at DESC);

CREATE TABLE business_domains (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    hostname text NOT NULL UNIQUE,
    verification_token text,
    verified_at timestamptz,
    is_primary boolean NOT NULL DEFAULT false,
    status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_domains IS 'Custom booking domains and verification state.';
CREATE INDEX ix_business_domains_business_created ON business_domains (business_id, created_at DESC);

CREATE TABLE employees (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES business_memberships(id),
    public_name text NOT NULL,
    job_title text,
    bio text,
    employment_status text NOT NULL DEFAULT 'ACTIVE',
    booking_enabled boolean NOT NULL DEFAULT true,
    colour text,
    started_on date,
    ended_on date,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, membership_id)
);
COMMENT ON TABLE employees IS 'Employee/professional record linked to a business membership.';
CREATE INDEX ix_employees_business_created ON employees (business_id, created_at DESC);

CREATE TABLE employee_branch_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    is_primary boolean NOT NULL DEFAULT false,
    active_from date,
    active_to date,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (employee_id, branch_id)
);
COMMENT ON TABLE employee_branch_assignments IS 'Branches where a professional works.';
CREATE INDEX ix_employee_branch_assignments_business_created ON employee_branch_assignments (business_id, created_at DESC);

CREATE TABLE employee_working_hours (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    weekday smallint NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    starts_at time NOT NULL,
    ends_at time NOT NULL,
    effective_from date,
    effective_to date,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE employee_working_hours IS 'Recurring employee availability.';
CREATE INDEX ix_employee_working_hours_business_created ON employee_working_hours (business_id, created_at DESC);

CREATE TABLE employee_shifts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id),
    branch_id uuid NOT NULL REFERENCES branches(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    shift_status text NOT NULL DEFAULT 'PUBLISHED',
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);
COMMENT ON TABLE employee_shifts IS 'Published shift instances.';
CREATE INDEX ix_employee_shifts_business_created ON employee_shifts (business_id, created_at DESC);

CREATE TABLE employee_schedule_exceptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id),
    branch_id uuid REFERENCES branches(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    exception_type text NOT NULL,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);
COMMENT ON TABLE employee_schedule_exceptions IS 'Breaks, leave and temporary unavailability.';
CREATE INDEX ix_employee_schedule_exceptions_business_created ON employee_schedule_exceptions (business_id, created_at DESC);

CREATE TABLE employee_time_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id),
    branch_id uuid NOT NULL REFERENCES branches(id),
    clock_in_at timestamptz NOT NULL,
    clock_out_at timestamptz,
    break_minutes integer NOT NULL DEFAULT 0,
    entry_source text NOT NULL DEFAULT 'MANUAL',
    approved_by_employee_id uuid REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (clock_out_at IS NULL OR clock_out_at > clock_in_at)
);
COMMENT ON TABLE employee_time_entries IS 'Clock-in/out and timesheet records.';
CREATE INDEX ix_employee_time_entries_business_created ON employee_time_entries (business_id, created_at DESC);

CREATE TABLE employee_compensation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id),
    compensation_type text NOT NULL,
    amount numeric(14,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    pay_frequency text,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE employee_compensation IS 'Salary/hourly/fixed compensation arrangements.';
CREATE INDEX ix_employee_compensation_business_created ON employee_compensation (business_id, created_at DESC);

CREATE TABLE team_targets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    employee_id uuid REFERENCES employees(id),
    target_type text NOT NULL,
    metric_code text NOT NULL,
    target_value numeric(16,2) NOT NULL,
    period_type text NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    currency char(3) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end >= period_start)
);
COMMENT ON TABLE team_targets IS 'Employee or branch KPI target definitions.';
CREATE INDEX ix_team_targets_business_created ON team_targets (business_id, created_at DESC);

CREATE TABLE team_target_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    team_target_id uuid NOT NULL REFERENCES team_targets(id),
    employee_id uuid REFERENCES employees(id),
    period_start date NOT NULL,
    period_end date NOT NULL,
    actual_value numeric(16,2) NOT NULL DEFAULT 0,
    achievement_ratio numeric(10,4),
    captured_at timestamptz NOT NULL DEFAULT now(),
    snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE team_target_results IS 'Periodic snapshots of actual performance against targets.';
CREATE INDEX ix_team_target_results_business_created ON team_target_results (business_id, created_at DESC);

CREATE TABLE commission_rules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid REFERENCES employees(id),
    service_id uuid,
    commission_type text NOT NULL,
    rate numeric(8,4) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    priority integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE commission_rules IS 'Professional and marketplace commission rules.';
CREATE INDEX ix_commission_rules_business_created ON commission_rules (business_id, created_at DESC);

CREATE TABLE commission_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id),
    commission_rule_id uuid REFERENCES commission_rules(id),
    source_type text NOT NULL,
    source_id uuid NOT NULL,
    gross_amount numeric(14,2) NOT NULL,
    commission_amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    entry_status text NOT NULL DEFAULT 'PENDING',
    approved_at timestamptz,
    paid_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE commission_entries IS 'Calculated, approved, paid or reversed commission ledger.';
CREATE INDEX ix_commission_entries_business_created ON commission_entries (business_id, created_at DESC);

CREATE TABLE service_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id uuid REFERENCES service_categories(id),
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    name_i18n jsonb NOT NULL DEFAULT '{}'::jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE service_categories IS 'Shared service taxonomy.';

CREATE TABLE services (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    category_id uuid REFERENCES service_categories(id),
    name text NOT NULL,
    description text,
    booking_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_published boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE services IS 'Business-owned service definitions.';
CREATE INDEX ix_services_business_created ON services (business_id, created_at DESC);

CREATE TABLE service_variants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    name text NOT NULL,
    description text,
    price_type text NOT NULL DEFAULT 'FIXED' CHECK (price_type IN ('FIXED','FROM','FREE')),
    default_price numeric(14,2),
    currency char(3) NOT NULL,
    duration_minutes integer NOT NULL,
    processing_minutes integer NOT NULL DEFAULT 0,
    finishing_minutes integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (duration_minutes >= 0),
    CHECK (processing_minutes >= 0),
    CHECK (finishing_minutes >= 0),
    CHECK (default_price IS NULL OR default_price >= 0)
);
COMMENT ON TABLE service_variants IS 'Options such as short/medium/long with default duration and price.';
CREATE INDEX ix_service_variants_business_created ON service_variants (business_id, created_at DESC);

CREATE TABLE employee_services (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    service_id uuid NOT NULL REFERENCES services(id),
    service_variant_id uuid REFERENCES service_variants(id),
    is_active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE employee_services IS 'Services and optional variants a professional can perform.';
CREATE INDEX ix_employee_services_business_created ON employee_services (business_id, created_at DESC);

CREATE TABLE branch_services (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    service_id uuid NOT NULL REFERENCES services(id),
    is_active boolean NOT NULL DEFAULT true,
    online_booking_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (branch_id, service_id)
);
COMMENT ON TABLE branch_services IS 'Services enabled at a branch.';
CREATE INDEX ix_branch_services_business_created ON branch_services (business_id, created_at DESC);

CREATE TABLE service_prices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_variant_id uuid NOT NULL REFERENCES service_variants(id),
    branch_id uuid REFERENCES branches(id),
    employee_id uuid REFERENCES employees(id),
    scope_key text NOT NULL,
    price_type text NOT NULL DEFAULT 'FIXED' CHECK (price_type IN ('FIXED','FROM','FREE')),
    price numeric(14,2),
    duration_minutes integer,
    effective_range tstzrange,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (price IS NULL OR price >= 0),
    CHECK (duration_minutes IS NULL OR duration_minutes >= 0)
);
COMMENT ON TABLE service_prices IS 'Branch and professional-specific variant price/duration overrides with explicit price currency.';
CREATE INDEX ix_service_prices_business_created ON service_prices (business_id, created_at DESC);

CREATE TABLE service_addons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id uuid NOT NULL REFERENCES services(id),
    name text NOT NULL,
    description text,
    default_price numeric(14,2),
    duration_minutes integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE service_addons IS 'Optional service extras; default price currency is explicit and branch overrides use local branch currency.';
CREATE INDEX ix_service_addons_business_created ON service_addons (business_id, created_at DESC);

CREATE TABLE service_variant_addons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_variant_id uuid NOT NULL REFERENCES service_variants(id),
    service_addon_id uuid NOT NULL REFERENCES service_addons(id),
    is_required boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (service_variant_id, service_addon_id)
);
COMMENT ON TABLE service_variant_addons IS 'Which add-ons are available for each variant.';
CREATE INDEX ix_service_variant_addons_business_created ON service_variant_addons (business_id, created_at DESC);

CREATE TABLE service_addon_prices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_addon_id uuid NOT NULL REFERENCES service_addons(id),
    branch_id uuid REFERENCES branches(id),
    employee_id uuid REFERENCES employees(id),
    price numeric(14,2) NOT NULL,
    effective_range tstzrange,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (price >= 0)
);
COMMENT ON TABLE service_addon_prices IS 'Branch/professional add-on pricing with explicit currency.';
CREATE INDEX ix_service_addon_prices_business_created ON service_addon_prices (business_id, created_at DESC);

CREATE TABLE service_pricing_rules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_variant_id uuid NOT NULL REFERENCES service_variants(id),
    rule_name text NOT NULL,
    rule_type text NOT NULL,
    adjustment_type text NOT NULL,
    adjustment_value numeric(14,2) NOT NULL,
    valid_range tstzrange,
    weekday_mask smallint[],
    priority integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE service_pricing_rules IS 'Peak/off-peak, scheduled and promotional pricing rules.';
CREATE INDEX ix_service_pricing_rules_business_created ON service_pricing_rules (business_id, created_at DESC);

CREATE TABLE service_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id uuid NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL,
    media_type text NOT NULL,
    caption text,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE service_media IS 'Service photographs and videos.';
CREATE INDEX ix_service_media_business_created ON service_media (business_id, created_at DESC);

CREATE TABLE resources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    name text NOT NULL,
    resource_type text NOT NULL,
    capacity integer NOT NULL DEFAULT 1,
    is_active boolean NOT NULL DEFAULT true,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (capacity > 0)
);
COMMENT ON TABLE resources IS 'Rooms, chairs, beds and equipment.';
CREATE INDEX ix_resources_business_created ON resources (business_id, created_at DESC);

CREATE TABLE service_resource_requirements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id uuid NOT NULL REFERENCES services(id),
    service_variant_id uuid REFERENCES service_variants(id),
    resource_type text,
    quantity integer NOT NULL DEFAULT 1,
    required boolean NOT NULL DEFAULT true,
    duration_scope text NOT NULL DEFAULT 'FULL',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (quantity > 0)
);
COMMENT ON TABLE service_resource_requirements IS 'Resources required by a service or variant.';
CREATE INDEX ix_service_resource_requirements_business_created ON service_resource_requirements (business_id, created_at DESC);

CREATE TABLE bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    business_customer_id uuid,
    booking_group_id uuid,
    booking_number text NOT NULL,
    booking_source text NOT NULL DEFAULT 'MARKETPLACE',
    status text NOT NULL DEFAULT 'PENDING',
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    customer_name_snapshot text,
    customer_phone_snapshot text,
    currency char(3) NOT NULL,
    subtotal_amount numeric(14,2) NOT NULL DEFAULT 0,
    discount_amount numeric(14,2) NOT NULL DEFAULT 0,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    total_amount numeric(14,2) NOT NULL DEFAULT 0,
    notes text,
    confirmed_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, booking_number),
    CHECK (ends_at > starts_at),
    CHECK (subtotal_amount >= 0),
    CHECK (discount_amount >= 0),
    CHECK (tax_amount >= 0),
    CHECK (total_amount >= 0)
);
COMMENT ON TABLE bookings IS 'Main customer appointment record.';
CREATE INDEX ix_bookings_business_created ON bookings (business_id, created_at DESC);

CREATE TABLE booking_groups (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    group_reference text NOT NULL,
    payer_business_customer_id uuid,
    organiser_user_id uuid REFERENCES identity.users(id),
    group_name text,
    currency char(3) NOT NULL,
    total_amount numeric(14,2) NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, group_reference)
);
COMMENT ON TABLE booking_groups IS 'Header linking multiple individual bookings in a group.';
CREATE INDEX ix_booking_groups_business_created ON booking_groups (business_id, created_at DESC);

CREATE TABLE booking_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    service_id uuid REFERENCES services(id),
    service_variant_id uuid REFERENCES service_variants(id),
    employee_id uuid REFERENCES employees(id),
    service_name_snapshot text NOT NULL,
    variant_name_snapshot text,
    employee_name_snapshot text,
    price_type_snapshot text NOT NULL DEFAULT 'FIXED',
    unit_price numeric(14,2) NOT NULL,
    quantity numeric(10,2) NOT NULL DEFAULT 1,
    discount_amount numeric(14,2) NOT NULL DEFAULT 0,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    total_amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    staff_occupied_range tstzrange,
    item_status text NOT NULL DEFAULT 'BOOKED',
    is_active boolean NOT NULL DEFAULT true,
    duration_minutes_snapshot integer NOT NULL DEFAULT 0,
    processing_minutes_snapshot integer NOT NULL DEFAULT 0,
    finishing_minutes_snapshot integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (unit_price >= 0),
    CHECK (quantity > 0),
    CHECK (total_amount >= 0)
);
COMMENT ON TABLE booking_items IS 'Individual service line with immutable service/professional/price/time snapshots.';
CREATE INDEX ix_booking_items_business_created ON booking_items (business_id, created_at DESC);

CREATE TABLE booking_item_addons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_item_id uuid NOT NULL REFERENCES booking_items(id) ON DELETE CASCADE,
    service_addon_id uuid REFERENCES service_addons(id),
    addon_name_snapshot text NOT NULL,
    quantity numeric(10,2) NOT NULL DEFAULT 1,
    unit_price numeric(14,2) NOT NULL,
    total_amount numeric(14,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (quantity > 0),
    CHECK (unit_price >= 0)
);
COMMENT ON TABLE booking_item_addons IS 'Selected add-ons captured at booking time.';
CREATE INDEX ix_booking_item_addons_business_created ON booking_item_addons (business_id, created_at DESC);

CREATE TABLE booking_item_resources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_item_id uuid NOT NULL REFERENCES booking_items(id) ON DELETE CASCADE,
    resource_id uuid NOT NULL REFERENCES resources(id),
    reserved_range tstzrange NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_item_resources IS 'Resource reservation ranges.';
CREATE INDEX ix_booking_item_resources_business_created ON booking_item_resources (business_id, created_at DESC);

CREATE TABLE booking_status_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_id uuid NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    from_status text,
    to_status text NOT NULL,
    changed_by_user_id uuid REFERENCES identity.users(id),
    reason text,
    changed_at timestamptz NOT NULL DEFAULT now(),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_status_history IS 'Auditable booking status transitions.';
CREATE INDEX ix_booking_status_history_business_created ON booking_status_history (business_id, created_at DESC);

CREATE TABLE booking_holds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    employee_id uuid REFERENCES employees(id),
    held_range tstzrange NOT NULL,
    hold_token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    hold_status text NOT NULL DEFAULT 'ACTIVE',
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_holds IS 'Temporary availability holds while booking/payment is completed.';
CREATE INDEX ix_booking_holds_business_created ON booking_holds (business_id, created_at DESC);

CREATE TABLE booking_waitlists (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    business_customer_id uuid NOT NULL,
    service_id uuid REFERENCES services(id),
    service_variant_id uuid REFERENCES service_variants(id),
    employee_id uuid REFERENCES employees(id),
    preferred_from timestamptz,
    preferred_to timestamptz,
    waitlist_status text NOT NULL DEFAULT 'ACTIVE',
    priority integer NOT NULL DEFAULT 0,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_waitlists IS 'Customer waitlist requests.';
CREATE INDEX ix_booking_waitlists_business_created ON booking_waitlists (business_id, created_at DESC);

CREATE TABLE booking_waitlist_offers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    waitlist_id uuid NOT NULL REFERENCES booking_waitlists(id) ON DELETE CASCADE,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    offer_token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    offer_status text NOT NULL DEFAULT 'OFFERED',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_waitlist_offers IS 'Time slots offered to waitlisted customers.';
CREATE INDEX ix_booking_waitlist_offers_business_created ON booking_waitlist_offers (business_id, created_at DESC);

CREATE TABLE calendar_blocks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    employee_id uuid REFERENCES employees(id),
    resource_id uuid REFERENCES resources(id),
    blocked_range tstzrange NOT NULL,
    block_type text NOT NULL,
    reason text,
    created_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE calendar_blocks IS 'Manual employee/resource calendar blocks.';
CREATE INDEX ix_calendar_blocks_business_created ON calendar_blocks (business_id, created_at DESC);

CREATE TABLE class_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    service_id uuid REFERENCES services(id),
    employee_id uuid REFERENCES employees(id),
    title text NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    capacity integer NOT NULL,
    price numeric(14,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    session_status text NOT NULL DEFAULT 'SCHEDULED',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (capacity > 0)
);
COMMENT ON TABLE class_sessions IS 'Scheduled group classes or sessions.';
CREATE INDEX ix_class_sessions_business_created ON class_sessions (business_id, created_at DESC);

CREATE TABLE class_enrollments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    class_session_id uuid NOT NULL REFERENCES class_sessions(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL,
    enrollment_status text NOT NULL DEFAULT 'CONFIRMED',
    amount_due numeric(14,2) NOT NULL DEFAULT 0,
    enrolled_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (class_session_id, business_customer_id)
);
COMMENT ON TABLE class_enrollments IS 'Individual customer class registrations.';
CREATE INDEX ix_class_enrollments_business_created ON class_enrollments (business_id, created_at DESC);

CREATE TABLE booking_cancellations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_id uuid NOT NULL REFERENCES bookings(id),
    cancelled_by_user_id uuid REFERENCES identity.users(id),
    actor_type text NOT NULL,
    reason_code text,
    reason_text text,
    cancelled_at timestamptz NOT NULL DEFAULT now(),
    fee_amount numeric(14,2) NOT NULL DEFAULT 0,
    refund_amount numeric(14,2) NOT NULL DEFAULT 0,
    outcome jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_cancellations IS 'Cancellation actor, reason, fee/refund outcome.';
CREATE INDEX ix_booking_cancellations_business_created ON booking_cancellations (business_id, created_at DESC);

CREATE TABLE booking_payment_policies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    service_id uuid REFERENCES services(id),
    service_variant_id uuid REFERENCES service_variants(id),
    customer_segment text,
    policy_type text NOT NULL,
    deposit_type text,
    deposit_value numeric(14,2),
    require_full_payment boolean NOT NULL DEFAULT false,
    require_card_guarantee boolean NOT NULL DEFAULT false,
    cancellation_window_hours integer,
    cancellation_fee_type text,
    cancellation_fee_value numeric(14,2),
    no_show_fee_type text,
    no_show_fee_value numeric(14,2),
    effective_from timestamptz,
    effective_to timestamptz,
    priority integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_payment_policies IS 'Deposit, full-prepayment, card-guarantee and fee policy scopes.';
CREATE INDEX ix_booking_payment_policies_business_created ON booking_payment_policies (business_id, created_at DESC);

CREATE TABLE booking_item_payment_terms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_item_id uuid NOT NULL UNIQUE REFERENCES booking_items(id),
    policy_id uuid REFERENCES booking_payment_policies(id),
    policy_snapshot jsonb NOT NULL,
    amount_due numeric(14,2) NOT NULL,
    deposit_due numeric(14,2) NOT NULL DEFAULT 0,
    captured_at timestamptz NOT NULL DEFAULT now(),
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_item_payment_terms IS 'Immutable payment-policy snapshot per booked item.';
CREATE INDEX ix_booking_item_payment_terms_business_created ON booking_item_payment_terms (business_id, created_at DESC);

CREATE TABLE booking_fees (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_id uuid NOT NULL REFERENCES bookings(id),
    booking_item_id uuid REFERENCES booking_items(id),
    fee_type text NOT NULL,
    amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    status text NOT NULL DEFAULT 'ASSESSED',
    assessed_at timestamptz NOT NULL DEFAULT now(),
    waived_by_user_id uuid REFERENCES identity.users(id),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (amount >= 0)
);
COMMENT ON TABLE booking_fees IS 'Late-cancellation, no-show and other appointment fees.';
CREATE INDEX ix_booking_fees_business_created ON booking_fees (business_id, created_at DESC);

CREATE TABLE business_customers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    user_id uuid REFERENCES identity.users(id),
    display_name text NOT NULL,
    email text,
    phone text,
    date_of_birth date,
    customer_code text,
    first_visit_at timestamptz,
    last_visit_at timestamptz,
    total_visits integer NOT NULL DEFAULT 0,
    lifetime_value numeric(14,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    marketing_opt_in boolean NOT NULL DEFAULT false,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, customer_code)
);
COMMENT ON TABLE business_customers IS 'Business-specific customer records; user_id nullable for guests.';
CREATE INDEX ix_business_customers_business_created ON business_customers (business_id, created_at DESC);

CREATE TABLE customer_notes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id) ON DELETE CASCADE,
    author_user_id uuid REFERENCES identity.users(id),
    note_text text NOT NULL,
    visibility text NOT NULL DEFAULT 'STAFF',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_notes IS 'Private business/customer notes.';
CREATE INDEX ix_customer_notes_business_created ON customer_notes (business_id, created_at DESC);

CREATE TABLE customer_tags (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    colour text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, name)
);
COMMENT ON TABLE customer_tags IS 'Customer segment tags.';
CREATE INDEX ix_customer_tags_business_created ON customer_tags (business_id, created_at DESC);

CREATE TABLE customer_tag_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id) ON DELETE CASCADE,
    customer_tag_id uuid NOT NULL REFERENCES customer_tags(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_customer_id, customer_tag_id)
);
COMMENT ON TABLE customer_tag_assignments IS 'Customer-to-tag mapping.';
CREATE INDEX ix_customer_tag_assignments_business_created ON customer_tag_assignments (business_id, created_at DESC);

CREATE TABLE customer_favourites (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id) ON DELETE CASCADE,
    employee_id uuid REFERENCES employees(id) ON DELETE CASCADE,
    favourite_type text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_favourites IS 'User favourites for businesses, branches or professionals.';

CREATE TABLE customer_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL,
    document_type text NOT NULL,
    access_classification text NOT NULL DEFAULT 'RESTRICTED',
    expires_at timestamptz,
    uploaded_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_documents IS 'Customer file references.';
CREATE INDEX ix_customer_documents_business_created ON customer_documents (business_id, created_at DESC);

CREATE TABLE customer_treatment_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    booking_item_id uuid REFERENCES booking_items(id),
    employee_id uuid REFERENCES employees(id),
    treatment_date timestamptz NOT NULL DEFAULT now(),
    treatment_name text NOT NULL,
    formula jsonb NOT NULL DEFAULT '{}'::jsonb,
    body_chart jsonb,
    reaction_notes text,
    aftercare_notes text,
    restricted_access boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_treatment_records IS 'Treatment history, formulas and body-chart data.';
CREATE INDEX ix_customer_treatment_records_business_created ON customer_treatment_records (business_id, created_at DESC);

CREATE TABLE patch_tests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    service_id uuid REFERENCES services(id),
    performed_by_employee_id uuid REFERENCES employees(id),
    tested_at timestamptz NOT NULL,
    valid_until timestamptz,
    result text NOT NULL,
    product_used text,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE patch_tests IS 'Patch-test dates, results and related service.';
CREATE INDEX ix_patch_tests_business_created ON patch_tests (business_id, created_at DESC);

CREATE TABLE customer_consents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    consent_type text NOT NULL,
    consent_status text NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    withdrawn_at timestamptz,
    source text,
    evidence_asset_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_consents IS 'Marketing, privacy and treatment consent events.';
CREATE INDEX ix_customer_consents_business_created ON customer_consents (business_id, created_at DESC);

CREATE TABLE form_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    form_type text NOT NULL,
    version integer NOT NULL DEFAULT 1,
    schema_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE form_templates IS 'Consultation/consent/aftercare form templates.';
CREATE INDEX ix_form_templates_business_created ON form_templates (business_id, created_at DESC);

CREATE TABLE form_fields (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    form_template_id uuid NOT NULL REFERENCES form_templates(id) ON DELETE CASCADE,
    field_key text NOT NULL,
    label text NOT NULL,
    field_type text NOT NULL,
    is_required boolean NOT NULL DEFAULT false,
    options_json jsonb,
    conditional_rules jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (form_template_id, field_key)
);
COMMENT ON TABLE form_fields IS 'Form questions, options and conditional rules.';
CREATE INDEX ix_form_fields_business_created ON form_fields (business_id, created_at DESC);

CREATE TABLE form_submissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    form_template_id uuid NOT NULL REFERENCES form_templates(id),
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    booking_id uuid REFERENCES bookings(id),
    submitted_by_user_id uuid REFERENCES identity.users(id),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    submission_status text NOT NULL DEFAULT 'SUBMITTED',
    template_version_snapshot integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE form_submissions IS 'Completed customer form submissions.';
CREATE INDEX ix_form_submissions_business_created ON form_submissions (business_id, created_at DESC);

CREATE TABLE form_answers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    form_submission_id uuid NOT NULL REFERENCES form_submissions(id) ON DELETE CASCADE,
    form_field_id uuid NOT NULL REFERENCES form_fields(id),
    answer_text text,
    answer_json jsonb,
    answer_asset_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (form_submission_id, form_field_id)
);
COMMENT ON TABLE form_answers IS 'Individual answers to form fields.';
CREATE INDEX ix_form_answers_business_created ON form_answers (business_id, created_at DESC);

CREATE TABLE marketplace_featured_listings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    placement_type text NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    ranking_weight numeric(10,4) NOT NULL DEFAULT 1,
    status text NOT NULL DEFAULT 'SCHEDULED',
    billing_reference uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE marketplace_featured_listings IS 'Paid/promoted marketplace placement windows.';
CREATE INDEX ix_marketplace_featured_listings_business_created ON marketplace_featured_listings (business_id, created_at DESC);

CREATE TABLE reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    business_customer_id uuid REFERENCES business_customers(id),
    booking_id uuid REFERENCES bookings(id),
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text text,
    business_response text,
    responded_at timestamptz,
    review_status text NOT NULL DEFAULT 'PUBLISHED',
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE reviews IS 'Marketplace or imported review, rating and business response.';
CREATE INDEX ix_reviews_business_created ON reviews (business_id, created_at DESC);

CREATE TABLE review_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    booking_id uuid REFERENCES bookings(id),
    request_token_hash text NOT NULL UNIQUE,
    sent_at timestamptz,
    expires_at timestamptz,
    review_id uuid REFERENCES reviews(id),
    request_status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE review_requests IS 'Automated review invitations.';
CREATE INDEX ix_review_requests_business_created ON review_requests (business_id, created_at DESC);

CREATE TABLE marketplace_search_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES identity.users(id),
    query_text text,
    filters jsonb NOT NULL DEFAULT '{}'::jsonb,
    location geography(Point,4326),
    result_count integer NOT NULL DEFAULT 0,
    session_id text,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE marketplace_search_events IS 'Search and discovery analytics.';

CREATE TABLE sales (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    business_customer_id uuid REFERENCES business_customers(id),
    sale_number text NOT NULL,
    sale_type text NOT NULL DEFAULT 'POS',
    sale_status text NOT NULL DEFAULT 'OPEN',
    currency char(3) NOT NULL,
    subtotal_amount numeric(14,2) NOT NULL DEFAULT 0,
    discount_amount numeric(14,2) NOT NULL DEFAULT 0,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    tip_amount numeric(14,2) NOT NULL DEFAULT 0,
    total_amount numeric(14,2) NOT NULL DEFAULT 0,
    sold_by_user_id uuid REFERENCES identity.users(id),
    sold_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, sale_number)
);
COMMENT ON TABLE sales IS 'POS or online-store sales and checkout totals.';
CREATE INDEX ix_sales_business_created ON sales (business_id, created_at DESC);

CREATE TABLE sale_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    sale_id uuid NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    line_type text NOT NULL,
    service_id uuid REFERENCES services(id),
    product_variant_id uuid,
    booking_item_id uuid REFERENCES booking_items(id),
    description_snapshot text NOT NULL,
    quantity numeric(12,3) NOT NULL DEFAULT 1,
    unit_price numeric(14,2) NOT NULL,
    discount_amount numeric(14,2) NOT NULL DEFAULT 0,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    line_total numeric(14,2) NOT NULL,
    employee_id uuid REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (quantity > 0),
    CHECK (unit_price >= 0)
);
COMMENT ON TABLE sale_items IS 'Service/product and other sale line snapshots.';
CREATE INDEX ix_sale_items_business_created ON sale_items (business_id, created_at DESC);

CREATE TABLE sale_tips (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    sale_id uuid NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    employee_id uuid REFERENCES employees(id),
    amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    allocation_status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (amount >= 0)
);
COMMENT ON TABLE sale_tips IS 'Tips and employee allocation.';
CREATE INDEX ix_sale_tips_business_created ON sale_tips (business_id, created_at DESC);

CREATE TABLE receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    sale_id uuid REFERENCES sales(id),
    payment_id uuid,
    receipt_number text NOT NULL,
    asset_id uuid,
    issued_at timestamptz NOT NULL DEFAULT now(),
    delivery_channel text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, receipt_number)
);
COMMENT ON TABLE receipts IS 'Issued sale/payment receipt metadata and file reference.';
CREATE INDEX ix_receipts_business_created ON receipts (business_id, created_at DESC);

CREATE TABLE quotes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    business_customer_id uuid REFERENCES business_customers(id),
    quote_number text NOT NULL,
    valid_until date,
    currency char(3) NOT NULL,
    subtotal_amount numeric(14,2) NOT NULL DEFAULT 0,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    total_amount numeric(14,2) NOT NULL DEFAULT 0,
    quote_status text NOT NULL DEFAULT 'DRAFT',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, quote_number)
);
COMMENT ON TABLE quotes IS 'Customer estimates and quotations.';
CREATE INDEX ix_quotes_business_created ON quotes (business_id, created_at DESC);

CREATE TABLE quote_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    quote_id uuid NOT NULL REFERENCES quotes(id) ON DELETE CASCADE,
    description text NOT NULL,
    quantity numeric(12,3) NOT NULL DEFAULT 1,
    unit_price numeric(14,2) NOT NULL,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    line_total numeric(14,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE quote_items IS 'Quote line items.';
CREATE INDEX ix_quote_items_business_created ON quote_items (business_id, created_at DESC);

CREATE TABLE invoices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid REFERENCES business_customers(id),
    sale_id uuid REFERENCES sales(id),
    invoice_number text NOT NULL,
    issued_on date NOT NULL,
    due_on date,
    currency char(3) NOT NULL,
    subtotal_amount numeric(14,2) NOT NULL,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    total_amount numeric(14,2) NOT NULL,
    invoice_status text NOT NULL DEFAULT 'ISSUED',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, invoice_number)
);
COMMENT ON TABLE invoices IS 'Business-to-customer invoices.';
CREATE INDEX ix_invoices_business_created ON invoices (business_id, created_at DESC);

CREATE TABLE invoice_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    invoice_id uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    description text NOT NULL,
    quantity numeric(12,3) NOT NULL DEFAULT 1,
    unit_price numeric(14,2) NOT NULL,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    line_total numeric(14,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE invoice_items IS 'Invoice lines.';
CREATE INDEX ix_invoice_items_business_created ON invoice_items (business_id, created_at DESC);

CREATE TABLE payment_methods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    method_type text NOT NULL,
    country_code char(2),
    provider_code text,
    is_online boolean NOT NULL DEFAULT false,
    is_cash boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payment_methods IS 'Global payment-method catalogue.';

CREATE TABLE payment_provider_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    provider_code text NOT NULL,
    provider_account_ref text,
    credentials_secret_ref text NOT NULL,
    account_status text NOT NULL DEFAULT 'PENDING',
    supported_currencies text[],
    webhook_secret_ref text,
    last_verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payment_provider_accounts IS 'Merchant/gateway configuration references.';
CREATE INDEX ix_payment_provider_accounts_business_created ON payment_provider_accounts (business_id, created_at DESC);

CREATE TABLE branch_payment_methods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    payment_method_id uuid NOT NULL REFERENCES payment_methods(id),
    provider_account_id uuid REFERENCES payment_provider_accounts(id),
    is_enabled boolean NOT NULL DEFAULT true,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (branch_id, payment_method_id)
);
COMMENT ON TABLE branch_payment_methods IS 'Methods enabled at a branch.';
CREATE INDEX ix_branch_payment_methods_business_created ON branch_payment_methods (business_id, created_at DESC);

CREATE TABLE merchant_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    employee_id uuid REFERENCES employees(id),
    provider_account_id uuid NOT NULL REFERENCES payment_provider_accounts(id),
    merchant_reference text,
    status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE merchant_accounts IS 'Business/branch/professional merchant account links.';
CREATE INDEX ix_merchant_accounts_business_created ON merchant_accounts (business_id, created_at DESC);

CREATE TABLE saved_payment_methods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    provider_account_id uuid REFERENCES payment_provider_accounts(id),
    provider_token_ref text NOT NULL,
    brand text,
    last_four char(4),
    expiry_month smallint,
    expiry_year smallint,
    is_default boolean NOT NULL DEFAULT false,
    consented_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE saved_payment_methods IS 'Provider token references; no card number or CVV.';
CREATE INDEX ix_saved_payment_methods_business_created ON saved_payment_methods (business_id, created_at DESC);

CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_method_id uuid REFERENCES payment_methods(id),
    provider_account_id uuid REFERENCES payment_provider_accounts(id),
    payment_reference text NOT NULL,
    source_type text NOT NULL,
    source_id uuid,
    amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    payment_status text NOT NULL DEFAULT 'PENDING',
    captured_amount numeric(14,2) NOT NULL DEFAULT 0,
    refunded_amount numeric(14,2) NOT NULL DEFAULT 0,
    provider_transaction_ref text,
    paid_at timestamptz,
    failure_code text,
    failure_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, payment_reference),
    CHECK (amount > 0),
    CHECK (captured_amount >= 0),
    CHECK (refunded_amount >= 0),
    CHECK (refunded_amount <= captured_amount)
);
COMMENT ON TABLE payments IS 'Payment transaction record across booking, POS and invoices.';
CREATE INDEX ix_payments_business_created ON payments (business_id, created_at DESC);

CREATE TABLE payment_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    attempt_number integer NOT NULL,
    provider_request_ref text,
    provider_response_code text,
    attempt_status text NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    safe_response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (payment_id, attempt_number)
);
COMMENT ON TABLE payment_attempts IS 'Provider payment attempts and outcomes.';
CREATE INDEX ix_payment_attempts_business_created ON payment_attempts (business_id, created_at DESC);

CREATE TABLE payment_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    provider_code text NOT NULL,
    provider_event_id text NOT NULL,
    payment_id uuid REFERENCES payments(id),
    event_type text NOT NULL,
    payload_hash text,
    signature_valid boolean,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    processing_status text NOT NULL DEFAULT 'RECEIVED',
    safe_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_code, provider_event_id)
);
COMMENT ON TABLE payment_events IS 'Webhook/provider event inbox with idempotency.';
CREATE INDEX ix_payment_events_business_created ON payment_events (business_id, created_at DESC);

CREATE TABLE payment_authorizations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_id uuid REFERENCES payments(id),
    saved_payment_method_id uuid REFERENCES saved_payment_methods(id),
    provider_authorization_ref text,
    authorized_amount numeric(14,2) NOT NULL,
    captured_amount numeric(14,2) NOT NULL DEFAULT 0,
    expires_at timestamptz,
    authorization_status text NOT NULL DEFAULT 'AUTHORIZED',
    authorized_at timestamptz,
    released_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payment_authorizations IS 'Card or method authorisations/guarantees.';
CREATE INDEX ix_payment_authorizations_business_created ON payment_authorizations (business_id, created_at DESC);

CREATE TABLE payment_obligations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    source_type text NOT NULL,
    source_id uuid NOT NULL,
    obligation_type text NOT NULL,
    amount_due numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    due_at timestamptz,
    obligation_status text NOT NULL DEFAULT 'OPEN',
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (amount_due >= 0)
);
COMMENT ON TABLE payment_obligations IS 'Amounts owed against one source record.';
CREATE INDEX ix_payment_obligations_business_created ON payment_obligations (business_id, created_at DESC);

CREATE TABLE payment_allocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_id uuid NOT NULL REFERENCES payments(id),
    payment_obligation_id uuid NOT NULL REFERENCES payment_obligations(id),
    amount_allocated numeric(14,2) NOT NULL,
    allocated_at timestamptz NOT NULL DEFAULT now(),
    allocation_status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (amount_allocated > 0)
);
COMMENT ON TABLE payment_allocations IS 'Allocation of successful payments to obligations.';
CREATE INDEX ix_payment_allocations_business_created ON payment_allocations (business_id, created_at DESC);

CREATE TABLE payment_links (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_obligation_id uuid NOT NULL REFERENCES payment_obligations(id),
    token_hash text NOT NULL UNIQUE,
    amount numeric(14,2),
    expires_at timestamptz NOT NULL,
    max_uses integer NOT NULL DEFAULT 1,
    uses_count integer NOT NULL DEFAULT 0,
    link_status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payment_links IS 'Secure links for deposits and outstanding balances.';
CREATE INDEX ix_payment_links_business_created ON payment_links (business_id, created_at DESC);

CREATE TABLE refunds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_id uuid NOT NULL REFERENCES payments(id),
    amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    reason text,
    refund_status text NOT NULL DEFAULT 'PENDING',
    provider_refund_ref text,
    requested_by_user_id uuid REFERENCES identity.users(id),
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (amount > 0)
);
COMMENT ON TABLE refunds IS 'Full and partial refund records.';
CREATE INDEX ix_refunds_business_created ON refunds (business_id, created_at DESC);

CREATE TABLE payment_terminals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    provider_account_id uuid REFERENCES payment_provider_accounts(id),
    terminal_code text NOT NULL,
    provider_terminal_ref text,
    terminal_status text NOT NULL DEFAULT 'ACTIVE',
    last_seen_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, terminal_code)
);
COMMENT ON TABLE payment_terminals IS 'Registered physical payment terminals.';
CREATE INDEX ix_payment_terminals_business_created ON payment_terminals (business_id, created_at DESC);

CREATE TABLE payment_reconciliations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    provider_account_id uuid NOT NULL REFERENCES payment_provider_accounts(id),
    period_start date NOT NULL,
    period_end date NOT NULL,
    statement_asset_id uuid,
    reconciliation_status text NOT NULL DEFAULT 'OPEN',
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payment_reconciliations IS 'Provider statement reconciliation runs.';
CREATE INDEX ix_payment_reconciliations_business_created ON payment_reconciliations (business_id, created_at DESC);

CREATE TABLE payment_reconciliation_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    reconciliation_id uuid NOT NULL REFERENCES payment_reconciliations(id) ON DELETE CASCADE,
    payment_id uuid REFERENCES payments(id),
    provider_reference text,
    statement_amount numeric(14,2) NOT NULL,
    system_amount numeric(14,2),
    difference_amount numeric(14,2),
    match_status text NOT NULL DEFAULT 'UNMATCHED',
    statement_line jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payment_reconciliation_items IS 'Statement-to-system line matching.';
CREATE INDEX ix_payment_reconciliation_items_business_created ON payment_reconciliation_items (business_id, created_at DESC);

CREATE TABLE settlement_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    provider_account_id uuid REFERENCES payment_provider_accounts(id),
    provider_batch_ref text,
    currency char(3) NOT NULL,
    gross_amount numeric(14,2) NOT NULL DEFAULT 0,
    fee_amount numeric(14,2) NOT NULL DEFAULT 0,
    net_amount numeric(14,2) NOT NULL DEFAULT 0,
    expected_at timestamptz,
    settled_at timestamptz,
    batch_status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE settlement_batches IS 'Provider settlement batches.';
CREATE INDEX ix_settlement_batches_business_created ON settlement_batches (business_id, created_at DESC);

CREATE TABLE settlement_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    settlement_batch_id uuid NOT NULL REFERENCES settlement_batches(id) ON DELETE CASCADE,
    payment_id uuid REFERENCES payments(id),
    gross_amount numeric(14,2) NOT NULL,
    fee_amount numeric(14,2) NOT NULL DEFAULT 0,
    net_amount numeric(14,2) NOT NULL,
    provider_reference text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE settlement_items IS 'Payments included in a settlement.';
CREATE INDEX ix_settlement_items_business_created ON settlement_items (business_id, created_at DESC);

CREATE TABLE business_wallets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    currency char(3) NOT NULL,
    provider_wallet_ref text,
    available_balance numeric(16,2) NOT NULL DEFAULT 0,
    pending_balance numeric(16,2) NOT NULL DEFAULT 0,
    wallet_status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, currency)
);
COMMENT ON TABLE business_wallets IS 'Wallet accounts only where licensed/provider-enabled.';
CREATE INDEX ix_business_wallets_business_created ON business_wallets (business_id, created_at DESC);

CREATE TABLE wallet_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    wallet_id uuid NOT NULL REFERENCES business_wallets(id),
    transaction_type text NOT NULL,
    amount numeric(16,2) NOT NULL,
    currency char(3) NOT NULL,
    source_type text,
    source_id uuid,
    balance_after numeric(16,2),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (amount > 0)
);
COMMENT ON TABLE wallet_transactions IS 'Wallet movement ledger.';
CREATE INDEX ix_wallet_transactions_business_created ON wallet_transactions (business_id, created_at DESC);

CREATE TABLE payouts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    merchant_account_id uuid REFERENCES merchant_accounts(id),
    payout_reference text NOT NULL,
    amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    destination_reference text,
    payout_status text NOT NULL DEFAULT 'PENDING',
    requested_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, payout_reference)
);
COMMENT ON TABLE payouts IS 'Business/professional payout requests and results.';
CREATE INDEX ix_payouts_business_created ON payouts (business_id, created_at DESC);

CREATE TABLE payout_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payout_id uuid NOT NULL REFERENCES payouts(id) ON DELETE CASCADE,
    source_type text NOT NULL,
    source_id uuid NOT NULL,
    amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payout_items IS 'Items included in payouts.';
CREATE INDEX ix_payout_items_business_created ON payout_items (business_id, created_at DESC);

CREATE TABLE payment_disputes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_id uuid NOT NULL REFERENCES payments(id),
    provider_dispute_ref text,
    dispute_reason text,
    disputed_amount numeric(14,2) NOT NULL,
    dispute_status text NOT NULL DEFAULT 'OPEN',
    evidence_due_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE payment_disputes IS 'Chargebacks and payment disputes.';
CREATE INDEX ix_payment_disputes_business_created ON payment_disputes (business_id, created_at DESC);

CREATE TABLE cash_registers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    register_name text NOT NULL,
    register_code text,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (branch_id, register_code)
);
COMMENT ON TABLE cash_registers IS 'Physical/logical cash tills by branch.';
CREATE INDEX ix_cash_registers_business_created ON cash_registers (business_id, created_at DESC);

CREATE TABLE cash_register_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    cash_register_id uuid NOT NULL REFERENCES cash_registers(id),
    opened_by_user_id uuid NOT NULL REFERENCES identity.users(id),
    closed_by_user_id uuid REFERENCES identity.users(id),
    opened_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    opening_float numeric(14,2) NOT NULL DEFAULT 0,
    expected_cash numeric(14,2),
    counted_cash numeric(14,2),
    variance numeric(14,2),
    session_status text NOT NULL DEFAULT 'OPEN',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE cash_register_sessions IS 'Opening/closing cash sessions and cash variance.';
CREATE INDEX ix_cash_register_sessions_business_created ON cash_register_sessions (business_id, created_at DESC);

CREATE TABLE cash_movements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    cash_register_session_id uuid NOT NULL REFERENCES cash_register_sessions(id),
    movement_type text NOT NULL,
    amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    reason text,
    recorded_by_user_id uuid NOT NULL REFERENCES identity.users(id),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    reference_text text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE cash_movements IS 'Non-sale cash in/out and adjustments; sale cash links to payments.';
CREATE INDEX ix_cash_movements_business_created ON cash_movements (business_id, created_at DESC);

CREATE TABLE financial_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    account_code text NOT NULL,
    account_name text NOT NULL,
    account_type text NOT NULL,
    parent_account_id uuid REFERENCES financial_accounts(id),
    currency char(3) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, account_code)
);
COMMENT ON TABLE financial_accounts IS 'Business chart of accounts.';
CREATE INDEX ix_financial_accounts_business_created ON financial_accounts (business_id, created_at DESC);

CREATE TABLE financial_journals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    journal_number text NOT NULL,
    journal_date date NOT NULL,
    description text,
    source_type text,
    source_id uuid,
    journal_status text NOT NULL DEFAULT 'DRAFT',
    posted_at timestamptz,
    posted_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, journal_number)
);
COMMENT ON TABLE financial_journals IS 'Accounting journal headers.';
CREATE INDEX ix_financial_journals_business_created ON financial_journals (business_id, created_at DESC);

CREATE TABLE financial_ledger_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    journal_id uuid NOT NULL REFERENCES financial_journals(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES financial_accounts(id),
    debit_amount numeric(16,2) NOT NULL DEFAULT 0,
    credit_amount numeric(16,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    line_description text,
    line_number integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (debit_amount >= 0),
    CHECK (credit_amount >= 0),
    CHECK (NOT (debit_amount > 0 AND credit_amount > 0))
);
COMMENT ON TABLE financial_ledger_entries IS 'Debit/credit ledger lines.';
CREATE INDEX ix_financial_ledger_entries_business_created ON financial_ledger_entries (business_id, created_at DESC);

CREATE TABLE product_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id uuid REFERENCES product_categories(id),
    code text NOT NULL,
    name text NOT NULL,
    name_i18n jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (code)
);
COMMENT ON TABLE product_categories IS 'Retail product taxonomy.';

CREATE TABLE products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    product_category_id uuid REFERENCES product_categories(id),
    name text NOT NULL,
    description text,
    brand text,
    is_active boolean NOT NULL DEFAULT true,
    is_online boolean NOT NULL DEFAULT false,
    tax_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE products IS 'Business product catalogue.';
CREATE INDEX ix_products_business_created ON products (business_id, created_at DESC);

CREATE TABLE product_variants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku text,
    barcode text,
    variant_name text,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    unit_of_measure text NOT NULL DEFAULT 'each',
    cost_price numeric(14,2),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, sku)
);
COMMENT ON TABLE product_variants IS 'SKU, barcode, size/colour and unit-of-measure variants.';
CREATE INDEX ix_product_variants_business_created ON product_variants (business_id, created_at DESC);

CREATE TABLE product_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL,
    media_type text NOT NULL DEFAULT 'IMAGE',
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE product_media IS 'Product images.';
CREATE INDEX ix_product_media_business_created ON product_media (business_id, created_at DESC);

CREATE TABLE product_prices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    product_variant_id uuid NOT NULL REFERENCES product_variants(id),
    branch_id uuid REFERENCES branches(id),
    price numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    effective_from timestamptz,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (price >= 0)
);
COMMENT ON TABLE product_prices IS 'Branch selling prices.';
CREATE INDEX ix_product_prices_business_created ON product_prices (business_id, created_at DESC);

CREATE TABLE branch_inventory (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    product_variant_id uuid NOT NULL REFERENCES product_variants(id),
    quantity_on_hand numeric(14,3) NOT NULL DEFAULT 0,
    quantity_reserved numeric(14,3) NOT NULL DEFAULT 0,
    reorder_level numeric(14,3) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (branch_id, product_variant_id),
    CHECK (quantity_on_hand >= 0),
    CHECK (quantity_reserved >= 0)
);
COMMENT ON TABLE branch_inventory IS 'Current branch stock and reservation balance.';
CREATE INDEX ix_branch_inventory_business_created ON branch_inventory (business_id, created_at DESC);

CREATE TABLE inventory_movements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    product_variant_id uuid NOT NULL REFERENCES product_variants(id),
    movement_type text NOT NULL,
    quantity_delta numeric(14,3) NOT NULL,
    unit_cost numeric(14,2),
    source_type text,
    source_id uuid,
    recorded_by_user_id uuid REFERENCES identity.users(id),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (quantity_delta <> 0)
);
COMMENT ON TABLE inventory_movements IS 'Immutable stock movement history.';
CREATE INDEX ix_inventory_movements_business_created ON inventory_movements (business_id, created_at DESC);

CREATE TABLE suppliers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    contact_name text,
    email text,
    phone text,
    address jsonb,
    tax_number text,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE suppliers IS 'Supplier records.';
CREATE INDEX ix_suppliers_business_created ON suppliers (business_id, created_at DESC);

CREATE TABLE purchase_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    supplier_id uuid NOT NULL REFERENCES suppliers(id),
    order_number text NOT NULL,
    order_date date NOT NULL,
    expected_at date,
    order_status text NOT NULL DEFAULT 'DRAFT',
    currency char(3) NOT NULL,
    total_amount numeric(14,2) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, order_number)
);
COMMENT ON TABLE purchase_orders IS 'Supplier orders.';
CREATE INDEX ix_purchase_orders_business_created ON purchase_orders (business_id, created_at DESC);

CREATE TABLE purchase_order_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    purchase_order_id uuid NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    product_variant_id uuid NOT NULL REFERENCES product_variants(id),
    quantity_ordered numeric(14,3) NOT NULL,
    unit_cost numeric(14,2) NOT NULL,
    quantity_received numeric(14,3) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (quantity_ordered > 0),
    CHECK (unit_cost >= 0)
);
COMMENT ON TABLE purchase_order_items IS 'Ordered products and quantities.';
CREATE INDEX ix_purchase_order_items_business_created ON purchase_order_items (business_id, created_at DESC);

CREATE TABLE goods_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    purchase_order_id uuid REFERENCES purchase_orders(id),
    branch_id uuid NOT NULL REFERENCES branches(id),
    supplier_id uuid REFERENCES suppliers(id),
    receipt_number text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    received_by_user_id uuid REFERENCES identity.users(id),
    receipt_status text NOT NULL DEFAULT 'RECEIVED',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, receipt_number)
);
COMMENT ON TABLE goods_receipts IS 'Supplier delivery receipt headers.';
CREATE INDEX ix_goods_receipts_business_created ON goods_receipts (business_id, created_at DESC);

CREATE TABLE goods_receipt_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    goods_receipt_id uuid NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    purchase_order_item_id uuid REFERENCES purchase_order_items(id),
    product_variant_id uuid NOT NULL REFERENCES product_variants(id),
    quantity_received numeric(14,3) NOT NULL,
    unit_cost numeric(14,2) NOT NULL,
    batch_number text,
    expires_on date,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE goods_receipt_items IS 'Received quantities and unit costs.';
CREATE INDEX ix_goods_receipt_items_business_created ON goods_receipt_items (business_id, created_at DESC);

CREATE TABLE online_store_settings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    store_slug text NOT NULL UNIQUE,
    store_status text NOT NULL DEFAULT 'DISABLED',
    currency char(3) NOT NULL,
    allow_pickup boolean NOT NULL DEFAULT true,
    allow_delivery boolean NOT NULL DEFAULT false,
    settings jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE online_store_settings IS 'Business online retail store configuration.';
CREATE INDEX ix_online_store_settings_business_created ON online_store_settings (business_id, created_at DESC);

CREATE TABLE delivery_methods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    code text NOT NULL,
    name text NOT NULL,
    method_type text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, code)
);
COMMENT ON TABLE delivery_methods IS 'Delivery and collection method catalogue.';
CREATE INDEX ix_delivery_methods_business_created ON delivery_methods (business_id, created_at DESC);

CREATE TABLE delivery_zones (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    delivery_method_id uuid NOT NULL REFERENCES delivery_methods(id),
    name text NOT NULL,
    administrative_area_id uuid,
    boundary geography(MultiPolygon,4326),
    fee numeric(14,2) NOT NULL DEFAULT 0,
    minimum_order_amount numeric(14,2),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE delivery_zones IS 'Configurable delivery areas and fees.';
CREATE INDEX ix_delivery_zones_business_created ON delivery_zones (business_id, created_at DESC);

CREATE TABLE sale_fulfilments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    sale_id uuid NOT NULL REFERENCES sales(id),
    delivery_method_id uuid REFERENCES delivery_methods(id),
    delivery_zone_id uuid REFERENCES delivery_zones(id),
    fulfilment_type text NOT NULL,
    fulfilment_status text NOT NULL DEFAULT 'PENDING',
    recipient_name text,
    recipient_phone text,
    address_snapshot jsonb,
    tracking_reference text,
    scheduled_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE sale_fulfilments IS 'Delivery/click-and-collect fulfilment for sales.';
CREATE INDEX ix_sale_fulfilments_business_created ON sale_fulfilments (business_id, created_at DESC);

CREATE TABLE subscription_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    description text,
    billing_interval text NOT NULL,
    price numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE subscription_plans IS 'Platform SaaS plan catalogue.';

CREATE TABLE plan_features (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_plan_id uuid NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
    feature_code text NOT NULL,
    quantity_limit bigint,
    is_enabled boolean NOT NULL DEFAULT true,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (subscription_plan_id, feature_code)
);
COMMENT ON TABLE plan_features IS 'Plan entitlements and usage limits.';

CREATE TABLE business_subscriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    subscription_plan_id uuid NOT NULL REFERENCES subscription_plans(id),
    subscription_status text NOT NULL DEFAULT 'TRIALING',
    starts_at timestamptz NOT NULL,
    current_period_start timestamptz,
    current_period_end timestamptz,
    trial_ends_at timestamptz,
    cancel_at_period_end boolean NOT NULL DEFAULT false,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_subscriptions IS 'Business subscription lifecycle.';
CREATE INDEX ix_business_subscriptions_business_created ON business_subscriptions (business_id, created_at DESC);

CREATE TABLE subscription_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_subscription_id uuid NOT NULL REFERENCES business_subscriptions(id),
    old_plan_id uuid REFERENCES subscription_plans(id),
    new_plan_id uuid REFERENCES subscription_plans(id),
    old_status text,
    new_status text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    change_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE subscription_history IS 'Plan and status changes.';
CREATE INDEX ix_subscription_history_business_created ON subscription_history (business_id, created_at DESC);

CREATE TABLE billing_invoices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_subscription_id uuid REFERENCES business_subscriptions(id),
    invoice_number text NOT NULL,
    issued_at timestamptz NOT NULL,
    due_at timestamptz,
    currency char(3) NOT NULL,
    subtotal_amount numeric(14,2) NOT NULL,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    total_amount numeric(14,2) NOT NULL,
    invoice_status text NOT NULL DEFAULT 'OPEN',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, invoice_number)
);
COMMENT ON TABLE billing_invoices IS 'Platform subscription invoices.';
CREATE INDEX ix_billing_invoices_business_created ON billing_invoices (business_id, created_at DESC);

CREATE TABLE billing_invoice_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    billing_invoice_id uuid NOT NULL REFERENCES billing_invoices(id) ON DELETE CASCADE,
    description text NOT NULL,
    quantity numeric(12,3) NOT NULL DEFAULT 1,
    unit_price numeric(14,2) NOT NULL,
    line_total numeric(14,2) NOT NULL,
    feature_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE billing_invoice_items IS 'Platform subscription invoice lines.';
CREATE INDEX ix_billing_invoice_items_business_created ON billing_invoice_items (business_id, created_at DESC);

CREATE TABLE billing_usage_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    feature_code text NOT NULL,
    quantity numeric(16,4) NOT NULL,
    unit text NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    source_type text,
    source_id uuid,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE billing_usage_records IS 'Metered usage such as SMS or AI.';
CREATE INDEX ix_billing_usage_records_business_created ON billing_usage_records (business_id, created_at DESC);

CREATE TABLE business_addons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    addon_code text NOT NULL,
    addon_name text NOT NULL,
    price numeric(14,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    starts_at timestamptz,
    ends_at timestamptz,
    addon_status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE business_addons IS 'Optional paid business features.';
CREATE INDEX ix_business_addons_business_created ON business_addons (business_id, created_at DESC);

CREATE TABLE gift_cards (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    gift_card_code_hash text NOT NULL UNIQUE,
    display_code_last4 char(4),
    face_value numeric(14,2) NOT NULL,
    current_balance numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    purchased_by_customer_id uuid REFERENCES business_customers(id),
    recipient_name text,
    recipient_email text,
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    gift_card_status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (face_value >= 0),
    CHECK (current_balance >= 0)
);
COMMENT ON TABLE gift_cards IS 'Gift card tokens, face value and lifecycle.';
CREATE INDEX ix_gift_cards_business_created ON gift_cards (business_id, created_at DESC);

CREATE TABLE gift_card_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    gift_card_id uuid NOT NULL REFERENCES gift_cards(id),
    transaction_type text NOT NULL,
    amount numeric(14,2) NOT NULL,
    balance_after numeric(14,2) NOT NULL,
    source_type text,
    source_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE gift_card_transactions IS 'Gift card issuance, redemption and adjustment ledger.';
CREATE INDEX ix_gift_card_transactions_business_created ON gift_card_transactions (business_id, created_at DESC);

CREATE TABLE service_packages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    description text,
    price numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    validity_days integer,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE service_packages IS 'Prepaid service package definitions.';
CREATE INDEX ix_service_packages_business_created ON service_packages (business_id, created_at DESC);

CREATE TABLE service_package_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_package_id uuid NOT NULL REFERENCES service_packages(id) ON DELETE CASCADE,
    service_id uuid NOT NULL REFERENCES services(id),
    service_variant_id uuid REFERENCES service_variants(id),
    quantity_included numeric(10,2) NOT NULL DEFAULT 1,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE service_package_items IS 'Services included in packages.';
CREATE INDEX ix_service_package_items_business_created ON service_package_items (business_id, created_at DESC);

CREATE TABLE customer_package_balances (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    service_package_id uuid NOT NULL REFERENCES service_packages(id),
    purchased_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    purchase_payment_id uuid REFERENCES payments(id),
    package_status text NOT NULL DEFAULT 'ACTIVE',
    remaining_units jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_package_balances IS 'Purchased customer package entitlements.';
CREATE INDEX ix_customer_package_balances_business_created ON customer_package_balances (business_id, created_at DESC);

CREATE TABLE package_redemptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    customer_package_balance_id uuid NOT NULL REFERENCES customer_package_balances(id),
    booking_item_id uuid REFERENCES booking_items(id),
    service_id uuid REFERENCES services(id),
    quantity_redeemed numeric(10,2) NOT NULL DEFAULT 1,
    redeemed_at timestamptz NOT NULL DEFAULT now(),
    redeemed_by_employee_id uuid REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE package_redemptions IS 'Package service consumption.';
CREATE INDEX ix_package_redemptions_business_created ON package_redemptions (business_id, created_at DESC);

CREATE TABLE membership_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    description text,
    price numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    billing_interval text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE membership_plans IS 'Recurring or one-time membership products.';
CREATE INDEX ix_membership_plans_business_created ON membership_plans (business_id, created_at DESC);

CREATE TABLE customer_memberships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    membership_plan_id uuid NOT NULL REFERENCES membership_plans(id),
    membership_status text NOT NULL DEFAULT 'ACTIVE',
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    auto_renew boolean NOT NULL DEFAULT false,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_memberships IS 'Customer membership subscription state.';
CREATE INDEX ix_customer_memberships_business_created ON customer_memberships (business_id, created_at DESC);

CREATE TABLE membership_benefits (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    membership_plan_id uuid NOT NULL REFERENCES membership_plans(id) ON DELETE CASCADE,
    benefit_type text NOT NULL,
    service_id uuid REFERENCES services(id),
    benefit_value numeric(14,2),
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE membership_benefits IS 'Included services and discounts.';
CREATE INDEX ix_membership_benefits_business_created ON membership_benefits (business_id, created_at DESC);

CREATE TABLE membership_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    customer_membership_id uuid NOT NULL REFERENCES customer_memberships(id),
    transaction_type text NOT NULL,
    amount numeric(14,2) NOT NULL DEFAULT 0,
    payment_id uuid REFERENCES payments(id),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE membership_transactions IS 'Membership payments and benefit use.';
CREATE INDEX ix_membership_transactions_business_created ON membership_transactions (business_id, created_at DESC);

CREATE TABLE loyalty_programmes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    programme_name text NOT NULL,
    points_per_currency_unit numeric(10,4) NOT NULL DEFAULT 1,
    currency char(3) NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    rules jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE loyalty_programmes IS 'Business loyalty programme settings.';
CREATE INDEX ix_loyalty_programmes_business_created ON loyalty_programmes (business_id, created_at DESC);

CREATE TABLE loyalty_tiers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    tier_name text NOT NULL,
    minimum_points bigint NOT NULL DEFAULT 0,
    benefits jsonb NOT NULL DEFAULT '{}'::jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, tier_name)
);
COMMENT ON TABLE loyalty_tiers IS 'Tier qualification levels and benefits.';
CREATE INDEX ix_loyalty_tiers_business_created ON loyalty_tiers (business_id, created_at DESC);

CREATE TABLE loyalty_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    points_balance bigint NOT NULL DEFAULT 0,
    lifetime_points bigint NOT NULL DEFAULT 0,
    loyalty_tier_id uuid REFERENCES loyalty_tiers(id),
    joined_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_customer_id)
);
COMMENT ON TABLE loyalty_accounts IS 'Customer points account.';
CREATE INDEX ix_loyalty_accounts_business_created ON loyalty_accounts (business_id, created_at DESC);

CREATE TABLE loyalty_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    loyalty_account_id uuid NOT NULL REFERENCES loyalty_accounts(id),
    transaction_type text NOT NULL,
    points_delta bigint NOT NULL,
    balance_after bigint NOT NULL,
    source_type text,
    source_id uuid,
    expires_at timestamptz,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE loyalty_transactions IS 'Points earning, redemption, expiry and adjustment ledger.';
CREATE INDEX ix_loyalty_transactions_business_created ON loyalty_transactions (business_id, created_at DESC);

CREATE TABLE loyalty_rewards (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    reward_name text NOT NULL,
    points_cost bigint NOT NULL,
    reward_type text NOT NULL,
    reward_value jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE loyalty_rewards IS 'Redeemable rewards.';
CREATE INDEX ix_loyalty_rewards_business_created ON loyalty_rewards (business_id, created_at DESC);

CREATE TABLE loyalty_redemptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    loyalty_account_id uuid NOT NULL REFERENCES loyalty_accounts(id),
    loyalty_reward_id uuid NOT NULL REFERENCES loyalty_rewards(id),
    points_spent bigint NOT NULL,
    redemption_code text NOT NULL UNIQUE,
    redemption_status text NOT NULL DEFAULT 'ISSUED',
    redeemed_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE loyalty_redemptions IS 'Reward redemption records.';
CREATE INDEX ix_loyalty_redemptions_business_created ON loyalty_redemptions (business_id, created_at DESC);

CREATE TABLE promotions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    promotion_code text,
    name text NOT NULL,
    promotion_type text NOT NULL,
    discount_type text NOT NULL,
    discount_value numeric(14,2) NOT NULL,
    starts_at timestamptz,
    ends_at timestamptz,
    usage_limit integer,
    per_customer_limit integer,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE promotions IS 'Promotions, discounts and peak/off-peak offers.';
CREATE INDEX ix_promotions_business_created ON promotions (business_id, created_at DESC);

CREATE TABLE promotion_redemptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    promotion_id uuid NOT NULL REFERENCES promotions(id),
    business_customer_id uuid REFERENCES business_customers(id),
    booking_id uuid REFERENCES bookings(id),
    sale_id uuid REFERENCES sales(id),
    discount_amount numeric(14,2) NOT NULL,
    redeemed_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE promotion_redemptions IS 'Promotion use against a booking or sale.';
CREATE INDEX ix_promotion_redemptions_business_created ON promotion_redemptions (business_id, created_at DESC);

CREATE TABLE marketing_campaign_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    channel text NOT NULL,
    subject_template text,
    body_template text NOT NULL,
    locale text NOT NULL DEFAULT 'en',
    template_status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE marketing_campaign_templates IS 'Reusable marketing templates.';
CREATE INDEX ix_marketing_campaign_templates_business_created ON marketing_campaign_templates (business_id, created_at DESC);

CREATE TABLE marketing_campaigns (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    template_id uuid REFERENCES marketing_campaign_templates(id),
    name text NOT NULL,
    channel text NOT NULL,
    audience_definition jsonb NOT NULL DEFAULT '{}'::jsonb,
    scheduled_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    campaign_status text NOT NULL DEFAULT 'DRAFT',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE marketing_campaigns IS 'Campaign definition, audience and schedule.';
CREATE INDEX ix_marketing_campaigns_business_created ON marketing_campaigns (business_id, created_at DESC);

CREATE TABLE marketing_campaign_recipients (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    campaign_id uuid NOT NULL REFERENCES marketing_campaigns(id) ON DELETE CASCADE,
    business_customer_id uuid NOT NULL REFERENCES business_customers(id),
    delivery_status text NOT NULL DEFAULT 'QUEUED',
    provider_reference text,
    sent_at timestamptz,
    failure_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (campaign_id, business_customer_id)
);
COMMENT ON TABLE marketing_campaign_recipients IS 'Campaign recipient and status.';
CREATE INDEX ix_marketing_campaign_recipients_business_created ON marketing_campaign_recipients (business_id, created_at DESC);

CREATE TABLE marketing_campaign_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    campaign_recipient_id uuid NOT NULL REFERENCES marketing_campaign_recipients(id) ON DELETE CASCADE,
    event_type text NOT NULL,
    event_at timestamptz NOT NULL DEFAULT now(),
    event_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE marketing_campaign_events IS 'Open/click/conversion/unsubscribe events.';
CREATE INDEX ix_marketing_campaign_events_business_created ON marketing_campaign_events (business_id, created_at DESC);

CREATE TABLE marketing_automations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    trigger_event text NOT NULL,
    automation_status text NOT NULL DEFAULT 'DRAFT',
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE marketing_automations IS 'Triggered customer lifecycle workflows.';
CREATE INDEX ix_marketing_automations_business_created ON marketing_automations (business_id, created_at DESC);

CREATE TABLE marketing_automation_steps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    automation_id uuid NOT NULL REFERENCES marketing_automations(id) ON DELETE CASCADE,
    step_order integer NOT NULL,
    action_type text NOT NULL,
    delay_seconds integer NOT NULL DEFAULT 0,
    action_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (automation_id, step_order)
);
COMMENT ON TABLE marketing_automation_steps IS 'Actions in an automation.';
CREATE INDEX ix_marketing_automation_steps_business_created ON marketing_automation_steps (business_id, created_at DESC);

CREATE TABLE marketing_automation_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    automation_id uuid NOT NULL REFERENCES marketing_automations(id),
    business_customer_id uuid REFERENCES business_customers(id),
    run_status text NOT NULL DEFAULT 'QUEUED',
    current_step integer NOT NULL DEFAULT 0,
    started_at timestamptz,
    completed_at timestamptz,
    context jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE marketing_automation_runs IS 'Per-customer automation execution state.';
CREATE INDEX ix_marketing_automation_runs_business_created ON marketing_automation_runs (business_id, created_at DESC);

CREATE TABLE customer_segments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name text NOT NULL,
    segment_type text NOT NULL DEFAULT 'DYNAMIC',
    filter_definition jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_calculated_at timestamptz,
    customer_count integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_segments IS 'Saved dynamic or static customer audiences.';
CREATE INDEX ix_customer_segments_business_created ON customer_segments (business_id, created_at DESC);

CREATE TABLE conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    conversation_type text NOT NULL,
    context_type text NOT NULL,
    context_id uuid,
    title text,
    created_by_user_id uuid REFERENCES identity.users(id),
    last_message_at timestamptz,
    conversation_status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE conversations IS 'Customer chats and internal team channels.';

CREATE TABLE conversation_participants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    participant_role text NOT NULL DEFAULT 'MEMBER',
    joined_at timestamptz NOT NULL DEFAULT now(),
    left_at timestamptz,
    is_muted boolean NOT NULL DEFAULT false,
    last_read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, user_id)
);
COMMENT ON TABLE conversation_participants IS 'Conversation membership and read/mute state.';

CREATE TABLE messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_user_id uuid REFERENCES identity.users(id),
    message_type text NOT NULL DEFAULT 'TEXT',
    body text,
    reply_to_message_id uuid REFERENCES messages(id),
    client_message_id text,
    sent_at timestamptz NOT NULL DEFAULT now(),
    edited_at timestamptz,
    deleted_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sender_user_id, client_message_id)
);
COMMENT ON TABLE messages IS 'Realtime text/file/system/AI messages and threads.';

CREATE TABLE message_attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL,
    file_name text,
    mime_type text,
    file_size_bytes bigint,
    duration_ms bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE message_attachments IS 'File references attached to messages.';

CREATE TABLE message_reactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    reaction text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (message_id, user_id, reaction)
);
COMMENT ON TABLE message_reactions IS 'Emoji reactions.';

CREATE TABLE message_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    delivered_at timestamptz,
    read_at timestamptz,
    receipt_status text NOT NULL DEFAULT 'SENT',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (message_id, user_id)
);
COMMENT ON TABLE message_receipts IS 'Per-recipient delivered/read receipts.';

CREATE TABLE notification_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_code text NOT NULL,
    channel text NOT NULL,
    locale text NOT NULL DEFAULT 'en',
    subject_template text,
    body_template text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (template_code, channel, locale)
);
COMMENT ON TABLE notification_templates IS 'Reusable multilingual notification content.';

CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    notification_type text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    scheduled_at timestamptz,
    read_at timestamptz,
    notification_status text NOT NULL DEFAULT 'QUEUED',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE notifications IS 'In-app and scheduled notification records.';

CREATE TABLE notification_deliveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    channel text NOT NULL,
    provider_code text,
    provider_message_ref text,
    delivery_status text NOT NULL DEFAULT 'QUEUED',
    attempt_count integer NOT NULL DEFAULT 0,
    last_attempt_at timestamptz,
    delivered_at timestamptz,
    failure_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE notification_deliveries IS 'Channel-specific delivery attempts/results.';

CREATE TABLE notification_preferences (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    notification_type text NOT NULL,
    channel text NOT NULL,
    is_enabled boolean NOT NULL DEFAULT true,
    quiet_hours_start time,
    quiet_hours_end time,
    timezone text NOT NULL ,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, notification_type, channel)
);
COMMENT ON TABLE notification_preferences IS 'User channel/type preferences and quiet hours.';

CREATE TABLE push_devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    platform text NOT NULL,
    push_token_hash text NOT NULL UNIQUE,
    provider_token_ref text,
    device_name text,
    app_version text,
    last_seen_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE push_devices IS 'Registered Android/iOS/web push tokens.';

CREATE TABLE ai_concierge_settings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    is_enabled boolean NOT NULL DEFAULT false,
    supported_channels text[] NOT NULL DEFAULT ARRAY['CHAT']::text[],
    supported_locales text[] NOT NULL DEFAULT ARRAY['en']::text[],
    system_configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    human_handoff_enabled boolean NOT NULL DEFAULT true,
    updated_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_concierge_settings IS 'Business AI receptionist configuration.';
CREATE INDEX ix_ai_concierge_settings_business_created ON ai_concierge_settings (business_id, created_at DESC);

CREATE TABLE ai_knowledge_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    title text NOT NULL,
    content text NOT NULL,
    locale text NOT NULL DEFAULT 'en',
    knowledge_type text NOT NULL DEFAULT 'FAQ',
    source_asset_id uuid,
    is_published boolean NOT NULL DEFAULT true,
    embedding jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_knowledge_entries IS 'Business FAQ and knowledge-base entries.';
CREATE INDEX ix_ai_knowledge_entries_business_created ON ai_knowledge_entries (business_id, created_at DESC);

CREATE TABLE ai_interactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    user_id uuid REFERENCES identity.users(id),
    business_customer_id uuid REFERENCES business_customers(id),
    channel text NOT NULL,
    locale text NOT NULL DEFAULT 'en',
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    transcript_asset_id uuid,
    interaction_status text NOT NULL DEFAULT 'OPEN',
    outcome jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_interactions IS 'AI call/chat session, transcript, and outcome.';
CREATE INDEX ix_ai_interactions_business_created ON ai_interactions (business_id, created_at DESC);

CREATE TABLE ai_action_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    ai_interaction_id uuid NOT NULL REFERENCES ai_interactions(id),
    action_type text NOT NULL,
    requested_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    result_payload jsonb,
    approval_required boolean NOT NULL DEFAULT true,
    approved_by_user_id uuid REFERENCES identity.users(id),
    executed_at timestamptz,
    action_status text NOT NULL DEFAULT 'PROPOSED',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_action_logs IS 'Auditable booking and other AI actions.';
CREATE INDEX ix_ai_action_logs_business_created ON ai_action_logs (business_id, created_at DESC);

CREATE TABLE ai_usage_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    ai_interaction_id uuid REFERENCES ai_interactions(id),
    provider_code text NOT NULL,
    model_code text NOT NULL,
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    audio_seconds numeric(12,2) NOT NULL DEFAULT 0,
    cost_amount numeric(14,6) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL DEFAULT 'USD',
    recorded_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE ai_usage_records IS 'AI usage and cost metering.';
CREATE INDEX ix_ai_usage_records_business_created ON ai_usage_records (business_id, created_at DESC);

CREATE TABLE saved_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    created_by_user_id uuid NOT NULL REFERENCES identity.users(id),
    report_name text NOT NULL,
    report_type text NOT NULL,
    filters jsonb NOT NULL DEFAULT '{}'::jsonb,
    columns_config jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_shared boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE saved_reports IS 'Saved report definitions and filters.';
CREATE INDEX ix_saved_reports_business_created ON saved_reports (business_id, created_at DESC);

CREATE TABLE report_schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    saved_report_id uuid NOT NULL REFERENCES saved_reports(id) ON DELETE CASCADE,
    frequency text NOT NULL,
    cron_expression text,
    timezone text NOT NULL ,
    recipients jsonb NOT NULL DEFAULT '[]'::jsonb,
    next_run_at timestamptz,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE report_schedules IS 'Scheduled report delivery configuration.';
CREATE INDEX ix_report_schedules_business_created ON report_schedules (business_id, created_at DESC);

CREATE TABLE data_exports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    requested_by_user_id uuid NOT NULL REFERENCES identity.users(id),
    export_type text NOT NULL,
    filters jsonb NOT NULL DEFAULT '{}'::jsonb,
    asset_id uuid,
    export_status text NOT NULL DEFAULT 'QUEUED',
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE data_exports IS 'Data export jobs and generated files.';

CREATE TABLE external_integrations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    provider_code text NOT NULL,
    integration_type text NOT NULL,
    external_account_ref text,
    credentials_secret_ref text,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    integration_status text NOT NULL DEFAULT 'DISABLED',
    last_sync_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE external_integrations IS 'External provider connections and secret references.';

CREATE TABLE integration_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    integration_id uuid REFERENCES external_integrations(id),
    provider_code text NOT NULL,
    provider_event_id text,
    event_type text NOT NULL,
    payload_hash text,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    processing_status text NOT NULL DEFAULT 'RECEIVED',
    safe_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_code, provider_event_id)
);
COMMENT ON TABLE integration_events IS 'External integration event processing history.';

CREATE TABLE platform_settings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key text NOT NULL UNIQUE,
    setting_value jsonb NOT NULL,
    updated_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE platform_settings IS 'Global platform configuration.';

CREATE TABLE feature_flags (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    flag_key text NOT NULL,
    description text,
    is_enabled boolean NOT NULL DEFAULT false,
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    rollout_percentage numeric(5,2) NOT NULL DEFAULT 0,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (flag_key, business_id)
);
COMMENT ON TABLE feature_flags IS 'Global or business-level feature rollout controls.';

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid REFERENCES businesses(id) ON DELETE SET NULL,
    actor_user_id uuid REFERENCES identity.users(id),
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid,
    ip_address inet,
    user_agent text,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    changes jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE audit_logs IS 'Security and business action audit trail.';

CREATE TABLE support_tickets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number text NOT NULL UNIQUE,
    opened_by_user_id uuid NOT NULL REFERENCES identity.users(id),
    business_id uuid REFERENCES businesses(id),
    assigned_to_user_id uuid REFERENCES identity.users(id),
    subject text NOT NULL,
    category text,
    priority text NOT NULL DEFAULT 'NORMAL',
    ticket_status text NOT NULL DEFAULT 'OPEN',
    created_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE support_tickets IS 'Customer/business support cases.';

CREATE TABLE support_ticket_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    support_ticket_id uuid NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    sender_user_id uuid NOT NULL REFERENCES identity.users(id),
    message_body text NOT NULL,
    is_internal boolean NOT NULL DEFAULT false,
    asset_id uuid,
    sent_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE support_ticket_messages IS 'Support case conversations.';

CREATE TABLE file_assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid REFERENCES businesses(id) ON DELETE CASCADE,
    owner_user_id uuid REFERENCES identity.users(id),
    storage_provider text NOT NULL DEFAULT 'R2',
    storage_key text NOT NULL UNIQUE,
    original_filename text,
    mime_type text,
    size_bytes bigint,
    checksum_sha256 text,
    access_classification text NOT NULL DEFAULT 'PRIVATE',
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE file_assets IS 'Cloud file metadata and access classification.';

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type text NOT NULL,
    event_version integer NOT NULL DEFAULT 1,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable event publication.';

CREATE TABLE idempotency_keys (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scope text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash text NOT NULL,
    response_status integer,
    response_body jsonb,
    resource_type text,
    resource_id uuid,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scope, idempotency_key)
);
COMMENT ON TABLE idempotency_keys IS 'Request deduplication records.';

CREATE TABLE administrative_areas (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL,
    parent_id uuid REFERENCES administrative_areas(id),
    area_type text NOT NULL,
    name text NOT NULL,
    name_local text,
    code text,
    boundary geography(MultiPolygon,4326),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE administrative_areas IS 'Country-scoped administrative areas with map geometry; administrative levels and labels are country-configured.';

CREATE TABLE countries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL UNIQUE,
    name text NOT NULL,
    phone_calling_code text,
    default_currency char(3),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE countries IS 'Country reference data.';

CREATE TABLE languages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    language_code text NOT NULL UNIQUE,
    name text NOT NULL,
    native_name text,
    is_rtl boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE languages IS 'Supported language reference data.';

CREATE TABLE currencies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    currency_code char(3) NOT NULL UNIQUE,
    name text NOT NULL,
    symbol text,
    minor_unit smallint NOT NULL DEFAULT 2,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE currencies IS 'Currency and decimal precision reference data.';

CREATE TABLE public_holidays (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL,
    administrative_area_id uuid REFERENCES administrative_areas(id),
    holiday_date date NOT NULL,
    name text NOT NULL,
    is_recurring boolean NOT NULL DEFAULT false,
    calendar_type text NOT NULL DEFAULT 'GREGORIAN',
    holiday_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public_holidays IS 'National and regional public holiday calendar.';

ALTER TABLE business_category_assignments ADD CONSTRAINT uq_business_category_assignments_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_memberships ADD CONSTRAINT uq_business_memberships_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_invitations ADD CONSTRAINT uq_business_invitations_business_id_id UNIQUE (business_id, id);
ALTER TABLE branches ADD CONSTRAINT uq_branches_business_id_id UNIQUE (business_id, id);
ALTER TABLE branch_hours ADD CONSTRAINT uq_branch_hours_business_id_id UNIQUE (business_id, id);
ALTER TABLE branch_schedule_exceptions ADD CONSTRAINT uq_branch_schedule_exceptions_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_settings ADD CONSTRAINT uq_business_settings_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_media ADD CONSTRAINT uq_business_media_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_verification_documents ADD CONSTRAINT uq_business_verification_documents_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_domains ADD CONSTRAINT uq_business_domains_business_id_id UNIQUE (business_id, id);
ALTER TABLE employees ADD CONSTRAINT uq_employees_business_id_id UNIQUE (business_id, id);
ALTER TABLE employee_branch_assignments ADD CONSTRAINT uq_employee_branch_assignments_business_id_id UNIQUE (business_id, id);
ALTER TABLE employee_working_hours ADD CONSTRAINT uq_employee_working_hours_business_id_id UNIQUE (business_id, id);
ALTER TABLE employee_shifts ADD CONSTRAINT uq_employee_shifts_business_id_id UNIQUE (business_id, id);
ALTER TABLE employee_schedule_exceptions ADD CONSTRAINT uq_employee_schedule_exceptions_business_id_id UNIQUE (business_id, id);
ALTER TABLE employee_time_entries ADD CONSTRAINT uq_employee_time_entries_business_id_id UNIQUE (business_id, id);
ALTER TABLE employee_compensation ADD CONSTRAINT uq_employee_compensation_business_id_id UNIQUE (business_id, id);
ALTER TABLE team_targets ADD CONSTRAINT uq_team_targets_business_id_id UNIQUE (business_id, id);
ALTER TABLE team_target_results ADD CONSTRAINT uq_team_target_results_business_id_id UNIQUE (business_id, id);
ALTER TABLE commission_rules ADD CONSTRAINT uq_commission_rules_business_id_id UNIQUE (business_id, id);
ALTER TABLE commission_entries ADD CONSTRAINT uq_commission_entries_business_id_id UNIQUE (business_id, id);
ALTER TABLE services ADD CONSTRAINT uq_services_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_variants ADD CONSTRAINT uq_service_variants_business_id_id UNIQUE (business_id, id);
ALTER TABLE employee_services ADD CONSTRAINT uq_employee_services_business_id_id UNIQUE (business_id, id);
ALTER TABLE branch_services ADD CONSTRAINT uq_branch_services_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_prices ADD CONSTRAINT uq_service_prices_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_addons ADD CONSTRAINT uq_service_addons_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_variant_addons ADD CONSTRAINT uq_service_variant_addons_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_addon_prices ADD CONSTRAINT uq_service_addon_prices_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_pricing_rules ADD CONSTRAINT uq_service_pricing_rules_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_media ADD CONSTRAINT uq_service_media_business_id_id UNIQUE (business_id, id);
ALTER TABLE resources ADD CONSTRAINT uq_resources_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_resource_requirements ADD CONSTRAINT uq_service_resource_requirements_business_id_id UNIQUE (business_id, id);
ALTER TABLE bookings ADD CONSTRAINT uq_bookings_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_groups ADD CONSTRAINT uq_booking_groups_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_items ADD CONSTRAINT uq_booking_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_item_addons ADD CONSTRAINT uq_booking_item_addons_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_item_resources ADD CONSTRAINT uq_booking_item_resources_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_status_history ADD CONSTRAINT uq_booking_status_history_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_holds ADD CONSTRAINT uq_booking_holds_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_waitlists ADD CONSTRAINT uq_booking_waitlists_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_waitlist_offers ADD CONSTRAINT uq_booking_waitlist_offers_business_id_id UNIQUE (business_id, id);
ALTER TABLE calendar_blocks ADD CONSTRAINT uq_calendar_blocks_business_id_id UNIQUE (business_id, id);
ALTER TABLE class_sessions ADD CONSTRAINT uq_class_sessions_business_id_id UNIQUE (business_id, id);
ALTER TABLE class_enrollments ADD CONSTRAINT uq_class_enrollments_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_cancellations ADD CONSTRAINT uq_booking_cancellations_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_payment_policies ADD CONSTRAINT uq_booking_payment_policies_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_item_payment_terms ADD CONSTRAINT uq_booking_item_payment_terms_business_id_id UNIQUE (business_id, id);
ALTER TABLE booking_fees ADD CONSTRAINT uq_booking_fees_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_customers ADD CONSTRAINT uq_business_customers_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_notes ADD CONSTRAINT uq_customer_notes_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_tags ADD CONSTRAINT uq_customer_tags_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_tag_assignments ADD CONSTRAINT uq_customer_tag_assignments_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_documents ADD CONSTRAINT uq_customer_documents_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_treatment_records ADD CONSTRAINT uq_customer_treatment_records_business_id_id UNIQUE (business_id, id);
ALTER TABLE patch_tests ADD CONSTRAINT uq_patch_tests_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_consents ADD CONSTRAINT uq_customer_consents_business_id_id UNIQUE (business_id, id);
ALTER TABLE form_templates ADD CONSTRAINT uq_form_templates_business_id_id UNIQUE (business_id, id);
ALTER TABLE form_fields ADD CONSTRAINT uq_form_fields_business_id_id UNIQUE (business_id, id);
ALTER TABLE form_submissions ADD CONSTRAINT uq_form_submissions_business_id_id UNIQUE (business_id, id);
ALTER TABLE form_answers ADD CONSTRAINT uq_form_answers_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketplace_featured_listings ADD CONSTRAINT uq_marketplace_featured_listings_business_id_id UNIQUE (business_id, id);
ALTER TABLE reviews ADD CONSTRAINT uq_reviews_business_id_id UNIQUE (business_id, id);
ALTER TABLE review_requests ADD CONSTRAINT uq_review_requests_business_id_id UNIQUE (business_id, id);
ALTER TABLE sales ADD CONSTRAINT uq_sales_business_id_id UNIQUE (business_id, id);
ALTER TABLE sale_items ADD CONSTRAINT uq_sale_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE sale_tips ADD CONSTRAINT uq_sale_tips_business_id_id UNIQUE (business_id, id);
ALTER TABLE receipts ADD CONSTRAINT uq_receipts_business_id_id UNIQUE (business_id, id);
ALTER TABLE quotes ADD CONSTRAINT uq_quotes_business_id_id UNIQUE (business_id, id);
ALTER TABLE quote_items ADD CONSTRAINT uq_quote_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE invoices ADD CONSTRAINT uq_invoices_business_id_id UNIQUE (business_id, id);
ALTER TABLE invoice_items ADD CONSTRAINT uq_invoice_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_provider_accounts ADD CONSTRAINT uq_payment_provider_accounts_business_id_id UNIQUE (business_id, id);
ALTER TABLE branch_payment_methods ADD CONSTRAINT uq_branch_payment_methods_business_id_id UNIQUE (business_id, id);
ALTER TABLE merchant_accounts ADD CONSTRAINT uq_merchant_accounts_business_id_id UNIQUE (business_id, id);
ALTER TABLE saved_payment_methods ADD CONSTRAINT uq_saved_payment_methods_business_id_id UNIQUE (business_id, id);
ALTER TABLE payments ADD CONSTRAINT uq_payments_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_attempts ADD CONSTRAINT uq_payment_attempts_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_events ADD CONSTRAINT uq_payment_events_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_authorizations ADD CONSTRAINT uq_payment_authorizations_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_obligations ADD CONSTRAINT uq_payment_obligations_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_allocations ADD CONSTRAINT uq_payment_allocations_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_links ADD CONSTRAINT uq_payment_links_business_id_id UNIQUE (business_id, id);
ALTER TABLE refunds ADD CONSTRAINT uq_refunds_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_terminals ADD CONSTRAINT uq_payment_terminals_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_reconciliations ADD CONSTRAINT uq_payment_reconciliations_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_reconciliation_items ADD CONSTRAINT uq_payment_reconciliation_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE settlement_batches ADD CONSTRAINT uq_settlement_batches_business_id_id UNIQUE (business_id, id);
ALTER TABLE settlement_items ADD CONSTRAINT uq_settlement_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_wallets ADD CONSTRAINT uq_business_wallets_business_id_id UNIQUE (business_id, id);
ALTER TABLE wallet_transactions ADD CONSTRAINT uq_wallet_transactions_business_id_id UNIQUE (business_id, id);
ALTER TABLE payouts ADD CONSTRAINT uq_payouts_business_id_id UNIQUE (business_id, id);
ALTER TABLE payout_items ADD CONSTRAINT uq_payout_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE payment_disputes ADD CONSTRAINT uq_payment_disputes_business_id_id UNIQUE (business_id, id);
ALTER TABLE cash_registers ADD CONSTRAINT uq_cash_registers_business_id_id UNIQUE (business_id, id);
ALTER TABLE cash_register_sessions ADD CONSTRAINT uq_cash_register_sessions_business_id_id UNIQUE (business_id, id);
ALTER TABLE cash_movements ADD CONSTRAINT uq_cash_movements_business_id_id UNIQUE (business_id, id);
ALTER TABLE financial_accounts ADD CONSTRAINT uq_financial_accounts_business_id_id UNIQUE (business_id, id);
ALTER TABLE financial_journals ADD CONSTRAINT uq_financial_journals_business_id_id UNIQUE (business_id, id);
ALTER TABLE financial_ledger_entries ADD CONSTRAINT uq_financial_ledger_entries_business_id_id UNIQUE (business_id, id);
ALTER TABLE products ADD CONSTRAINT uq_products_business_id_id UNIQUE (business_id, id);
ALTER TABLE product_variants ADD CONSTRAINT uq_product_variants_business_id_id UNIQUE (business_id, id);
ALTER TABLE product_media ADD CONSTRAINT uq_product_media_business_id_id UNIQUE (business_id, id);
ALTER TABLE product_prices ADD CONSTRAINT uq_product_prices_business_id_id UNIQUE (business_id, id);
ALTER TABLE branch_inventory ADD CONSTRAINT uq_branch_inventory_business_id_id UNIQUE (business_id, id);
ALTER TABLE inventory_movements ADD CONSTRAINT uq_inventory_movements_business_id_id UNIQUE (business_id, id);
ALTER TABLE suppliers ADD CONSTRAINT uq_suppliers_business_id_id UNIQUE (business_id, id);
ALTER TABLE purchase_orders ADD CONSTRAINT uq_purchase_orders_business_id_id UNIQUE (business_id, id);
ALTER TABLE purchase_order_items ADD CONSTRAINT uq_purchase_order_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE goods_receipts ADD CONSTRAINT uq_goods_receipts_business_id_id UNIQUE (business_id, id);
ALTER TABLE goods_receipt_items ADD CONSTRAINT uq_goods_receipt_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE online_store_settings ADD CONSTRAINT uq_online_store_settings_business_id_id UNIQUE (business_id, id);
ALTER TABLE delivery_methods ADD CONSTRAINT uq_delivery_methods_business_id_id UNIQUE (business_id, id);
ALTER TABLE delivery_zones ADD CONSTRAINT uq_delivery_zones_business_id_id UNIQUE (business_id, id);
ALTER TABLE sale_fulfilments ADD CONSTRAINT uq_sale_fulfilments_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_subscriptions ADD CONSTRAINT uq_business_subscriptions_business_id_id UNIQUE (business_id, id);
ALTER TABLE subscription_history ADD CONSTRAINT uq_subscription_history_business_id_id UNIQUE (business_id, id);
ALTER TABLE billing_invoices ADD CONSTRAINT uq_billing_invoices_business_id_id UNIQUE (business_id, id);
ALTER TABLE billing_invoice_items ADD CONSTRAINT uq_billing_invoice_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE billing_usage_records ADD CONSTRAINT uq_billing_usage_records_business_id_id UNIQUE (business_id, id);
ALTER TABLE business_addons ADD CONSTRAINT uq_business_addons_business_id_id UNIQUE (business_id, id);
ALTER TABLE gift_cards ADD CONSTRAINT uq_gift_cards_business_id_id UNIQUE (business_id, id);
ALTER TABLE gift_card_transactions ADD CONSTRAINT uq_gift_card_transactions_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_packages ADD CONSTRAINT uq_service_packages_business_id_id UNIQUE (business_id, id);
ALTER TABLE service_package_items ADD CONSTRAINT uq_service_package_items_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_package_balances ADD CONSTRAINT uq_customer_package_balances_business_id_id UNIQUE (business_id, id);
ALTER TABLE package_redemptions ADD CONSTRAINT uq_package_redemptions_business_id_id UNIQUE (business_id, id);
ALTER TABLE membership_plans ADD CONSTRAINT uq_membership_plans_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_memberships ADD CONSTRAINT uq_customer_memberships_business_id_id UNIQUE (business_id, id);
ALTER TABLE membership_benefits ADD CONSTRAINT uq_membership_benefits_business_id_id UNIQUE (business_id, id);
ALTER TABLE membership_transactions ADD CONSTRAINT uq_membership_transactions_business_id_id UNIQUE (business_id, id);
ALTER TABLE loyalty_programmes ADD CONSTRAINT uq_loyalty_programmes_business_id_id UNIQUE (business_id, id);
ALTER TABLE loyalty_tiers ADD CONSTRAINT uq_loyalty_tiers_business_id_id UNIQUE (business_id, id);
ALTER TABLE loyalty_accounts ADD CONSTRAINT uq_loyalty_accounts_business_id_id UNIQUE (business_id, id);
ALTER TABLE loyalty_transactions ADD CONSTRAINT uq_loyalty_transactions_business_id_id UNIQUE (business_id, id);
ALTER TABLE loyalty_rewards ADD CONSTRAINT uq_loyalty_rewards_business_id_id UNIQUE (business_id, id);
ALTER TABLE loyalty_redemptions ADD CONSTRAINT uq_loyalty_redemptions_business_id_id UNIQUE (business_id, id);
ALTER TABLE promotions ADD CONSTRAINT uq_promotions_business_id_id UNIQUE (business_id, id);
ALTER TABLE promotion_redemptions ADD CONSTRAINT uq_promotion_redemptions_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketing_campaign_templates ADD CONSTRAINT uq_marketing_campaign_templates_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketing_campaigns ADD CONSTRAINT uq_marketing_campaigns_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketing_campaign_recipients ADD CONSTRAINT uq_marketing_campaign_recipients_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketing_campaign_events ADD CONSTRAINT uq_marketing_campaign_events_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketing_automations ADD CONSTRAINT uq_marketing_automations_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketing_automation_steps ADD CONSTRAINT uq_marketing_automation_steps_business_id_id UNIQUE (business_id, id);
ALTER TABLE marketing_automation_runs ADD CONSTRAINT uq_marketing_automation_runs_business_id_id UNIQUE (business_id, id);
ALTER TABLE customer_segments ADD CONSTRAINT uq_customer_segments_business_id_id UNIQUE (business_id, id);
ALTER TABLE ai_concierge_settings ADD CONSTRAINT uq_ai_concierge_settings_business_id_id UNIQUE (business_id, id);
ALTER TABLE ai_knowledge_entries ADD CONSTRAINT uq_ai_knowledge_entries_business_id_id UNIQUE (business_id, id);
ALTER TABLE ai_interactions ADD CONSTRAINT uq_ai_interactions_business_id_id UNIQUE (business_id, id);
ALTER TABLE ai_action_logs ADD CONSTRAINT uq_ai_action_logs_business_id_id UNIQUE (business_id, id);
ALTER TABLE ai_usage_records ADD CONSTRAINT uq_ai_usage_records_business_id_id UNIQUE (business_id, id);
ALTER TABLE saved_reports ADD CONSTRAINT uq_saved_reports_business_id_id UNIQUE (business_id, id);
ALTER TABLE report_schedules ADD CONSTRAINT uq_report_schedules_business_id_id UNIQUE (business_id, id);

-- -----------------------------------------------------------------------------
-- Version 1.1 additions: 17 tables (197 original + 17 = 214)
-- -----------------------------------------------------------------------------

CREATE TABLE pay_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    run_number text NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    payment_date date,
    currency char(3) NOT NULL,
    run_status text NOT NULL DEFAULT 'DRAFT' CHECK (run_status IN ('DRAFT','CALCULATED','SUBMITTED','APPROVED','PARTIALLY_PAID','PAID','CANCELLED')),
    base_gross_total numeric(16,2) NOT NULL DEFAULT 0,
    earning_adjustments_total numeric(16,2) NOT NULL DEFAULT 0,
    deductions_total numeric(16,2) NOT NULL DEFAULT 0,
    net_total numeric(16,2) NOT NULL DEFAULT 0,
    submitted_at timestamptz,
    approved_at timestamptz,
    approved_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, run_number),
    CHECK (period_end >= period_start),
    CHECK (base_gross_total >= 0 AND earning_adjustments_total >= 0 AND deductions_total >= 0 AND net_total >= 0),
    CHECK (net_total = base_gross_total + earning_adjustments_total - deductions_total)
);
COMMENT ON TABLE pay_runs IS 'Payroll period header, workflow status and aggregate totals.';

CREATE TABLE pay_run_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    pay_run_id uuid NOT NULL REFERENCES pay_runs(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id),
    currency char(3) NOT NULL,
    compensation_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    time_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    base_gross_amount numeric(16,2) NOT NULL DEFAULT 0,
    earning_adjustments_amount numeric(16,2) NOT NULL DEFAULT 0,
    deductions_amount numeric(16,2) NOT NULL DEFAULT 0,
    net_amount numeric(16,2) NOT NULL DEFAULT 0,
    item_status text NOT NULL DEFAULT 'CALCULATED' CHECK (item_status IN ('CALCULATED','APPROVED','PARTIALLY_PAID','PAID','CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, pay_run_id, employee_id),
    CHECK (base_gross_amount >= 0 AND earning_adjustments_amount >= 0 AND deductions_amount >= 0 AND net_amount >= 0),
    CHECK (net_amount = base_gross_amount + earning_adjustments_amount - deductions_amount)
);
COMMENT ON TABLE pay_run_items IS 'Employee-level gross/net pay calculation snapshots.';

CREATE TABLE pay_run_adjustments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    pay_run_item_id uuid NOT NULL REFERENCES pay_run_items(id) ON DELETE CASCADE,
    adjustment_type text NOT NULL CHECK (adjustment_type IN ('EARNING','DEDUCTION')),
    adjustment_code text NOT NULL,
    description text NOT NULL,
    amount numeric(16,2) NOT NULL CHECK (amount > 0),
    source_type text,
    source_id uuid,
    created_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (length(btrim(adjustment_code)) > 0)
);
COMMENT ON TABLE pay_run_adjustments IS 'Auditable earning/deduction adjustments for a pay item.';

CREATE TABLE pay_run_payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    pay_run_item_id uuid NOT NULL REFERENCES pay_run_items(id) ON DELETE CASCADE,
    payment_id uuid REFERENCES payments(id),
    amount numeric(16,2) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    payment_status text NOT NULL DEFAULT 'PENDING' CHECK (payment_status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','REVERSED')),
    provider_reference text,
    paid_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE pay_run_payments IS 'One or more payroll disbursements against a pay item.';

CREATE TABLE booking_item_stages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_item_id uuid NOT NULL REFERENCES booking_items(id) ON DELETE CASCADE,
    stage_order integer NOT NULL,
    stage_type text NOT NULL CHECK (stage_type IN ('CONSULTATION','APPLICATION','PROCESSING','FINISHING','CLEANUP','OTHER')),
    stage_name text NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    employee_id uuid REFERENCES employees(id),
    staff_occupied_range tstzrange,
    stage_status text NOT NULL DEFAULT 'SCHEDULED' CHECK (stage_status IN ('SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED')),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, booking_item_id, stage_order),
    CHECK (ends_at > starts_at),
    CHECK (staff_occupied_range IS NULL OR (NOT isempty(staff_occupied_range) AND lower_inc(staff_occupied_range) AND NOT upper_inc(staff_occupied_range))),
    CHECK (staff_occupied_range IS NULL OR staff_occupied_range <@ tstzrange(starts_at, ends_at, '[)')),
    CHECK (staff_occupied_range IS NULL OR employee_id IS NOT NULL)
);
COMMENT ON TABLE booking_item_stages IS 'Ordered service, processing, finishing and consultation stages with stage-specific staff occupancy.';

CREATE TABLE schedule_reservations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid NOT NULL REFERENCES branches(id),
    reservation_kind text NOT NULL CHECK (reservation_kind IN ('STAFF','RESOURCE')),
    employee_id uuid REFERENCES employees(id),
    resource_id uuid REFERENCES resources(id),
    resource_unit integer,
    reservation_range tstzrange NOT NULL,
    reservation_status text NOT NULL CHECK (reservation_status IN ('HELD','CONFIRMED','BLOCKED','RELEASED','EXPIRED')),
    booking_item_stage_id uuid REFERENCES booking_item_stages(id) ON DELETE CASCADE,
    booking_item_resource_id uuid REFERENCES booking_item_resources(id) ON DELETE CASCADE,
    booking_hold_id uuid REFERENCES booking_holds(id) ON DELETE CASCADE,
    calendar_block_id uuid REFERENCES calendar_blocks(id) ON DELETE CASCADE,
    hold_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (NOT isempty(reservation_range)),
    CHECK (lower_inc(reservation_range) AND NOT upper_inc(reservation_range)),
    CHECK (num_nonnulls(booking_item_stage_id, booking_item_resource_id, booking_hold_id, calendar_block_id) = 1),
    CHECK ((reservation_kind = 'STAFF' AND employee_id IS NOT NULL AND resource_id IS NULL AND resource_unit IS NULL)
        OR (reservation_kind = 'RESOURCE' AND resource_id IS NOT NULL AND employee_id IS NULL AND resource_unit IS NOT NULL AND resource_unit > 0)),
    CHECK ((reservation_status = 'HELD' AND hold_expires_at IS NOT NULL AND booking_hold_id IS NOT NULL)
        OR reservation_status <> 'HELD')
);
COMMENT ON TABLE schedule_reservations IS 'Canonical conflict ledger for staff and resource capacity across bookings, holds and calendar blocks.';

CREATE TABLE business_tax_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    branch_id uuid REFERENCES branches(id),
    jurisdiction_code text NOT NULL ,
    tax_identifier text,
    is_registered boolean NOT NULL DEFAULT false,
    tax_inclusive_prices boolean NOT NULL DEFAULT false,
    rounding_method text NOT NULL DEFAULT 'HALF_UP' CHECK (rounding_method IN ('HALF_UP','HALF_EVEN','DOWN','UP')),
    calculation_mode text NOT NULL DEFAULT 'PER_LINE' CHECK (calculation_mode IN ('PER_LINE','PER_INVOICE')),
    effective_from date NOT NULL DEFAULT CURRENT_DATE,
    effective_to date,
    profile_status text NOT NULL DEFAULT 'ACTIVE' CHECK (profile_status IN ('ACTIVE','INACTIVE','PENDING_VERIFICATION')),
    settings jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE business_tax_profiles IS 'Business/branch tax identity and jurisdiction calculation settings.';

CREATE TABLE tax_rates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    tax_profile_id uuid NOT NULL REFERENCES business_tax_profiles(id) ON DELETE CASCADE,
    tax_code text NOT NULL,
    tax_name text NOT NULL,
    rate_percent numeric(9,6) NOT NULL,
    effective_range daterange NOT NULL,
    is_compound boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (rate_percent >= 0 AND rate_percent <= 100),
    CHECK (NOT isempty(effective_range) AND lower_inc(effective_range) AND NOT upper_inc(effective_range))
);
COMMENT ON TABLE tax_rates IS 'Versioned effective tax rates; historical transaction lines snapshot the applied rate.';

CREATE TABLE booking_item_tax_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_item_id uuid NOT NULL REFERENCES booking_items(id) ON DELETE CASCADE,
    tax_rate_id uuid REFERENCES tax_rates(id),
    tax_code_snapshot text NOT NULL,
    tax_name_snapshot text NOT NULL,
    rate_percent_snapshot numeric(9,6) NOT NULL,
    taxable_amount numeric(14,2) NOT NULL,
    tax_amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (rate_percent_snapshot >= 0 AND taxable_amount >= 0 AND tax_amount >= 0)
);
COMMENT ON TABLE booking_item_tax_lines IS 'Tax snapshots on booked service items.';

CREATE TABLE sale_item_tax_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    sale_item_id uuid NOT NULL REFERENCES sale_items(id) ON DELETE CASCADE,
    tax_rate_id uuid REFERENCES tax_rates(id),
    tax_code_snapshot text NOT NULL,
    tax_name_snapshot text NOT NULL,
    rate_percent_snapshot numeric(9,6) NOT NULL,
    taxable_amount numeric(14,2) NOT NULL,
    tax_amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (rate_percent_snapshot >= 0 AND taxable_amount >= 0 AND tax_amount >= 0)
);
COMMENT ON TABLE sale_item_tax_lines IS 'Tax snapshots on POS and online sale items.';

CREATE TABLE invoice_item_tax_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    invoice_item_id uuid NOT NULL REFERENCES invoice_items(id) ON DELETE CASCADE,
    tax_rate_id uuid REFERENCES tax_rates(id),
    tax_code_snapshot text NOT NULL,
    tax_name_snapshot text NOT NULL,
    rate_percent_snapshot numeric(9,6) NOT NULL,
    taxable_amount numeric(14,2) NOT NULL,
    tax_amount numeric(14,2) NOT NULL,
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (rate_percent_snapshot >= 0 AND taxable_amount >= 0 AND tax_amount >= 0)
);
COMMENT ON TABLE invoice_item_tax_lines IS 'Tax snapshots on invoice items.';

CREATE TABLE refund_allocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    refund_id uuid NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
    payment_allocation_id uuid NOT NULL REFERENCES payment_allocations(id),
    amount numeric(14,2) NOT NULL CHECK (amount > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, refund_id, payment_allocation_id)
);
COMMENT ON TABLE refund_allocations IS 'Maps a refund in payment currency back to original payment allocations and snapshots the corresponding obligation-currency reduction.';

CREATE TABLE referral_programs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    program_name text NOT NULL,
    program_status text NOT NULL DEFAULT 'DRAFT' CHECK (program_status IN ('DRAFT','ACTIVE','PAUSED','ENDED')),
    qualifying_event text NOT NULL DEFAULT 'FIRST_COMPLETED_BOOKING',
    reward_type text NOT NULL CHECK (reward_type IN ('FIXED_CREDIT','PERCENTAGE_DISCOUNT','LOYALTY_POINTS','GIFT_CARD','SERVICE_CREDIT')),
    reward_value numeric(14,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    starts_at timestamptz,
    ends_at timestamptz,
    terms jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (reward_value >= 0),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);
COMMENT ON TABLE referral_programs IS 'Business refer-a-friend rules and reward terms.';

CREATE TABLE referrals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    referral_program_id uuid NOT NULL REFERENCES referral_programs(id),
    referral_code text NOT NULL,
    referrer_customer_id uuid REFERENCES business_customers(id),
    referrer_user_id uuid REFERENCES identity.users(id),
    referred_customer_id uuid REFERENCES business_customers(id),
    referred_user_id uuid REFERENCES identity.users(id),
    qualifying_booking_id uuid REFERENCES bookings(id),
    qualifying_sale_id uuid REFERENCES sales(id),
    referral_status text NOT NULL DEFAULT 'PENDING' CHECK (referral_status IN ('PENDING','QUALIFIED','REWARDED','REJECTED','EXPIRED')),
    referred_at timestamptz NOT NULL DEFAULT now(),
    qualified_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, referral_code),
    CHECK (num_nonnulls(referrer_customer_id, referrer_user_id) = 1),
    CHECK (num_nonnulls(referred_customer_id, referred_user_id) = 1)
);
COMMENT ON TABLE referrals IS 'Referral attribution, qualification and lifecycle.';

CREATE TABLE referral_rewards (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    referral_id uuid NOT NULL REFERENCES referrals(id) ON DELETE CASCADE,
    beneficiary_customer_id uuid REFERENCES business_customers(id),
    beneficiary_user_id uuid REFERENCES identity.users(id),
    reward_type text NOT NULL,
    reward_value numeric(14,2) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    reward_status text NOT NULL DEFAULT 'PENDING' CHECK (reward_status IN ('PENDING','AVAILABLE','PARTIALLY_REDEEMED','REDEEMED','EXPIRED','REVERSED')),
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(beneficiary_customer_id, beneficiary_user_id) = 1),
    CHECK (reward_value >= 0)
);
COMMENT ON TABLE referral_rewards IS 'Referrer/referred customer reward entitlements.';

CREATE TABLE referral_reward_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    referral_reward_id uuid NOT NULL REFERENCES referral_rewards(id),
    transaction_type text NOT NULL CHECK (transaction_type IN ('ISSUED','REDEEMED','EXPIRED','REVERSED','ADJUSTED')),
    amount numeric(14,2) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    source_type text,
    source_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE referral_reward_transactions IS 'Reward issue/redemption/expiry/reversal ledger.';

CREATE TABLE notification_delivery_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id uuid NOT NULL REFERENCES notification_deliveries(id) ON DELETE CASCADE,
    attempt_number integer NOT NULL,
    attempt_status text NOT NULL DEFAULT 'STARTED' CHECK (attempt_status IN ('STARTED','SUCCEEDED','FAILED','RETRYABLE_FAILURE','PERMANENT_FAILURE')),
    provider_request_id text,
    provider_response_code text,
    provider_response jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code text,
    error_message text,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (delivery_id, attempt_number),
    CHECK ((attempt_status = 'STARTED' AND finished_at IS NULL) OR (attempt_status <> 'STARTED' AND finished_at IS NOT NULL))
);
COMMENT ON TABLE notification_delivery_attempts IS 'Numbered per-channel provider attempt history and outcomes.';

-- v1.1 structural alterations to existing v1.0 tables.
ALTER TABLE booking_item_resources ADD COLUMN booking_item_stage_id uuid;
ALTER TABLE booking_item_resources ADD COLUMN resource_unit integer NOT NULL DEFAULT 1;
ALTER TABLE booking_item_resources ADD CONSTRAINT ck_booking_item_resources_unit_positive CHECK (resource_unit > 0);

ALTER TABLE booking_holds DROP CONSTRAINT IF EXISTS booking_holds_hold_token_hash_key;
ALTER TABLE booking_holds ADD COLUMN hold_group_id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE booking_holds ADD COLUMN resource_id uuid;
ALTER TABLE booking_holds ADD COLUMN resource_unit integer;
ALTER TABLE booking_holds ADD CONSTRAINT ck_booking_holds_target CHECK (employee_id IS NOT NULL OR resource_id IS NOT NULL);
ALTER TABLE booking_holds ADD CONSTRAINT ck_booking_holds_resource_unit CHECK ((resource_id IS NULL AND resource_unit IS NULL) OR (resource_id IS NOT NULL AND resource_unit > 0));
CREATE INDEX ix_booking_holds_token_hash ON booking_holds (hold_token_hash);

ALTER TABLE calendar_blocks ADD CONSTRAINT ck_calendar_blocks_exactly_one_target CHECK (num_nonnulls(employee_id, resource_id) = 1);
ALTER TABLE booking_item_resources ADD CONSTRAINT fk_booking_item_resources_stage FOREIGN KEY (booking_item_stage_id) REFERENCES booking_item_stages(id);

ALTER TABLE financial_journals ADD COLUMN base_currency char(3) NOT NULL;
ALTER TABLE financial_ledger_entries ADD COLUMN exchange_rate numeric(18,8) NOT NULL DEFAULT 1;
ALTER TABLE financial_ledger_entries ADD COLUMN base_debit_amount numeric(16,2) NOT NULL DEFAULT 0;
ALTER TABLE financial_ledger_entries ADD COLUMN base_credit_amount numeric(16,2) NOT NULL DEFAULT 0;
ALTER TABLE financial_ledger_entries ADD CONSTRAINT ck_financial_ledger_exchange_positive CHECK (exchange_rate > 0);
ALTER TABLE financial_ledger_entries ADD CONSTRAINT ck_financial_ledger_base_nonnegative CHECK (base_debit_amount >= 0 AND base_credit_amount >= 0);
ALTER TABLE financial_ledger_entries ADD CONSTRAINT ck_financial_ledger_base_one_side CHECK (NOT (base_debit_amount > 0 AND base_credit_amount > 0));
ALTER TABLE external_integrations ADD CONSTRAINT uq_external_integrations_business_id UNIQUE (business_id, id);



-- -----------------------------------------------------------------------------
-- v1.2 multi-country architecture: business markets, legal entities, country
-- configuration, FX, country-specific numbering and fiscal calendars.
-- -----------------------------------------------------------------------------

CREATE TABLE business_market_countries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    country_code char(2) NOT NULL REFERENCES countries(country_code),
    market_status text NOT NULL DEFAULT 'PLANNED' CHECK (market_status IN ('PLANNED','ACTIVE','SUSPENDED','CLOSED')),
    activated_at timestamptz,
    suspended_at timestamptz,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, country_code),
    CHECK (market_status <> 'ACTIVE' OR activated_at IS NOT NULL)
);
COMMENT ON TABLE business_market_countries IS 'Markets enabled or planned for one business tenant. Country rollout is controlled independently of the business tenant.';

CREATE TABLE business_legal_entities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    entity_code text NOT NULL,
    legal_name text NOT NULL,
    entity_type text NOT NULL DEFAULT 'OTHER' CHECK (entity_type IN ('COMPANY','SOLE_TRADER','PARTNERSHIP','NON_PROFIT','OTHER')),
    country_code char(2) NOT NULL REFERENCES countries(country_code),
    registration_number text,
    functional_currency char(3) NOT NULL REFERENCES currencies(currency_code),
    entity_status text NOT NULL DEFAULT 'PENDING_VERIFICATION' CHECK (entity_status IN ('DRAFT','PENDING_VERIFICATION','ACTIVE','SUSPENDED','CLOSED')),
    is_primary boolean NOT NULL DEFAULT false,
    registered_on date,
    effective_from date NOT NULL DEFAULT CURRENT_DATE,
    effective_to date,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, entity_code),
    UNIQUE (business_id, id),
    UNIQUE (business_id, id, country_code),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE business_legal_entities IS 'Country-specific legal persons or registered businesses under one shared platform business tenant. Tax, invoicing, payroll and accounting are scoped to this entity.';

CREATE TABLE country_locales (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    language_code text NOT NULL REFERENCES languages(language_code),
    locale_code text NOT NULL,
    is_default boolean NOT NULL DEFAULT false,
    is_available boolean NOT NULL DEFAULT true,
    translation_status text NOT NULL DEFAULT 'PLANNED' CHECK (translation_status IN ('PLANNED','PARTIAL','COMPLETE')),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (country_code, language_code),
    UNIQUE (locale_code)
);
COMMENT ON TABLE country_locales IS 'Locales offered in each market; translation status is separate from language availability.';

CREATE TABLE country_currencies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    currency_code char(3) NOT NULL REFERENCES currencies(currency_code),
    is_primary boolean NOT NULL DEFAULT false,
    is_legal_tender boolean NOT NULL DEFAULT true,
    is_enabled boolean NOT NULL DEFAULT true,
    effective_from date NOT NULL DEFAULT CURRENT_DATE,
    effective_to date,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (country_code, currency_code),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE country_currencies IS 'Currencies accepted/configured for a country. Each tenant transaction still snapshots its actual currency.';

CREATE TABLE country_admin_area_levels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    level_number smallint NOT NULL,
    level_code text NOT NULL,
    display_name text NOT NULL,
    display_name_local text,
    is_required boolean NOT NULL DEFAULT false,
    allows_children boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (country_code, level_number),
    UNIQUE (country_code, level_code),
    CHECK (level_number > 0)
);
COMMENT ON TABLE country_admin_area_levels IS 'Country-specific administrative hierarchy definitions. Labels such as region, county, zone, district, woreda or kebele are configuration, not global columns.';

CREATE TABLE country_calendar_systems (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    calendar_code text NOT NULL,
    calendar_role text NOT NULL CHECK (calendar_role IN ('DISPLAY','PUBLIC_HOLIDAY','FISCAL')),
    is_default boolean NOT NULL DEFAULT false,
    is_supported boolean NOT NULL DEFAULT true,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (country_code, calendar_code, calendar_role)
);
COMMENT ON TABLE country_calendar_systems IS 'Calendar presentation and holiday/fiscal-calendar configuration. UTC timestamps remain canonical for appointments and events.';

CREATE TABLE country_compliance_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    profile_code text NOT NULL,
    profile_version integer NOT NULL DEFAULT 1,
    profile_type text NOT NULL CHECK (profile_type IN ('TAX','INVOICING','PAYROLL','PRIVACY','CONSUMER','PAYMENTS','OTHER')),
    effective_from date NOT NULL,
    effective_to date,
    profile_status text NOT NULL DEFAULT 'DRAFT' CHECK (profile_status IN ('DRAFT','REVIEW_REQUIRED','APPROVED','RETIRED')),
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    source_reference text,
    reviewed_by text,
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (country_code, profile_code, profile_version),
    CHECK (profile_version > 0),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE country_compliance_profiles IS 'Versioned country rule/configuration profiles. Content must be reviewed by qualified local advisers before activation; it is not legal advice.';

CREATE TABLE country_feature_availability (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    feature_code text NOT NULL,
    availability_status text NOT NULL DEFAULT 'PLANNED' CHECK (availability_status IN ('PLANNED','PILOT','AVAILABLE','SUSPENDED','RETIRED')),
    effective_from date,
    effective_to date,
    configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (country_code, feature_code),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE country_feature_availability IS 'Feature rollout control by market; does not replace business subscription entitlements.';

CREATE TABLE country_payment_methods (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    payment_method_id uuid NOT NULL REFERENCES payment_methods(id),
    provider_code text,
    availability_status text NOT NULL DEFAULT 'PLANNED' CHECK (availability_status IN ('PLANNED','CONFIGURATION_REQUIRED','AVAILABLE','SUSPENDED')),
    requires_merchant_onboarding boolean NOT NULL DEFAULT false,
    configuration_requirements jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (country_code, payment_method_id)
);
COMMENT ON TABLE country_payment_methods IS 'Country-to-payment-method catalogue. A method is not production-enabled merely because it exists in the catalogue.';

CREATE TABLE payment_provider_country_capabilities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code text NOT NULL,
    country_code char(2) NOT NULL REFERENCES countries(country_code) ON DELETE CASCADE,
    currency_code char(3) NOT NULL REFERENCES currencies(currency_code),
    capability_status text NOT NULL DEFAULT 'UNKNOWN' CHECK (capability_status IN ('UNKNOWN','PLANNED','VERIFIED','AVAILABLE','SUSPENDED')),
    capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
    settlement_currency char(3) REFERENCES currencies(currency_code),
    requires_local_legal_entity boolean NOT NULL DEFAULT true,
    verified_at timestamptz,
    source_reference text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_code, country_code, currency_code)
);
COMMENT ON TABLE payment_provider_country_capabilities IS 'Verified provider, country, currency and capability matrix. Provider availability must be confirmed contractually and technically.';

CREATE TABLE exchange_rates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency char(3) NOT NULL REFERENCES currencies(currency_code),
    quote_currency char(3) NOT NULL REFERENCES currencies(currency_code),
    rate numeric(24,12) NOT NULL,
    rate_source text NOT NULL,
    rate_status text NOT NULL DEFAULT 'REFERENCE' CHECK (rate_status IN ('REFERENCE','INDICATIVE','EXECUTABLE','RETIRED')),
    effective_at timestamptz NOT NULL,
    expires_at timestamptz,
    source_reference text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (base_currency, quote_currency, rate_source, effective_at),
    CHECK (base_currency <> quote_currency),
    CHECK (rate > 0),
    CHECK (expires_at IS NULL OR expires_at > effective_at)
);
COMMENT ON TABLE exchange_rates IS 'Versioned FX quotes. Reference rates are not automatically suitable for settlement, accounting or regulatory reporting.';

CREATE TABLE currency_conversions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    legal_entity_id uuid NOT NULL,
    exchange_rate_id uuid REFERENCES exchange_rates(id),
    conversion_purpose text NOT NULL CHECK (conversion_purpose IN ('PAYMENT','SETTLEMENT','ACCOUNTING','PAYOUT','REPORTING','MANUAL_ADJUSTMENT','OTHER')),
    source_type text,
    source_id uuid,
    source_amount numeric(18,4) NOT NULL,
    source_currency char(3) NOT NULL REFERENCES currencies(currency_code),
    target_amount numeric(18,4) NOT NULL,
    target_currency char(3) NOT NULL REFERENCES currencies(currency_code),
    applied_rate numeric(24,12) NOT NULL,
    rate_source_snapshot text NOT NULL,
    rounding_adjustment numeric(18,4) NOT NULL DEFAULT 0,
    converted_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_amount >= 0 AND target_amount >= 0),
    CHECK (source_currency <> target_currency),
    CHECK (applied_rate > 0)
);
COMMENT ON TABLE currency_conversions IS 'Immutable conversion snapshots attached to a business/legal-entity transaction or reporting event.';

CREATE TABLE document_number_sequences (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    legal_entity_id uuid NOT NULL,
    document_type text NOT NULL,
    sequence_key text NOT NULL DEFAULT 'DEFAULT',
    prefix text,
    suffix text,
    next_number bigint NOT NULL DEFAULT 1,
    minimum_digits smallint NOT NULL DEFAULT 6,
    reset_policy text NOT NULL DEFAULT 'NEVER' CHECK (reset_policy IN ('NEVER','CALENDAR_YEAR','FISCAL_YEAR')),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, legal_entity_id, document_type, sequence_key),
    CHECK (next_number > 0),
    CHECK (minimum_digits BETWEEN 1 AND 18)
);
COMMENT ON TABLE document_number_sequences IS 'Locked sequence rows for entity-scoped booking, invoice, receipt, journal and payroll numbering.';

CREATE TABLE legal_entity_fiscal_years (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    legal_entity_id uuid NOT NULL,
    fiscal_year_code text NOT NULL,
    fiscal_year_label text NOT NULL,
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    fiscal_year_range daterange GENERATED ALWAYS AS (daterange(starts_on, ends_on + 1, '[)')) STORED,
    year_status text NOT NULL DEFAULT 'OPEN' CHECK (year_status IN ('OPEN','CLOSING','CLOSED','LOCKED')),
    closed_at timestamptz,
    closed_by_user_id uuid REFERENCES identity.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, legal_entity_id, fiscal_year_code),
    CHECK (ends_on >= starts_on)
);
COMMENT ON TABLE legal_entity_fiscal_years IS 'Entity-specific fiscal years with non-overlapping date ranges and close controls.';

-- Single-default rules and effective compliance profile periods.
CREATE UNIQUE INDEX uq_country_locales_one_default ON country_locales(country_code) WHERE is_default AND is_available;
CREATE UNIQUE INDEX uq_country_currencies_one_primary ON country_currencies(country_code) WHERE is_primary AND is_enabled;
CREATE UNIQUE INDEX uq_country_calendar_one_default_per_role ON country_calendar_systems(country_code, calendar_role) WHERE is_default AND is_supported;
ALTER TABLE country_compliance_profiles ADD CONSTRAINT ex_country_compliance_profiles_approved_period
    EXCLUDE USING gist (country_code WITH =, profile_code WITH =, profile_type WITH =,
                        daterange(effective_from,effective_to,'[]') WITH &&)
    WHERE (profile_status='APPROVED');

-- Remove Ethiopia-only defaults and address columns. Country, currency and timezone
-- must be supplied explicitly for every branch and every monetary transaction.
ALTER TABLE businesses ADD COLUMN home_country_code char(2) NOT NULL;
ALTER TABLE businesses ADD COLUMN reporting_currency char(3) NOT NULL;
ALTER TABLE businesses ADD CONSTRAINT fk_businesses_home_country FOREIGN KEY (home_country_code) REFERENCES countries(country_code);
ALTER TABLE businesses ADD CONSTRAINT fk_businesses_reporting_currency FOREIGN KEY (reporting_currency) REFERENCES currencies(currency_code);

ALTER TABLE branches ALTER COLUMN country_code DROP DEFAULT;
ALTER TABLE branches ALTER COLUMN timezone DROP DEFAULT;
ALTER TABLE branches ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE branches ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE branches ADD COLUMN address_metadata jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE branches ADD CONSTRAINT uq_branches_entity_reference UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE branches ADD CONSTRAINT uq_branches_biz_id_entity_country UNIQUE (business_id, id, legal_entity_id, country_code);
ALTER TABLE administrative_areas ADD CONSTRAINT uq_administrative_areas_country_id UNIQUE (country_code, id);
ALTER TABLE branches ADD CONSTRAINT fk_branches_country FOREIGN KEY (country_code) REFERENCES countries(country_code);
ALTER TABLE branches ADD CONSTRAINT fk_branches_currency_country FOREIGN KEY (country_code, currency) REFERENCES country_currencies(country_code, currency_code);
ALTER TABLE branches ADD CONSTRAINT fk_branches_legal_entity_country FOREIGN KEY (business_id, legal_entity_id, country_code) REFERENCES business_legal_entities(business_id, id, country_code);
ALTER TABLE branches ADD CONSTRAINT fk_branches_admin_area_country FOREIGN KEY (country_code, administrative_area_id) REFERENCES administrative_areas(country_code, id);
ALTER TABLE branches ADD CONSTRAINT ck_branches_timezone_nonblank CHECK (length(btrim(timezone)) > 0);

ALTER TABLE administrative_areas ADD CONSTRAINT fk_administrative_areas_country FOREIGN KEY (country_code) REFERENCES countries(country_code);
ALTER TABLE administrative_areas DROP CONSTRAINT administrative_areas_parent_id_fkey;
ALTER TABLE administrative_areas ADD CONSTRAINT fk_administrative_areas_parent_same_country FOREIGN KEY (country_code, parent_id) REFERENCES administrative_areas(country_code, id);
ALTER TABLE administrative_areas ADD CONSTRAINT fk_administrative_areas_country_level FOREIGN KEY (country_code, area_type) REFERENCES country_admin_area_levels(country_code, level_code);
ALTER TABLE public_holidays ADD CONSTRAINT fk_public_holidays_country FOREIGN KEY (country_code) REFERENCES countries(country_code);
ALTER TABLE public_holidays ADD CONSTRAINT fk_public_holidays_area_same_country FOREIGN KEY (country_code, administrative_area_id) REFERENCES administrative_areas(country_code, id);
ALTER TABLE countries ADD CONSTRAINT fk_countries_default_currency FOREIGN KEY (default_currency) REFERENCES currencies(currency_code);

-- Add legal-entity scope to accounting, payroll, payment and customer transaction records.
ALTER TABLE business_tax_profiles ALTER COLUMN jurisdiction_code DROP DEFAULT;
ALTER TABLE business_tax_profiles ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE business_tax_profiles ADD COLUMN country_code char(2) NOT NULL;
ALTER TABLE business_tax_profiles ADD CONSTRAINT fk_tax_profiles_entity_country FOREIGN KEY (business_id, legal_entity_id, country_code) REFERENCES business_legal_entities(business_id, id, country_code);
ALTER TABLE business_tax_profiles ADD CONSTRAINT fk_tax_profiles_branch_entity_country FOREIGN KEY (business_id, branch_id, legal_entity_id, country_code) REFERENCES branches(business_id, id, legal_entity_id, country_code);

ALTER TABLE bookings ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE bookings ADD CONSTRAINT uq_bookings_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_branch_entity FOREIGN KEY (business_id, branch_id, legal_entity_id) REFERENCES branches(business_id, id, legal_entity_id);
ALTER TABLE sales ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE sales ADD CONSTRAINT uq_sales_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE sales ADD CONSTRAINT fk_sales_branch_entity FOREIGN KEY (business_id, branch_id, legal_entity_id) REFERENCES branches(business_id, id, legal_entity_id);
ALTER TABLE invoices ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE invoices ADD CONSTRAINT uq_invoices_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_sale_entity FOREIGN KEY (business_id, sale_id, legal_entity_id) REFERENCES sales(business_id, id, legal_entity_id);

ALTER TABLE employee_compensation ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE employee_compensation ADD CONSTRAINT fk_employee_comp_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE pay_runs ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE pay_runs ADD CONSTRAINT uq_pay_runs_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE pay_runs ADD CONSTRAINT fk_pay_runs_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE pay_run_items ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE pay_run_items ADD CONSTRAINT fk_pay_run_items_run_entity FOREIGN KEY (business_id, pay_run_id, legal_entity_id) REFERENCES pay_runs(business_id, id, legal_entity_id);

ALTER TABLE payment_provider_accounts ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE payment_provider_accounts ADD COLUMN country_code char(2) NOT NULL;
ALTER TABLE payment_provider_accounts ADD CONSTRAINT uq_provider_accounts_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE payment_provider_accounts ADD CONSTRAINT fk_provider_accounts_entity_country FOREIGN KEY (business_id, legal_entity_id, country_code) REFERENCES business_legal_entities(business_id, id, country_code);
ALTER TABLE payments ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE payments ADD CONSTRAINT uq_payments_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE payments ADD CONSTRAINT fk_payments_provider_entity FOREIGN KEY (business_id, provider_account_id, legal_entity_id) REFERENCES payment_provider_accounts(business_id, id, legal_entity_id);
ALTER TABLE payment_obligations ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE payment_obligations ADD CONSTRAINT uq_payment_obligations_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE payment_obligations ADD CONSTRAINT fk_payment_obligations_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE payment_allocations ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE payment_allocations ADD CONSTRAINT fk_payment_allocations_payment_entity FOREIGN KEY (business_id, payment_id, legal_entity_id) REFERENCES payments(business_id, id, legal_entity_id);
ALTER TABLE payment_allocations ADD CONSTRAINT fk_payment_allocations_obligation_entity FOREIGN KEY (business_id, payment_obligation_id, legal_entity_id) REFERENCES payment_obligations(business_id, id, legal_entity_id);
ALTER TABLE refunds ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE refunds ADD CONSTRAINT uq_refunds_entity_ref UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_payment_entity FOREIGN KEY (business_id, payment_id, legal_entity_id) REFERENCES payments(business_id, id, legal_entity_id);
ALTER TABLE refund_allocations ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE refund_allocations ADD CONSTRAINT fk_refund_allocations_refund_entity FOREIGN KEY (business_id, refund_id, legal_entity_id) REFERENCES refunds(business_id, id, legal_entity_id);

ALTER TABLE financial_accounts ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE financial_accounts ADD CONSTRAINT uq_financial_accounts_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE financial_accounts ADD CONSTRAINT fk_financial_accounts_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE financial_journals ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE financial_journals ALTER COLUMN base_currency DROP DEFAULT;
ALTER TABLE financial_journals ADD CONSTRAINT uq_financial_journals_biz_id_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE financial_journals ADD CONSTRAINT fk_financial_journals_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE financial_ledger_entries ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE financial_ledger_entries ADD CONSTRAINT fk_ledger_entries_journal_entity FOREIGN KEY (business_id, journal_id, legal_entity_id) REFERENCES financial_journals(business_id, id, legal_entity_id);
ALTER TABLE financial_ledger_entries ADD CONSTRAINT fk_ledger_entries_account_entity FOREIGN KEY (business_id, account_id, legal_entity_id) REFERENCES financial_accounts(business_id, id, legal_entity_id);

ALTER TABLE merchant_accounts ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_merchant_accounts_provider_entity FOREIGN KEY (business_id, provider_account_id, legal_entity_id) REFERENCES payment_provider_accounts(business_id, id, legal_entity_id);

CREATE OR REPLACE FUNCTION validate_payment_allocation_currency_snapshot() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_payment_currency char(3); v_payment_entity uuid; v_obligation_currency char(3); v_obligation_entity uuid;
        v_payment_minor smallint; v_obligation_minor smallint; v_fx record; v_rate_status text;
BEGIN
    SELECT currency,legal_entity_id INTO v_payment_currency,v_payment_entity
      FROM payments WHERE business_id=NEW.business_id AND id=NEW.payment_id;
    SELECT currency,legal_entity_id INTO v_obligation_currency,v_obligation_entity
      FROM payment_obligations WHERE business_id=NEW.business_id AND id=NEW.payment_obligation_id;
    IF v_payment_currency IS NULL OR v_obligation_currency IS NULL THEN
        RAISE EXCEPTION 'Payment and obligation must exist in the same business';
    END IF;
    IF v_payment_entity IS DISTINCT FROM NEW.legal_entity_id OR v_obligation_entity IS DISTINCT FROM NEW.legal_entity_id THEN
        RAISE EXCEPTION 'A payment allocation cannot cross legal-entity boundaries';
    END IF;
    IF NEW.payment_currency IS DISTINCT FROM v_payment_currency OR NEW.obligation_currency IS DISTINCT FROM v_obligation_currency THEN
        RAISE EXCEPTION 'Payment allocation currencies must match their source payment and obligation';
    END IF;
    SELECT minor_unit INTO v_payment_minor FROM currencies WHERE currency_code=v_payment_currency;
    SELECT minor_unit INTO v_obligation_minor FROM currencies WHERE currency_code=v_obligation_currency;
    IF NEW.payment_amount <> round(NEW.payment_amount,v_payment_minor) OR NEW.amount_allocated <> round(NEW.amount_allocated,v_obligation_minor) THEN
        RAISE EXCEPTION 'Payment allocation amounts must respect their currency minor units';
    END IF;
    IF v_payment_currency = v_obligation_currency THEN
        IF NEW.currency_conversion_id IS NOT NULL OR NEW.exchange_rate_snapshot <> 1 OR NEW.payment_amount <> NEW.amount_allocated THEN
            RAISE EXCEPTION 'Same-currency allocations must use a 1:1 rate and equal amounts';
        END IF;
    ELSE
        IF NEW.currency_conversion_id IS NULL THEN RAISE EXCEPTION 'Cross-currency allocation requires a conversion snapshot'; END IF;
        SELECT business_id,legal_entity_id,source_amount,source_currency,target_amount,target_currency,applied_rate,
               conversion_purpose,converted_at,exchange_rate_id
          INTO v_fx FROM currency_conversions WHERE id=NEW.currency_conversion_id;
        IF v_fx.business_id IS DISTINCT FROM NEW.business_id OR v_fx.legal_entity_id IS DISTINCT FROM NEW.legal_entity_id
           OR v_fx.source_currency IS DISTINCT FROM v_payment_currency OR v_fx.target_currency IS DISTINCT FROM v_obligation_currency
           OR round(v_fx.source_amount,v_payment_minor) <> NEW.payment_amount
           OR round(v_fx.target_amount,v_obligation_minor) <> NEW.amount_allocated
           OR v_fx.applied_rate IS DISTINCT FROM NEW.exchange_rate_snapshot
           OR v_fx.conversion_purpose NOT IN ('PAYMENT','SETTLEMENT') THEN
            RAISE EXCEPTION 'Cross-currency allocation must match a payment/settlement conversion snapshot';
        END IF;
        IF v_fx.exchange_rate_id IS NOT NULL THEN
            SELECT rate_status INTO v_rate_status FROM exchange_rates WHERE id=v_fx.exchange_rate_id;
            IF v_rate_status <> 'EXECUTABLE' THEN RAISE EXCEPTION 'Payment allocation requires an executable FX quote'; END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_10_validate_payment_allocation_currency BEFORE INSERT OR UPDATE ON payment_allocations
FOR EACH ROW EXECUTE FUNCTION validate_payment_allocation_currency_snapshot();

-- Business-owned multi-country tables need tenant composite keys for safe joins.
ALTER TABLE business_market_countries ADD CONSTRAINT uq_business_market_countries_biz_id UNIQUE (business_id, id);
ALTER TABLE business_legal_entities ADD CONSTRAINT uq_business_legal_entities_biz_market UNIQUE (business_id, country_code, id);
ALTER TABLE currency_conversions ADD CONSTRAINT uq_currency_conversions_biz_id UNIQUE (business_id, id);
ALTER TABLE document_number_sequences ADD CONSTRAINT uq_document_number_sequences_biz_id UNIQUE (business_id, id);
ALTER TABLE legal_entity_fiscal_years ADD CONSTRAINT uq_legal_entity_fiscal_years_biz_id UNIQUE (business_id, id);
ALTER TABLE business_legal_entities ADD CONSTRAINT fk_legal_entities_market_country FOREIGN KEY (business_id, country_code) REFERENCES business_market_countries(business_id, country_code);
ALTER TABLE currency_conversions ADD CONSTRAINT fk_currency_conversions_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE document_number_sequences ADD CONSTRAINT fk_doc_sequences_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE legal_entity_fiscal_years ADD CONSTRAINT fk_fiscal_years_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE legal_entity_fiscal_years ADD CONSTRAINT ex_legal_entity_fiscal_years_no_overlap EXCLUDE USING gist (business_id WITH =, legal_entity_id WITH =, fiscal_year_range WITH &&);

-- Country and entity consistency checks; keep timezone validation in the application against the IANA TZ database.
CREATE OR REPLACE FUNCTION validate_legal_entity_country_configuration() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM business_market_countries m WHERE m.business_id=NEW.business_id AND m.country_code=NEW.country_code AND m.market_status IN ('ACTIVE','PLANNED')) THEN
        RAISE EXCEPTION 'Legal entity country must first be configured as a business market';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM country_currencies cc WHERE cc.country_code=NEW.country_code AND cc.currency_code=NEW.functional_currency AND cc.is_enabled AND cc.is_legal_tender) THEN
        RAISE EXCEPTION 'Legal-entity functional currency must be enabled legal tender for its country';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_legal_entity_country BEFORE INSERT OR UPDATE OF business_id,country_code,functional_currency ON business_legal_entities
FOR EACH ROW EXECUTE FUNCTION validate_legal_entity_country_configuration();

CREATE OR REPLACE FUNCTION validate_branch_country_configuration() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_entity_country char(2); v_functional_currency char(3);
BEGIN
    IF NOT EXISTS (SELECT 1 FROM country_currencies cc WHERE cc.country_code=NEW.country_code AND cc.currency_code=NEW.currency AND cc.is_enabled AND cc.is_legal_tender) THEN
        RAISE EXCEPTION 'Currency % is not enabled legal tender for country %', NEW.currency, NEW.country_code;
    END IF;
    SELECT country_code,functional_currency INTO v_entity_country,v_functional_currency
      FROM business_legal_entities WHERE business_id=NEW.business_id AND id=NEW.legal_entity_id;
    IF v_entity_country IS NULL OR v_entity_country IS DISTINCT FROM NEW.country_code THEN
        RAISE EXCEPTION 'Branch country must match its legal entity country';
    END IF;
    IF v_functional_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'Branch operating currency must match its legal entity functional currency';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM business_market_countries m WHERE m.business_id=NEW.business_id AND m.country_code=NEW.country_code AND m.market_status='ACTIVE') THEN
        RAISE EXCEPTION 'A branch can only be opened in an ACTIVE business market';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION validate_service_price_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_expected char(3); v_variant_currency char(3);
BEGIN
    SELECT currency INTO v_variant_currency FROM service_variants WHERE business_id=NEW.business_id AND id=NEW.service_variant_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Service variant does not belong to this business'; END IF;
    IF NEW.branch_id IS NULL THEN v_expected := v_variant_currency;
    ELSE SELECT currency INTO v_expected FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
    END IF;
    IF v_expected IS NULL OR NEW.currency IS DISTINCT FROM v_expected THEN
        RAISE EXCEPTION 'Service price currency must match the default variant currency or selected branch currency';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_service_price_currency BEFORE INSERT OR UPDATE OF business_id,service_variant_id,branch_id,currency ON service_prices
FOR EACH ROW EXECUTE FUNCTION validate_service_price_currency();

CREATE OR REPLACE FUNCTION validate_service_addon_price_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_expected char(3);
BEGIN
    IF NEW.branch_id IS NULL THEN
        SELECT currency INTO v_expected FROM service_addons WHERE business_id=NEW.business_id AND id=NEW.service_addon_id;
    ELSE
        SELECT currency INTO v_expected FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
    END IF;
    IF v_expected IS NULL OR NEW.currency IS DISTINCT FROM v_expected THEN
        RAISE EXCEPTION 'Add-on price currency must match its default add-on or selected branch currency';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_service_addon_price_currency BEFORE INSERT OR UPDATE OF business_id,service_addon_id,branch_id,currency ON service_addon_prices
FOR EACH ROW EXECUTE FUNCTION validate_service_addon_price_currency();

CREATE OR REPLACE FUNCTION validate_booking_sale_invoice_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_branch_currency char(3); v_entity_currency char(3); v_entity uuid;
BEGIN
    IF TG_TABLE_NAME IN ('bookings','sales') THEN
        SELECT currency,legal_entity_id INTO v_branch_currency,v_entity
          FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
        IF v_branch_currency IS NULL OR NEW.currency IS DISTINCT FROM v_branch_currency OR NEW.legal_entity_id IS DISTINCT FROM v_entity THEN
            RAISE EXCEPTION 'Booking/sale currency and legal entity must match the selected branch';
        END IF;
    ELSE
        IF NEW.sale_id IS NOT NULL THEN
            SELECT currency,legal_entity_id INTO v_branch_currency,v_entity FROM sales WHERE business_id=NEW.business_id AND id=NEW.sale_id;
        ELSE
            SELECT functional_currency INTO v_branch_currency FROM business_legal_entities WHERE business_id=NEW.business_id AND id=NEW.legal_entity_id;
            v_entity := NEW.legal_entity_id;
        END IF;
        IF v_branch_currency IS NULL OR NEW.currency IS DISTINCT FROM v_branch_currency OR NEW.legal_entity_id IS DISTINCT FROM v_entity THEN
            RAISE EXCEPTION 'Invoice currency and legal entity must match its sale or legal entity functional currency';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_booking_currency BEFORE INSERT OR UPDATE OF branch_id,currency,legal_entity_id ON bookings
FOR EACH ROW EXECUTE FUNCTION validate_booking_sale_invoice_currency();
CREATE TRIGGER trg_validate_sale_currency BEFORE INSERT OR UPDATE OF branch_id,currency,legal_entity_id ON sales
FOR EACH ROW EXECUTE FUNCTION validate_booking_sale_invoice_currency();
CREATE TRIGGER trg_validate_invoice_currency BEFORE INSERT OR UPDATE OF sale_id,currency,legal_entity_id ON invoices
FOR EACH ROW EXECUTE FUNCTION validate_booking_sale_invoice_currency();

CREATE TRIGGER trg_validate_branch_country_configuration BEFORE INSERT OR UPDATE OF business_id, legal_entity_id, country_code, currency ON branches
FOR EACH ROW EXECUTE FUNCTION validate_branch_country_configuration();

CREATE OR REPLACE FUNCTION validate_business_tax_profile_country() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM business_legal_entities le WHERE le.business_id=NEW.business_id AND le.id=NEW.legal_entity_id AND le.country_code=NEW.country_code) THEN
        RAISE EXCEPTION 'Tax profile country must match its legal entity country';
    END IF;
    IF NEW.branch_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM branches b WHERE b.business_id=NEW.business_id AND b.id=NEW.branch_id AND b.legal_entity_id=NEW.legal_entity_id AND b.country_code=NEW.country_code) THEN
        RAISE EXCEPTION 'Branch-specific tax profile must reference a branch of the same legal entity and country';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_tax_profile_country BEFORE INSERT OR UPDATE OF business_id, legal_entity_id, country_code, branch_id ON business_tax_profiles
FOR EACH ROW EXECUTE FUNCTION validate_business_tax_profile_country();

CREATE OR REPLACE FUNCTION validate_country_payment_method_for_branch() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_country char(2); v_status text;
BEGIN
    SELECT country_code INTO v_country FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
    SELECT availability_status INTO v_status FROM country_payment_methods cpm WHERE cpm.country_code=v_country AND cpm.payment_method_id=NEW.payment_method_id;
    IF v_status IS NULL THEN RAISE EXCEPTION 'Payment method is not configured for branch country %', v_country; END IF;
    IF NEW.is_enabled AND v_status <> 'AVAILABLE' THEN RAISE EXCEPTION 'Payment method is not AVAILABLE in country %', v_country; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_branch_payment_method_country BEFORE INSERT OR UPDATE OF branch_id, payment_method_id, is_enabled ON branch_payment_methods
FOR EACH ROW EXECUTE FUNCTION validate_country_payment_method_for_branch();

CREATE OR REPLACE FUNCTION validate_transaction_entity_country() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_entity uuid; v_country char(2);
BEGIN
    IF TG_TABLE_NAME='booking_item_tax_lines' THEN
        SELECT b.legal_entity_id, le.country_code INTO v_entity,v_country FROM booking_items bi JOIN bookings b ON b.business_id=bi.business_id AND b.id=bi.booking_id JOIN business_legal_entities le ON le.business_id=b.business_id AND le.id=b.legal_entity_id WHERE bi.business_id=NEW.business_id AND bi.id=NEW.booking_item_id;
    ELSIF TG_TABLE_NAME='sale_item_tax_lines' THEN
        SELECT s.legal_entity_id, le.country_code INTO v_entity,v_country FROM sale_items si JOIN sales s ON s.business_id=si.business_id AND s.id=si.sale_id JOIN business_legal_entities le ON le.business_id=s.business_id AND le.id=s.legal_entity_id WHERE si.business_id=NEW.business_id AND si.id=NEW.sale_item_id;
    ELSE
        SELECT i.legal_entity_id, le.country_code INTO v_entity,v_country FROM invoice_items ii JOIN invoices i ON i.business_id=ii.business_id AND i.id=ii.invoice_id JOIN business_legal_entities le ON le.business_id=i.business_id AND le.id=i.legal_entity_id WHERE ii.business_id=NEW.business_id AND ii.id=NEW.invoice_item_id;
    END IF;
    IF v_entity IS DISTINCT FROM NEW.legal_entity_id OR v_country IS DISTINCT FROM NEW.country_code THEN
        RAISE EXCEPTION 'Tax line legal entity/country must match its source transaction';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_booking_item_tax_entity BEFORE INSERT OR UPDATE ON booking_item_tax_lines FOR EACH ROW EXECUTE FUNCTION validate_transaction_entity_country();
CREATE TRIGGER trg_sale_item_tax_entity BEFORE INSERT OR UPDATE ON sale_item_tax_lines FOR EACH ROW EXECUTE FUNCTION validate_transaction_entity_country();
CREATE TRIGGER trg_invoice_item_tax_entity BEFORE INSERT OR UPDATE ON invoice_item_tax_lines FOR EACH ROW EXECUTE FUNCTION validate_transaction_entity_country();

CREATE OR REPLACE FUNCTION validate_currency_conversion_snapshot() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_base char(3); v_quote char(3); v_rate numeric(24,12); v_status text;
        v_effective timestamptz; v_expires timestamptz; v_source_minor smallint; v_target_minor smallint;
BEGIN
    SELECT minor_unit INTO v_source_minor FROM currencies WHERE currency_code=NEW.source_currency;
    SELECT minor_unit INTO v_target_minor FROM currencies WHERE currency_code=NEW.target_currency;
    IF v_source_minor IS NULL OR v_target_minor IS NULL THEN RAISE EXCEPTION 'Unknown currency in conversion snapshot'; END IF;
    IF NEW.source_amount <= 0 OR NEW.target_amount <= 0 OR NEW.applied_rate <= 0 THEN
        RAISE EXCEPTION 'FX conversion amounts and rate must be positive';
    END IF;
    IF NEW.source_amount <> round(NEW.source_amount,v_source_minor) OR NEW.target_amount <> round(NEW.target_amount,v_target_minor) THEN
        RAISE EXCEPTION 'FX source/target amounts do not respect currency minor units';
    END IF;
    IF NEW.target_amount <> round(NEW.source_amount * NEW.applied_rate,v_target_minor) THEN
        RAISE EXCEPTION 'FX target amount does not reconcile to source amount and applied rate';
    END IF;
    IF NEW.exchange_rate_id IS NOT NULL THEN
        SELECT base_currency,quote_currency,rate,rate_status,effective_at,expires_at
          INTO v_base,v_quote,v_rate,v_status,v_effective,v_expires
          FROM exchange_rates WHERE id=NEW.exchange_rate_id;
        IF v_base IS DISTINCT FROM NEW.source_currency OR v_quote IS DISTINCT FROM NEW.target_currency OR v_rate IS DISTINCT FROM NEW.applied_rate THEN
            RAISE EXCEPTION 'FX snapshot does not match the referenced exchange-rate quote';
        END IF;
        IF NEW.converted_at < v_effective OR (v_expires IS NOT NULL AND NEW.converted_at >= v_expires) THEN
            RAISE EXCEPTION 'FX quote was not effective at the conversion timestamp';
        END IF;
        IF NEW.conversion_purpose IN ('PAYMENT','SETTLEMENT') AND v_status <> 'EXECUTABLE' THEN
            RAISE EXCEPTION 'Payment/settlement conversion requires an executable FX quote';
        END IF;
    ELSIF NEW.conversion_purpose IN ('PAYMENT','SETTLEMENT') AND length(btrim(NEW.rate_source_snapshot))=0 THEN
        RAISE EXCEPTION 'Manual payment/settlement FX requires a recorded rate source';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_currency_conversion_snapshot BEFORE INSERT OR UPDATE ON currency_conversions
FOR EACH ROW EXECUTE FUNCTION validate_currency_conversion_snapshot();
CREATE OR REPLACE FUNCTION prevent_currency_conversion_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    RAISE EXCEPTION 'Currency conversion snapshots are immutable; create a correcting conversion instead';
END;
$function$;
CREATE TRIGGER trg_currency_conversion_immutable BEFORE UPDATE OR DELETE ON currency_conversions
FOR EACH ROW EXECUTE FUNCTION prevent_currency_conversion_mutation();

CREATE OR REPLACE FUNCTION validate_refund_currency_snapshot() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3); v_entity uuid; v_captured numeric(14,2); v_refunded numeric(14,2); v_minor smallint; v_pending numeric(14,2);
BEGIN
    SELECT currency,legal_entity_id,captured_amount,refunded_amount
      INTO v_currency,v_entity,v_captured,v_refunded
      FROM payments WHERE business_id=NEW.business_id AND id=NEW.payment_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Refund payment does not belong to this business'; END IF;
    IF NEW.currency IS DISTINCT FROM v_currency OR NEW.legal_entity_id IS DISTINCT FROM v_entity THEN
        RAISE EXCEPTION 'Refund currency and legal entity must match the original payment';
    END IF;
    SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=v_currency;
    IF NEW.amount <> round(NEW.amount,v_minor) THEN RAISE EXCEPTION 'Refund amount does not respect payment currency minor units'; END IF;
    SELECT COALESCE(sum(amount),0) INTO v_pending FROM refunds
     WHERE business_id=NEW.business_id AND payment_id=NEW.payment_id
       AND refund_status NOT IN ('COMPLETED','CANCELLED','FAILED','REJECTED')
       AND id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid);
    IF NEW.refund_status NOT IN ('CANCELLED','FAILED','REJECTED') AND v_pending+NEW.amount > v_captured-v_refunded THEN
        RAISE EXCEPTION 'Pending refunds exceed captured unrefunded payment value';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_10_validate_refund_currency BEFORE INSERT OR UPDATE OF payment_id,amount,currency,legal_entity_id,refund_status ON refunds
FOR EACH ROW EXECUTE FUNCTION validate_refund_currency_snapshot();

CREATE OR REPLACE FUNCTION validate_refund_allocation_snapshot() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_refund_payment uuid; v_refund_currency char(3); v_refund_entity uuid; v_refund_status text;
        v_alloc_payment uuid; v_payment_currency char(3); v_obligation_currency char(3); v_alloc_entity uuid;
        v_rate numeric(24,12); v_payment_minor smallint; v_obligation_minor smallint;
BEGIN
    SELECT payment_id,currency,legal_entity_id,refund_status
      INTO v_refund_payment,v_refund_currency,v_refund_entity,v_refund_status
      FROM refunds WHERE business_id=NEW.business_id AND id=NEW.refund_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Refund allocation parent is missing or belongs to another business'; END IF;
    IF v_refund_status IN ('COMPLETED','CANCELLED','FAILED','REJECTED') THEN RAISE EXCEPTION 'Refund allocations require an open refund'; END IF;
    SELECT payment_id,payment_currency,obligation_currency,legal_entity_id,exchange_rate_snapshot
      INTO v_alloc_payment,v_payment_currency,v_obligation_currency,v_alloc_entity,v_rate
      FROM payment_allocations WHERE business_id=NEW.business_id AND id=NEW.payment_allocation_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Original payment allocation is missing or belongs to another business'; END IF;
    IF v_refund_payment IS DISTINCT FROM v_alloc_payment OR v_refund_currency IS DISTINCT FROM v_payment_currency
       OR v_refund_entity IS DISTINCT FROM v_alloc_entity OR NEW.legal_entity_id IS DISTINCT FROM v_alloc_entity THEN
        RAISE EXCEPTION 'Refund allocation must map to the same payment, payment currency and legal entity';
    END IF;
    SELECT minor_unit INTO v_payment_minor FROM currencies WHERE currency_code=v_payment_currency;
    SELECT minor_unit INTO v_obligation_minor FROM currencies WHERE currency_code=v_obligation_currency;
    IF NEW.amount <= 0 OR NEW.amount <> round(NEW.amount,v_payment_minor) THEN
        RAISE EXCEPTION 'Refund allocation amount must respect payment currency minor units';
    END IF;
    NEW.payment_currency := v_payment_currency;
    NEW.obligation_currency := v_obligation_currency;
    NEW.exchange_rate_snapshot := v_rate;
    NEW.obligation_amount := round(NEW.amount * v_rate,v_obligation_minor);
    IF NEW.obligation_amount <= 0 THEN RAISE EXCEPTION 'Refund allocation rounds to zero in obligation currency'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_10_validate_refund_allocation_snapshot BEFORE INSERT OR UPDATE ON refund_allocations
FOR EACH ROW EXECUTE FUNCTION validate_refund_allocation_snapshot();

CREATE OR REPLACE FUNCTION set_financial_journal_base_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT functional_currency INTO v_currency FROM business_legal_entities WHERE business_id=NEW.business_id AND id=NEW.legal_entity_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Unknown legal entity %', NEW.legal_entity_id; END IF;
    IF NEW.base_currency IS NULL THEN NEW.base_currency := v_currency; END IF;
    IF NEW.base_currency <> v_currency THEN RAISE EXCEPTION 'Journal base currency must equal legal entity functional currency %', v_currency; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_set_financial_journal_base_currency BEFORE INSERT OR UPDATE OF legal_entity_id, base_currency ON financial_journals
FOR EACH ROW EXECUTE FUNCTION set_financial_journal_base_currency();


-- Composite tenant keys (business_id, id) for every business-owned parent.
ALTER TABLE booking_item_stages ADD CONSTRAINT uq_booking_item_stages_biz_id UNIQUE (business_id, id);
ALTER TABLE booking_item_tax_lines ADD CONSTRAINT uq_booking_item_tax_lines_biz_id UNIQUE (business_id, id);
ALTER TABLE business_tax_profiles ADD CONSTRAINT uq_business_tax_profiles_biz_id UNIQUE (business_id, id);
ALTER TABLE invoice_item_tax_lines ADD CONSTRAINT uq_invoice_item_tax_lines_biz_id UNIQUE (business_id, id);
ALTER TABLE pay_run_adjustments ADD CONSTRAINT uq_pay_run_adjustments_biz_id UNIQUE (business_id, id);
ALTER TABLE pay_run_items ADD CONSTRAINT uq_pay_run_items_biz_id UNIQUE (business_id, id);
ALTER TABLE pay_run_payments ADD CONSTRAINT uq_pay_run_payments_biz_id UNIQUE (business_id, id);
ALTER TABLE pay_runs ADD CONSTRAINT uq_pay_runs_biz_id UNIQUE (business_id, id);
ALTER TABLE referral_programs ADD CONSTRAINT uq_referral_programs_biz_id UNIQUE (business_id, id);
ALTER TABLE referral_reward_transactions ADD CONSTRAINT uq_referral_reward_transactions_biz_id UNIQUE (business_id, id);
ALTER TABLE referral_rewards ADD CONSTRAINT uq_referral_rewards_biz_id UNIQUE (business_id, id);
ALTER TABLE referrals ADD CONSTRAINT uq_referrals_biz_id UNIQUE (business_id, id);
ALTER TABLE refund_allocations ADD CONSTRAINT uq_refund_allocations_biz_id UNIQUE (business_id, id);
ALTER TABLE sale_item_tax_lines ADD CONSTRAINT uq_sale_item_tax_lines_biz_id UNIQUE (business_id, id);
ALTER TABLE schedule_reservations ADD CONSTRAINT uq_schedule_reservations_biz_id UNIQUE (business_id, id);
ALTER TABLE tax_rates ADD CONSTRAINT uq_tax_rates_biz_id UNIQUE (business_id, id);


-- Global file-asset references retained from the v1.0 baseline.
ALTER TABLE business_media ADD CONSTRAINT fk_business_media_asset FOREIGN KEY (asset_id) REFERENCES file_assets(id);
ALTER TABLE business_verification_documents ADD CONSTRAINT fk_business_verification_asset FOREIGN KEY (asset_id) REFERENCES file_assets(id);
ALTER TABLE service_media ADD CONSTRAINT fk_service_media_asset FOREIGN KEY (asset_id) REFERENCES file_assets(id);
ALTER TABLE product_media ADD CONSTRAINT fk_product_media_asset FOREIGN KEY (asset_id) REFERENCES file_assets(id);



-- v1.2 entity keys and same-business / same-entity relationships.
CREATE UNIQUE INDEX uq_business_legal_entities_one_primary ON business_legal_entities (business_id) WHERE is_primary;
ALTER TABLE business_tax_profiles ADD CONSTRAINT uq_tax_profiles_biz_entity_id UNIQUE (business_id, id, legal_entity_id);

ALTER TABLE payment_provider_country_capabilities ADD CONSTRAINT fk_provider_country_currency_country_currency FOREIGN KEY (country_code, currency_code) REFERENCES country_currencies(country_code, currency_code);
ALTER TABLE countries ADD CONSTRAINT ck_countries_country_code_format CHECK (country_code ~ '^[A-Z]{2}$');
ALTER TABLE currencies ADD CONSTRAINT ck_currencies_currency_code_format CHECK (currency_code ~ '^[A-Z]{3}$');

ALTER TABLE bookings ADD CONSTRAINT fk_bookings_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE sales ADD CONSTRAINT fk_sales_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE employee_compensation ADD CONSTRAINT fk_employee_compensation_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE pay_runs ADD CONSTRAINT fk_pay_runs_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE pay_run_items ADD CONSTRAINT fk_pay_run_items_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE payment_provider_accounts ADD CONSTRAINT fk_payment_provider_accounts_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE payments ADD CONSTRAINT fk_payments_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE payment_obligations ADD CONSTRAINT fk_payment_obligations_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE payment_allocations ADD CONSTRAINT fk_payment_allocations_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE payment_allocations ADD CONSTRAINT uq_payment_allocations_biz_entity UNIQUE (business_id, id, legal_entity_id);
ALTER TABLE payment_allocations ADD COLUMN payment_amount numeric(14,2) NOT NULL;
ALTER TABLE payment_allocations ADD COLUMN payment_currency char(3) NOT NULL REFERENCES currencies(currency_code);
ALTER TABLE payment_allocations ADD COLUMN obligation_currency char(3) NOT NULL REFERENCES currencies(currency_code);
ALTER TABLE payment_allocations ADD COLUMN exchange_rate_snapshot numeric(24,12) NOT NULL DEFAULT 1;
ALTER TABLE payment_allocations ADD COLUMN currency_conversion_id uuid REFERENCES currency_conversions(id);
ALTER TABLE payment_allocations ADD CONSTRAINT fk_payment_allocations_conversion_tenant FOREIGN KEY (business_id,currency_conversion_id) REFERENCES currency_conversions(business_id,id);
ALTER TABLE payment_allocations ADD CONSTRAINT ck_payment_allocations_payment_amount_positive CHECK (payment_amount > 0);
ALTER TABLE payment_allocations ADD CONSTRAINT ck_payment_allocations_fx_rate_positive CHECK (exchange_rate_snapshot > 0);
ALTER TABLE refund_allocations ADD COLUMN obligation_amount numeric(14,2) NOT NULL;
ALTER TABLE refund_allocations ADD COLUMN payment_currency char(3) NOT NULL REFERENCES currencies(currency_code);
ALTER TABLE refund_allocations ADD COLUMN obligation_currency char(3) NOT NULL REFERENCES currencies(currency_code);
ALTER TABLE refund_allocations ADD COLUMN exchange_rate_snapshot numeric(24,12) NOT NULL DEFAULT 1;
ALTER TABLE refund_allocations ADD CONSTRAINT ck_refund_allocations_obligation_amount_positive CHECK (obligation_amount > 0);
ALTER TABLE refund_allocations ADD CONSTRAINT ck_refund_allocations_exchange_rate_positive CHECK (exchange_rate_snapshot > 0);
ALTER TABLE refund_allocations ADD CONSTRAINT fk_refund_allocations_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE refund_allocations ADD CONSTRAINT fk_refund_allocations_payment_allocation_entity FOREIGN KEY (business_id, payment_allocation_id, legal_entity_id) REFERENCES payment_allocations(business_id, id, legal_entity_id);
ALTER TABLE financial_accounts ADD CONSTRAINT fk_financial_accounts_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE financial_journals ADD CONSTRAINT fk_financial_journals_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE financial_ledger_entries ADD CONSTRAINT fk_financial_ledger_entries_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_merchant_accounts_legal_entity FOREIGN KEY (business_id, legal_entity_id) REFERENCES business_legal_entities(business_id, id);

-- Country/legal-entity ownership for invoices and tax records is kept in the transaction snapshot.
ALTER TABLE booking_item_tax_lines ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE booking_item_tax_lines ADD COLUMN country_code char(2) NOT NULL;
ALTER TABLE booking_item_tax_lines ADD CONSTRAINT fk_booking_item_tax_entity_country FOREIGN KEY (business_id, legal_entity_id, country_code) REFERENCES business_legal_entities(business_id, id, country_code);
ALTER TABLE sale_item_tax_lines ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE sale_item_tax_lines ADD COLUMN country_code char(2) NOT NULL;
ALTER TABLE sale_item_tax_lines ADD CONSTRAINT fk_sale_item_tax_entity_country FOREIGN KEY (business_id, legal_entity_id, country_code) REFERENCES business_legal_entities(business_id, id, country_code);
ALTER TABLE invoice_item_tax_lines ADD COLUMN legal_entity_id uuid NOT NULL;
ALTER TABLE invoice_item_tax_lines ADD COLUMN country_code char(2) NOT NULL;
ALTER TABLE invoice_item_tax_lines ADD CONSTRAINT fk_invoice_item_tax_entity_country FOREIGN KEY (business_id, legal_entity_id, country_code) REFERENCES business_legal_entities(business_id, id, country_code);

-- Explicit currency on service and add-on price records prevents a shared catalogue price
-- from being interpreted accidentally in a branch's local currency.
ALTER TABLE service_prices ADD COLUMN currency char(3) NOT NULL REFERENCES currencies(currency_code);
ALTER TABLE service_addons ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE service_addons ADD CONSTRAINT ck_service_addons_default_price_currency
    CHECK (default_price IS NULL OR currency IS NOT NULL);
ALTER TABLE service_addon_prices ADD COLUMN currency char(3) NOT NULL REFERENCES currencies(currency_code);

-- Entity currency and country currency availability are checked in the domain services and
-- retained as snapshots on every transaction; the database rejects missing entity references.
CREATE INDEX ix_business_market_countries_status ON business_market_countries (business_id, market_status, country_code);
CREATE INDEX ix_business_legal_entities_country_status ON business_legal_entities (business_id, country_code, entity_status);
CREATE INDEX ix_country_payment_methods_status ON country_payment_methods (country_code, availability_status);
CREATE INDEX ix_provider_country_capabilities_status ON payment_provider_country_capabilities (provider_code, country_code, capability_status);
CREATE INDEX ix_exchange_rates_pair_time ON exchange_rates (base_currency, quote_currency, effective_at DESC);
CREATE INDEX ix_currency_conversions_entity_time ON currency_conversions (business_id, legal_entity_id, converted_at DESC);
CREATE INDEX ix_document_number_sequences_entity ON document_number_sequences (business_id, legal_entity_id, document_type);
CREATE INDEX ix_fiscal_years_entity_period ON legal_entity_fiscal_years (business_id, legal_entity_id, starts_on, ends_on);

-- Composite foreign keys: a business-owned row can only reference a parent in the same business.
ALTER TABLE ai_action_logs ADD CONSTRAINT fk_ai_action_logs_ai_interaction_id_c0cbfe FOREIGN KEY (business_id, ai_interaction_id) REFERENCES ai_interactions (business_id, id) NOT VALID;
ALTER TABLE ai_interactions ADD CONSTRAINT fk_ai_interactions_business_customer__9b04ff FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE ai_usage_records ADD CONSTRAINT fk_ai_usage_records_ai_interaction_id_507693 FOREIGN KEY (business_id, ai_interaction_id) REFERENCES ai_interactions (business_id, id) NOT VALID;
ALTER TABLE billing_invoice_items ADD CONSTRAINT fk_billing_invoice_items_billing_invoice_id_884b37 FOREIGN KEY (business_id, billing_invoice_id) REFERENCES billing_invoices (business_id, id) NOT VALID;
ALTER TABLE billing_invoices ADD CONSTRAINT fk_billing_invoices_business_subscript_38da5b FOREIGN KEY (business_id, business_subscription_id) REFERENCES business_subscriptions (business_id, id) NOT VALID;
ALTER TABLE booking_cancellations ADD CONSTRAINT fk_booking_cancellations_booking_id_6eecb1 FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE booking_fees ADD CONSTRAINT fk_booking_fees_booking_id_64bc1b FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE booking_fees ADD CONSTRAINT fk_booking_fees_booking_item_id_91895e FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE booking_holds ADD CONSTRAINT fk_booking_holds_branch_id_a3c222 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE booking_holds ADD CONSTRAINT fk_booking_holds_employee_id_e8715a FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE booking_item_addons ADD CONSTRAINT fk_booking_item_addons_booking_item_id_71571c FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE booking_item_addons ADD CONSTRAINT fk_booking_item_addons_service_addon_id_cfc3bc FOREIGN KEY (business_id, service_addon_id) REFERENCES service_addons (business_id, id) NOT VALID;
ALTER TABLE booking_item_payment_terms ADD CONSTRAINT fk_booking_item_payment_t_booking_item_id_b90d7e FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE booking_item_payment_terms ADD CONSTRAINT fk_booking_item_payment_t_policy_id_b3f5b9 FOREIGN KEY (business_id, policy_id) REFERENCES booking_payment_policies (business_id, id) NOT VALID;
ALTER TABLE booking_item_resources ADD CONSTRAINT fk_booking_item_resources_booking_item_id_5ad224 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE booking_item_resources ADD CONSTRAINT fk_booking_item_resources_resource_id_6ed848 FOREIGN KEY (business_id, resource_id) REFERENCES resources (business_id, id) NOT VALID;
ALTER TABLE booking_item_stages ADD CONSTRAINT fk_booking_item_stages_booking_item_id_12b728 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE booking_item_stages ADD CONSTRAINT fk_booking_item_stages_employee_id_696a58 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE booking_item_tax_lines ADD CONSTRAINT fk_booking_item_tax_lines_booking_item_id_dd56a2 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE booking_item_tax_lines ADD CONSTRAINT fk_booking_item_tax_lines_tax_rate_id_81bfe5 FOREIGN KEY (business_id, tax_rate_id) REFERENCES tax_rates (business_id, id) NOT VALID;
ALTER TABLE booking_items ADD CONSTRAINT fk_booking_items_booking_id_ed5dd0 FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE booking_items ADD CONSTRAINT fk_booking_items_employee_id_c5268c FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE booking_items ADD CONSTRAINT fk_booking_items_service_id_b48144 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE booking_items ADD CONSTRAINT fk_booking_items_service_variant_id_063e0a FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE booking_payment_policies ADD CONSTRAINT fk_booking_payment_polici_branch_id_76f40b FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE booking_payment_policies ADD CONSTRAINT fk_booking_payment_polici_service_id_fafb23 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE booking_payment_policies ADD CONSTRAINT fk_booking_payment_polici_service_variant_id_65e3ae FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE booking_status_history ADD CONSTRAINT fk_booking_status_history_booking_id_26e3a8 FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE booking_waitlist_offers ADD CONSTRAINT fk_booking_waitlist_offer_waitlist_id_76bdc1 FOREIGN KEY (business_id, waitlist_id) REFERENCES booking_waitlists (business_id, id) NOT VALID;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_booking_waitlists_branch_id_eb6292 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_booking_waitlists_employee_id_180eea FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_booking_waitlists_service_id_5a3394 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_booking_waitlists_service_variant_id_cdffcb FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_branch_id_ad8ffb FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE branch_hours ADD CONSTRAINT fk_branch_hours_branch_id_1ce5e7 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE branch_inventory ADD CONSTRAINT fk_branch_inventory_branch_id_c6c401 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE branch_inventory ADD CONSTRAINT fk_branch_inventory_product_variant_id_594044 FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants (business_id, id) NOT VALID;
ALTER TABLE branch_payment_methods ADD CONSTRAINT fk_branch_payment_methods_branch_id_12cc4f FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE branch_payment_methods ADD CONSTRAINT fk_branch_payment_methods_provider_account_i_4a859a FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts (business_id, id) NOT VALID;
ALTER TABLE branch_schedule_exceptions ADD CONSTRAINT fk_branch_schedule_except_branch_id_0c087b FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE branch_services ADD CONSTRAINT fk_branch_services_branch_id_744523 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE branch_services ADD CONSTRAINT fk_branch_services_service_id_b569e6 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE business_tax_profiles ADD CONSTRAINT fk_business_tax_profiles_branch_id_a2e112 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE calendar_blocks ADD CONSTRAINT fk_calendar_blocks_branch_id_c7a8d9 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE calendar_blocks ADD CONSTRAINT fk_calendar_blocks_employee_id_e333e8 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE calendar_blocks ADD CONSTRAINT fk_calendar_blocks_resource_id_c8f857 FOREIGN KEY (business_id, resource_id) REFERENCES resources (business_id, id) NOT VALID;
ALTER TABLE cash_movements ADD CONSTRAINT fk_cash_movements_cash_register_sess_541aa9 FOREIGN KEY (business_id, cash_register_session_id) REFERENCES cash_register_sessions (business_id, id) NOT VALID;
ALTER TABLE cash_register_sessions ADD CONSTRAINT fk_cash_register_sessions_cash_register_id_fe92c3 FOREIGN KEY (business_id, cash_register_id) REFERENCES cash_registers (business_id, id) NOT VALID;
ALTER TABLE cash_registers ADD CONSTRAINT fk_cash_registers_branch_id_fcadc3 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE class_enrollments ADD CONSTRAINT fk_class_enrollments_class_session_id_05ed62 FOREIGN KEY (business_id, class_session_id) REFERENCES class_sessions (business_id, id) NOT VALID;
ALTER TABLE class_sessions ADD CONSTRAINT fk_class_sessions_branch_id_82a4f1 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE class_sessions ADD CONSTRAINT fk_class_sessions_employee_id_73fba7 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE class_sessions ADD CONSTRAINT fk_class_sessions_service_id_c4f93c FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE commission_entries ADD CONSTRAINT fk_commission_entries_commission_rule_id_ab09b8 FOREIGN KEY (business_id, commission_rule_id) REFERENCES commission_rules (business_id, id) NOT VALID;
ALTER TABLE commission_entries ADD CONSTRAINT fk_commission_entries_employee_id_9daab7 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE commission_rules ADD CONSTRAINT fk_commission_rules_employee_id_8035d4 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE customer_consents ADD CONSTRAINT fk_customer_consents_business_customer__0602d2 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE customer_documents ADD CONSTRAINT fk_customer_documents_business_customer__c0a34a FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE customer_memberships ADD CONSTRAINT fk_customer_memberships_business_customer__0fec24 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE customer_memberships ADD CONSTRAINT fk_customer_memberships_membership_plan_id_9ae2ce FOREIGN KEY (business_id, membership_plan_id) REFERENCES membership_plans (business_id, id) NOT VALID;
ALTER TABLE customer_notes ADD CONSTRAINT fk_customer_notes_business_customer__2d4e2f FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE customer_package_balances ADD CONSTRAINT fk_customer_package_balan_business_customer__fa1d35 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE customer_package_balances ADD CONSTRAINT fk_customer_package_balan_purchase_payment_i_951de8 FOREIGN KEY (business_id, purchase_payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE customer_package_balances ADD CONSTRAINT fk_customer_package_balan_service_package_id_6d5656 FOREIGN KEY (business_id, service_package_id) REFERENCES service_packages (business_id, id) NOT VALID;
ALTER TABLE customer_tag_assignments ADD CONSTRAINT fk_customer_tag_assignmen_business_customer__59ed3b FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE customer_tag_assignments ADD CONSTRAINT fk_customer_tag_assignmen_customer_tag_id_3c442e FOREIGN KEY (business_id, customer_tag_id) REFERENCES customer_tags (business_id, id) NOT VALID;
ALTER TABLE customer_treatment_records ADD CONSTRAINT fk_customer_treatment_rec_booking_item_id_4b71c9 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE customer_treatment_records ADD CONSTRAINT fk_customer_treatment_rec_business_customer__b7afec FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE customer_treatment_records ADD CONSTRAINT fk_customer_treatment_rec_employee_id_7f082f FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE delivery_zones ADD CONSTRAINT fk_delivery_zones_delivery_method_id_0e34b7 FOREIGN KEY (business_id, delivery_method_id) REFERENCES delivery_methods (business_id, id) NOT VALID;
ALTER TABLE employee_branch_assignments ADD CONSTRAINT fk_employee_branch_assign_branch_id_0b7967 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE employee_branch_assignments ADD CONSTRAINT fk_employee_branch_assign_employee_id_e5ca3b FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employee_compensation ADD CONSTRAINT fk_employee_compensation_employee_id_acdd61 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employee_schedule_exceptions ADD CONSTRAINT fk_employee_schedule_exce_branch_id_67eefa FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE employee_schedule_exceptions ADD CONSTRAINT fk_employee_schedule_exce_employee_id_e9c434 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employee_services ADD CONSTRAINT fk_employee_services_employee_id_159c4e FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employee_services ADD CONSTRAINT fk_employee_services_service_id_a5a93f FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE employee_services ADD CONSTRAINT fk_employee_services_service_variant_id_aad5cc FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE employee_shifts ADD CONSTRAINT fk_employee_shifts_branch_id_c8616e FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE employee_shifts ADD CONSTRAINT fk_employee_shifts_employee_id_00c429 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employee_time_entries ADD CONSTRAINT fk_employee_time_entries_approved_by_employ_7842d0 FOREIGN KEY (business_id, approved_by_employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employee_time_entries ADD CONSTRAINT fk_employee_time_entries_branch_id_a648a0 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE employee_time_entries ADD CONSTRAINT fk_employee_time_entries_employee_id_adc546 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employee_working_hours ADD CONSTRAINT fk_employee_working_hours_branch_id_b53c75 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE employee_working_hours ADD CONSTRAINT fk_employee_working_hours_employee_id_980163 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE employees ADD CONSTRAINT fk_employees_membership_id_727a8c FOREIGN KEY (business_id, membership_id) REFERENCES business_memberships (business_id, id) NOT VALID;
ALTER TABLE financial_accounts ADD CONSTRAINT fk_financial_accounts_parent_account_id_3d6bcf FOREIGN KEY (business_id, parent_account_id) REFERENCES financial_accounts (business_id, id) NOT VALID;
ALTER TABLE financial_ledger_entries ADD CONSTRAINT fk_financial_ledger_entri_account_id_3aba0f FOREIGN KEY (business_id, account_id) REFERENCES financial_accounts (business_id, id) NOT VALID;
ALTER TABLE financial_ledger_entries ADD CONSTRAINT fk_financial_ledger_entri_journal_id_d0d07f FOREIGN KEY (business_id, journal_id) REFERENCES financial_journals (business_id, id) NOT VALID;
ALTER TABLE form_answers ADD CONSTRAINT fk_form_answers_form_field_id_e052d0 FOREIGN KEY (business_id, form_field_id) REFERENCES form_fields (business_id, id) NOT VALID;
ALTER TABLE form_answers ADD CONSTRAINT fk_form_answers_form_submission_id_6d8ce6 FOREIGN KEY (business_id, form_submission_id) REFERENCES form_submissions (business_id, id) NOT VALID;
ALTER TABLE form_fields ADD CONSTRAINT fk_form_fields_form_template_id_7dbf75 FOREIGN KEY (business_id, form_template_id) REFERENCES form_templates (business_id, id) NOT VALID;
ALTER TABLE form_submissions ADD CONSTRAINT fk_form_submissions_booking_id_56b81d FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE form_submissions ADD CONSTRAINT fk_form_submissions_business_customer__31e394 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE form_submissions ADD CONSTRAINT fk_form_submissions_form_template_id_0cc0af FOREIGN KEY (business_id, form_template_id) REFERENCES form_templates (business_id, id) NOT VALID;
ALTER TABLE gift_card_transactions ADD CONSTRAINT fk_gift_card_transactions_gift_card_id_558a55 FOREIGN KEY (business_id, gift_card_id) REFERENCES gift_cards (business_id, id) NOT VALID;
ALTER TABLE gift_cards ADD CONSTRAINT fk_gift_cards_purchased_by_custo_f59a54 FOREIGN KEY (business_id, purchased_by_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE goods_receipt_items ADD CONSTRAINT fk_goods_receipt_items_goods_receipt_id_647694 FOREIGN KEY (business_id, goods_receipt_id) REFERENCES goods_receipts (business_id, id) NOT VALID;
ALTER TABLE goods_receipt_items ADD CONSTRAINT fk_goods_receipt_items_product_variant_id_47d87f FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants (business_id, id) NOT VALID;
ALTER TABLE goods_receipt_items ADD CONSTRAINT fk_goods_receipt_items_purchase_order_ite_783501 FOREIGN KEY (business_id, purchase_order_item_id) REFERENCES purchase_order_items (business_id, id) NOT VALID;
ALTER TABLE goods_receipts ADD CONSTRAINT fk_goods_receipts_branch_id_a21fb7 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE goods_receipts ADD CONSTRAINT fk_goods_receipts_purchase_order_id_ecea53 FOREIGN KEY (business_id, purchase_order_id) REFERENCES purchase_orders (business_id, id) NOT VALID;
ALTER TABLE goods_receipts ADD CONSTRAINT fk_goods_receipts_supplier_id_d1c2f5 FOREIGN KEY (business_id, supplier_id) REFERENCES suppliers (business_id, id) NOT VALID;
ALTER TABLE inventory_movements ADD CONSTRAINT fk_inventory_movements_branch_id_58ffee FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE inventory_movements ADD CONSTRAINT fk_inventory_movements_product_variant_id_da9559 FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants (business_id, id) NOT VALID;
ALTER TABLE invoice_item_tax_lines ADD CONSTRAINT fk_invoice_item_tax_lines_invoice_item_id_b0e18d FOREIGN KEY (business_id, invoice_item_id) REFERENCES invoice_items (business_id, id) NOT VALID;
ALTER TABLE invoice_item_tax_lines ADD CONSTRAINT fk_invoice_item_tax_lines_tax_rate_id_bde74c FOREIGN KEY (business_id, tax_rate_id) REFERENCES tax_rates (business_id, id) NOT VALID;
ALTER TABLE invoice_items ADD CONSTRAINT fk_invoice_items_invoice_id_68b1aa FOREIGN KEY (business_id, invoice_id) REFERENCES invoices (business_id, id) NOT VALID;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_business_customer__7598fd FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_sale_id_d9d962 FOREIGN KEY (business_id, sale_id) REFERENCES sales (business_id, id) NOT VALID;
ALTER TABLE loyalty_accounts ADD CONSTRAINT fk_loyalty_accounts_business_customer__c643cc FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE loyalty_accounts ADD CONSTRAINT fk_loyalty_accounts_loyalty_tier_id_3b125a FOREIGN KEY (business_id, loyalty_tier_id) REFERENCES loyalty_tiers (business_id, id) NOT VALID;
ALTER TABLE loyalty_redemptions ADD CONSTRAINT fk_loyalty_redemptions_loyalty_account_id_b95b00 FOREIGN KEY (business_id, loyalty_account_id) REFERENCES loyalty_accounts (business_id, id) NOT VALID;
ALTER TABLE loyalty_redemptions ADD CONSTRAINT fk_loyalty_redemptions_loyalty_reward_id_427069 FOREIGN KEY (business_id, loyalty_reward_id) REFERENCES loyalty_rewards (business_id, id) NOT VALID;
ALTER TABLE loyalty_transactions ADD CONSTRAINT fk_loyalty_transactions_loyalty_account_id_b76476 FOREIGN KEY (business_id, loyalty_account_id) REFERENCES loyalty_accounts (business_id, id) NOT VALID;
ALTER TABLE marketing_automation_runs ADD CONSTRAINT fk_marketing_automation_r_automation_id_cbd574 FOREIGN KEY (business_id, automation_id) REFERENCES marketing_automations (business_id, id) NOT VALID;
ALTER TABLE marketing_automation_runs ADD CONSTRAINT fk_marketing_automation_r_business_customer__fc7e5b FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE marketing_automation_steps ADD CONSTRAINT fk_marketing_automation_s_automation_id_edd1a7 FOREIGN KEY (business_id, automation_id) REFERENCES marketing_automations (business_id, id) NOT VALID;
ALTER TABLE marketing_campaign_events ADD CONSTRAINT fk_marketing_campaign_eve_campaign_recipient_a636a5 FOREIGN KEY (business_id, campaign_recipient_id) REFERENCES marketing_campaign_recipients (business_id, id) NOT VALID;
ALTER TABLE marketing_campaign_recipients ADD CONSTRAINT fk_marketing_campaign_rec_business_customer__f5f9a1 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE marketing_campaign_recipients ADD CONSTRAINT fk_marketing_campaign_rec_campaign_id_8b1a5d FOREIGN KEY (business_id, campaign_id) REFERENCES marketing_campaigns (business_id, id) NOT VALID;
ALTER TABLE marketing_campaigns ADD CONSTRAINT fk_marketing_campaigns_template_id_de6ee4 FOREIGN KEY (business_id, template_id) REFERENCES marketing_campaign_templates (business_id, id) NOT VALID;
ALTER TABLE marketplace_featured_listings ADD CONSTRAINT fk_marketplace_featured_l_branch_id_838293 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE membership_benefits ADD CONSTRAINT fk_membership_benefits_membership_plan_id_a26b94 FOREIGN KEY (business_id, membership_plan_id) REFERENCES membership_plans (business_id, id) NOT VALID;
ALTER TABLE membership_benefits ADD CONSTRAINT fk_membership_benefits_service_id_5c9f55 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE membership_transactions ADD CONSTRAINT fk_membership_transaction_customer_membershi_c35fc1 FOREIGN KEY (business_id, customer_membership_id) REFERENCES customer_memberships (business_id, id) NOT VALID;
ALTER TABLE membership_transactions ADD CONSTRAINT fk_membership_transaction_payment_id_ae35b4 FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_merchant_accounts_branch_id_e880bc FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_merchant_accounts_employee_id_55c3e5 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_merchant_accounts_provider_account_i_3ecde5 FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts (business_id, id) NOT VALID;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_package_redemptions_booking_item_id_c4b2b5 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_package_redemptions_customer_package_b_e9b64b FOREIGN KEY (business_id, customer_package_balance_id) REFERENCES customer_package_balances (business_id, id) NOT VALID;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_package_redemptions_redeemed_by_employ_9baca7 FOREIGN KEY (business_id, redeemed_by_employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_package_redemptions_service_id_732994 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE patch_tests ADD CONSTRAINT fk_patch_tests_business_customer__9a5126 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE patch_tests ADD CONSTRAINT fk_patch_tests_performed_by_emplo_dea1be FOREIGN KEY (business_id, performed_by_employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE patch_tests ADD CONSTRAINT fk_patch_tests_service_id_cbbd72 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE pay_run_adjustments ADD CONSTRAINT fk_pay_run_adjustments_pay_run_item_id_7cd835 FOREIGN KEY (business_id, pay_run_item_id) REFERENCES pay_run_items (business_id, id) NOT VALID;
ALTER TABLE pay_run_items ADD CONSTRAINT fk_pay_run_items_employee_id_fc12db FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE pay_run_items ADD CONSTRAINT fk_pay_run_items_pay_run_id_82859d FOREIGN KEY (business_id, pay_run_id) REFERENCES pay_runs (business_id, id) NOT VALID;
ALTER TABLE pay_run_payments ADD CONSTRAINT fk_pay_run_payments_pay_run_item_id_f64fa8 FOREIGN KEY (business_id, pay_run_item_id) REFERENCES pay_run_items (business_id, id) NOT VALID;
ALTER TABLE pay_run_payments ADD CONSTRAINT fk_pay_run_payments_payment_id_be5dfa FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE payment_allocations ADD CONSTRAINT fk_payment_allocations_payment_id_61b2b4 FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE payment_allocations ADD CONSTRAINT fk_payment_allocations_payment_obligation_55157e FOREIGN KEY (business_id, payment_obligation_id) REFERENCES payment_obligations (business_id, id) NOT VALID;
ALTER TABLE payment_attempts ADD CONSTRAINT fk_payment_attempts_payment_id_d124fd FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE payment_authorizations ADD CONSTRAINT fk_payment_authorizations_payment_id_429f6a FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE payment_authorizations ADD CONSTRAINT fk_payment_authorizations_saved_payment_meth_40ba99 FOREIGN KEY (business_id, saved_payment_method_id) REFERENCES saved_payment_methods (business_id, id) NOT VALID;
ALTER TABLE payment_disputes ADD CONSTRAINT fk_payment_disputes_payment_id_8f2a75 FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE payment_events ADD CONSTRAINT fk_payment_events_payment_id_938c0b FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE payment_links ADD CONSTRAINT fk_payment_links_payment_obligation_25c69b FOREIGN KEY (business_id, payment_obligation_id) REFERENCES payment_obligations (business_id, id) NOT VALID;
ALTER TABLE payment_reconciliation_items ADD CONSTRAINT fk_payment_reconciliation_payment_id_8a38c1 FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE payment_reconciliation_items ADD CONSTRAINT fk_payment_reconciliation_reconciliation_id_c4db19 FOREIGN KEY (business_id, reconciliation_id) REFERENCES payment_reconciliations (business_id, id) NOT VALID;
ALTER TABLE payment_reconciliations ADD CONSTRAINT fk_payment_reconciliation_provider_account_i_752094 FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts (business_id, id) NOT VALID;
ALTER TABLE payment_terminals ADD CONSTRAINT fk_payment_terminals_branch_id_bdaafc FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE payment_terminals ADD CONSTRAINT fk_payment_terminals_provider_account_i_a51581 FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts (business_id, id) NOT VALID;
ALTER TABLE payments ADD CONSTRAINT fk_payments_provider_account_i_04ac0a FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts (business_id, id) NOT VALID;
ALTER TABLE payout_items ADD CONSTRAINT fk_payout_items_payout_id_e4b6c0 FOREIGN KEY (business_id, payout_id) REFERENCES payouts (business_id, id) NOT VALID;
ALTER TABLE payouts ADD CONSTRAINT fk_payouts_merchant_account_i_c32995 FOREIGN KEY (business_id, merchant_account_id) REFERENCES merchant_accounts (business_id, id) NOT VALID;
ALTER TABLE product_media ADD CONSTRAINT fk_product_media_product_id_6160cf FOREIGN KEY (business_id, product_id) REFERENCES products (business_id, id) NOT VALID;
ALTER TABLE product_prices ADD CONSTRAINT fk_product_prices_branch_id_b6eccd FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE product_prices ADD CONSTRAINT fk_product_prices_product_variant_id_630dd0 FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants (business_id, id) NOT VALID;
ALTER TABLE product_variants ADD CONSTRAINT fk_product_variants_product_id_dbf148 FOREIGN KEY (business_id, product_id) REFERENCES products (business_id, id) NOT VALID;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_promotion_redemptions_booking_id_47dc68 FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_promotion_redemptions_business_customer__9b899a FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_promotion_redemptions_promotion_id_695d57 FOREIGN KEY (business_id, promotion_id) REFERENCES promotions (business_id, id) NOT VALID;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_promotion_redemptions_sale_id_009325 FOREIGN KEY (business_id, sale_id) REFERENCES sales (business_id, id) NOT VALID;
ALTER TABLE purchase_order_items ADD CONSTRAINT fk_purchase_order_items_product_variant_id_1e4826 FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants (business_id, id) NOT VALID;
ALTER TABLE purchase_order_items ADD CONSTRAINT fk_purchase_order_items_purchase_order_id_fc9386 FOREIGN KEY (business_id, purchase_order_id) REFERENCES purchase_orders (business_id, id) NOT VALID;
ALTER TABLE purchase_orders ADD CONSTRAINT fk_purchase_orders_branch_id_9dda1a FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE purchase_orders ADD CONSTRAINT fk_purchase_orders_supplier_id_7a90d5 FOREIGN KEY (business_id, supplier_id) REFERENCES suppliers (business_id, id) NOT VALID;
ALTER TABLE quote_items ADD CONSTRAINT fk_quote_items_quote_id_8ca655 FOREIGN KEY (business_id, quote_id) REFERENCES quotes (business_id, id) NOT VALID;
ALTER TABLE quotes ADD CONSTRAINT fk_quotes_branch_id_d1c938 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE quotes ADD CONSTRAINT fk_quotes_business_customer__bcc10d FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE receipts ADD CONSTRAINT fk_receipts_payment_id_b3103b FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE receipts ADD CONSTRAINT fk_receipts_sale_id_13f95b FOREIGN KEY (business_id, sale_id) REFERENCES sales (business_id, id) NOT VALID;
ALTER TABLE referral_reward_transactions ADD CONSTRAINT fk_referral_reward_transa_referral_reward_id_aed46c FOREIGN KEY (business_id, referral_reward_id) REFERENCES referral_rewards (business_id, id) NOT VALID;
ALTER TABLE referral_rewards ADD CONSTRAINT fk_referral_rewards_beneficiary_custom_00961e FOREIGN KEY (business_id, beneficiary_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE referral_rewards ADD CONSTRAINT fk_referral_rewards_referral_id_a4bdcc FOREIGN KEY (business_id, referral_id) REFERENCES referrals (business_id, id) NOT VALID;
ALTER TABLE referrals ADD CONSTRAINT fk_referrals_qualifying_booking_3932f7 FOREIGN KEY (business_id, qualifying_booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE referrals ADD CONSTRAINT fk_referrals_qualifying_sale_id_2b02c9 FOREIGN KEY (business_id, qualifying_sale_id) REFERENCES sales (business_id, id) NOT VALID;
ALTER TABLE referrals ADD CONSTRAINT fk_referrals_referral_program_i_41742f FOREIGN KEY (business_id, referral_program_id) REFERENCES referral_programs (business_id, id) NOT VALID;
ALTER TABLE referrals ADD CONSTRAINT fk_referrals_referred_customer__e9d366 FOREIGN KEY (business_id, referred_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE referrals ADD CONSTRAINT fk_referrals_referrer_customer__941555 FOREIGN KEY (business_id, referrer_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE refund_allocations ADD CONSTRAINT fk_refund_allocations_payment_allocation_0b0c4f FOREIGN KEY (business_id, payment_allocation_id) REFERENCES payment_allocations (business_id, id) NOT VALID;
ALTER TABLE refund_allocations ADD CONSTRAINT fk_refund_allocations_refund_id_699a29 FOREIGN KEY (business_id, refund_id) REFERENCES refunds (business_id, id) NOT VALID;
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_payment_id_01d81b FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE report_schedules ADD CONSTRAINT fk_report_schedules_saved_report_id_da5a18 FOREIGN KEY (business_id, saved_report_id) REFERENCES saved_reports (business_id, id) NOT VALID;
ALTER TABLE resources ADD CONSTRAINT fk_resources_branch_id_fa0e80 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE review_requests ADD CONSTRAINT fk_review_requests_booking_id_2c0fee FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE review_requests ADD CONSTRAINT fk_review_requests_business_customer__24bdbd FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE review_requests ADD CONSTRAINT fk_review_requests_review_id_cf478c FOREIGN KEY (business_id, review_id) REFERENCES reviews (business_id, id) NOT VALID;
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_booking_id_a6a71b FOREIGN KEY (business_id, booking_id) REFERENCES bookings (business_id, id) NOT VALID;
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_branch_id_d64bc6 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_business_customer__f7eee9 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE sale_fulfilments ADD CONSTRAINT fk_sale_fulfilments_delivery_method_id_6dadbe FOREIGN KEY (business_id, delivery_method_id) REFERENCES delivery_methods (business_id, id) NOT VALID;
ALTER TABLE sale_fulfilments ADD CONSTRAINT fk_sale_fulfilments_delivery_zone_id_d06953 FOREIGN KEY (business_id, delivery_zone_id) REFERENCES delivery_zones (business_id, id) NOT VALID;
ALTER TABLE sale_fulfilments ADD CONSTRAINT fk_sale_fulfilments_sale_id_cfb56f FOREIGN KEY (business_id, sale_id) REFERENCES sales (business_id, id) NOT VALID;
ALTER TABLE sale_item_tax_lines ADD CONSTRAINT fk_sale_item_tax_lines_sale_item_id_8c207e FOREIGN KEY (business_id, sale_item_id) REFERENCES sale_items (business_id, id) NOT VALID;
ALTER TABLE sale_item_tax_lines ADD CONSTRAINT fk_sale_item_tax_lines_tax_rate_id_5d584e FOREIGN KEY (business_id, tax_rate_id) REFERENCES tax_rates (business_id, id) NOT VALID;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_booking_item_id_97a2b4 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items (business_id, id) NOT VALID;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_employee_id_7c2b10 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_sale_id_f5404f FOREIGN KEY (business_id, sale_id) REFERENCES sales (business_id, id) NOT VALID;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_service_id_99dd3f FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE sale_tips ADD CONSTRAINT fk_sale_tips_employee_id_05605d FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE sale_tips ADD CONSTRAINT fk_sale_tips_sale_id_ebaabc FOREIGN KEY (business_id, sale_id) REFERENCES sales (business_id, id) NOT VALID;
ALTER TABLE sales ADD CONSTRAINT fk_sales_branch_id_bb77b8 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE sales ADD CONSTRAINT fk_sales_business_customer__5a650f FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE saved_payment_methods ADD CONSTRAINT fk_saved_payment_methods_business_customer__4e9a21 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE saved_payment_methods ADD CONSTRAINT fk_saved_payment_methods_provider_account_i_c24c6f FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts (business_id, id) NOT VALID;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_schedule_reservations_booking_hold_id_fe515e FOREIGN KEY (business_id, booking_hold_id) REFERENCES booking_holds (business_id, id) NOT VALID;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_schedule_reservations_booking_item_resou_7881c0 FOREIGN KEY (business_id, booking_item_resource_id) REFERENCES booking_item_resources (business_id, id) NOT VALID;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_schedule_reservations_booking_item_stage_164f7a FOREIGN KEY (business_id, booking_item_stage_id) REFERENCES booking_item_stages (business_id, id) NOT VALID;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_schedule_reservations_branch_id_387b3f FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_schedule_reservations_calendar_block_id_a54bdf FOREIGN KEY (business_id, calendar_block_id) REFERENCES calendar_blocks (business_id, id) NOT VALID;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_schedule_reservations_employee_id_b6db82 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_schedule_reservations_resource_id_a2ee38 FOREIGN KEY (business_id, resource_id) REFERENCES resources (business_id, id) NOT VALID;
ALTER TABLE service_addon_prices ADD CONSTRAINT fk_service_addon_prices_branch_id_f581eb FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE service_addon_prices ADD CONSTRAINT fk_service_addon_prices_employee_id_09de7c FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE service_addon_prices ADD CONSTRAINT fk_service_addon_prices_service_addon_id_834c12 FOREIGN KEY (business_id, service_addon_id) REFERENCES service_addons (business_id, id) NOT VALID;
ALTER TABLE service_addons ADD CONSTRAINT fk_service_addons_service_id_3c1992 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE service_media ADD CONSTRAINT fk_service_media_service_id_3a600a FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE service_package_items ADD CONSTRAINT fk_service_package_items_service_id_eda234 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE service_package_items ADD CONSTRAINT fk_service_package_items_service_package_id_fe2faa FOREIGN KEY (business_id, service_package_id) REFERENCES service_packages (business_id, id) NOT VALID;
ALTER TABLE service_package_items ADD CONSTRAINT fk_service_package_items_service_variant_id_173acf FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE service_prices ADD CONSTRAINT fk_service_prices_branch_id_803dba FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE service_prices ADD CONSTRAINT fk_service_prices_employee_id_aaeda9 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE service_prices ADD CONSTRAINT fk_service_prices_service_variant_id_05dc95 FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE service_pricing_rules ADD CONSTRAINT fk_service_pricing_rules_service_variant_id_a531d2 FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE service_resource_requirements ADD CONSTRAINT fk_service_resource_requi_service_id_f20e0a FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE service_resource_requirements ADD CONSTRAINT fk_service_resource_requi_service_variant_id_847a88 FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE service_variant_addons ADD CONSTRAINT fk_service_variant_addons_service_addon_id_73b229 FOREIGN KEY (business_id, service_addon_id) REFERENCES service_addons (business_id, id) NOT VALID;
ALTER TABLE service_variant_addons ADD CONSTRAINT fk_service_variant_addons_service_variant_id_bfedec FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants (business_id, id) NOT VALID;
ALTER TABLE service_variants ADD CONSTRAINT fk_service_variants_service_id_988856 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE settlement_batches ADD CONSTRAINT fk_settlement_batches_provider_account_i_6d6bae FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts (business_id, id) NOT VALID;
ALTER TABLE settlement_items ADD CONSTRAINT fk_settlement_items_payment_id_ed4be5 FOREIGN KEY (business_id, payment_id) REFERENCES payments (business_id, id) NOT VALID;
ALTER TABLE settlement_items ADD CONSTRAINT fk_settlement_items_settlement_batch_i_4b7672 FOREIGN KEY (business_id, settlement_batch_id) REFERENCES settlement_batches (business_id, id) NOT VALID;
ALTER TABLE subscription_history ADD CONSTRAINT fk_subscription_history_business_subscript_bac9aa FOREIGN KEY (business_id, business_subscription_id) REFERENCES business_subscriptions (business_id, id) NOT VALID;
ALTER TABLE tax_rates ADD CONSTRAINT fk_tax_rates_tax_profile_id_962012 FOREIGN KEY (business_id, tax_profile_id) REFERENCES business_tax_profiles (business_id, id) NOT VALID;
ALTER TABLE team_target_results ADD CONSTRAINT fk_team_target_results_employee_id_d10777 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE team_target_results ADD CONSTRAINT fk_team_target_results_team_target_id_70b4e4 FOREIGN KEY (business_id, team_target_id) REFERENCES team_targets (business_id, id) NOT VALID;
ALTER TABLE team_targets ADD CONSTRAINT fk_team_targets_branch_id_3942f4 FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id) NOT VALID;
ALTER TABLE team_targets ADD CONSTRAINT fk_team_targets_employee_id_1104e8 FOREIGN KEY (business_id, employee_id) REFERENCES employees (business_id, id) NOT VALID;
ALTER TABLE wallet_transactions ADD CONSTRAINT fk_wallet_transactions_wallet_id_7d3251 FOREIGN KEY (business_id, wallet_id) REFERENCES business_wallets (business_id, id) NOT VALID;
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_business_customer__5e2bad FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_booking_group_id_12e159 FOREIGN KEY (business_id, booking_group_id) REFERENCES booking_groups (business_id, id) NOT VALID;
ALTER TABLE booking_groups ADD CONSTRAINT fk_booking_groups_payer_business_cus_ab1fa6 FOREIGN KEY (business_id, payer_business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_booking_waitlists_business_customer__4f1da5 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE class_enrollments ADD CONSTRAINT fk_class_enrollments_business_customer__8ed36d FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers (business_id, id) NOT VALID;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_product_variant_id_df69de FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants (business_id, id) NOT VALID;
ALTER TABLE commission_rules ADD CONSTRAINT fk_commission_rules_service_id_c83184 FOREIGN KEY (business_id, service_id) REFERENCES services (business_id, id) NOT VALID;
ALTER TABLE booking_item_resources ADD CONSTRAINT fk_booking_item_resources_booking_item_stage_f087c5 FOREIGN KEY (business_id, booking_item_stage_id) REFERENCES booking_item_stages (business_id, id) NOT VALID;
ALTER TABLE booking_holds ADD CONSTRAINT fk_booking_holds_resource_id_1ece30 FOREIGN KEY (business_id, resource_id) REFERENCES resources (business_id, id) NOT VALID;
ALTER TABLE ai_action_logs VALIDATE CONSTRAINT fk_ai_action_logs_ai_interaction_id_c0cbfe;
ALTER TABLE ai_interactions VALIDATE CONSTRAINT fk_ai_interactions_business_customer__9b04ff;
ALTER TABLE ai_usage_records VALIDATE CONSTRAINT fk_ai_usage_records_ai_interaction_id_507693;
ALTER TABLE billing_invoice_items VALIDATE CONSTRAINT fk_billing_invoice_items_billing_invoice_id_884b37;
ALTER TABLE billing_invoices VALIDATE CONSTRAINT fk_billing_invoices_business_subscript_38da5b;
ALTER TABLE booking_cancellations VALIDATE CONSTRAINT fk_booking_cancellations_booking_id_6eecb1;
ALTER TABLE booking_fees VALIDATE CONSTRAINT fk_booking_fees_booking_id_64bc1b;
ALTER TABLE booking_fees VALIDATE CONSTRAINT fk_booking_fees_booking_item_id_91895e;
ALTER TABLE booking_holds VALIDATE CONSTRAINT fk_booking_holds_branch_id_a3c222;
ALTER TABLE booking_holds VALIDATE CONSTRAINT fk_booking_holds_employee_id_e8715a;
ALTER TABLE booking_item_addons VALIDATE CONSTRAINT fk_booking_item_addons_booking_item_id_71571c;
ALTER TABLE booking_item_addons VALIDATE CONSTRAINT fk_booking_item_addons_service_addon_id_cfc3bc;
ALTER TABLE booking_item_payment_terms VALIDATE CONSTRAINT fk_booking_item_payment_t_booking_item_id_b90d7e;
ALTER TABLE booking_item_payment_terms VALIDATE CONSTRAINT fk_booking_item_payment_t_policy_id_b3f5b9;
ALTER TABLE booking_item_resources VALIDATE CONSTRAINT fk_booking_item_resources_booking_item_id_5ad224;
ALTER TABLE booking_item_resources VALIDATE CONSTRAINT fk_booking_item_resources_resource_id_6ed848;
ALTER TABLE booking_item_stages VALIDATE CONSTRAINT fk_booking_item_stages_booking_item_id_12b728;
ALTER TABLE booking_item_stages VALIDATE CONSTRAINT fk_booking_item_stages_employee_id_696a58;
ALTER TABLE booking_item_tax_lines VALIDATE CONSTRAINT fk_booking_item_tax_lines_booking_item_id_dd56a2;
ALTER TABLE booking_item_tax_lines VALIDATE CONSTRAINT fk_booking_item_tax_lines_tax_rate_id_81bfe5;
ALTER TABLE booking_items VALIDATE CONSTRAINT fk_booking_items_booking_id_ed5dd0;
ALTER TABLE booking_items VALIDATE CONSTRAINT fk_booking_items_employee_id_c5268c;
ALTER TABLE booking_items VALIDATE CONSTRAINT fk_booking_items_service_id_b48144;
ALTER TABLE booking_items VALIDATE CONSTRAINT fk_booking_items_service_variant_id_063e0a;
ALTER TABLE booking_payment_policies VALIDATE CONSTRAINT fk_booking_payment_polici_branch_id_76f40b;
ALTER TABLE booking_payment_policies VALIDATE CONSTRAINT fk_booking_payment_polici_service_id_fafb23;
ALTER TABLE booking_payment_policies VALIDATE CONSTRAINT fk_booking_payment_polici_service_variant_id_65e3ae;
ALTER TABLE booking_status_history VALIDATE CONSTRAINT fk_booking_status_history_booking_id_26e3a8;
ALTER TABLE booking_waitlist_offers VALIDATE CONSTRAINT fk_booking_waitlist_offer_waitlist_id_76bdc1;
ALTER TABLE booking_waitlists VALIDATE CONSTRAINT fk_booking_waitlists_branch_id_eb6292;
ALTER TABLE booking_waitlists VALIDATE CONSTRAINT fk_booking_waitlists_employee_id_180eea;
ALTER TABLE booking_waitlists VALIDATE CONSTRAINT fk_booking_waitlists_service_id_5a3394;
ALTER TABLE booking_waitlists VALIDATE CONSTRAINT fk_booking_waitlists_service_variant_id_cdffcb;
ALTER TABLE bookings VALIDATE CONSTRAINT fk_bookings_branch_id_ad8ffb;
ALTER TABLE branch_hours VALIDATE CONSTRAINT fk_branch_hours_branch_id_1ce5e7;
ALTER TABLE branch_inventory VALIDATE CONSTRAINT fk_branch_inventory_branch_id_c6c401;
ALTER TABLE branch_inventory VALIDATE CONSTRAINT fk_branch_inventory_product_variant_id_594044;
ALTER TABLE branch_payment_methods VALIDATE CONSTRAINT fk_branch_payment_methods_branch_id_12cc4f;
ALTER TABLE branch_payment_methods VALIDATE CONSTRAINT fk_branch_payment_methods_provider_account_i_4a859a;
ALTER TABLE branch_schedule_exceptions VALIDATE CONSTRAINT fk_branch_schedule_except_branch_id_0c087b;
ALTER TABLE branch_services VALIDATE CONSTRAINT fk_branch_services_branch_id_744523;
ALTER TABLE branch_services VALIDATE CONSTRAINT fk_branch_services_service_id_b569e6;
ALTER TABLE business_tax_profiles VALIDATE CONSTRAINT fk_business_tax_profiles_branch_id_a2e112;
ALTER TABLE calendar_blocks VALIDATE CONSTRAINT fk_calendar_blocks_branch_id_c7a8d9;
ALTER TABLE calendar_blocks VALIDATE CONSTRAINT fk_calendar_blocks_employee_id_e333e8;
ALTER TABLE calendar_blocks VALIDATE CONSTRAINT fk_calendar_blocks_resource_id_c8f857;
ALTER TABLE cash_movements VALIDATE CONSTRAINT fk_cash_movements_cash_register_sess_541aa9;
ALTER TABLE cash_register_sessions VALIDATE CONSTRAINT fk_cash_register_sessions_cash_register_id_fe92c3;
ALTER TABLE cash_registers VALIDATE CONSTRAINT fk_cash_registers_branch_id_fcadc3;
ALTER TABLE class_enrollments VALIDATE CONSTRAINT fk_class_enrollments_class_session_id_05ed62;
ALTER TABLE class_sessions VALIDATE CONSTRAINT fk_class_sessions_branch_id_82a4f1;
ALTER TABLE class_sessions VALIDATE CONSTRAINT fk_class_sessions_employee_id_73fba7;
ALTER TABLE class_sessions VALIDATE CONSTRAINT fk_class_sessions_service_id_c4f93c;
ALTER TABLE commission_entries VALIDATE CONSTRAINT fk_commission_entries_commission_rule_id_ab09b8;
ALTER TABLE commission_entries VALIDATE CONSTRAINT fk_commission_entries_employee_id_9daab7;
ALTER TABLE commission_rules VALIDATE CONSTRAINT fk_commission_rules_employee_id_8035d4;
ALTER TABLE customer_consents VALIDATE CONSTRAINT fk_customer_consents_business_customer__0602d2;
ALTER TABLE customer_documents VALIDATE CONSTRAINT fk_customer_documents_business_customer__c0a34a;
ALTER TABLE customer_memberships VALIDATE CONSTRAINT fk_customer_memberships_business_customer__0fec24;
ALTER TABLE customer_memberships VALIDATE CONSTRAINT fk_customer_memberships_membership_plan_id_9ae2ce;
ALTER TABLE customer_notes VALIDATE CONSTRAINT fk_customer_notes_business_customer__2d4e2f;
ALTER TABLE customer_package_balances VALIDATE CONSTRAINT fk_customer_package_balan_business_customer__fa1d35;
ALTER TABLE customer_package_balances VALIDATE CONSTRAINT fk_customer_package_balan_purchase_payment_i_951de8;
ALTER TABLE customer_package_balances VALIDATE CONSTRAINT fk_customer_package_balan_service_package_id_6d5656;
ALTER TABLE customer_tag_assignments VALIDATE CONSTRAINT fk_customer_tag_assignmen_business_customer__59ed3b;
ALTER TABLE customer_tag_assignments VALIDATE CONSTRAINT fk_customer_tag_assignmen_customer_tag_id_3c442e;
ALTER TABLE customer_treatment_records VALIDATE CONSTRAINT fk_customer_treatment_rec_booking_item_id_4b71c9;
ALTER TABLE customer_treatment_records VALIDATE CONSTRAINT fk_customer_treatment_rec_business_customer__b7afec;
ALTER TABLE customer_treatment_records VALIDATE CONSTRAINT fk_customer_treatment_rec_employee_id_7f082f;
ALTER TABLE delivery_zones VALIDATE CONSTRAINT fk_delivery_zones_delivery_method_id_0e34b7;
ALTER TABLE employee_branch_assignments VALIDATE CONSTRAINT fk_employee_branch_assign_branch_id_0b7967;
ALTER TABLE employee_branch_assignments VALIDATE CONSTRAINT fk_employee_branch_assign_employee_id_e5ca3b;
ALTER TABLE employee_compensation VALIDATE CONSTRAINT fk_employee_compensation_employee_id_acdd61;
ALTER TABLE employee_schedule_exceptions VALIDATE CONSTRAINT fk_employee_schedule_exce_branch_id_67eefa;
ALTER TABLE employee_schedule_exceptions VALIDATE CONSTRAINT fk_employee_schedule_exce_employee_id_e9c434;
ALTER TABLE employee_services VALIDATE CONSTRAINT fk_employee_services_employee_id_159c4e;
ALTER TABLE employee_services VALIDATE CONSTRAINT fk_employee_services_service_id_a5a93f;
ALTER TABLE employee_services VALIDATE CONSTRAINT fk_employee_services_service_variant_id_aad5cc;
ALTER TABLE employee_shifts VALIDATE CONSTRAINT fk_employee_shifts_branch_id_c8616e;
ALTER TABLE employee_shifts VALIDATE CONSTRAINT fk_employee_shifts_employee_id_00c429;
ALTER TABLE employee_time_entries VALIDATE CONSTRAINT fk_employee_time_entries_approved_by_employ_7842d0;
ALTER TABLE employee_time_entries VALIDATE CONSTRAINT fk_employee_time_entries_branch_id_a648a0;
ALTER TABLE employee_time_entries VALIDATE CONSTRAINT fk_employee_time_entries_employee_id_adc546;
ALTER TABLE employee_working_hours VALIDATE CONSTRAINT fk_employee_working_hours_branch_id_b53c75;
ALTER TABLE employee_working_hours VALIDATE CONSTRAINT fk_employee_working_hours_employee_id_980163;
ALTER TABLE employees VALIDATE CONSTRAINT fk_employees_membership_id_727a8c;
ALTER TABLE financial_accounts VALIDATE CONSTRAINT fk_financial_accounts_parent_account_id_3d6bcf;
ALTER TABLE financial_ledger_entries VALIDATE CONSTRAINT fk_financial_ledger_entri_account_id_3aba0f;
ALTER TABLE financial_ledger_entries VALIDATE CONSTRAINT fk_financial_ledger_entri_journal_id_d0d07f;
ALTER TABLE form_answers VALIDATE CONSTRAINT fk_form_answers_form_field_id_e052d0;
ALTER TABLE form_answers VALIDATE CONSTRAINT fk_form_answers_form_submission_id_6d8ce6;
ALTER TABLE form_fields VALIDATE CONSTRAINT fk_form_fields_form_template_id_7dbf75;
ALTER TABLE form_submissions VALIDATE CONSTRAINT fk_form_submissions_booking_id_56b81d;
ALTER TABLE form_submissions VALIDATE CONSTRAINT fk_form_submissions_business_customer__31e394;
ALTER TABLE form_submissions VALIDATE CONSTRAINT fk_form_submissions_form_template_id_0cc0af;
ALTER TABLE gift_card_transactions VALIDATE CONSTRAINT fk_gift_card_transactions_gift_card_id_558a55;
ALTER TABLE gift_cards VALIDATE CONSTRAINT fk_gift_cards_purchased_by_custo_f59a54;
ALTER TABLE goods_receipt_items VALIDATE CONSTRAINT fk_goods_receipt_items_goods_receipt_id_647694;
ALTER TABLE goods_receipt_items VALIDATE CONSTRAINT fk_goods_receipt_items_product_variant_id_47d87f;
ALTER TABLE goods_receipt_items VALIDATE CONSTRAINT fk_goods_receipt_items_purchase_order_ite_783501;
ALTER TABLE goods_receipts VALIDATE CONSTRAINT fk_goods_receipts_branch_id_a21fb7;
ALTER TABLE goods_receipts VALIDATE CONSTRAINT fk_goods_receipts_purchase_order_id_ecea53;
ALTER TABLE goods_receipts VALIDATE CONSTRAINT fk_goods_receipts_supplier_id_d1c2f5;
ALTER TABLE inventory_movements VALIDATE CONSTRAINT fk_inventory_movements_branch_id_58ffee;
ALTER TABLE inventory_movements VALIDATE CONSTRAINT fk_inventory_movements_product_variant_id_da9559;
ALTER TABLE invoice_item_tax_lines VALIDATE CONSTRAINT fk_invoice_item_tax_lines_invoice_item_id_b0e18d;
ALTER TABLE invoice_item_tax_lines VALIDATE CONSTRAINT fk_invoice_item_tax_lines_tax_rate_id_bde74c;
ALTER TABLE invoice_items VALIDATE CONSTRAINT fk_invoice_items_invoice_id_68b1aa;
ALTER TABLE invoices VALIDATE CONSTRAINT fk_invoices_business_customer__7598fd;
ALTER TABLE invoices VALIDATE CONSTRAINT fk_invoices_sale_id_d9d962;
ALTER TABLE loyalty_accounts VALIDATE CONSTRAINT fk_loyalty_accounts_business_customer__c643cc;
ALTER TABLE loyalty_accounts VALIDATE CONSTRAINT fk_loyalty_accounts_loyalty_tier_id_3b125a;
ALTER TABLE loyalty_redemptions VALIDATE CONSTRAINT fk_loyalty_redemptions_loyalty_account_id_b95b00;
ALTER TABLE loyalty_redemptions VALIDATE CONSTRAINT fk_loyalty_redemptions_loyalty_reward_id_427069;
ALTER TABLE loyalty_transactions VALIDATE CONSTRAINT fk_loyalty_transactions_loyalty_account_id_b76476;
ALTER TABLE marketing_automation_runs VALIDATE CONSTRAINT fk_marketing_automation_r_automation_id_cbd574;
ALTER TABLE marketing_automation_runs VALIDATE CONSTRAINT fk_marketing_automation_r_business_customer__fc7e5b;
ALTER TABLE marketing_automation_steps VALIDATE CONSTRAINT fk_marketing_automation_s_automation_id_edd1a7;
ALTER TABLE marketing_campaign_events VALIDATE CONSTRAINT fk_marketing_campaign_eve_campaign_recipient_a636a5;
ALTER TABLE marketing_campaign_recipients VALIDATE CONSTRAINT fk_marketing_campaign_rec_business_customer__f5f9a1;
ALTER TABLE marketing_campaign_recipients VALIDATE CONSTRAINT fk_marketing_campaign_rec_campaign_id_8b1a5d;
ALTER TABLE marketing_campaigns VALIDATE CONSTRAINT fk_marketing_campaigns_template_id_de6ee4;
ALTER TABLE marketplace_featured_listings VALIDATE CONSTRAINT fk_marketplace_featured_l_branch_id_838293;
ALTER TABLE membership_benefits VALIDATE CONSTRAINT fk_membership_benefits_membership_plan_id_a26b94;
ALTER TABLE membership_benefits VALIDATE CONSTRAINT fk_membership_benefits_service_id_5c9f55;
ALTER TABLE membership_transactions VALIDATE CONSTRAINT fk_membership_transaction_customer_membershi_c35fc1;
ALTER TABLE membership_transactions VALIDATE CONSTRAINT fk_membership_transaction_payment_id_ae35b4;
ALTER TABLE merchant_accounts VALIDATE CONSTRAINT fk_merchant_accounts_branch_id_e880bc;
ALTER TABLE merchant_accounts VALIDATE CONSTRAINT fk_merchant_accounts_employee_id_55c3e5;
ALTER TABLE merchant_accounts VALIDATE CONSTRAINT fk_merchant_accounts_provider_account_i_3ecde5;
ALTER TABLE package_redemptions VALIDATE CONSTRAINT fk_package_redemptions_booking_item_id_c4b2b5;
ALTER TABLE package_redemptions VALIDATE CONSTRAINT fk_package_redemptions_customer_package_b_e9b64b;
ALTER TABLE package_redemptions VALIDATE CONSTRAINT fk_package_redemptions_redeemed_by_employ_9baca7;
ALTER TABLE package_redemptions VALIDATE CONSTRAINT fk_package_redemptions_service_id_732994;
ALTER TABLE patch_tests VALIDATE CONSTRAINT fk_patch_tests_business_customer__9a5126;
ALTER TABLE patch_tests VALIDATE CONSTRAINT fk_patch_tests_performed_by_emplo_dea1be;
ALTER TABLE patch_tests VALIDATE CONSTRAINT fk_patch_tests_service_id_cbbd72;
ALTER TABLE pay_run_adjustments VALIDATE CONSTRAINT fk_pay_run_adjustments_pay_run_item_id_7cd835;
ALTER TABLE pay_run_items VALIDATE CONSTRAINT fk_pay_run_items_employee_id_fc12db;
ALTER TABLE pay_run_items VALIDATE CONSTRAINT fk_pay_run_items_pay_run_id_82859d;
ALTER TABLE pay_run_payments VALIDATE CONSTRAINT fk_pay_run_payments_pay_run_item_id_f64fa8;
ALTER TABLE pay_run_payments VALIDATE CONSTRAINT fk_pay_run_payments_payment_id_be5dfa;
ALTER TABLE payment_allocations VALIDATE CONSTRAINT fk_payment_allocations_payment_id_61b2b4;
ALTER TABLE payment_allocations VALIDATE CONSTRAINT fk_payment_allocations_payment_obligation_55157e;
ALTER TABLE payment_attempts VALIDATE CONSTRAINT fk_payment_attempts_payment_id_d124fd;
ALTER TABLE payment_authorizations VALIDATE CONSTRAINT fk_payment_authorizations_payment_id_429f6a;
ALTER TABLE payment_authorizations VALIDATE CONSTRAINT fk_payment_authorizations_saved_payment_meth_40ba99;
ALTER TABLE payment_disputes VALIDATE CONSTRAINT fk_payment_disputes_payment_id_8f2a75;
ALTER TABLE payment_events VALIDATE CONSTRAINT fk_payment_events_payment_id_938c0b;
ALTER TABLE payment_links VALIDATE CONSTRAINT fk_payment_links_payment_obligation_25c69b;
ALTER TABLE payment_reconciliation_items VALIDATE CONSTRAINT fk_payment_reconciliation_payment_id_8a38c1;
ALTER TABLE payment_reconciliation_items VALIDATE CONSTRAINT fk_payment_reconciliation_reconciliation_id_c4db19;
ALTER TABLE payment_reconciliations VALIDATE CONSTRAINT fk_payment_reconciliation_provider_account_i_752094;
ALTER TABLE payment_terminals VALIDATE CONSTRAINT fk_payment_terminals_branch_id_bdaafc;
ALTER TABLE payment_terminals VALIDATE CONSTRAINT fk_payment_terminals_provider_account_i_a51581;
ALTER TABLE payments VALIDATE CONSTRAINT fk_payments_provider_account_i_04ac0a;
ALTER TABLE payout_items VALIDATE CONSTRAINT fk_payout_items_payout_id_e4b6c0;
ALTER TABLE payouts VALIDATE CONSTRAINT fk_payouts_merchant_account_i_c32995;
ALTER TABLE product_media VALIDATE CONSTRAINT fk_product_media_product_id_6160cf;
ALTER TABLE product_prices VALIDATE CONSTRAINT fk_product_prices_branch_id_b6eccd;
ALTER TABLE product_prices VALIDATE CONSTRAINT fk_product_prices_product_variant_id_630dd0;
ALTER TABLE product_variants VALIDATE CONSTRAINT fk_product_variants_product_id_dbf148;
ALTER TABLE promotion_redemptions VALIDATE CONSTRAINT fk_promotion_redemptions_booking_id_47dc68;
ALTER TABLE promotion_redemptions VALIDATE CONSTRAINT fk_promotion_redemptions_business_customer__9b899a;
ALTER TABLE promotion_redemptions VALIDATE CONSTRAINT fk_promotion_redemptions_promotion_id_695d57;
ALTER TABLE promotion_redemptions VALIDATE CONSTRAINT fk_promotion_redemptions_sale_id_009325;
ALTER TABLE purchase_order_items VALIDATE CONSTRAINT fk_purchase_order_items_product_variant_id_1e4826;
ALTER TABLE purchase_order_items VALIDATE CONSTRAINT fk_purchase_order_items_purchase_order_id_fc9386;
ALTER TABLE purchase_orders VALIDATE CONSTRAINT fk_purchase_orders_branch_id_9dda1a;
ALTER TABLE purchase_orders VALIDATE CONSTRAINT fk_purchase_orders_supplier_id_7a90d5;
ALTER TABLE quote_items VALIDATE CONSTRAINT fk_quote_items_quote_id_8ca655;
ALTER TABLE quotes VALIDATE CONSTRAINT fk_quotes_branch_id_d1c938;
ALTER TABLE quotes VALIDATE CONSTRAINT fk_quotes_business_customer__bcc10d;
ALTER TABLE receipts VALIDATE CONSTRAINT fk_receipts_payment_id_b3103b;
ALTER TABLE receipts VALIDATE CONSTRAINT fk_receipts_sale_id_13f95b;
ALTER TABLE referral_reward_transactions VALIDATE CONSTRAINT fk_referral_reward_transa_referral_reward_id_aed46c;
ALTER TABLE referral_rewards VALIDATE CONSTRAINT fk_referral_rewards_beneficiary_custom_00961e;
ALTER TABLE referral_rewards VALIDATE CONSTRAINT fk_referral_rewards_referral_id_a4bdcc;
ALTER TABLE referrals VALIDATE CONSTRAINT fk_referrals_qualifying_booking_3932f7;
ALTER TABLE referrals VALIDATE CONSTRAINT fk_referrals_qualifying_sale_id_2b02c9;
ALTER TABLE referrals VALIDATE CONSTRAINT fk_referrals_referral_program_i_41742f;
ALTER TABLE referrals VALIDATE CONSTRAINT fk_referrals_referred_customer__e9d366;
ALTER TABLE referrals VALIDATE CONSTRAINT fk_referrals_referrer_customer__941555;
ALTER TABLE refund_allocations VALIDATE CONSTRAINT fk_refund_allocations_payment_allocation_0b0c4f;
ALTER TABLE refund_allocations VALIDATE CONSTRAINT fk_refund_allocations_refund_id_699a29;
ALTER TABLE refunds VALIDATE CONSTRAINT fk_refunds_payment_id_01d81b;
ALTER TABLE report_schedules VALIDATE CONSTRAINT fk_report_schedules_saved_report_id_da5a18;
ALTER TABLE resources VALIDATE CONSTRAINT fk_resources_branch_id_fa0e80;
ALTER TABLE review_requests VALIDATE CONSTRAINT fk_review_requests_booking_id_2c0fee;
ALTER TABLE review_requests VALIDATE CONSTRAINT fk_review_requests_business_customer__24bdbd;
ALTER TABLE review_requests VALIDATE CONSTRAINT fk_review_requests_review_id_cf478c;
ALTER TABLE reviews VALIDATE CONSTRAINT fk_reviews_booking_id_a6a71b;
ALTER TABLE reviews VALIDATE CONSTRAINT fk_reviews_branch_id_d64bc6;
ALTER TABLE reviews VALIDATE CONSTRAINT fk_reviews_business_customer__f7eee9;
ALTER TABLE sale_fulfilments VALIDATE CONSTRAINT fk_sale_fulfilments_delivery_method_id_6dadbe;
ALTER TABLE sale_fulfilments VALIDATE CONSTRAINT fk_sale_fulfilments_delivery_zone_id_d06953;
ALTER TABLE sale_fulfilments VALIDATE CONSTRAINT fk_sale_fulfilments_sale_id_cfb56f;
ALTER TABLE sale_item_tax_lines VALIDATE CONSTRAINT fk_sale_item_tax_lines_sale_item_id_8c207e;
ALTER TABLE sale_item_tax_lines VALIDATE CONSTRAINT fk_sale_item_tax_lines_tax_rate_id_5d584e;
ALTER TABLE sale_items VALIDATE CONSTRAINT fk_sale_items_booking_item_id_97a2b4;
ALTER TABLE sale_items VALIDATE CONSTRAINT fk_sale_items_employee_id_7c2b10;
ALTER TABLE sale_items VALIDATE CONSTRAINT fk_sale_items_sale_id_f5404f;
ALTER TABLE sale_items VALIDATE CONSTRAINT fk_sale_items_service_id_99dd3f;
ALTER TABLE sale_tips VALIDATE CONSTRAINT fk_sale_tips_employee_id_05605d;
ALTER TABLE sale_tips VALIDATE CONSTRAINT fk_sale_tips_sale_id_ebaabc;
ALTER TABLE sales VALIDATE CONSTRAINT fk_sales_branch_id_bb77b8;
ALTER TABLE sales VALIDATE CONSTRAINT fk_sales_business_customer__5a650f;
ALTER TABLE saved_payment_methods VALIDATE CONSTRAINT fk_saved_payment_methods_business_customer__4e9a21;
ALTER TABLE saved_payment_methods VALIDATE CONSTRAINT fk_saved_payment_methods_provider_account_i_c24c6f;
ALTER TABLE schedule_reservations VALIDATE CONSTRAINT fk_schedule_reservations_booking_hold_id_fe515e;
ALTER TABLE schedule_reservations VALIDATE CONSTRAINT fk_schedule_reservations_booking_item_resou_7881c0;
ALTER TABLE schedule_reservations VALIDATE CONSTRAINT fk_schedule_reservations_booking_item_stage_164f7a;
ALTER TABLE schedule_reservations VALIDATE CONSTRAINT fk_schedule_reservations_branch_id_387b3f;
ALTER TABLE schedule_reservations VALIDATE CONSTRAINT fk_schedule_reservations_calendar_block_id_a54bdf;
ALTER TABLE schedule_reservations VALIDATE CONSTRAINT fk_schedule_reservations_employee_id_b6db82;
ALTER TABLE schedule_reservations VALIDATE CONSTRAINT fk_schedule_reservations_resource_id_a2ee38;
ALTER TABLE service_addon_prices VALIDATE CONSTRAINT fk_service_addon_prices_branch_id_f581eb;
ALTER TABLE service_addon_prices VALIDATE CONSTRAINT fk_service_addon_prices_employee_id_09de7c;
ALTER TABLE service_addon_prices VALIDATE CONSTRAINT fk_service_addon_prices_service_addon_id_834c12;
ALTER TABLE service_addons VALIDATE CONSTRAINT fk_service_addons_service_id_3c1992;
ALTER TABLE service_media VALIDATE CONSTRAINT fk_service_media_service_id_3a600a;
ALTER TABLE service_package_items VALIDATE CONSTRAINT fk_service_package_items_service_id_eda234;
ALTER TABLE service_package_items VALIDATE CONSTRAINT fk_service_package_items_service_package_id_fe2faa;
ALTER TABLE service_package_items VALIDATE CONSTRAINT fk_service_package_items_service_variant_id_173acf;
ALTER TABLE service_prices VALIDATE CONSTRAINT fk_service_prices_branch_id_803dba;
ALTER TABLE service_prices VALIDATE CONSTRAINT fk_service_prices_employee_id_aaeda9;
ALTER TABLE service_prices VALIDATE CONSTRAINT fk_service_prices_service_variant_id_05dc95;
ALTER TABLE service_pricing_rules VALIDATE CONSTRAINT fk_service_pricing_rules_service_variant_id_a531d2;
ALTER TABLE service_resource_requirements VALIDATE CONSTRAINT fk_service_resource_requi_service_id_f20e0a;
ALTER TABLE service_resource_requirements VALIDATE CONSTRAINT fk_service_resource_requi_service_variant_id_847a88;
ALTER TABLE service_variant_addons VALIDATE CONSTRAINT fk_service_variant_addons_service_addon_id_73b229;
ALTER TABLE service_variant_addons VALIDATE CONSTRAINT fk_service_variant_addons_service_variant_id_bfedec;
ALTER TABLE service_variants VALIDATE CONSTRAINT fk_service_variants_service_id_988856;
ALTER TABLE settlement_batches VALIDATE CONSTRAINT fk_settlement_batches_provider_account_i_6d6bae;
ALTER TABLE settlement_items VALIDATE CONSTRAINT fk_settlement_items_payment_id_ed4be5;
ALTER TABLE settlement_items VALIDATE CONSTRAINT fk_settlement_items_settlement_batch_i_4b7672;
ALTER TABLE subscription_history VALIDATE CONSTRAINT fk_subscription_history_business_subscript_bac9aa;
ALTER TABLE tax_rates VALIDATE CONSTRAINT fk_tax_rates_tax_profile_id_962012;
ALTER TABLE team_target_results VALIDATE CONSTRAINT fk_team_target_results_employee_id_d10777;
ALTER TABLE team_target_results VALIDATE CONSTRAINT fk_team_target_results_team_target_id_70b4e4;
ALTER TABLE team_targets VALIDATE CONSTRAINT fk_team_targets_branch_id_3942f4;
ALTER TABLE team_targets VALIDATE CONSTRAINT fk_team_targets_employee_id_1104e8;
ALTER TABLE wallet_transactions VALIDATE CONSTRAINT fk_wallet_transactions_wallet_id_7d3251;
ALTER TABLE bookings VALIDATE CONSTRAINT fk_bookings_business_customer__5e2bad;
ALTER TABLE bookings VALIDATE CONSTRAINT fk_bookings_booking_group_id_12e159;
ALTER TABLE booking_groups VALIDATE CONSTRAINT fk_booking_groups_payer_business_cus_ab1fa6;
ALTER TABLE booking_waitlists VALIDATE CONSTRAINT fk_booking_waitlists_business_customer__4f1da5;
ALTER TABLE class_enrollments VALIDATE CONSTRAINT fk_class_enrollments_business_customer__8ed36d;
ALTER TABLE sale_items VALIDATE CONSTRAINT fk_sale_items_product_variant_id_df69de;
ALTER TABLE commission_rules VALIDATE CONSTRAINT fk_commission_rules_service_id_c83184;
ALTER TABLE booking_item_resources VALIDATE CONSTRAINT fk_booking_item_resources_booking_item_stage_f087c5;
ALTER TABLE booking_holds VALIDATE CONSTRAINT fk_booking_holds_resource_id_1ece30;


CREATE UNIQUE INDEX uq_cash_register_one_open_session ON cash_register_sessions (cash_register_id) WHERE session_status = 'OPEN';
CREATE INDEX ix_branches_location ON branches USING gist (location);
CREATE INDEX ix_administrative_areas_boundary ON administrative_areas USING gist (boundary);
CREATE INDEX ix_bookings_branch_starts ON bookings (business_id, branch_id, starts_at) WHERE status NOT IN ('CANCELLED', 'REJECTED');
CREATE INDEX ix_booking_items_employee ON booking_items (business_id, employee_id) WHERE employee_id IS NOT NULL;
CREATE INDEX ix_payment_obligations_source ON payment_obligations (business_id, source_type, source_id);
CREATE INDEX ix_payment_allocations_obligation ON payment_allocations (business_id, payment_obligation_id);
CREATE INDEX ix_inventory_movements_variant_date ON inventory_movements (business_id, product_variant_id, occurred_at DESC);
CREATE INDEX ix_messages_conversation_sent ON messages (conversation_id, sent_at DESC);
CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_audit_logs_business_time ON audit_logs (business_id, occurred_at DESC);


-- -----------------------------------------------------------------------------
-- v1.1: effective pricing integrity (typed scopes, no opaque scope_key comparison)
-- -----------------------------------------------------------------------------
ALTER TABLE service_prices DROP CONSTRAINT IF EXISTS ex_service_prices_effective_scope;
ALTER TABLE service_prices DROP COLUMN scope_key; -- typed branch/employee columns are authoritative in v1.1
ALTER TABLE service_prices ADD CONSTRAINT ck_service_prices_employee_requires_branch CHECK (employee_id IS NULL OR branch_id IS NOT NULL);
ALTER TABLE service_prices ADD CONSTRAINT ck_service_prices_effective_range CHECK (effective_range IS NOT NULL AND NOT isempty(effective_range) AND lower_inc(effective_range) AND NOT upper_inc(effective_range));
ALTER TABLE service_prices ADD CONSTRAINT ck_service_prices_price_type_amount CHECK (
    (price_type = 'FREE' AND price = 0) OR
    (price_type IN ('FIXED','FROM') AND price IS NOT NULL AND price >= 0)
);
ALTER TABLE service_prices ADD CONSTRAINT ex_service_prices_default_scope EXCLUDE USING gist
    (business_id WITH =, service_variant_id WITH =, effective_range WITH &&)
    WHERE (is_active AND branch_id IS NULL AND employee_id IS NULL AND effective_range IS NOT NULL);
ALTER TABLE service_prices ADD CONSTRAINT ex_service_prices_branch_scope EXCLUDE USING gist
    (business_id WITH =, service_variant_id WITH =, branch_id WITH =, effective_range WITH &&)
    WHERE (is_active AND branch_id IS NOT NULL AND employee_id IS NULL AND effective_range IS NOT NULL);
ALTER TABLE service_prices ADD CONSTRAINT ex_service_prices_employee_scope EXCLUDE USING gist
    (business_id WITH =, service_variant_id WITH =, branch_id WITH =, employee_id WITH =, effective_range WITH &&)
    WHERE (is_active AND branch_id IS NOT NULL AND employee_id IS NOT NULL AND effective_range IS NOT NULL);

-- -----------------------------------------------------------------------------
-- v1.1: one reservation ledger for staff/resource capacity
-- -----------------------------------------------------------------------------
ALTER TABLE booking_items DROP CONSTRAINT IF EXISTS ex_booking_items_staff_overlap;
ALTER TABLE booking_item_resources DROP CONSTRAINT IF EXISTS ex_booking_item_resource_overlap;
CREATE INDEX ix_schedule_reservations_business_range ON schedule_reservations (business_id, lower(reservation_range));
CREATE INDEX ix_schedule_reservations_staff ON schedule_reservations (business_id, employee_id) WHERE employee_id IS NOT NULL;
CREATE INDEX ix_schedule_reservations_resource ON schedule_reservations (business_id, resource_id, resource_unit) WHERE resource_id IS NOT NULL;
CREATE UNIQUE INDEX uq_schedule_reservation_stage_source ON schedule_reservations (booking_item_stage_id) WHERE booking_item_stage_id IS NOT NULL;
CREATE UNIQUE INDEX uq_schedule_reservation_resource_source ON schedule_reservations (booking_item_resource_id) WHERE booking_item_resource_id IS NOT NULL;
CREATE UNIQUE INDEX uq_schedule_reservation_calendar_source_staff ON schedule_reservations (calendar_block_id, employee_id) WHERE calendar_block_id IS NOT NULL AND employee_id IS NOT NULL;
CREATE UNIQUE INDEX uq_schedule_reservation_calendar_source_resource ON schedule_reservations (calendar_block_id, resource_id, resource_unit) WHERE calendar_block_id IS NOT NULL AND resource_id IS NOT NULL;
CREATE INDEX ix_schedule_reservation_hold_source ON schedule_reservations (booking_hold_id) WHERE booking_hold_id IS NOT NULL;
ALTER TABLE schedule_reservations ADD CONSTRAINT ex_schedule_reservations_staff_overlap EXCLUDE USING gist
    (business_id WITH =, employee_id WITH =, reservation_range WITH &&)
    WHERE (employee_id IS NOT NULL AND reservation_status IN ('HELD','CONFIRMED','BLOCKED'));
ALTER TABLE schedule_reservations ADD CONSTRAINT ex_schedule_reservations_resource_overlap EXCLUDE USING gist
    (business_id WITH =, resource_id WITH =, resource_unit WITH =, reservation_range WITH &&)
    WHERE (resource_id IS NOT NULL AND reservation_status IN ('HELD','CONFIRMED','BLOCKED'));

CREATE OR REPLACE FUNCTION validate_schedule_reservation_target() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_capacity integer; v_branch uuid; v_timezone text; v_local_date date;
BEGIN
    IF NEW.employee_id IS NOT NULL THEN
        SELECT b.timezone INTO v_timezone FROM branches b WHERE b.business_id=NEW.business_id AND b.id=NEW.branch_id;
        IF v_timezone IS NULL THEN RAISE EXCEPTION 'Unknown branch % for reservation', NEW.branch_id; END IF;
        v_local_date := (lower(NEW.reservation_range) AT TIME ZONE v_timezone)::date;
        IF NOT EXISTS (
            SELECT 1 FROM employee_branch_assignments eba
            WHERE eba.business_id=NEW.business_id AND eba.employee_id=NEW.employee_id AND eba.branch_id=NEW.branch_id
              AND (eba.active_from IS NULL OR eba.active_from <= v_local_date)
              AND (eba.active_to IS NULL OR eba.active_to >= v_local_date)
        ) THEN RAISE EXCEPTION 'Employee % is not assigned to branch % on %', NEW.employee_id, NEW.branch_id, v_local_date; END IF;
    ELSE
        SELECT r.capacity, r.branch_id INTO v_capacity, v_branch
          FROM resources r WHERE r.business_id=NEW.business_id AND r.id=NEW.resource_id FOR UPDATE;
        IF v_capacity IS NULL THEN RAISE EXCEPTION 'Unknown resource %', NEW.resource_id; END IF;
        IF v_branch <> NEW.branch_id THEN RAISE EXCEPTION 'Resource % does not belong to branch %', NEW.resource_id, NEW.branch_id; END IF;
        IF NEW.resource_unit < 1 OR NEW.resource_unit > v_capacity THEN RAISE EXCEPTION 'Resource unit % outside capacity 1..%', NEW.resource_unit, v_capacity; END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_schedule_reservation_target BEFORE INSERT OR UPDATE ON schedule_reservations
FOR EACH ROW EXECUTE FUNCTION validate_schedule_reservation_target();

CREATE OR REPLACE FUNCTION sync_booking_stage_reservation() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_branch uuid; v_booking_status text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        DELETE FROM schedule_reservations WHERE booking_item_stage_id=OLD.id;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.staff_occupied_range IS NOT NULL AND NEW.employee_id IS NOT NULL AND NEW.stage_status <> 'CANCELLED' THEN
        SELECT b.branch_id, b.status INTO v_branch, v_booking_status
          FROM booking_items bi JOIN bookings b ON b.business_id=bi.business_id AND b.id=bi.booking_id
         WHERE bi.business_id=NEW.business_id AND bi.id=NEW.booking_item_id;
        INSERT INTO schedule_reservations (business_id, branch_id, reservation_kind, employee_id, reservation_range, reservation_status, booking_item_stage_id)
        VALUES (NEW.business_id, v_branch, 'STAFF', NEW.employee_id, NEW.staff_occupied_range,
                CASE WHEN v_booking_status IN ('CANCELLED','REJECTED','NO_SHOW') THEN 'RELEASED' ELSE 'CONFIRMED' END, NEW.id);
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_sync_booking_stage_reservation AFTER INSERT OR UPDATE OR DELETE ON booking_item_stages
FOR EACH ROW EXECUTE FUNCTION sync_booking_stage_reservation();

-- A confirmed booking item must have an explicit stage plan; an assigned professional
-- must occupy at least one stage so the shared ledger cannot silently omit staff time.
CREATE OR REPLACE FUNCTION assert_confirmed_booking_item_stages() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_booking_id uuid; v_business_id uuid; v_booking_status text;
BEGIN
    IF TG_TABLE_NAME='bookings' THEN
        IF TG_OP='DELETE' THEN RETURN NULL; END IF;
        v_booking_id := NEW.id; v_business_id := NEW.business_id;
    ELSIF TG_TABLE_NAME='booking_item_stages' THEN
        IF TG_OP='DELETE' THEN
            SELECT booking_id INTO v_booking_id FROM booking_items WHERE business_id=OLD.business_id AND id=OLD.booking_item_id;
            v_business_id := OLD.business_id;
        ELSE
            SELECT booking_id INTO v_booking_id FROM booking_items WHERE business_id=NEW.business_id AND id=NEW.booking_item_id;
            v_business_id := NEW.business_id;
        END IF;
    ELSIF TG_OP='DELETE' THEN
        v_booking_id := OLD.booking_id; v_business_id := OLD.business_id;
    ELSE
        v_booking_id := NEW.booking_id; v_business_id := NEW.business_id;
    END IF;
    SELECT status INTO v_booking_status FROM bookings WHERE business_id=v_business_id AND id=v_booking_id;
    IF v_booking_status='CONFIRMED' THEN
        IF EXISTS (
            SELECT 1 FROM booking_items bi
             WHERE bi.business_id=v_business_id AND bi.booking_id=v_booking_id AND bi.is_active
               AND NOT EXISTS (SELECT 1 FROM booking_item_stages st
                                WHERE st.business_id=bi.business_id AND st.booking_item_id=bi.id
                                  AND st.stage_status<>'CANCELLED')
        ) THEN
            RAISE EXCEPTION 'Every active item on a confirmed booking must have at least one non-cancelled stage';
        END IF;
        IF EXISTS (
            SELECT 1 FROM booking_items bi
             WHERE bi.business_id=v_business_id AND bi.booking_id=v_booking_id AND bi.is_active AND bi.employee_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM booking_item_stages st
                                WHERE st.business_id=bi.business_id AND st.booking_item_id=bi.id
                                  AND st.employee_id=bi.employee_id AND st.staff_occupied_range IS NOT NULL
                                  AND st.stage_status<>'CANCELLED')
        ) THEN
            RAISE EXCEPTION 'An assigned professional must occupy at least one non-cancelled stage for the confirmed item';
        END IF;
    END IF;
    RETURN NULL;
END;
$function$;
CREATE CONSTRAINT TRIGGER ct_confirmed_booking_stages_items AFTER INSERT OR UPDATE OR DELETE ON booking_items
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_confirmed_booking_item_stages();
CREATE CONSTRAINT TRIGGER ct_confirmed_booking_stages_detail AFTER INSERT OR UPDATE OR DELETE ON booking_item_stages
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_confirmed_booking_item_stages();
CREATE CONSTRAINT TRIGGER ct_confirmed_booking_stages_header AFTER INSERT OR UPDATE ON bookings
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_confirmed_booking_item_stages();


CREATE OR REPLACE FUNCTION release_booking_schedule_reservations() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    IF NEW.status IN ('CANCELLED','REJECTED','NO_SHOW') AND OLD.status IS DISTINCT FROM NEW.status THEN
        UPDATE schedule_reservations sr SET reservation_status='RELEASED', updated_at=now()
         WHERE sr.business_id=NEW.business_id AND sr.reservation_status IN ('HELD','CONFIRMED','BLOCKED')
           AND (EXISTS (SELECT 1 FROM booking_item_stages st JOIN booking_items bi ON bi.business_id=st.business_id AND bi.id=st.booking_item_id WHERE st.id=sr.booking_item_stage_id AND bi.booking_id=NEW.id)
             OR EXISTS (SELECT 1 FROM booking_item_resources bir JOIN booking_items bi ON bi.business_id=bir.business_id AND bi.id=bir.booking_item_id WHERE bir.id=sr.booking_item_resource_id AND bi.booking_id=NEW.id));
    ELSIF OLD.status IN ('CANCELLED','REJECTED','NO_SHOW') AND NEW.status NOT IN ('CANCELLED','REJECTED','NO_SHOW') THEN
        UPDATE schedule_reservations sr SET reservation_status='CONFIRMED', updated_at=now()
         WHERE sr.business_id=NEW.business_id AND sr.reservation_status='RELEASED'
           AND (EXISTS (SELECT 1 FROM booking_item_stages st JOIN booking_items bi ON bi.business_id=st.business_id AND bi.id=st.booking_item_id WHERE st.id=sr.booking_item_stage_id AND bi.booking_id=NEW.id AND st.stage_status<>'CANCELLED')
             OR EXISTS (SELECT 1 FROM booking_item_resources bir JOIN booking_items bi ON bi.business_id=bir.business_id AND bi.id=bir.booking_item_id WHERE bir.id=sr.booking_item_resource_id AND bi.booking_id=NEW.id AND bir.is_active));
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_release_booking_schedule_reservations AFTER UPDATE OF status ON bookings
FOR EACH ROW EXECUTE FUNCTION release_booking_schedule_reservations();

CREATE OR REPLACE FUNCTION sync_booking_item_resource_reservation() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_branch uuid; v_booking_status text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        DELETE FROM schedule_reservations WHERE booking_item_resource_id=OLD.id;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.is_active THEN
        SELECT b.branch_id, b.status INTO v_branch, v_booking_status FROM booking_items bi JOIN bookings b
          ON b.business_id=bi.business_id AND b.id=bi.booking_id
         WHERE bi.business_id=NEW.business_id AND bi.id=NEW.booking_item_id;
        INSERT INTO schedule_reservations (business_id, branch_id, reservation_kind, resource_id, resource_unit, reservation_range, reservation_status, booking_item_resource_id)
        VALUES (NEW.business_id, v_branch, 'RESOURCE', NEW.resource_id, NEW.resource_unit, NEW.reserved_range,
                CASE WHEN v_booking_status IN ('CANCELLED','REJECTED','NO_SHOW') THEN 'RELEASED' ELSE 'CONFIRMED' END, NEW.id);
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_sync_booking_item_resource_reservation AFTER INSERT OR UPDATE OR DELETE ON booking_item_resources
FOR EACH ROW EXECUTE FUNCTION sync_booking_item_resource_reservation();

CREATE OR REPLACE FUNCTION sync_booking_hold_reservations() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        DELETE FROM schedule_reservations WHERE booking_hold_id=OLD.id;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.hold_status IN ('ACTIVE','PENDING') AND NEW.expires_at > now() THEN
        IF NEW.employee_id IS NOT NULL THEN
            INSERT INTO schedule_reservations (business_id, branch_id, reservation_kind, employee_id, reservation_range, reservation_status, booking_hold_id, hold_expires_at)
            VALUES (NEW.business_id, NEW.branch_id, 'STAFF', NEW.employee_id, NEW.held_range, 'HELD', NEW.id, NEW.expires_at);
        END IF;
        IF NEW.resource_id IS NOT NULL THEN
            INSERT INTO schedule_reservations (business_id, branch_id, reservation_kind, resource_id, resource_unit, reservation_range, reservation_status, booking_hold_id, hold_expires_at)
            VALUES (NEW.business_id, NEW.branch_id, 'RESOURCE', NEW.resource_id, NEW.resource_unit, NEW.held_range, 'HELD', NEW.id, NEW.expires_at);
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_sync_booking_hold_reservations AFTER INSERT OR UPDATE OR DELETE ON booking_holds
FOR EACH ROW EXECUTE FUNCTION sync_booking_hold_reservations();

CREATE OR REPLACE FUNCTION sync_calendar_block_reservation() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_capacity integer; v_unit integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        DELETE FROM schedule_reservations WHERE calendar_block_id=OLD.id;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.employee_id IS NOT NULL THEN
        INSERT INTO schedule_reservations (business_id, branch_id, reservation_kind, employee_id, reservation_range, reservation_status, calendar_block_id)
        VALUES (NEW.business_id, NEW.branch_id, 'STAFF', NEW.employee_id, NEW.blocked_range, 'BLOCKED', NEW.id);
    ELSE
        SELECT capacity INTO v_capacity FROM resources WHERE business_id=NEW.business_id AND id=NEW.resource_id;
        IF v_capacity IS NULL THEN RAISE EXCEPTION 'Unknown calendar-block resource %', NEW.resource_id; END IF;
        FOR v_unit IN 1..v_capacity LOOP
            INSERT INTO schedule_reservations (business_id, branch_id, reservation_kind, resource_id, resource_unit, reservation_range, reservation_status, calendar_block_id)
            VALUES (NEW.business_id, NEW.branch_id, 'RESOURCE', NEW.resource_id, v_unit, NEW.blocked_range, 'BLOCKED', NEW.id);
        END LOOP;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_sync_calendar_block_reservation AFTER INSERT OR UPDATE OR DELETE ON calendar_blocks
FOR EACH ROW EXECUTE FUNCTION sync_calendar_block_reservation();

CREATE OR REPLACE FUNCTION expire_due_schedule_reservations(p_business_id uuid DEFAULT NULL) RETURNS integer
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_count integer;
BEGIN
    UPDATE schedule_reservations
       SET reservation_status='EXPIRED', updated_at=now()
     WHERE reservation_status='HELD' AND hold_expires_at <= now()
       AND (p_business_id IS NULL OR business_id=p_business_id);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    UPDATE booking_holds h SET hold_status='EXPIRED', updated_at=now()
     WHERE h.hold_status IN ('ACTIVE','PENDING') AND h.expires_at <= now()
       AND (p_business_id IS NULL OR h.business_id=p_business_id);
    RETURN v_count;
END;
$function$;

-- -----------------------------------------------------------------------------
-- v1.1: tenant-scoped access context and row-level security
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_set_tenant_context(p_business_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, beauty_platform, identity, pg_temp
AS $function$
DECLARE v_user_id uuid;
BEGIN
    v_user_id := NULLIF(current_setting('app.current_user_id', true), '')::uuid;
    IF v_user_id IS NULL THEN RAISE EXCEPTION 'Authenticated user context is required'; END IF;
    IF NOT EXISTS (SELECT 1 FROM beauty_platform.business_memberships m
                   WHERE m.business_id=p_business_id AND m.user_id=v_user_id AND m.membership_status='ACTIVE') THEN
        RAISE EXCEPTION 'User is not an active member of business %', p_business_id USING ERRCODE='42501';
    END IF;
    PERFORM set_config('app.current_business_id', p_business_id::text, true);
END;
$function$;
CREATE OR REPLACE FUNCTION app_clear_tenant_context() RETURNS void
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    PERFORM set_config('app.current_business_id', '', true);
END;
$function$;
-- Tenant creation and first OWNER membership are performed atomically by a restricted function.
ALTER TABLE businesses NO FORCE ROW LEVEL SECURITY;
DROP FUNCTION IF EXISTS create_business_for_current_user(text,text,text);
CREATE OR REPLACE FUNCTION create_business_for_current_user(
    p_legal_name text, p_display_name text, p_slug text,
    p_home_country_code char(2), p_functional_currency char(3)
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, beauty_platform, identity, pg_temp
AS $function$
DECLARE v_user_id uuid; v_owner_role_id uuid; v_business_id uuid; v_legal_entity_id uuid;
BEGIN
    v_user_id := NULLIF(current_setting('app.current_user_id', true), '')::uuid;
    IF v_user_id IS NULL THEN RAISE EXCEPTION 'Authenticated user context is required'; END IF;
    IF NOT EXISTS (SELECT 1 FROM identity.users u WHERE u.id=v_user_id AND u.account_status='ACTIVE') THEN
        RAISE EXCEPTION 'An active user account is required to create a business';
    END IF;
    IF p_legal_name IS NULL OR length(btrim(p_legal_name))=0 OR p_display_name IS NULL OR length(btrim(p_display_name))=0 THEN
        RAISE EXCEPTION 'Legal and display names are required';
    END IF;
    IF p_slug IS NULL OR lower(btrim(p_slug)) !~ '^[a-z0-9]+(-[a-z0-9]+)*$' THEN
        RAISE EXCEPTION 'Business slug must contain lowercase letters, numbers and single hyphen separators';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM beauty_platform.countries c WHERE c.country_code=p_home_country_code AND c.is_active) THEN
        RAISE EXCEPTION 'The requested home country is not an active platform market';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM beauty_platform.country_currencies cc WHERE cc.country_code=p_home_country_code AND cc.currency_code=p_functional_currency AND cc.is_enabled) THEN
        RAISE EXCEPTION 'The functional currency is not enabled for the selected home country';
    END IF;
    SELECT id INTO v_owner_role_id FROM beauty_platform.business_roles WHERE code='OWNER';
    IF v_owner_role_id IS NULL THEN RAISE EXCEPTION 'BUSINESS/OWNER role seed is missing'; END IF;
    INSERT INTO beauty_platform.businesses (owner_user_id,legal_name,display_name,slug,home_country_code,reporting_currency)
    VALUES (v_user_id,btrim(p_legal_name),btrim(p_display_name),lower(btrim(p_slug)),p_home_country_code,p_functional_currency)
    RETURNING id INTO v_business_id;
    PERFORM set_config('app.current_business_id',v_business_id::text,true);
    INSERT INTO beauty_platform.business_memberships (business_id,user_id,role_id,membership_status)
    VALUES (v_business_id,v_user_id,v_owner_role_id,'ACTIVE');
    INSERT INTO beauty_platform.business_market_countries (business_id,country_code,market_status,activated_at)
    VALUES (v_business_id,p_home_country_code,'ACTIVE',now());
    INSERT INTO beauty_platform.business_legal_entities (business_id,entity_code,legal_name,entity_type,country_code,functional_currency,entity_status,is_primary)
    VALUES (v_business_id,'PRIMARY',btrim(p_legal_name),'OTHER',p_home_country_code,p_functional_currency,'PENDING_VERIFICATION',true)
    RETURNING id INTO v_legal_entity_id;
    RETURN v_business_id;
END;
$function$;
REVOKE ALL ON FUNCTION app_set_tenant_context(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_clear_tenant_context() FROM PUBLIC;
REVOKE ALL ON FUNCTION create_business_for_current_user(text,text,text,char(2),char(3)) FROM PUBLIC;
COMMENT ON FUNCTION app_set_tenant_context(uuid) IS 'Call after setting app.current_user_id from a verified authentication identity; requires an active business membership. Grant EXECUTE only to the API runtime role.';
COMMENT ON FUNCTION create_business_for_current_user(text,text,text,char(2),char(3)) IS 'Atomically creates a business, active OWNER membership, initial market and primary legal entity. Grant EXECUTE only to the authenticated API role.';

DO $rls$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.table_name
          FROM information_schema.columns c
         WHERE c.table_schema='beauty_platform' AND c.column_name='business_id' AND c.is_nullable='NO'
         GROUP BY c.table_name
    LOOP
        EXECUTE format('ALTER TABLE beauty_platform.%I ENABLE ROW LEVEL SECURITY', r.table_name);
        IF r.table_name <> 'business_memberships' THEN
            EXECUTE format('ALTER TABLE beauty_platform.%I FORCE ROW LEVEL SECURITY', r.table_name);
        END IF;
        IF r.table_name = 'business_memberships' THEN
            EXECUTE 'CREATE POLICY membership_scope_policy ON beauty_platform.business_memberships USING (business_id = NULLIF(current_setting(''app.current_business_id'', true), '''')::uuid OR user_id = NULLIF(current_setting(''app.current_user_id'', true), '''')::uuid) WITH CHECK (business_id = NULLIF(current_setting(''app.current_business_id'', true), '''')::uuid)';
        ELSE
            EXECUTE format('CREATE POLICY tenant_scope_policy ON beauty_platform.%I USING (business_id = NULLIF(current_setting(''app.current_business_id'', true), '''')::uuid) WITH CHECK (business_id = NULLIF(current_setting(''app.current_business_id'', true), '''')::uuid)', r.table_name);
        END IF;
    END LOOP;
END;
$rls$;

ALTER TABLE businesses ENABLE ROW LEVEL SECURITY;
ALTER TABLE businesses FORCE ROW LEVEL SECURITY;
CREATE POLICY business_root_scope_policy ON businesses
USING (id = NULLIF(current_setting('app.current_business_id', true), '')::uuid)
WITH CHECK (id = NULLIF(current_setting('app.current_business_id', true), '')::uuid);
-- Owner bypass is required only for the restricted create_business_for_current_user() function.
ALTER TABLE businesses NO FORCE ROW LEVEL SECURITY;

-- Identity and private self-service records. Authentication lookup must use a dedicated, tightly controlled auth service role.
DO $user_rls$
DECLARE r record;
BEGIN
    FOR r IN SELECT table_name FROM information_schema.columns
              WHERE table_schema='beauty_platform' AND column_name='user_id'
              AND table_name IN ('notifications','notification_preferences','push_devices','customer_favourites','marketplace_search_events')
              GROUP BY table_name
    LOOP
        EXECUTE format('ALTER TABLE beauty_platform.%I ENABLE ROW LEVEL SECURITY', r.table_name);
        EXECUTE format('ALTER TABLE beauty_platform.%I FORCE ROW LEVEL SECURITY', r.table_name);
        EXECUTE format('CREATE POLICY self_user_scope_policy ON beauty_platform.%I USING (user_id = NULLIF(current_setting(''app.current_user_id'', true), '''')::uuid) WITH CHECK (user_id = NULLIF(current_setting(''app.current_user_id'', true), '''')::uuid)', r.table_name);
    END LOOP;
END;
$user_rls$;
do not grant direct users/credential table reads.';



-- Private messaging and notification records are participant/recipient scoped,
-- not business-member scoped (customers may chat with a business without being staff).
CREATE OR REPLACE FUNCTION app_is_conversation_participant(p_conversation_id uuid, p_user_id uuid) RETURNS boolean
LANGUAGE sql SECURITY DEFINER STABLE
SET search_path = pg_catalog, beauty_platform, identity, pg_temp
AS $function$
    SELECT EXISTS (SELECT 1 FROM beauty_platform.conversation_participants cp
                    WHERE cp.conversation_id=p_conversation_id AND cp.user_id=p_user_id AND cp.left_at IS NULL)
$function$;
REVOKE ALL ON FUNCTION app_is_conversation_participant(uuid,uuid) FROM PUBLIC;
ALTER TABLE conversation_participants NO FORCE ROW LEVEL SECURITY;

ALTER TABLE conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversations FORCE ROW LEVEL SECURITY;
CREATE POLICY conversation_participant_select ON conversations FOR SELECT
USING (app_is_conversation_participant(id, NULLIF(current_setting('app.current_user_id',true),'')::uuid)
       OR created_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid);
CREATE POLICY conversation_creator_insert ON conversations FOR INSERT
WITH CHECK (created_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       AND (business_id IS NULL OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid));
CREATE POLICY conversation_creator_update ON conversations FOR UPDATE
USING (created_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid)
WITH CHECK (created_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       AND (business_id IS NULL OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid));

ALTER TABLE conversation_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_participants FORCE ROW LEVEL SECURITY;
CREATE POLICY conversation_participants_select ON conversation_participants FOR SELECT
USING (app_is_conversation_participant(conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid)
       OR user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid);
CREATE POLICY conversation_participants_insert ON conversation_participants FOR INSERT
WITH CHECK (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR EXISTS (SELECT 1 FROM conversations c WHERE c.id=conversation_id AND c.created_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid));
CREATE POLICY conversation_participants_update_self ON conversation_participants FOR UPDATE
USING (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid)
WITH CHECK (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid);

ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages FORCE ROW LEVEL SECURITY;
CREATE POLICY message_participant_select ON messages FOR SELECT
USING (app_is_conversation_participant(conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid));
CREATE POLICY message_sender_insert ON messages FOR INSERT
WITH CHECK (sender_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       AND app_is_conversation_participant(conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid));
CREATE POLICY message_sender_update ON messages FOR UPDATE
USING (sender_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       AND app_is_conversation_participant(conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid))
WITH CHECK (sender_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       AND app_is_conversation_participant(conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid));

ALTER TABLE message_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY message_attachment_participant_access ON message_attachments FOR ALL
USING (EXISTS (SELECT 1 FROM messages m WHERE m.id=message_id AND app_is_conversation_participant(m.conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid)))
WITH CHECK (EXISTS (SELECT 1 FROM messages m WHERE m.id=message_id AND m.sender_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid AND app_is_conversation_participant(m.conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid)));

ALTER TABLE message_reactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_reactions FORCE ROW LEVEL SECURITY;
CREATE POLICY message_reaction_select ON message_reactions FOR SELECT
USING (EXISTS (SELECT 1 FROM messages m WHERE m.id=message_id AND app_is_conversation_participant(m.conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid)));
CREATE POLICY message_reaction_insert ON message_reactions FOR INSERT
WITH CHECK (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid AND EXISTS (SELECT 1 FROM messages m WHERE m.id=message_id AND app_is_conversation_participant(m.conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid)));
CREATE POLICY message_reaction_delete ON message_reactions FOR DELETE
USING (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid);

ALTER TABLE message_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY message_receipt_select ON message_receipts FOR SELECT
USING (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid OR EXISTS (SELECT 1 FROM messages m WHERE m.id=message_id AND app_is_conversation_participant(m.conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid)));
CREATE POLICY message_receipt_insert ON message_receipts FOR INSERT
WITH CHECK (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid AND EXISTS (SELECT 1 FROM messages m WHERE m.id=message_id AND app_is_conversation_participant(m.conversation_id,NULLIF(current_setting('app.current_user_id',true),'')::uuid)));
CREATE POLICY message_receipt_update_self ON message_receipts FOR UPDATE
USING (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid)
WITH CHECK (user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid);


-- Rows with nullable business_id are intentionally hybrid (user-owned, global or tenant-scoped).
-- They receive explicit policies here rather than the generic tenant policy.
ALTER TABLE data_exports ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_exports FORCE ROW LEVEL SECURITY;
CREATE POLICY data_export_recipient_select ON data_exports FOR SELECT
USING (requested_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);
CREATE POLICY data_export_owner_insert ON data_exports FOR INSERT
WITH CHECK (requested_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       AND (business_id IS NULL OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid));
CREATE POLICY data_export_owner_update ON data_exports FOR UPDATE
USING (requested_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid)
WITH CHECK (requested_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid);

ALTER TABLE external_integrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_integrations FORCE ROW LEVEL SECURITY;
CREATE POLICY external_integration_tenant_policy ON external_integrations
USING (business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)
WITH CHECK (business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);
ALTER TABLE integration_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_events FORCE ROW LEVEL SECURITY;
CREATE POLICY integration_event_tenant_policy ON integration_events
USING (business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)
WITH CHECK (business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);

ALTER TABLE feature_flags ENABLE ROW LEVEL SECURITY;
ALTER TABLE feature_flags FORCE ROW LEVEL SECURITY;
CREATE POLICY feature_flag_select_policy ON feature_flags FOR SELECT
USING (business_id IS NULL OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);
CREATE POLICY feature_flag_tenant_write_policy ON feature_flags FOR ALL
USING (business_id IS NOT NULL AND business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)
WITH CHECK (business_id IS NOT NULL AND business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_log_tenant_select ON audit_logs FOR SELECT
USING (business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);
CREATE POLICY audit_log_tenant_insert ON audit_logs FOR INSERT
WITH CHECK (business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);

ALTER TABLE support_tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_tickets FORCE ROW LEVEL SECURITY;
CREATE POLICY support_ticket_select ON support_tickets FOR SELECT
USING (opened_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);
CREATE POLICY support_ticket_insert ON support_tickets FOR INSERT
WITH CHECK (opened_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       AND (business_id IS NULL OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid));
CREATE POLICY support_ticket_update ON support_tickets FOR UPDATE
USING (opened_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)
WITH CHECK (opened_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);

ALTER TABLE support_ticket_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_ticket_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY support_ticket_message_select ON support_ticket_messages FOR SELECT
USING (EXISTS (SELECT 1 FROM support_tickets st WHERE st.id=support_ticket_id
        AND (st.opened_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
             OR st.business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)
        AND (NOT is_internal OR st.business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)));
CREATE POLICY support_ticket_message_insert ON support_ticket_messages FOR INSERT
WITH CHECK (sender_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
        AND EXISTS (SELECT 1 FROM support_tickets st WHERE st.id=support_ticket_id
         AND (st.opened_by_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
              OR st.business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)));

ALTER TABLE file_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_assets FORCE ROW LEVEL SECURITY;
CREATE POLICY file_asset_select_policy ON file_assets FOR SELECT
USING (access_classification='PUBLIC'
       OR owner_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);
CREATE POLICY file_asset_insert_policy ON file_assets FOR INSERT
WITH CHECK (owner_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);
CREATE POLICY file_asset_update_policy ON file_assets FOR UPDATE
USING (owner_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid)
WITH CHECK (owner_user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid
       OR business_id=NULLIF(current_setting('app.current_business_id',true),'')::uuid);

ALTER TABLE notification_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_delivery_recipient_access ON notification_deliveries FOR SELECT
USING (EXISTS (SELECT 1 FROM notifications n WHERE n.id=notification_id AND n.user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid));
ALTER TABLE notification_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_delivery_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_attempt_recipient_access ON notification_delivery_attempts FOR SELECT
USING (EXISTS (SELECT 1 FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id
                WHERE d.id=delivery_id AND n.user_id=NULLIF(current_setting('app.current_user_id',true),'')::uuid));

-- -----------------------------------------------------------------------------
-- v1.1: notification delivery attempt history
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_notification_one_started_attempt ON notification_delivery_attempts (delivery_id) WHERE attempt_status='STARTED';
CREATE OR REPLACE FUNCTION maintain_notification_delivery_attempt() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_attempt_count integer;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Notification delivery attempts are retained and cannot be deleted'; END IF;
    IF TG_OP='UPDATE' THEN
        IF OLD.attempt_status <> 'STARTED' OR NEW.attempt_status='STARTED' OR OLD.delivery_id<>NEW.delivery_id OR OLD.attempt_number<>NEW.attempt_number THEN
            RAISE EXCEPTION 'A delivery attempt can transition only once from STARTED to a final outcome';
        END IF;
        NEW.finished_at := COALESCE(NEW.finished_at, now());
        RETURN NEW;
    END IF;
    SELECT d.attempt_count INTO v_attempt_count FROM notification_deliveries d WHERE d.id=NEW.delivery_id FOR UPDATE;
    IF NEW.attempt_number <> COALESCE(v_attempt_count,0)+1 THEN
        RAISE EXCEPTION 'Expected attempt number %, received %', COALESCE(v_attempt_count,0)+1, NEW.attempt_number;
    END IF;
    IF NEW.attempt_status <> 'STARTED' THEN RAISE EXCEPTION 'Attempts must be inserted in STARTED state'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_notification_attempt_guard BEFORE INSERT OR UPDATE OR DELETE ON notification_delivery_attempts
FOR EACH ROW EXECUTE FUNCTION maintain_notification_delivery_attempt();

CREATE OR REPLACE FUNCTION reflect_notification_delivery_attempt() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    IF TG_OP='INSERT' THEN
        UPDATE notification_deliveries SET attempt_count=NEW.attempt_number, last_attempt_at=NEW.started_at,
            delivery_status='SENDING', updated_at=now() WHERE id=NEW.delivery_id;
    ELSE
        UPDATE notification_deliveries SET delivery_status=CASE
            WHEN NEW.attempt_status='SUCCEEDED' THEN 'DELIVERED'
            WHEN NEW.attempt_status='PERMANENT_FAILURE' THEN 'FAILED'
            WHEN NEW.attempt_status='RETRYABLE_FAILURE' THEN 'RETRY'
            ELSE 'SENDING' END,
            delivered_at=CASE WHEN NEW.attempt_status='SUCCEEDED' THEN NEW.finished_at ELSE delivered_at END,
            failure_reason=CASE WHEN NEW.attempt_status IN ('FAILED','RETRYABLE_FAILURE','PERMANENT_FAILURE') THEN COALESCE(NEW.error_message,NEW.error_code) ELSE NULL END,
            updated_at=now() WHERE id=NEW.delivery_id;
    END IF;
    RETURN NULL;
END;
$function$;
CREATE TRIGGER trg_notification_attempt_reflect AFTER INSERT OR UPDATE ON notification_delivery_attempts
FOR EACH ROW EXECUTE FUNCTION reflect_notification_delivery_attempt();

-- -----------------------------------------------------------------------------
-- v1.1: payroll and adjustment integrity
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION assert_pay_run_consistency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_run_id uuid; v_status text; v_currency char(3); v_count integer;
        v_base numeric(16,2); v_earn numeric(16,2); v_deduct numeric(16,2); v_net numeric(16,2);
BEGIN
    IF TG_TABLE_NAME='pay_runs' THEN
        IF TG_OP='DELETE' THEN v_run_id := OLD.id; ELSE v_run_id := NEW.id; END IF;
    ELSE
        IF TG_OP='DELETE' THEN v_run_id := OLD.pay_run_id; ELSE v_run_id := NEW.pay_run_id; END IF;
    END IF;
    SELECT run_status,currency,base_gross_total,earning_adjustments_total,deductions_total,net_total
      INTO v_status,v_currency,v_base,v_earn,v_deduct,v_net FROM pay_runs WHERE id=v_run_id;
    IF NOT FOUND OR v_status NOT IN ('APPROVED','PARTIALLY_PAID','PAID') THEN RETURN NULL; END IF;
    SELECT count(*),COALESCE(sum(base_gross_amount),0),COALESCE(sum(earning_adjustments_amount),0),
           COALESCE(sum(deductions_amount),0),COALESCE(sum(net_amount),0)
      INTO v_count,v_base,v_earn,v_deduct,v_net FROM pay_run_items WHERE pay_run_id=v_run_id AND business_id=(SELECT business_id FROM pay_runs WHERE id=v_run_id);
    IF v_count=0 THEN RAISE EXCEPTION 'An approved pay run must contain at least one employee line'; END IF;
    IF EXISTS (SELECT 1 FROM pay_run_items WHERE pay_run_id=v_run_id AND currency<>v_currency) THEN
        RAISE EXCEPTION 'Pay run and employee line currencies must match';
    END IF;
    IF v_base <> (SELECT base_gross_total FROM pay_runs WHERE id=v_run_id)
       OR v_earn <> (SELECT earning_adjustments_total FROM pay_runs WHERE id=v_run_id)
       OR v_deduct <> (SELECT deductions_total FROM pay_runs WHERE id=v_run_id)
       OR v_net <> (SELECT net_total FROM pay_runs WHERE id=v_run_id) THEN
        RAISE EXCEPTION 'Pay run header totals must equal the employee pay line totals';
    END IF;
    RETURN NULL;
END;
$function$;
CREATE CONSTRAINT TRIGGER ct_pay_run_consistency_header AFTER INSERT OR UPDATE ON pay_runs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_pay_run_consistency();
CREATE CONSTRAINT TRIGGER ct_pay_run_consistency_items AFTER INSERT OR UPDATE OR DELETE ON pay_run_items
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_pay_run_consistency();

CREATE OR REPLACE FUNCTION assert_pay_item_adjustments() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_item uuid; v_status text; v_earn numeric(16,2); v_deduct numeric(16,2); v_expected_earn numeric(16,2); v_expected_deduct numeric(16,2);
BEGIN
    IF TG_OP='DELETE' THEN v_item := OLD.pay_run_item_id; ELSE v_item := NEW.pay_run_item_id; END IF;
    SELECT pr.run_status,pi.earning_adjustments_amount,pi.deductions_amount
      INTO v_status,v_expected_earn,v_expected_deduct
      FROM pay_run_items pi JOIN pay_runs pr ON pr.business_id=pi.business_id AND pr.id=pi.pay_run_id
     WHERE pi.id=v_item;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF v_status IN ('APPROVED','PARTIALLY_PAID','PAID') THEN
        SELECT COALESCE(sum(amount) FILTER (WHERE adjustment_type='EARNING'),0),
               COALESCE(sum(amount) FILTER (WHERE adjustment_type='DEDUCTION'),0)
          INTO v_earn,v_deduct FROM pay_run_adjustments WHERE pay_run_item_id=v_item;
        IF v_earn<>v_expected_earn OR v_deduct<>v_expected_deduct THEN
            RAISE EXCEPTION 'Pay item adjustment totals do not match its adjustments';
        END IF;
    END IF;
    RETURN NULL;
END;
$function$;
CREATE CONSTRAINT TRIGGER ct_pay_item_adjustments AFTER INSERT OR UPDATE OR DELETE ON pay_run_adjustments
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_pay_item_adjustments();
CREATE CONSTRAINT TRIGGER ct_pay_item_adjustments_line AFTER INSERT OR UPDATE ON pay_run_items
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_pay_item_adjustments();

CREATE OR REPLACE FUNCTION guard_approved_payroll_changes() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_run_id uuid; v_status text;
BEGIN
    IF TG_TABLE_NAME='pay_runs' THEN
        IF TG_OP='UPDATE' AND OLD.run_status IN ('APPROVED','PARTIALLY_PAID','PAID') AND (to_jsonb(NEW)-'updated_at') IS DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN
            RAISE EXCEPTION 'Approved payroll runs are immutable; use a later adjustment run';
        ELSIF TG_OP='DELETE' AND OLD.run_status IN ('APPROVED','PARTIALLY_PAID','PAID') THEN
            RAISE EXCEPTION 'Approved payroll runs cannot be deleted';
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='pay_run_items' THEN
        IF TG_OP='DELETE' THEN v_run_id := OLD.pay_run_id; ELSE v_run_id := NEW.pay_run_id; END IF;
    ELSE
        IF TG_OP='DELETE' THEN
            SELECT pay_run_id INTO v_run_id FROM pay_run_items WHERE id=OLD.pay_run_item_id;
        ELSE
            SELECT pay_run_id INTO v_run_id FROM pay_run_items WHERE id=NEW.pay_run_item_id;
        END IF;
    END IF;
    SELECT run_status INTO v_status FROM pay_runs WHERE id=v_run_id;
    IF v_status IN ('APPROVED','PARTIALLY_PAID','PAID') THEN RAISE EXCEPTION 'Approved payroll lines and adjustments are immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$function$;
CREATE TRIGGER trg_guard_pay_run_header BEFORE UPDATE OR DELETE ON pay_runs FOR EACH ROW EXECUTE FUNCTION guard_approved_payroll_changes();
CREATE TRIGGER trg_guard_pay_run_items BEFORE INSERT OR UPDATE OR DELETE ON pay_run_items FOR EACH ROW EXECUTE FUNCTION guard_approved_payroll_changes();
CREATE TRIGGER trg_guard_pay_run_adjustments BEFORE INSERT OR UPDATE OR DELETE ON pay_run_adjustments FOR EACH ROW EXECUTE FUNCTION guard_approved_payroll_changes();

CREATE OR REPLACE FUNCTION enforce_payroll_disbursement_limit() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_net numeric(16,2); v_run_status text; v_paid numeric(16,2);
BEGIN
    SELECT pi.net_amount,pr.run_status INTO v_net,v_run_status
      FROM pay_run_items pi JOIN pay_runs pr ON pr.business_id=pi.business_id AND pr.id=pi.pay_run_id
     WHERE pi.id=NEW.pay_run_item_id AND pi.business_id=NEW.business_id FOR UPDATE OF pi,pr;
    IF NOT FOUND THEN RAISE EXCEPTION 'Pay run item not found for this business'; END IF;
    IF v_run_status NOT IN ('APPROVED','PARTIALLY_PAID','PAID') THEN RAISE EXCEPTION 'Payroll must be approved before disbursement'; END IF;
    IF NEW.payment_status IN ('SUCCEEDED') THEN
        SELECT COALESCE(sum(amount),0) INTO v_paid FROM pay_run_payments
         WHERE pay_run_item_id=NEW.pay_run_item_id AND payment_status='SUCCEEDED' AND id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid);
        IF v_paid+NEW.amount>v_net THEN RAISE EXCEPTION 'Payroll disbursements cannot exceed net pay'; END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_payroll_disbursement_limit BEFORE INSERT OR UPDATE ON pay_run_payments
FOR EACH ROW EXECUTE FUNCTION enforce_payroll_disbursement_limit();

-- -----------------------------------------------------------------------------
-- v1.1: payment allocation and refund allocation integrity
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION enforce_payment_allocation_limits() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_captured numeric(14,2); v_refunded numeric(14,2); v_obligation numeric(14,2);
        v_payment uuid; v_obligation_id uuid; v_used_payment numeric(18,2); v_used_obligation numeric(18,2);
BEGIN
    v_payment := NEW.payment_id; v_obligation_id := NEW.payment_obligation_id;
    SELECT captured_amount,refunded_amount INTO v_captured,v_refunded
      FROM payments WHERE business_id=NEW.business_id AND id=v_payment FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Payment does not belong to allocation business'; END IF;
    SELECT amount_due INTO v_obligation FROM payment_obligations
     WHERE business_id=NEW.business_id AND id=v_obligation_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Payment obligation does not belong to allocation business'; END IF;
    IF v_captured<=0 THEN RAISE EXCEPTION 'A payment must be captured before allocation'; END IF;
    SELECT COALESCE(sum(pa.payment_amount),0)
           - COALESCE((SELECT sum(ra.amount) FROM refund_allocations ra
                       JOIN refunds r ON r.business_id=ra.business_id AND r.id=ra.refund_id AND r.refund_status='COMPLETED'
                       JOIN payment_allocations px ON px.business_id=ra.business_id AND px.id=ra.payment_allocation_id
                       WHERE px.business_id=NEW.business_id AND px.payment_id=v_payment),0)
      INTO v_used_payment
      FROM payment_allocations pa
     WHERE pa.business_id=NEW.business_id AND pa.payment_id=v_payment AND pa.allocation_status='ACTIVE'
       AND pa.id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid);
    SELECT COALESCE(sum(pa.amount_allocated),0)
           - COALESCE((SELECT sum(ra.obligation_amount) FROM refund_allocations ra
                       JOIN refunds r ON r.business_id=ra.business_id AND r.id=ra.refund_id AND r.refund_status='COMPLETED'
                       JOIN payment_allocations px ON px.business_id=ra.business_id AND px.id=ra.payment_allocation_id
                       WHERE px.business_id=NEW.business_id AND px.payment_obligation_id=v_obligation_id),0)
      INTO v_used_obligation
      FROM payment_allocations pa
     WHERE pa.business_id=NEW.business_id AND pa.payment_obligation_id=v_obligation_id AND pa.allocation_status='ACTIVE'
       AND pa.id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid);
    IF v_used_payment+NEW.payment_amount>v_captured-v_refunded THEN
        RAISE EXCEPTION 'Payment allocation exceeds captured unrefunded funds in payment currency';
    END IF;
    IF v_used_obligation+NEW.amount_allocated>v_obligation THEN
        RAISE EXCEPTION 'Payment allocation exceeds the obligation balance in obligation currency';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_20_payment_allocation_limits BEFORE INSERT OR UPDATE ON payment_allocations
FOR EACH ROW EXECUTE FUNCTION enforce_payment_allocation_limits();

CREATE OR REPLACE FUNCTION enforce_refund_allocation_limits() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_refund_payment uuid; v_refund_amount numeric(14,2); v_refund_status text;
        v_alloc_payment uuid; v_alloc_payment_amount numeric(14,2); v_alloc_obligation_amount numeric(14,2);
        v_used_payment numeric(18,2); v_used_obligation numeric(18,2); v_refund_total numeric(18,2);
BEGIN
    SELECT payment_id,amount,refund_status INTO v_refund_payment,v_refund_amount,v_refund_status
      FROM refunds WHERE business_id=NEW.business_id AND id=NEW.refund_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Refund does not belong to this business'; END IF;
    IF v_refund_status IN ('COMPLETED','CANCELLED','FAILED','REJECTED') THEN RAISE EXCEPTION 'Refund is no longer allocatable'; END IF;
    SELECT payment_id,payment_amount,amount_allocated INTO v_alloc_payment,v_alloc_payment_amount,v_alloc_obligation_amount
      FROM payment_allocations WHERE business_id=NEW.business_id AND id=NEW.payment_allocation_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Payment allocation does not belong to this business'; END IF;
    IF v_refund_payment<>v_alloc_payment THEN RAISE EXCEPTION 'Refund allocation must refer to the refund original payment'; END IF;
    SELECT COALESCE(sum(amount),0) INTO v_used_payment FROM refund_allocations
     WHERE business_id=NEW.business_id AND payment_allocation_id=NEW.payment_allocation_id
       AND id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid);
    SELECT COALESCE(sum(obligation_amount),0) INTO v_used_obligation FROM refund_allocations
     WHERE business_id=NEW.business_id AND payment_allocation_id=NEW.payment_allocation_id
       AND id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid);
    SELECT COALESCE(sum(amount),0) INTO v_refund_total FROM refund_allocations
     WHERE business_id=NEW.business_id AND refund_id=NEW.refund_id
       AND id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid);
    IF v_used_payment+NEW.amount>v_alloc_payment_amount THEN RAISE EXCEPTION 'Refund allocations exceed the original payment-currency allocation'; END IF;
    IF v_used_obligation+NEW.obligation_amount>v_alloc_obligation_amount THEN RAISE EXCEPTION 'Refund allocations exceed the original obligation-currency allocation'; END IF;
    IF v_refund_total+NEW.amount>v_refund_amount THEN RAISE EXCEPTION 'Refund allocations exceed the refund amount'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_20_refund_allocation_limits BEFORE INSERT OR UPDATE ON refund_allocations
FOR EACH ROW EXECUTE FUNCTION enforce_refund_allocation_limits();

CREATE OR REPLACE FUNCTION assert_completed_refund_allocated() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_total numeric(14,2);
BEGIN
    IF NEW.refund_status='COMPLETED' AND OLD.refund_status IS DISTINCT FROM 'COMPLETED' THEN
        SELECT COALESCE(sum(amount),0) INTO v_total FROM refund_allocations WHERE refund_id=NEW.id AND business_id=NEW.business_id;
        IF v_total<>NEW.amount THEN RAISE EXCEPTION 'Completed refund must be fully mapped to original payment allocations'; END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_completed_refund_allocation_check BEFORE UPDATE OF refund_status ON refunds
FOR EACH ROW EXECUTE FUNCTION assert_completed_refund_allocated();
CREATE OR REPLACE FUNCTION reject_completed_refund_insert() RETURNS trigger LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN IF NEW.refund_status='COMPLETED' THEN RAISE EXCEPTION 'Create refund as PENDING, allocate it, then transition to COMPLETED'; END IF; RETURN NEW; END;
$function$;
CREATE TRIGGER trg_reject_completed_refund_insert BEFORE INSERT ON refunds FOR EACH ROW EXECUTE FUNCTION reject_completed_refund_insert();

CREATE OR REPLACE FUNCTION guard_completed_refund_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    IF OLD.refund_status='COMPLETED' THEN RAISE EXCEPTION 'Completed refunds are immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$function$;
CREATE TRIGGER trg_guard_completed_refund BEFORE UPDATE OR DELETE ON refunds
FOR EACH ROW EXECUTE FUNCTION guard_completed_refund_immutable();

-- -----------------------------------------------------------------------------
-- v1.1: referral lifecycle constraints and append-only reward transactions
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION validate_referral_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    IF NEW.referrer_user_id IS NOT NULL AND NEW.referred_user_id IS NOT NULL AND NEW.referrer_user_id=NEW.referred_user_id THEN
        RAISE EXCEPTION 'Self-referrals are not allowed';
    END IF;
    IF NEW.referrer_customer_id IS NOT NULL AND NEW.referred_customer_id IS NOT NULL AND NEW.referrer_customer_id=NEW.referred_customer_id THEN
        RAISE EXCEPTION 'Self-referrals are not allowed';
    END IF;
    IF NEW.referred_customer_id IS NOT NULL AND EXISTS (SELECT 1 FROM referrals r WHERE r.business_id=NEW.business_id AND r.referral_program_id=NEW.referral_program_id AND r.referred_customer_id=NEW.referred_customer_id AND r.id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid)) THEN
        RAISE EXCEPTION 'A customer can only be referred once per programme';
    END IF;
    IF NEW.referred_user_id IS NOT NULL AND EXISTS (SELECT 1 FROM referrals r WHERE r.business_id=NEW.business_id AND r.referral_program_id=NEW.referral_program_id AND r.referred_user_id=NEW.referred_user_id AND r.id<>COALESCE(NEW.id,'00000000-0000-0000-0000-000000000000'::uuid)) THEN
        RAISE EXCEPTION 'A user can only be referred once per programme';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_referral_identity BEFORE INSERT OR UPDATE ON referrals
FOR EACH ROW EXECUTE FUNCTION validate_referral_identity();

CREATE OR REPLACE FUNCTION prevent_referral_reward_transaction_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    RAISE EXCEPTION 'Referral reward transactions are append-only';
END;
$function$;
CREATE TRIGGER trg_referral_reward_transaction_immutable BEFORE UPDATE OR DELETE ON referral_reward_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_referral_reward_transaction_mutation();

-- -----------------------------------------------------------------------------
-- v1.1: double-entry journal validation and immutability
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION guard_posted_journal() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_journal uuid; v_status text;
BEGIN
    IF TG_TABLE_NAME='financial_journals' THEN
        IF TG_OP='DELETE' THEN
            IF OLD.journal_status='POSTED' THEN RAISE EXCEPTION 'Posted journals cannot be deleted'; END IF;
            RETURN OLD;
        END IF;
        IF OLD.journal_status='POSTED' THEN RAISE EXCEPTION 'Posted journals are immutable; create a reversal journal'; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN v_journal := OLD.journal_id; ELSE v_journal := NEW.journal_id; END IF;
    SELECT journal_status INTO v_status FROM financial_journals WHERE id=v_journal;
    IF v_status='POSTED' THEN RAISE EXCEPTION 'Lines in a posted journal are immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$function$;
CREATE TRIGGER trg_guard_posted_journal_header BEFORE UPDATE OR DELETE ON financial_journals
FOR EACH ROW EXECUTE FUNCTION guard_posted_journal();
CREATE TRIGGER trg_guard_posted_journal_lines BEFORE INSERT OR UPDATE OR DELETE ON financial_ledger_entries
FOR EACH ROW EXECUTE FUNCTION guard_posted_journal();
CREATE OR REPLACE FUNCTION validate_journal_currency_rate() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_base_currency char(3); v_base_minor smallint; v_line_minor smallint;
BEGIN
    SELECT j.base_currency,c.minor_unit INTO v_base_currency,v_base_minor
      FROM financial_journals j JOIN currencies c ON c.currency_code=j.base_currency
     WHERE j.business_id=NEW.business_id AND j.id=NEW.journal_id;
    IF v_base_currency IS NULL THEN RAISE EXCEPTION 'Financial journal does not belong to this business'; END IF;
    SELECT minor_unit INTO v_line_minor FROM currencies WHERE currency_code=NEW.currency;
    IF v_line_minor IS NULL THEN RAISE EXCEPTION 'Unknown ledger-entry currency %',NEW.currency; END IF;
    IF NEW.debit_amount <> round(NEW.debit_amount,v_line_minor) OR NEW.credit_amount <> round(NEW.credit_amount,v_line_minor) THEN
        RAISE EXCEPTION 'Ledger line amounts must respect transaction currency minor units';
    END IF;
    IF NEW.base_debit_amount <> round(NEW.base_debit_amount,v_base_minor) OR NEW.base_credit_amount <> round(NEW.base_credit_amount,v_base_minor) THEN
        RAISE EXCEPTION 'Ledger base amounts must respect legal-entity functional currency minor units';
    END IF;
    IF NEW.currency=v_base_currency AND NEW.exchange_rate<>1 THEN RAISE EXCEPTION 'Exchange rate must be 1 when line currency equals journal base currency'; END IF;
    IF NEW.base_debit_amount <> round(NEW.debit_amount*NEW.exchange_rate,v_base_minor)
       OR NEW.base_credit_amount <> round(NEW.credit_amount*NEW.exchange_rate,v_base_minor) THEN
        RAISE EXCEPTION 'Ledger base amount must equal the source amount translated at its recorded rate';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_journal_currency_rate BEFORE INSERT OR UPDATE ON financial_ledger_entries FOR EACH ROW EXECUTE FUNCTION validate_journal_currency_rate();

CREATE OR REPLACE FUNCTION assert_posted_journal_balanced() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_journal uuid; v_status text; v_count integer; v_debit numeric(18,2); v_credit numeric(18,2);
BEGIN
    IF TG_TABLE_NAME='financial_journals' THEN
        IF TG_OP='DELETE' THEN v_journal := OLD.id; ELSE v_journal := NEW.id; END IF;
    ELSE
        IF TG_OP='DELETE' THEN v_journal := OLD.journal_id; ELSE v_journal := NEW.journal_id; END IF;
    END IF;
    SELECT journal_status INTO v_status FROM financial_journals WHERE id=v_journal;
    IF v_status IS DISTINCT FROM 'POSTED' THEN RETURN NULL; END IF;
    SELECT count(*),COALESCE(sum(base_debit_amount),0),COALESCE(sum(base_credit_amount),0)
      INTO v_count,v_debit,v_credit FROM financial_ledger_entries WHERE journal_id=v_journal;
    IF v_count<2 OR v_debit<>v_credit THEN RAISE EXCEPTION 'A posted journal must have at least two balanced base-currency lines (debits %, credits %)',v_debit,v_credit; END IF;
    RETURN NULL;
END;
$function$;
CREATE CONSTRAINT TRIGGER ct_posted_journal_balance_header AFTER INSERT OR UPDATE ON financial_journals
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_posted_journal_balanced();
CREATE CONSTRAINT TRIGGER ct_posted_journal_balance_lines AFTER INSERT OR UPDATE OR DELETE ON financial_ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_posted_journal_balanced();



-- Role scope is a domain invariant, not a permissions matrix.
CREATE OR REPLACE FUNCTION validate_integration_tenant_link() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_integration_business uuid;
BEGIN
    IF NEW.integration_id IS NOT NULL THEN
        SELECT business_id INTO v_integration_business FROM external_integrations WHERE id=NEW.integration_id;
        IF v_integration_business IS DISTINCT FROM NEW.business_id THEN
            RAISE EXCEPTION 'Integration event and external integration must have the same business scope';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_integration_tenant_link BEFORE INSERT OR UPDATE OF integration_id,business_id ON integration_events
FOR EACH ROW EXECUTE FUNCTION validate_integration_tenant_link();

CREATE OR REPLACE FUNCTION validate_business_asset_link() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_asset_business uuid; v_asset_owner uuid; v_access text; v_business uuid; v_asset uuid;
BEGIN
    v_business := NEW.business_id; v_asset := NEW.asset_id;
    SELECT business_id,owner_user_id,access_classification INTO v_asset_business,v_asset_owner,v_access FROM file_assets WHERE id=v_asset;
    IF NOT FOUND THEN RAISE EXCEPTION 'Referenced file asset does not exist'; END IF;
    IF v_asset_business IS NOT NULL AND v_asset_business<>v_business THEN
        RAISE EXCEPTION 'A business cannot attach another business tenant''s asset';
    END IF;
    IF v_asset_business IS NULL AND v_access<>'PUBLIC' THEN
        IF v_asset_owner IS NULL OR NOT EXISTS (SELECT 1 FROM business_memberships bm WHERE bm.business_id=v_business AND bm.user_id=v_asset_owner AND bm.membership_status='ACTIVE') THEN
            RAISE EXCEPTION 'A private user asset can only be attached by its active business owner';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_business_media_asset_tenant BEFORE INSERT OR UPDATE OF asset_id,business_id ON business_media FOR EACH ROW EXECUTE FUNCTION validate_business_asset_link();
CREATE TRIGGER trg_business_verification_asset_tenant BEFORE INSERT OR UPDATE OF asset_id,business_id ON business_verification_documents FOR EACH ROW EXECUTE FUNCTION validate_business_asset_link();
CREATE TRIGGER trg_service_media_asset_tenant BEFORE INSERT OR UPDATE OF asset_id,business_id ON service_media FOR EACH ROW EXECUTE FUNCTION validate_business_asset_link();
CREATE TRIGGER trg_product_media_asset_tenant BEFORE INSERT OR UPDATE OF asset_id,business_id ON product_media FOR EACH ROW EXECUTE FUNCTION validate_business_asset_link();

-- Tax profile periods must not overlap for the same business/branch scope.
ALTER TABLE business_tax_profiles ADD COLUMN effective_range daterange
    GENERATED ALWAYS AS (daterange(effective_from,effective_to,'[]')) STORED;
ALTER TABLE business_tax_profiles ADD CONSTRAINT ex_business_tax_profiles_effective_period EXCLUDE USING gist
    (business_id WITH =, (COALESCE(branch_id,'00000000-0000-0000-0000-000000000000'::uuid)) WITH =, effective_range WITH &&)
    WHERE (profile_status='ACTIVE');
-- Tax rate validity and immutable transaction snapshots.
ALTER TABLE tax_rates ADD CONSTRAINT ex_tax_rates_effective_period EXCLUDE USING gist
    (business_id WITH =, tax_profile_id WITH =, tax_code WITH =, effective_range WITH &&)
    WHERE (is_active);
CREATE OR REPLACE FUNCTION prevent_tax_snapshot_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    RAISE EXCEPTION 'Tax snapshots are immutable; issue a correction or reversal instead';
END;
$function$;
CREATE TRIGGER trg_booking_item_tax_snapshot_immutable BEFORE UPDATE OR DELETE ON booking_item_tax_lines FOR EACH ROW EXECUTE FUNCTION prevent_tax_snapshot_mutation();
CREATE TRIGGER trg_sale_item_tax_snapshot_immutable BEFORE UPDATE OR DELETE ON sale_item_tax_lines FOR EACH ROW EXECUTE FUNCTION prevent_tax_snapshot_mutation();
CREATE TRIGGER trg_invoice_item_tax_snapshot_immutable BEFORE UPDATE OR DELETE ON invoice_item_tax_lines FOR EACH ROW EXECUTE FUNCTION prevent_tax_snapshot_mutation();

-- Referral deduplication is backed by unique indexes to protect concurrent requests.
CREATE UNIQUE INDEX uq_referral_program_customer_once ON referrals (business_id,referral_program_id,referred_customer_id) WHERE referred_customer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_referral_program_user_once ON referrals (business_id,referral_program_id,referred_user_id) WHERE referred_user_id IS NOT NULL;

-- Accounting base values are explicit snapshots and must reconcile to source values and rate.

CREATE OR REPLACE FUNCTION assert_pay_run_adjustments_for_header() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_item record; v_earn numeric(16,2); v_deduct numeric(16,2);
BEGIN
    IF NEW.run_status IN ('APPROVED','PARTIALLY_PAID','PAID') THEN
        FOR v_item IN SELECT id,earning_adjustments_amount,deductions_amount FROM pay_run_items WHERE pay_run_id=NEW.id LOOP
            SELECT COALESCE(sum(amount) FILTER (WHERE adjustment_type='EARNING'),0),
                   COALESCE(sum(amount) FILTER (WHERE adjustment_type='DEDUCTION'),0)
              INTO v_earn,v_deduct FROM pay_run_adjustments WHERE pay_run_item_id=v_item.id;
            IF v_earn<>v_item.earning_adjustments_amount OR v_deduct<>v_item.deductions_amount THEN
                RAISE EXCEPTION 'Pay item % adjustment totals do not match its adjustments',v_item.id;
            END IF;
        END LOOP;
    END IF;
    RETURN NULL;
END;
$function$;
CREATE CONSTRAINT TRIGGER ct_pay_run_adjustments_on_header AFTER INSERT OR UPDATE ON pay_runs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_pay_run_adjustments_for_header();

CREATE OR REPLACE FUNCTION prevent_refund_allocation_after_completion() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_status text; v_refund_id uuid;
BEGIN
    IF TG_OP='DELETE' THEN v_refund_id:=OLD.refund_id; ELSE v_refund_id:=NEW.refund_id; END IF;
    SELECT refund_status INTO v_status FROM refunds WHERE id=v_refund_id;
    IF v_status='COMPLETED' THEN RAISE EXCEPTION 'Allocations for a completed refund are immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$function$;
CREATE TRIGGER trg_refund_allocation_immutable AFTER UPDATE OR DELETE ON refund_allocations
FOR EACH ROW EXECUTE FUNCTION prevent_refund_allocation_after_completion();

CREATE OR REPLACE FUNCTION apply_completed_refund_to_payment() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_refunded numeric(14,2); v_captured numeric(14,2);
BEGIN
    IF NEW.refund_status='COMPLETED' AND OLD.refund_status IS DISTINCT FROM 'COMPLETED' THEN
        SELECT refunded_amount,captured_amount INTO v_refunded,v_captured FROM payments WHERE id=NEW.payment_id FOR UPDATE;
        IF v_refunded+NEW.amount>v_captured THEN RAISE EXCEPTION 'Completed refunds cannot exceed captured payment amount'; END IF;
        UPDATE payments SET refunded_amount=refunded_amount+NEW.amount,
            payment_status=CASE WHEN refunded_amount+NEW.amount>=captured_amount THEN 'REFUNDED' ELSE 'PARTIALLY_REFUNDED' END,
            updated_at=now() WHERE id=NEW.payment_id;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_apply_completed_refund_to_payment AFTER UPDATE OF refund_status ON refunds
FOR EACH ROW EXECUTE FUNCTION apply_completed_refund_to_payment();

-- Additional effective-date and lookup indexes.
CREATE INDEX ix_tax_rates_profile_code ON tax_rates (business_id, tax_profile_id, tax_code);
CREATE INDEX ix_business_tax_profiles_branch ON business_tax_profiles (business_id, branch_id);
CREATE INDEX ix_pay_run_items_employee ON pay_run_items (business_id, employee_id);
CREATE INDEX ix_referrals_referred_customer ON referrals (business_id, referred_customer_id) WHERE referred_customer_id IS NOT NULL;
CREATE INDEX ix_schedule_reservations_active_range ON schedule_reservations USING gist (reservation_range) WHERE reservation_status IN ('HELD','CONFIRMED','BLOCKED');


CREATE OR REPLACE FUNCTION validate_currency_amount_precision() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_row jsonb; v_currency char(3); v_minor smallint; v_field text; v_amount numeric; i integer;
BEGIN
    v_row := to_jsonb(NEW);
    v_currency := NULLIF(v_row->>TG_ARGV[0],'')::char(3);
    IF v_currency IS NULL THEN
        FOR i IN 1..(TG_NARGS-1) LOOP
            v_field := TG_ARGV[i];
            v_amount := NULLIF(v_row->>v_field,'')::numeric;
            IF v_amount IS NOT NULL THEN RAISE EXCEPTION 'Currency field % is required when monetary amount % is supplied', TG_ARGV[0], v_field; END IF;
        END LOOP;
        RETURN NEW;
    END IF;
    SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=v_currency;
    IF v_minor IS NULL THEN RAISE EXCEPTION 'Unknown currency %', v_currency; END IF;
    FOR i IN 1..(TG_NARGS-1) LOOP
        v_field := TG_ARGV[i];
        v_amount := NULLIF(v_row->>v_field,'')::numeric;
        IF v_amount IS NOT NULL AND v_amount <> round(v_amount,v_minor) THEN
            RAISE EXCEPTION 'Amount field % has more fractional digits than currency % permits (%)', v_field, v_currency, v_minor;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$function$;

-- Currency precision uses the minor_unit configured for each ISO currency.
CREATE TRIGGER trg_currency_precision_employee_compensation BEFORE INSERT OR UPDATE ON employee_compensation FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_commission_entries BEFORE INSERT OR UPDATE ON commission_entries FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','gross_amount','commission_amount');
CREATE TRIGGER trg_currency_precision_service_variants BEFORE INSERT OR UPDATE ON service_variants FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','default_price');
CREATE TRIGGER trg_currency_precision_bookings BEFORE INSERT OR UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','subtotal_amount','discount_amount','tax_amount','total_amount');
CREATE TRIGGER trg_currency_precision_booking_groups BEFORE INSERT OR UPDATE ON booking_groups FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','total_amount');
CREATE TRIGGER trg_currency_precision_booking_items BEFORE INSERT OR UPDATE ON booking_items FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','unit_price','discount_amount','tax_amount','total_amount');
CREATE TRIGGER trg_currency_precision_class_sessions BEFORE INSERT OR UPDATE ON class_sessions FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');
CREATE TRIGGER trg_currency_precision_booking_item_payment_terms BEFORE INSERT OR UPDATE ON booking_item_payment_terms FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount_due');
CREATE TRIGGER trg_currency_precision_booking_fees BEFORE INSERT OR UPDATE ON booking_fees FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_business_customers BEFORE INSERT OR UPDATE ON business_customers FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','lifetime_value');
CREATE TRIGGER trg_currency_precision_sales BEFORE INSERT OR UPDATE ON sales FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','subtotal_amount','discount_amount','tax_amount','tip_amount','total_amount');
CREATE TRIGGER trg_currency_precision_sale_tips BEFORE INSERT OR UPDATE ON sale_tips FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_quotes BEFORE INSERT OR UPDATE ON quotes FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','subtotal_amount','tax_amount','total_amount');
CREATE TRIGGER trg_currency_precision_invoices BEFORE INSERT OR UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','subtotal_amount','tax_amount','total_amount');
CREATE TRIGGER trg_currency_precision_payments BEFORE INSERT OR UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount','captured_amount','refunded_amount');
CREATE TRIGGER trg_currency_precision_payment_obligations BEFORE INSERT OR UPDATE ON payment_obligations FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount_due');
CREATE TRIGGER trg_currency_precision_refunds BEFORE INSERT OR UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_settlement_batches BEFORE INSERT OR UPDATE ON settlement_batches FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','gross_amount','fee_amount','net_amount');
CREATE TRIGGER trg_currency_precision_business_wallets BEFORE INSERT OR UPDATE ON business_wallets FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','available_balance','pending_balance');
CREATE TRIGGER trg_currency_precision_wallet_transactions BEFORE INSERT OR UPDATE ON wallet_transactions FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount','balance_after');
CREATE TRIGGER trg_currency_precision_payouts BEFORE INSERT OR UPDATE ON payouts FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_payout_items BEFORE INSERT OR UPDATE ON payout_items FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_cash_movements BEFORE INSERT OR UPDATE ON cash_movements FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_product_prices BEFORE INSERT OR UPDATE ON product_prices FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');
CREATE TRIGGER trg_currency_precision_purchase_orders BEFORE INSERT OR UPDATE ON purchase_orders FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','total_amount');
CREATE TRIGGER trg_currency_precision_subscription_plans BEFORE INSERT OR UPDATE ON subscription_plans FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');
CREATE TRIGGER trg_currency_precision_billing_invoices BEFORE INSERT OR UPDATE ON billing_invoices FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','subtotal_amount','tax_amount','total_amount');
CREATE TRIGGER trg_currency_precision_business_addons BEFORE INSERT OR UPDATE ON business_addons FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');
CREATE TRIGGER trg_currency_precision_gift_cards BEFORE INSERT OR UPDATE ON gift_cards FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','face_value','current_balance');
CREATE TRIGGER trg_currency_precision_service_packages BEFORE INSERT OR UPDATE ON service_packages FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');
CREATE TRIGGER trg_currency_precision_membership_plans BEFORE INSERT OR UPDATE ON membership_plans FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');
CREATE TRIGGER trg_currency_precision_ai_usage_records BEFORE INSERT OR UPDATE ON ai_usage_records FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','cost_amount');
CREATE TRIGGER trg_currency_precision_pay_run_items BEFORE INSERT OR UPDATE ON pay_run_items FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','base_gross_amount','earning_adjustments_amount','deductions_amount','net_amount');
CREATE TRIGGER trg_currency_precision_pay_run_payments BEFORE INSERT OR UPDATE ON pay_run_payments FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_booking_item_tax_lines BEFORE INSERT OR UPDATE ON booking_item_tax_lines FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','taxable_amount','tax_amount');
CREATE TRIGGER trg_currency_precision_sale_item_tax_lines BEFORE INSERT OR UPDATE ON sale_item_tax_lines FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','taxable_amount','tax_amount');
CREATE TRIGGER trg_currency_precision_invoice_item_tax_lines BEFORE INSERT OR UPDATE ON invoice_item_tax_lines FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','taxable_amount','tax_amount');
CREATE TRIGGER trg_currency_precision_referral_reward_transactions BEFORE INSERT OR UPDATE ON referral_reward_transactions FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE TRIGGER trg_currency_precision_service_prices BEFORE INSERT OR UPDATE ON service_prices FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');
CREATE TRIGGER trg_currency_precision_service_addons BEFORE INSERT OR UPDATE ON service_addons FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','default_price');
CREATE TRIGGER trg_currency_precision_service_addon_prices BEFORE INSERT OR UPDATE ON service_addon_prices FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','price');

CREATE TRIGGER trg_currency_precision_pay_runs BEFORE INSERT OR UPDATE ON pay_runs FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','base_gross_total','earning_adjustments_total','deductions_total','net_total');
CREATE TRIGGER trg_currency_precision_payment_authorizations BEFORE INSERT OR UPDATE ON payment_authorizations FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','authorized_amount','captured_amount');
CREATE TRIGGER trg_currency_precision_payment_reconciliation_items BEFORE INSERT OR UPDATE ON payment_reconciliation_items FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','statement_amount','system_amount','difference_amount');
CREATE TRIGGER trg_currency_precision_delivery_zones BEFORE INSERT OR UPDATE ON delivery_zones FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','fee','minimum_order_amount');
CREATE TRIGGER trg_currency_precision_inventory_movements BEFORE INSERT OR UPDATE ON inventory_movements FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','unit_cost');
CREATE TRIGGER trg_currency_precision_membership_transactions BEFORE INSERT OR UPDATE ON membership_transactions FOR EACH ROW EXECUTE FUNCTION validate_currency_amount_precision('currency','amount');
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$function$;
DO $block$
DECLARE r record;
BEGIN
    FOR r IN SELECT table_name FROM information_schema.columns WHERE table_schema = 'beauty_platform' AND column_name = 'updated_at' LOOP
        EXECUTE format('CREATE TRIGGER trg_%I_updated_at BEFORE UPDATE ON beauty_platform.%I FOR EACH ROW EXECUTE FUNCTION beauty_platform.touch_updated_at()', r.table_name, r.table_name);
    END LOOP;
END;
$block$;

-- Reference data: Ethiopia initial market and East African expansion catalogue.
INSERT INTO business_roles (code, label, is_system) VALUES
 ('OWNER','Business owner',true),
 ('MANAGER','Business manager',true),
 ('RECEPTIONIST','Receptionist',true),
 ('PROFESSIONAL','Beauty professional',true),
 ('CASHIER','Cashier',true),
 ('INVENTORY_MANAGER','Inventory manager',true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO currencies (currency_code, name, symbol, minor_unit) VALUES
 ('ETB','Ethiopian Birr','Br',2),
 ('KES','Kenyan Shilling','KSh',2),
 ('UGX','Ugandan Shilling','USh',0),
 ('TZS','Tanzanian Shilling','TSh',2),
 ('RWF','Rwandan Franc','FRw',0),
 ('BIF','Burundian Franc','FBu',0),
 ('SOS','Somali Shilling','Sh',2),
 ('SSP','South Sudanese Pound','£',2),
 ('CDF','Congolese Franc','FC',2),
 ('USD','US Dollar','$',2),
 ('GBP','Pound Sterling','£',2)
ON CONFLICT (currency_code) DO NOTHING;

INSERT INTO countries (country_code, name, phone_calling_code, default_currency) VALUES
 ('ET','Ethiopia','+251','ETB'),
 ('KE','Kenya','+254','KES'),
 ('UG','Uganda','+256','UGX'),
 ('TZ','Tanzania','+255','TZS'),
 ('RW','Rwanda','+250','RWF'),
 ('BI','Burundi','+257','BIF'),
 ('SO','Somalia','+252','SOS'),
 ('SS','South Sudan','+211','SSP'),
 ('CD','Democratic Republic of the Congo','+243','CDF')
ON CONFLICT (country_code) DO NOTHING;

INSERT INTO country_currencies (country_code, currency_code, is_primary, is_legal_tender, is_enabled) VALUES
 ('ET','ETB',true,true,true),('KE','KES',true,true,true),('UG','UGX',true,true,true),
 ('TZ','TZS',true,true,true),('RW','RWF',true,true,true),('BI','BIF',true,true,true),
 ('SO','SOS',true,true,true),('SS','SSP',true,true,true),('CD','CDF',true,true,true)
ON CONFLICT (country_code,currency_code) DO NOTHING;

INSERT INTO languages (language_code, name, native_name) VALUES
 ('en','English','English'),('am','Amharic','አማርኛ'),('ti','Tigrinya','ትግርኛ'),('om','Oromo','Afaan Oromoo'),
 ('sw','Swahili','Kiswahili'),('rw','Kinyarwanda','Ikinyarwanda'),('rn','Kirundi','Ikirundi'),
 ('so','Somali','Soomaali'),('ar','Arabic','العربية'),('fr','French','Français'),('ln','Lingala','Lingála')
ON CONFLICT (language_code) DO NOTHING;

INSERT INTO country_locales (country_code,language_code,locale_code,is_default,is_available,translation_status,sort_order) VALUES
 ('ET','en','en-ET',true,true,'PARTIAL',1),('ET','am','am-ET',false,true,'PARTIAL',2),('ET','ti','ti-ET',false,true,'PLANNED',3),('ET','om','om-ET',false,true,'PLANNED',4),
 ('KE','en','en-KE',true,true,'PLANNED',1),('KE','sw','sw-KE',false,true,'PLANNED',2),
 ('UG','en','en-UG',true,true,'PLANNED',1),('UG','sw','sw-UG',false,true,'PLANNED',2),
 ('TZ','sw','sw-TZ',true,true,'PLANNED',1),('TZ','en','en-TZ',false,true,'PLANNED',2),
 ('RW','rw','rw-RW',true,true,'PLANNED',1),('RW','en','en-RW',false,true,'PLANNED',2),('RW','fr','fr-RW',false,true,'PLANNED',3),('RW','sw','sw-RW',false,true,'PLANNED',4),
 ('BI','rn','rn-BI',true,true,'PLANNED',1),('BI','fr','fr-BI',false,true,'PLANNED',2),
 ('SO','so','so-SO',true,true,'PLANNED',1),('SO','ar','ar-SO',false,true,'PLANNED',2),
 ('SS','en','en-SS',true,true,'PLANNED',1),('SS','ar','ar-SS',false,true,'PLANNED',2),
 ('CD','fr','fr-CD',true,true,'PLANNED',1),('CD','sw','sw-CD',false,true,'PLANNED',2),('CD','ln','ln-CD',false,true,'PLANNED',3)
ON CONFLICT (country_code,language_code) DO NOTHING;

INSERT INTO country_admin_area_levels (country_code,level_number,level_code,display_name,sort_order)
SELECT c.country_code, n.level_number, 'LEVEL_' || n.level_number::text, 'Administrative level ' || n.level_number::text, n.level_number
FROM countries c CROSS JOIN generate_series(1,6) AS n(level_number)
ON CONFLICT (country_code,level_number) DO NOTHING;

INSERT INTO country_calendar_systems (country_code,calendar_code,calendar_role,is_default,is_supported,configuration) VALUES
 ('ET','GREGORIAN','DISPLAY',false,true,'{}'::jsonb),
 ('ET','ETHIOPIAN','DISPLAY',true,true,'{"display_only":true}'::jsonb),
 ('ET','GREGORIAN','PUBLIC_HOLIDAY',true,true,'{}'::jsonb),
 ('KE','GREGORIAN','DISPLAY',true,true,'{}'::jsonb),('KE','GREGORIAN','PUBLIC_HOLIDAY',true,true,'{}'::jsonb),
 ('UG','GREGORIAN','DISPLAY',true,true,'{}'::jsonb),('UG','GREGORIAN','PUBLIC_HOLIDAY',true,true,'{}'::jsonb),
 ('TZ','GREGORIAN','DISPLAY',true,true,'{}'::jsonb),('TZ','GREGORIAN','PUBLIC_HOLIDAY',true,true,'{}'::jsonb)
ON CONFLICT (country_code,calendar_code,calendar_role) DO NOTHING;

INSERT INTO payment_methods (code, name, method_type, country_code, provider_code, is_online, is_cash, is_active) VALUES
 ('CASH','Cash','CASH',NULL,NULL,false,true,true),
 ('BANK_TRANSFER','Bank transfer','BANK_TRANSFER',NULL,NULL,true,false,true),
 ('TELEBIRR','Telebirr','MOBILE_MONEY','ET','TELEBIRR',true,false,true),
 ('CBE_BIRR','CBE Birr','MOBILE_MONEY','ET','CBE_BIRR',true,false,true),
 ('CBE','Commercial Bank of Ethiopia','BANK_TRANSFER','ET','CBE',true,false,true),
 ('CHAPA','Chapa','PAYMENT_GATEWAY','ET','CHAPA',true,false,true),
 ('ARIFPAY','ArifPay','PAYMENT_GATEWAY','ET','ARIFPAY',true,false,true),
 ('MPESA','M-Pesa','MOBILE_MONEY',NULL,'MPESA',true,false,true),
 ('CARD','Card','CARD',NULL,NULL,true,false,true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO country_payment_methods (country_code,payment_method_id,provider_code,availability_status,requires_merchant_onboarding) 
SELECT 'ET',pm.id,pm.provider_code,
       CASE WHEN pm.code='CASH' THEN 'AVAILABLE' ELSE 'CONFIGURATION_REQUIRED' END,
       CASE WHEN pm.code IN ('TELEBIRR','CBE_BIRR','CBE','CHAPA','ARIFPAY') THEN true ELSE false END
FROM payment_methods pm WHERE pm.code IN ('CASH','BANK_TRANSFER','TELEBIRR','CBE_BIRR','CBE','CHAPA','ARIFPAY')
ON CONFLICT (country_code,payment_method_id) DO NOTHING;

-- M-Pesa is represented as a planned catalogue capability in Kenya; do not enable it until provider onboarding and live capability verification are complete.
INSERT INTO country_payment_methods (country_code,payment_method_id,provider_code,availability_status,requires_merchant_onboarding)
SELECT 'KE',pm.id,'MPESA','PLANNED',true FROM payment_methods pm WHERE pm.code='MPESA'
ON CONFLICT (country_code,payment_method_id) DO NOTHING;

-- Provider capability rows are deliberately not seeded as AVAILABLE. Verify contracts, settlement currencies, refunds, webhooks and local onboarding before activation.
-- Deployment notes:
-- 1) Create a dedicated non-owner application role (no SUPERUSER, no BYPASSRLS).
-- 2) Grant required table/sequence privileges and EXECUTE on app_set_tenant_context(uuid) only to that role.
-- 3) On onboarding, set app.current_user_id and call create_business_for_current_user(..., p_home_country_code, p_functional_currency); it creates the business, OWNER membership, initial market and primary legal entity atomically.
-- 4) On business signup, call create_business_for_current_user(legal_name, display_name, slug, home_country_code, functional_currency); then create branches with an explicit legal entity, ISO country code, enabled currency and IANA timezone.
-- 5) In every tenant transaction, set app.current_user_id from the verified authenticated identity, then call app_set_tenant_context(:business_id).
-- 6) The app.current_user_id custom setting is trusted middleware context, not cryptographic proof; parameterise SQL and prevent arbitrary SQL execution.
-- 7) Run expire_due_schedule_reservations(:business_id) before slot calculations/booking commits and from a scheduled worker.
-- 8) Route anonymous marketplace traffic through curated public projections/API endpoints; do not grant raw tenant-table access.




-- Tenant-integrity hardening: replace id-only foreign keys between business-owned tables
-- with composite (business_id, id) references. This prevents cross-tenant links even on INSERT.
ALTER TABLE branch_hours DROP CONSTRAINT IF EXISTS branch_hours_branch_id_fkey;
ALTER TABLE branch_hours ADD CONSTRAINT fk_tenant_branch_hours_branch_id_8c94ef FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id) ON DELETE CASCADE;
ALTER TABLE branch_schedule_exceptions DROP CONSTRAINT IF EXISTS branch_schedule_exceptions_branch_id_fkey;
ALTER TABLE branch_schedule_exceptions ADD CONSTRAINT fk_tenant_branch_schedule_exceptions_branch_id_7399ef FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id) ON DELETE CASCADE;
ALTER TABLE employees DROP CONSTRAINT IF EXISTS employees_membership_id_fkey;
ALTER TABLE employees ADD CONSTRAINT fk_tenant_employees_membership_dcf97d FOREIGN KEY (business_id, membership_id) REFERENCES business_memberships(business_id, id);
ALTER TABLE employee_branch_assignments DROP CONSTRAINT IF EXISTS employee_branch_assignments_employee_id_fkey;
ALTER TABLE employee_branch_assignments ADD CONSTRAINT fk_tenant_employee_branch_assignments_employee_i_1ed0f5 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id) ON DELETE CASCADE;
ALTER TABLE employee_branch_assignments DROP CONSTRAINT IF EXISTS employee_branch_assignments_branch_id_fkey;
ALTER TABLE employee_branch_assignments ADD CONSTRAINT fk_tenant_employee_branch_assignments_branch_id_149e5b FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id) ON DELETE CASCADE;
ALTER TABLE employee_working_hours DROP CONSTRAINT IF EXISTS employee_working_hours_employee_id_fkey;
ALTER TABLE employee_working_hours ADD CONSTRAINT fk_tenant_employee_working_hours_employee_i_a1425d FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id) ON DELETE CASCADE;
ALTER TABLE employee_working_hours DROP CONSTRAINT IF EXISTS employee_working_hours_branch_id_fkey;
ALTER TABLE employee_working_hours ADD CONSTRAINT fk_tenant_employee_working_hours_branch_id_bcc2fb FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE employee_shifts DROP CONSTRAINT IF EXISTS employee_shifts_employee_id_fkey;
ALTER TABLE employee_shifts ADD CONSTRAINT fk_tenant_employee_shifts_employee_i_826c4c FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE employee_shifts DROP CONSTRAINT IF EXISTS employee_shifts_branch_id_fkey;
ALTER TABLE employee_shifts ADD CONSTRAINT fk_tenant_employee_shifts_branch_id_e004cf FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE employee_schedule_exceptions DROP CONSTRAINT IF EXISTS employee_schedule_exceptions_employee_id_fkey;
ALTER TABLE employee_schedule_exceptions ADD CONSTRAINT fk_tenant_employee_schedule_exceptions_employee_i_b4f3e2 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE employee_schedule_exceptions DROP CONSTRAINT IF EXISTS employee_schedule_exceptions_branch_id_fkey;
ALTER TABLE employee_schedule_exceptions ADD CONSTRAINT fk_tenant_employee_schedule_exceptions_branch_id_52191b FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE employee_time_entries DROP CONSTRAINT IF EXISTS employee_time_entries_employee_id_fkey;
ALTER TABLE employee_time_entries ADD CONSTRAINT fk_tenant_employee_time_entries_employee_i_39b655 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE employee_time_entries DROP CONSTRAINT IF EXISTS employee_time_entries_branch_id_fkey;
ALTER TABLE employee_time_entries ADD CONSTRAINT fk_tenant_employee_time_entries_branch_id_9cac8f FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE employee_time_entries DROP CONSTRAINT IF EXISTS employee_time_entries_approved_by_employee_id_fkey;
ALTER TABLE employee_time_entries ADD CONSTRAINT fk_tenant_employee_time_entries_approved_b_365255 FOREIGN KEY (business_id, approved_by_employee_id) REFERENCES employees(business_id, id);
ALTER TABLE employee_compensation DROP CONSTRAINT IF EXISTS employee_compensation_employee_id_fkey;
ALTER TABLE employee_compensation ADD CONSTRAINT fk_tenant_employee_compensation_employee_i_62e4cf FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE team_targets DROP CONSTRAINT IF EXISTS team_targets_branch_id_fkey;
ALTER TABLE team_targets ADD CONSTRAINT fk_tenant_team_targets_branch_id_2c6f0e FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE team_targets DROP CONSTRAINT IF EXISTS team_targets_employee_id_fkey;
ALTER TABLE team_targets ADD CONSTRAINT fk_tenant_team_targets_employee_i_d8f460 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE team_target_results DROP CONSTRAINT IF EXISTS team_target_results_team_target_id_fkey;
ALTER TABLE team_target_results ADD CONSTRAINT fk_tenant_team_target_results_team_targe_c8303a FOREIGN KEY (business_id, team_target_id) REFERENCES team_targets(business_id, id);
ALTER TABLE team_target_results DROP CONSTRAINT IF EXISTS team_target_results_employee_id_fkey;
ALTER TABLE team_target_results ADD CONSTRAINT fk_tenant_team_target_results_employee_i_c26bf1 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE commission_rules DROP CONSTRAINT IF EXISTS commission_rules_employee_id_fkey;
ALTER TABLE commission_rules ADD CONSTRAINT fk_tenant_commission_rules_employee_i_0bfc7b FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE commission_entries DROP CONSTRAINT IF EXISTS commission_entries_employee_id_fkey;
ALTER TABLE commission_entries ADD CONSTRAINT fk_tenant_commission_entries_employee_i_5b23ca FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE commission_entries DROP CONSTRAINT IF EXISTS commission_entries_commission_rule_id_fkey;
ALTER TABLE commission_entries ADD CONSTRAINT fk_tenant_commission_entries_commission_e7d4f9 FOREIGN KEY (business_id, commission_rule_id) REFERENCES commission_rules(business_id, id);
ALTER TABLE service_variants DROP CONSTRAINT IF EXISTS service_variants_service_id_fkey;
ALTER TABLE service_variants ADD CONSTRAINT fk_tenant_service_variants_service_id_0232e9 FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id) ON DELETE CASCADE;
ALTER TABLE employee_services DROP CONSTRAINT IF EXISTS employee_services_employee_id_fkey;
ALTER TABLE employee_services ADD CONSTRAINT fk_tenant_employee_services_employee_i_da7730 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id) ON DELETE CASCADE;
ALTER TABLE employee_services DROP CONSTRAINT IF EXISTS employee_services_service_id_fkey;
ALTER TABLE employee_services ADD CONSTRAINT fk_tenant_employee_services_service_id_b790b6 FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE employee_services DROP CONSTRAINT IF EXISTS employee_services_service_variant_id_fkey;
ALTER TABLE employee_services ADD CONSTRAINT fk_tenant_employee_services_service_va_3e149b FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE branch_services DROP CONSTRAINT IF EXISTS branch_services_branch_id_fkey;
ALTER TABLE branch_services ADD CONSTRAINT fk_tenant_branch_services_branch_id_209925 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id) ON DELETE CASCADE;
ALTER TABLE branch_services DROP CONSTRAINT IF EXISTS branch_services_service_id_fkey;
ALTER TABLE branch_services ADD CONSTRAINT fk_tenant_branch_services_service_id_dfa0da FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE service_prices DROP CONSTRAINT IF EXISTS service_prices_service_variant_id_fkey;
ALTER TABLE service_prices ADD CONSTRAINT fk_tenant_service_prices_service_va_ce8b3a FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE service_prices DROP CONSTRAINT IF EXISTS service_prices_branch_id_fkey;
ALTER TABLE service_prices ADD CONSTRAINT fk_tenant_service_prices_branch_id_0fb74c FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE service_prices DROP CONSTRAINT IF EXISTS service_prices_employee_id_fkey;
ALTER TABLE service_prices ADD CONSTRAINT fk_tenant_service_prices_employee_i_bb8165 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE service_addons DROP CONSTRAINT IF EXISTS service_addons_service_id_fkey;
ALTER TABLE service_addons ADD CONSTRAINT fk_tenant_service_addons_service_id_5bd05e FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE service_variant_addons DROP CONSTRAINT IF EXISTS service_variant_addons_service_variant_id_fkey;
ALTER TABLE service_variant_addons ADD CONSTRAINT fk_tenant_service_variant_addons_service_va_10b592 FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE service_variant_addons DROP CONSTRAINT IF EXISTS service_variant_addons_service_addon_id_fkey;
ALTER TABLE service_variant_addons ADD CONSTRAINT fk_tenant_service_variant_addons_service_ad_b8dfca FOREIGN KEY (business_id, service_addon_id) REFERENCES service_addons(business_id, id);
ALTER TABLE service_addon_prices DROP CONSTRAINT IF EXISTS service_addon_prices_service_addon_id_fkey;
ALTER TABLE service_addon_prices ADD CONSTRAINT fk_tenant_service_addon_prices_service_ad_d1fdcf FOREIGN KEY (business_id, service_addon_id) REFERENCES service_addons(business_id, id);
ALTER TABLE service_addon_prices DROP CONSTRAINT IF EXISTS service_addon_prices_branch_id_fkey;
ALTER TABLE service_addon_prices ADD CONSTRAINT fk_tenant_service_addon_prices_branch_id_5c5973 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE service_addon_prices DROP CONSTRAINT IF EXISTS service_addon_prices_employee_id_fkey;
ALTER TABLE service_addon_prices ADD CONSTRAINT fk_tenant_service_addon_prices_employee_i_a7b9b3 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE service_pricing_rules DROP CONSTRAINT IF EXISTS service_pricing_rules_service_variant_id_fkey;
ALTER TABLE service_pricing_rules ADD CONSTRAINT fk_tenant_service_pricing_rules_service_va_cad5cd FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE service_media DROP CONSTRAINT IF EXISTS service_media_service_id_fkey;
ALTER TABLE service_media ADD CONSTRAINT fk_tenant_service_media_service_id_953ebb FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id) ON DELETE CASCADE;
ALTER TABLE resources DROP CONSTRAINT IF EXISTS resources_branch_id_fkey;
ALTER TABLE resources ADD CONSTRAINT fk_tenant_resources_branch_id_ff61e4 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE service_resource_requirements DROP CONSTRAINT IF EXISTS service_resource_requirements_service_id_fkey;
ALTER TABLE service_resource_requirements ADD CONSTRAINT fk_tenant_service_resource_requirements_service_id_76184e FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE service_resource_requirements DROP CONSTRAINT IF EXISTS service_resource_requirements_service_variant_id_fkey;
ALTER TABLE service_resource_requirements ADD CONSTRAINT fk_tenant_service_resource_requirements_service_va_98d0cb FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_branch_id_fkey;
ALTER TABLE bookings ADD CONSTRAINT fk_tenant_bookings_branch_id_72405f FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE booking_items DROP CONSTRAINT IF EXISTS booking_items_booking_id_fkey;
ALTER TABLE booking_items ADD CONSTRAINT fk_tenant_booking_items_booking_id_f1db36 FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_items DROP CONSTRAINT IF EXISTS booking_items_service_id_fkey;
ALTER TABLE booking_items ADD CONSTRAINT fk_tenant_booking_items_service_id_f403ca FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE booking_items DROP CONSTRAINT IF EXISTS booking_items_service_variant_id_fkey;
ALTER TABLE booking_items ADD CONSTRAINT fk_tenant_booking_items_service_va_a235cc FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE booking_items DROP CONSTRAINT IF EXISTS booking_items_employee_id_fkey;
ALTER TABLE booking_items ADD CONSTRAINT fk_tenant_booking_items_employee_i_351fac FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE booking_item_addons DROP CONSTRAINT IF EXISTS booking_item_addons_booking_item_id_fkey;
ALTER TABLE booking_item_addons ADD CONSTRAINT fk_tenant_booking_item_addons_booking_it_025d25 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_item_addons DROP CONSTRAINT IF EXISTS booking_item_addons_service_addon_id_fkey;
ALTER TABLE booking_item_addons ADD CONSTRAINT fk_tenant_booking_item_addons_service_ad_8f8fba FOREIGN KEY (business_id, service_addon_id) REFERENCES service_addons(business_id, id);
ALTER TABLE booking_item_resources DROP CONSTRAINT IF EXISTS booking_item_resources_booking_item_id_fkey;
ALTER TABLE booking_item_resources ADD CONSTRAINT fk_tenant_booking_item_resources_booking_it_a00f7c FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_item_resources DROP CONSTRAINT IF EXISTS booking_item_resources_resource_id_fkey;
ALTER TABLE booking_item_resources ADD CONSTRAINT fk_tenant_booking_item_resources_resource_i_484a14 FOREIGN KEY (business_id, resource_id) REFERENCES resources(business_id, id);
ALTER TABLE booking_status_history DROP CONSTRAINT IF EXISTS booking_status_history_booking_id_fkey;
ALTER TABLE booking_status_history ADD CONSTRAINT fk_tenant_booking_status_history_booking_id_4b66f6 FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_holds DROP CONSTRAINT IF EXISTS booking_holds_branch_id_fkey;
ALTER TABLE booking_holds ADD CONSTRAINT fk_tenant_booking_holds_branch_id_560c36 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE booking_holds DROP CONSTRAINT IF EXISTS booking_holds_employee_id_fkey;
ALTER TABLE booking_holds ADD CONSTRAINT fk_tenant_booking_holds_employee_i_6000e6 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE booking_waitlists DROP CONSTRAINT IF EXISTS booking_waitlists_branch_id_fkey;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_tenant_booking_waitlists_branch_id_523be6 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE booking_waitlists DROP CONSTRAINT IF EXISTS booking_waitlists_service_id_fkey;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_tenant_booking_waitlists_service_id_cd5582 FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE booking_waitlists DROP CONSTRAINT IF EXISTS booking_waitlists_service_variant_id_fkey;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_tenant_booking_waitlists_service_va_95a2be FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE booking_waitlists DROP CONSTRAINT IF EXISTS booking_waitlists_employee_id_fkey;
ALTER TABLE booking_waitlists ADD CONSTRAINT fk_tenant_booking_waitlists_employee_i_3d71ef FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE booking_waitlist_offers DROP CONSTRAINT IF EXISTS booking_waitlist_offers_waitlist_id_fkey;
ALTER TABLE booking_waitlist_offers ADD CONSTRAINT fk_tenant_booking_waitlist_offers_waitlist_i_f07e92 FOREIGN KEY (business_id, waitlist_id) REFERENCES booking_waitlists(business_id, id) ON DELETE CASCADE;
ALTER TABLE calendar_blocks DROP CONSTRAINT IF EXISTS calendar_blocks_branch_id_fkey;
ALTER TABLE calendar_blocks ADD CONSTRAINT fk_tenant_calendar_blocks_branch_id_01954d FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE calendar_blocks DROP CONSTRAINT IF EXISTS calendar_blocks_employee_id_fkey;
ALTER TABLE calendar_blocks ADD CONSTRAINT fk_tenant_calendar_blocks_employee_i_4fa0a1 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE calendar_blocks DROP CONSTRAINT IF EXISTS calendar_blocks_resource_id_fkey;
ALTER TABLE calendar_blocks ADD CONSTRAINT fk_tenant_calendar_blocks_resource_i_f20aa0 FOREIGN KEY (business_id, resource_id) REFERENCES resources(business_id, id);
ALTER TABLE class_sessions DROP CONSTRAINT IF EXISTS class_sessions_branch_id_fkey;
ALTER TABLE class_sessions ADD CONSTRAINT fk_tenant_class_sessions_branch_id_12e3ce FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE class_sessions DROP CONSTRAINT IF EXISTS class_sessions_service_id_fkey;
ALTER TABLE class_sessions ADD CONSTRAINT fk_tenant_class_sessions_service_id_2bb1c0 FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE class_sessions DROP CONSTRAINT IF EXISTS class_sessions_employee_id_fkey;
ALTER TABLE class_sessions ADD CONSTRAINT fk_tenant_class_sessions_employee_i_07af1c FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE class_enrollments DROP CONSTRAINT IF EXISTS class_enrollments_class_session_id_fkey;
ALTER TABLE class_enrollments ADD CONSTRAINT fk_tenant_class_enrollments_class_sess_714c9d FOREIGN KEY (business_id, class_session_id) REFERENCES class_sessions(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_cancellations DROP CONSTRAINT IF EXISTS booking_cancellations_booking_id_fkey;
ALTER TABLE booking_cancellations ADD CONSTRAINT fk_tenant_booking_cancellations_booking_id_57d176 FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id);
ALTER TABLE booking_payment_policies DROP CONSTRAINT IF EXISTS booking_payment_policies_branch_id_fkey;
ALTER TABLE booking_payment_policies ADD CONSTRAINT fk_tenant_booking_payment_policies_branch_id_75f17c FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE booking_payment_policies DROP CONSTRAINT IF EXISTS booking_payment_policies_service_id_fkey;
ALTER TABLE booking_payment_policies ADD CONSTRAINT fk_tenant_booking_payment_policies_service_id_bad21f FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE booking_payment_policies DROP CONSTRAINT IF EXISTS booking_payment_policies_service_variant_id_fkey;
ALTER TABLE booking_payment_policies ADD CONSTRAINT fk_tenant_booking_payment_policies_service_va_a39f1c FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE booking_item_payment_terms DROP CONSTRAINT IF EXISTS booking_item_payment_terms_booking_item_id_fkey;
ALTER TABLE booking_item_payment_terms ADD CONSTRAINT fk_tenant_booking_item_payment_terms_booking_it_af91d8 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id);
ALTER TABLE booking_item_payment_terms DROP CONSTRAINT IF EXISTS booking_item_payment_terms_policy_id_fkey;
ALTER TABLE booking_item_payment_terms ADD CONSTRAINT fk_tenant_booking_item_payment_terms_policy_id_9636ee FOREIGN KEY (business_id, policy_id) REFERENCES booking_payment_policies(business_id, id);
ALTER TABLE booking_fees DROP CONSTRAINT IF EXISTS booking_fees_booking_id_fkey;
ALTER TABLE booking_fees ADD CONSTRAINT fk_tenant_booking_fees_booking_id_3020db FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id);
ALTER TABLE booking_fees DROP CONSTRAINT IF EXISTS booking_fees_booking_item_id_fkey;
ALTER TABLE booking_fees ADD CONSTRAINT fk_tenant_booking_fees_booking_it_002061 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id);
ALTER TABLE customer_notes DROP CONSTRAINT IF EXISTS customer_notes_business_customer_id_fkey;
ALTER TABLE customer_notes ADD CONSTRAINT fk_tenant_customer_notes_business_c_bf25f2 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id) ON DELETE CASCADE;
ALTER TABLE customer_tag_assignments DROP CONSTRAINT IF EXISTS customer_tag_assignments_business_customer_id_fkey;
ALTER TABLE customer_tag_assignments ADD CONSTRAINT fk_tenant_customer_tag_assignments_business_c_0759e6 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id) ON DELETE CASCADE;
ALTER TABLE customer_tag_assignments DROP CONSTRAINT IF EXISTS customer_tag_assignments_customer_tag_id_fkey;
ALTER TABLE customer_tag_assignments ADD CONSTRAINT fk_tenant_customer_tag_assignments_customer_t_5b9412 FOREIGN KEY (business_id, customer_tag_id) REFERENCES customer_tags(business_id, id) ON DELETE CASCADE;
ALTER TABLE customer_documents DROP CONSTRAINT IF EXISTS customer_documents_business_customer_id_fkey;
ALTER TABLE customer_documents ADD CONSTRAINT fk_tenant_customer_documents_business_c_7721c9 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id) ON DELETE CASCADE;
ALTER TABLE customer_treatment_records DROP CONSTRAINT IF EXISTS customer_treatment_records_business_customer_id_fkey;
ALTER TABLE customer_treatment_records ADD CONSTRAINT fk_tenant_customer_treatment_records_business_c_e3e979 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE customer_treatment_records DROP CONSTRAINT IF EXISTS customer_treatment_records_booking_item_id_fkey;
ALTER TABLE customer_treatment_records ADD CONSTRAINT fk_tenant_customer_treatment_records_booking_it_657166 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id);
ALTER TABLE customer_treatment_records DROP CONSTRAINT IF EXISTS customer_treatment_records_employee_id_fkey;
ALTER TABLE customer_treatment_records ADD CONSTRAINT fk_tenant_customer_treatment_records_employee_i_57d3ac FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE patch_tests DROP CONSTRAINT IF EXISTS patch_tests_business_customer_id_fkey;
ALTER TABLE patch_tests ADD CONSTRAINT fk_tenant_patch_tests_business_c_bdb5ba FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE patch_tests DROP CONSTRAINT IF EXISTS patch_tests_service_id_fkey;
ALTER TABLE patch_tests ADD CONSTRAINT fk_tenant_patch_tests_service_id_aec4c0 FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE patch_tests DROP CONSTRAINT IF EXISTS patch_tests_performed_by_employee_id_fkey;
ALTER TABLE patch_tests ADD CONSTRAINT fk_tenant_patch_tests_performed__5cc44a FOREIGN KEY (business_id, performed_by_employee_id) REFERENCES employees(business_id, id);
ALTER TABLE customer_consents DROP CONSTRAINT IF EXISTS customer_consents_business_customer_id_fkey;
ALTER TABLE customer_consents ADD CONSTRAINT fk_tenant_customer_consents_business_c_e5006f FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE form_fields DROP CONSTRAINT IF EXISTS form_fields_form_template_id_fkey;
ALTER TABLE form_fields ADD CONSTRAINT fk_tenant_form_fields_form_templ_6e58c9 FOREIGN KEY (business_id, form_template_id) REFERENCES form_templates(business_id, id) ON DELETE CASCADE;
ALTER TABLE form_submissions DROP CONSTRAINT IF EXISTS form_submissions_form_template_id_fkey;
ALTER TABLE form_submissions ADD CONSTRAINT fk_tenant_form_submissions_form_templ_32c5d9 FOREIGN KEY (business_id, form_template_id) REFERENCES form_templates(business_id, id);
ALTER TABLE form_submissions DROP CONSTRAINT IF EXISTS form_submissions_business_customer_id_fkey;
ALTER TABLE form_submissions ADD CONSTRAINT fk_tenant_form_submissions_business_c_fb6146 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE form_submissions DROP CONSTRAINT IF EXISTS form_submissions_booking_id_fkey;
ALTER TABLE form_submissions ADD CONSTRAINT fk_tenant_form_submissions_booking_id_de6c1c FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id);
ALTER TABLE form_answers DROP CONSTRAINT IF EXISTS form_answers_form_submission_id_fkey;
ALTER TABLE form_answers ADD CONSTRAINT fk_tenant_form_answers_form_submi_1463bb FOREIGN KEY (business_id, form_submission_id) REFERENCES form_submissions(business_id, id) ON DELETE CASCADE;
ALTER TABLE form_answers DROP CONSTRAINT IF EXISTS form_answers_form_field_id_fkey;
ALTER TABLE form_answers ADD CONSTRAINT fk_tenant_form_answers_form_field_f7e514 FOREIGN KEY (business_id, form_field_id) REFERENCES form_fields(business_id, id);
ALTER TABLE marketplace_featured_listings DROP CONSTRAINT IF EXISTS marketplace_featured_listings_branch_id_fkey;
ALTER TABLE marketplace_featured_listings ADD CONSTRAINT fk_tenant_marketplace_featured_listings_branch_id_985979 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE reviews DROP CONSTRAINT IF EXISTS reviews_branch_id_fkey;
ALTER TABLE reviews ADD CONSTRAINT fk_tenant_reviews_branch_id_2260a2 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE reviews DROP CONSTRAINT IF EXISTS reviews_business_customer_id_fkey;
ALTER TABLE reviews ADD CONSTRAINT fk_tenant_reviews_business_c_fa3594 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE reviews DROP CONSTRAINT IF EXISTS reviews_booking_id_fkey;
ALTER TABLE reviews ADD CONSTRAINT fk_tenant_reviews_booking_id_c7baba FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id);
ALTER TABLE review_requests DROP CONSTRAINT IF EXISTS review_requests_business_customer_id_fkey;
ALTER TABLE review_requests ADD CONSTRAINT fk_tenant_review_requests_business_c_f4b946 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE review_requests DROP CONSTRAINT IF EXISTS review_requests_booking_id_fkey;
ALTER TABLE review_requests ADD CONSTRAINT fk_tenant_review_requests_booking_id_097e93 FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id);
ALTER TABLE review_requests DROP CONSTRAINT IF EXISTS review_requests_review_id_fkey;
ALTER TABLE review_requests ADD CONSTRAINT fk_tenant_review_requests_review_id_30996f FOREIGN KEY (business_id, review_id) REFERENCES reviews(business_id, id);
ALTER TABLE sales DROP CONSTRAINT IF EXISTS sales_branch_id_fkey;
ALTER TABLE sales ADD CONSTRAINT fk_tenant_sales_branch_id_61b733 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE sales DROP CONSTRAINT IF EXISTS sales_business_customer_id_fkey;
ALTER TABLE sales ADD CONSTRAINT fk_tenant_sales_business_c_b27a07 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE sale_items DROP CONSTRAINT IF EXISTS sale_items_sale_id_fkey;
ALTER TABLE sale_items ADD CONSTRAINT fk_tenant_sale_items_sale_id_e9918f FOREIGN KEY (business_id, sale_id) REFERENCES sales(business_id, id) ON DELETE CASCADE;
ALTER TABLE sale_items DROP CONSTRAINT IF EXISTS sale_items_service_id_fkey;
ALTER TABLE sale_items ADD CONSTRAINT fk_tenant_sale_items_service_id_791a8f FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE sale_items DROP CONSTRAINT IF EXISTS sale_items_booking_item_id_fkey;
ALTER TABLE sale_items ADD CONSTRAINT fk_tenant_sale_items_booking_it_89a0d5 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id);
ALTER TABLE sale_items DROP CONSTRAINT IF EXISTS sale_items_employee_id_fkey;
ALTER TABLE sale_items ADD CONSTRAINT fk_tenant_sale_items_employee_i_efad73 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE sale_tips DROP CONSTRAINT IF EXISTS sale_tips_sale_id_fkey;
ALTER TABLE sale_tips ADD CONSTRAINT fk_tenant_sale_tips_sale_id_002bc5 FOREIGN KEY (business_id, sale_id) REFERENCES sales(business_id, id) ON DELETE CASCADE;
ALTER TABLE sale_tips DROP CONSTRAINT IF EXISTS sale_tips_employee_id_fkey;
ALTER TABLE sale_tips ADD CONSTRAINT fk_tenant_sale_tips_employee_i_a50df4 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE receipts DROP CONSTRAINT IF EXISTS receipts_sale_id_fkey;
ALTER TABLE receipts ADD CONSTRAINT fk_tenant_receipts_sale_id_699874 FOREIGN KEY (business_id, sale_id) REFERENCES sales(business_id, id);
ALTER TABLE quotes DROP CONSTRAINT IF EXISTS quotes_branch_id_fkey;
ALTER TABLE quotes ADD CONSTRAINT fk_tenant_quotes_branch_id_123134 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE quotes DROP CONSTRAINT IF EXISTS quotes_business_customer_id_fkey;
ALTER TABLE quotes ADD CONSTRAINT fk_tenant_quotes_business_c_108fa2 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE quote_items DROP CONSTRAINT IF EXISTS quote_items_quote_id_fkey;
ALTER TABLE quote_items ADD CONSTRAINT fk_tenant_quote_items_quote_id_4b9f50 FOREIGN KEY (business_id, quote_id) REFERENCES quotes(business_id, id) ON DELETE CASCADE;
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_business_customer_id_fkey;
ALTER TABLE invoices ADD CONSTRAINT fk_tenant_invoices_business_c_b61617 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_sale_id_fkey;
ALTER TABLE invoices ADD CONSTRAINT fk_tenant_invoices_sale_id_de5fd4 FOREIGN KEY (business_id, sale_id) REFERENCES sales(business_id, id);
ALTER TABLE invoice_items DROP CONSTRAINT IF EXISTS invoice_items_invoice_id_fkey;
ALTER TABLE invoice_items ADD CONSTRAINT fk_tenant_invoice_items_invoice_id_a341d9 FOREIGN KEY (business_id, invoice_id) REFERENCES invoices(business_id, id) ON DELETE CASCADE;
ALTER TABLE branch_payment_methods DROP CONSTRAINT IF EXISTS branch_payment_methods_branch_id_fkey;
ALTER TABLE branch_payment_methods ADD CONSTRAINT fk_tenant_branch_payment_methods_branch_id_d6e910 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE branch_payment_methods DROP CONSTRAINT IF EXISTS branch_payment_methods_provider_account_id_fkey;
ALTER TABLE branch_payment_methods ADD CONSTRAINT fk_tenant_branch_payment_methods_provider_a_411902 FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts(business_id, id);
ALTER TABLE merchant_accounts DROP CONSTRAINT IF EXISTS merchant_accounts_branch_id_fkey;
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_tenant_merchant_accounts_branch_id_74df57 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE merchant_accounts DROP CONSTRAINT IF EXISTS merchant_accounts_employee_id_fkey;
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_tenant_merchant_accounts_employee_i_36aea7 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE merchant_accounts DROP CONSTRAINT IF EXISTS merchant_accounts_provider_account_id_fkey;
ALTER TABLE merchant_accounts ADD CONSTRAINT fk_tenant_merchant_accounts_provider_a_7fef0d FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts(business_id, id);
ALTER TABLE saved_payment_methods DROP CONSTRAINT IF EXISTS saved_payment_methods_business_customer_id_fkey;
ALTER TABLE saved_payment_methods ADD CONSTRAINT fk_tenant_saved_payment_methods_business_c_8f6bcc FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE saved_payment_methods DROP CONSTRAINT IF EXISTS saved_payment_methods_provider_account_id_fkey;
ALTER TABLE saved_payment_methods ADD CONSTRAINT fk_tenant_saved_payment_methods_provider_a_a20a75 FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts(business_id, id);
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_provider_account_id_fkey;
ALTER TABLE payments ADD CONSTRAINT fk_tenant_payments_provider_a_0ddede FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts(business_id, id);
ALTER TABLE payment_attempts DROP CONSTRAINT IF EXISTS payment_attempts_payment_id_fkey;
ALTER TABLE payment_attempts ADD CONSTRAINT fk_tenant_payment_attempts_payment_id_293251 FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id) ON DELETE CASCADE;
ALTER TABLE payment_events DROP CONSTRAINT IF EXISTS payment_events_payment_id_fkey;
ALTER TABLE payment_events ADD CONSTRAINT fk_tenant_payment_events_payment_id_878a18 FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE payment_authorizations DROP CONSTRAINT IF EXISTS payment_authorizations_payment_id_fkey;
ALTER TABLE payment_authorizations ADD CONSTRAINT fk_tenant_payment_authorizations_payment_id_198da9 FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE payment_authorizations DROP CONSTRAINT IF EXISTS payment_authorizations_saved_payment_method_id_fkey;
ALTER TABLE payment_authorizations ADD CONSTRAINT fk_tenant_payment_authorizations_saved_paym_72df02 FOREIGN KEY (business_id, saved_payment_method_id) REFERENCES saved_payment_methods(business_id, id);
ALTER TABLE payment_allocations DROP CONSTRAINT IF EXISTS payment_allocations_payment_id_fkey;
ALTER TABLE payment_allocations ADD CONSTRAINT fk_tenant_payment_allocations_payment_id_8b537a FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE payment_allocations DROP CONSTRAINT IF EXISTS payment_allocations_payment_obligation_id_fkey;
ALTER TABLE payment_allocations ADD CONSTRAINT fk_tenant_payment_allocations_payment_ob_ecddcb FOREIGN KEY (business_id, payment_obligation_id) REFERENCES payment_obligations(business_id, id);
ALTER TABLE payment_links DROP CONSTRAINT IF EXISTS payment_links_payment_obligation_id_fkey;
ALTER TABLE payment_links ADD CONSTRAINT fk_tenant_payment_links_payment_ob_66acf0 FOREIGN KEY (business_id, payment_obligation_id) REFERENCES payment_obligations(business_id, id);
ALTER TABLE refunds DROP CONSTRAINT IF EXISTS refunds_payment_id_fkey;
ALTER TABLE refunds ADD CONSTRAINT fk_tenant_refunds_payment_id_c3b83f FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE payment_terminals DROP CONSTRAINT IF EXISTS payment_terminals_branch_id_fkey;
ALTER TABLE payment_terminals ADD CONSTRAINT fk_tenant_payment_terminals_branch_id_d63289 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE payment_terminals DROP CONSTRAINT IF EXISTS payment_terminals_provider_account_id_fkey;
ALTER TABLE payment_terminals ADD CONSTRAINT fk_tenant_payment_terminals_provider_a_341dfa FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts(business_id, id);
ALTER TABLE payment_reconciliations DROP CONSTRAINT IF EXISTS payment_reconciliations_provider_account_id_fkey;
ALTER TABLE payment_reconciliations ADD CONSTRAINT fk_tenant_payment_reconciliations_provider_a_9e1aed FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts(business_id, id);
ALTER TABLE payment_reconciliation_items DROP CONSTRAINT IF EXISTS payment_reconciliation_items_reconciliation_id_fkey;
ALTER TABLE payment_reconciliation_items ADD CONSTRAINT fk_tenant_payment_reconciliation_items_reconcilia_2e686d FOREIGN KEY (business_id, reconciliation_id) REFERENCES payment_reconciliations(business_id, id) ON DELETE CASCADE;
ALTER TABLE payment_reconciliation_items DROP CONSTRAINT IF EXISTS payment_reconciliation_items_payment_id_fkey;
ALTER TABLE payment_reconciliation_items ADD CONSTRAINT fk_tenant_payment_reconciliation_items_payment_id_4d7356 FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE settlement_batches DROP CONSTRAINT IF EXISTS settlement_batches_provider_account_id_fkey;
ALTER TABLE settlement_batches ADD CONSTRAINT fk_tenant_settlement_batches_provider_a_11a589 FOREIGN KEY (business_id, provider_account_id) REFERENCES payment_provider_accounts(business_id, id);
ALTER TABLE settlement_items DROP CONSTRAINT IF EXISTS settlement_items_settlement_batch_id_fkey;
ALTER TABLE settlement_items ADD CONSTRAINT fk_tenant_settlement_items_settlement_a57896 FOREIGN KEY (business_id, settlement_batch_id) REFERENCES settlement_batches(business_id, id) ON DELETE CASCADE;
ALTER TABLE settlement_items DROP CONSTRAINT IF EXISTS settlement_items_payment_id_fkey;
ALTER TABLE settlement_items ADD CONSTRAINT fk_tenant_settlement_items_payment_id_529abf FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_wallet_id_fkey;
ALTER TABLE wallet_transactions ADD CONSTRAINT fk_tenant_wallet_transactions_wallet_id_0d7c99 FOREIGN KEY (business_id, wallet_id) REFERENCES business_wallets(business_id, id);
ALTER TABLE payouts DROP CONSTRAINT IF EXISTS payouts_merchant_account_id_fkey;
ALTER TABLE payouts ADD CONSTRAINT fk_tenant_payouts_merchant_a_58b989 FOREIGN KEY (business_id, merchant_account_id) REFERENCES merchant_accounts(business_id, id);
ALTER TABLE payout_items DROP CONSTRAINT IF EXISTS payout_items_payout_id_fkey;
ALTER TABLE payout_items ADD CONSTRAINT fk_tenant_payout_items_payout_id_097135 FOREIGN KEY (business_id, payout_id) REFERENCES payouts(business_id, id) ON DELETE CASCADE;
ALTER TABLE payment_disputes DROP CONSTRAINT IF EXISTS payment_disputes_payment_id_fkey;
ALTER TABLE payment_disputes ADD CONSTRAINT fk_tenant_payment_disputes_payment_id_6a4f98 FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE cash_registers DROP CONSTRAINT IF EXISTS cash_registers_branch_id_fkey;
ALTER TABLE cash_registers ADD CONSTRAINT fk_tenant_cash_registers_branch_id_0bccb8 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE cash_register_sessions DROP CONSTRAINT IF EXISTS cash_register_sessions_cash_register_id_fkey;
ALTER TABLE cash_register_sessions ADD CONSTRAINT fk_tenant_cash_register_sessions_cash_regis_960037 FOREIGN KEY (business_id, cash_register_id) REFERENCES cash_registers(business_id, id);
ALTER TABLE cash_movements DROP CONSTRAINT IF EXISTS cash_movements_cash_register_session_id_fkey;
ALTER TABLE cash_movements ADD CONSTRAINT fk_tenant_cash_movements_cash_regis_62d390 FOREIGN KEY (business_id, cash_register_session_id) REFERENCES cash_register_sessions(business_id, id);
ALTER TABLE financial_accounts DROP CONSTRAINT IF EXISTS financial_accounts_parent_account_id_fkey;
ALTER TABLE financial_accounts ADD CONSTRAINT fk_tenant_financial_accounts_parent_acc_cb83c5 FOREIGN KEY (business_id, parent_account_id) REFERENCES financial_accounts(business_id, id);
ALTER TABLE financial_ledger_entries DROP CONSTRAINT IF EXISTS financial_ledger_entries_journal_id_fkey;
ALTER TABLE financial_ledger_entries ADD CONSTRAINT fk_tenant_financial_ledger_entries_journal_id_6ad865 FOREIGN KEY (business_id, journal_id) REFERENCES financial_journals(business_id, id) ON DELETE CASCADE;
ALTER TABLE financial_ledger_entries DROP CONSTRAINT IF EXISTS financial_ledger_entries_account_id_fkey;
ALTER TABLE financial_ledger_entries ADD CONSTRAINT fk_tenant_financial_ledger_entries_account_id_883267 FOREIGN KEY (business_id, account_id) REFERENCES financial_accounts(business_id, id);
ALTER TABLE product_variants DROP CONSTRAINT IF EXISTS product_variants_product_id_fkey;
ALTER TABLE product_variants ADD CONSTRAINT fk_tenant_product_variants_product_id_32783c FOREIGN KEY (business_id, product_id) REFERENCES products(business_id, id) ON DELETE CASCADE;
ALTER TABLE product_media DROP CONSTRAINT IF EXISTS product_media_product_id_fkey;
ALTER TABLE product_media ADD CONSTRAINT fk_tenant_product_media_product_id_5f0a76 FOREIGN KEY (business_id, product_id) REFERENCES products(business_id, id) ON DELETE CASCADE;
ALTER TABLE product_prices DROP CONSTRAINT IF EXISTS product_prices_product_variant_id_fkey;
ALTER TABLE product_prices ADD CONSTRAINT fk_tenant_product_prices_product_va_559cde FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants(business_id, id);
ALTER TABLE product_prices DROP CONSTRAINT IF EXISTS product_prices_branch_id_fkey;
ALTER TABLE product_prices ADD CONSTRAINT fk_tenant_product_prices_branch_id_d4ae53 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE branch_inventory DROP CONSTRAINT IF EXISTS branch_inventory_branch_id_fkey;
ALTER TABLE branch_inventory ADD CONSTRAINT fk_tenant_branch_inventory_branch_id_93fbd3 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE branch_inventory DROP CONSTRAINT IF EXISTS branch_inventory_product_variant_id_fkey;
ALTER TABLE branch_inventory ADD CONSTRAINT fk_tenant_branch_inventory_product_va_9ab438 FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants(business_id, id);
ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS inventory_movements_branch_id_fkey;
ALTER TABLE inventory_movements ADD CONSTRAINT fk_tenant_inventory_movements_branch_id_7230a8 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS inventory_movements_product_variant_id_fkey;
ALTER TABLE inventory_movements ADD CONSTRAINT fk_tenant_inventory_movements_product_va_b70ed1 FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants(business_id, id);
ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS purchase_orders_branch_id_fkey;
ALTER TABLE purchase_orders ADD CONSTRAINT fk_tenant_purchase_orders_branch_id_99698f FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS purchase_orders_supplier_id_fkey;
ALTER TABLE purchase_orders ADD CONSTRAINT fk_tenant_purchase_orders_supplier_i_b9bca2 FOREIGN KEY (business_id, supplier_id) REFERENCES suppliers(business_id, id);
ALTER TABLE purchase_order_items DROP CONSTRAINT IF EXISTS purchase_order_items_purchase_order_id_fkey;
ALTER TABLE purchase_order_items ADD CONSTRAINT fk_tenant_purchase_order_items_purchase_o_f9020d FOREIGN KEY (business_id, purchase_order_id) REFERENCES purchase_orders(business_id, id) ON DELETE CASCADE;
ALTER TABLE purchase_order_items DROP CONSTRAINT IF EXISTS purchase_order_items_product_variant_id_fkey;
ALTER TABLE purchase_order_items ADD CONSTRAINT fk_tenant_purchase_order_items_product_va_71274b FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants(business_id, id);
ALTER TABLE goods_receipts DROP CONSTRAINT IF EXISTS goods_receipts_purchase_order_id_fkey;
ALTER TABLE goods_receipts ADD CONSTRAINT fk_tenant_goods_receipts_purchase_o_04beb6 FOREIGN KEY (business_id, purchase_order_id) REFERENCES purchase_orders(business_id, id);
ALTER TABLE goods_receipts DROP CONSTRAINT IF EXISTS goods_receipts_branch_id_fkey;
ALTER TABLE goods_receipts ADD CONSTRAINT fk_tenant_goods_receipts_branch_id_677e6e FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE goods_receipts DROP CONSTRAINT IF EXISTS goods_receipts_supplier_id_fkey;
ALTER TABLE goods_receipts ADD CONSTRAINT fk_tenant_goods_receipts_supplier_i_2a49b0 FOREIGN KEY (business_id, supplier_id) REFERENCES suppliers(business_id, id);
ALTER TABLE goods_receipt_items DROP CONSTRAINT IF EXISTS goods_receipt_items_goods_receipt_id_fkey;
ALTER TABLE goods_receipt_items ADD CONSTRAINT fk_tenant_goods_receipt_items_goods_rece_d88382 FOREIGN KEY (business_id, goods_receipt_id) REFERENCES goods_receipts(business_id, id) ON DELETE CASCADE;
ALTER TABLE goods_receipt_items DROP CONSTRAINT IF EXISTS goods_receipt_items_purchase_order_item_id_fkey;
ALTER TABLE goods_receipt_items ADD CONSTRAINT fk_tenant_goods_receipt_items_purchase_o_86fd06 FOREIGN KEY (business_id, purchase_order_item_id) REFERENCES purchase_order_items(business_id, id);
ALTER TABLE goods_receipt_items DROP CONSTRAINT IF EXISTS goods_receipt_items_product_variant_id_fkey;
ALTER TABLE goods_receipt_items ADD CONSTRAINT fk_tenant_goods_receipt_items_product_va_aa8d36 FOREIGN KEY (business_id, product_variant_id) REFERENCES product_variants(business_id, id);
ALTER TABLE delivery_zones DROP CONSTRAINT IF EXISTS delivery_zones_delivery_method_id_fkey;
ALTER TABLE delivery_zones ADD CONSTRAINT fk_tenant_delivery_zones_delivery_m_63f59c FOREIGN KEY (business_id, delivery_method_id) REFERENCES delivery_methods(business_id, id);
ALTER TABLE sale_fulfilments DROP CONSTRAINT IF EXISTS sale_fulfilments_sale_id_fkey;
ALTER TABLE sale_fulfilments ADD CONSTRAINT fk_tenant_sale_fulfilments_sale_id_f856a8 FOREIGN KEY (business_id, sale_id) REFERENCES sales(business_id, id);
ALTER TABLE sale_fulfilments DROP CONSTRAINT IF EXISTS sale_fulfilments_delivery_method_id_fkey;
ALTER TABLE sale_fulfilments ADD CONSTRAINT fk_tenant_sale_fulfilments_delivery_m_76f233 FOREIGN KEY (business_id, delivery_method_id) REFERENCES delivery_methods(business_id, id);
ALTER TABLE sale_fulfilments DROP CONSTRAINT IF EXISTS sale_fulfilments_delivery_zone_id_fkey;
ALTER TABLE sale_fulfilments ADD CONSTRAINT fk_tenant_sale_fulfilments_delivery_z_a7177d FOREIGN KEY (business_id, delivery_zone_id) REFERENCES delivery_zones(business_id, id);
ALTER TABLE subscription_history DROP CONSTRAINT IF EXISTS subscription_history_business_subscription_id_fkey;
ALTER TABLE subscription_history ADD CONSTRAINT fk_tenant_subscription_history_business_s_45dc36 FOREIGN KEY (business_id, business_subscription_id) REFERENCES business_subscriptions(business_id, id);
ALTER TABLE billing_invoices DROP CONSTRAINT IF EXISTS billing_invoices_business_subscription_id_fkey;
ALTER TABLE billing_invoices ADD CONSTRAINT fk_tenant_billing_invoices_business_s_f657d4 FOREIGN KEY (business_id, business_subscription_id) REFERENCES business_subscriptions(business_id, id);
ALTER TABLE billing_invoice_items DROP CONSTRAINT IF EXISTS billing_invoice_items_billing_invoice_id_fkey;
ALTER TABLE billing_invoice_items ADD CONSTRAINT fk_tenant_billing_invoice_items_billing_in_4d20cf FOREIGN KEY (business_id, billing_invoice_id) REFERENCES billing_invoices(business_id, id) ON DELETE CASCADE;
ALTER TABLE gift_cards DROP CONSTRAINT IF EXISTS gift_cards_purchased_by_customer_id_fkey;
ALTER TABLE gift_cards ADD CONSTRAINT fk_tenant_gift_cards_purchased__6dc348 FOREIGN KEY (business_id, purchased_by_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE gift_card_transactions DROP CONSTRAINT IF EXISTS gift_card_transactions_gift_card_id_fkey;
ALTER TABLE gift_card_transactions ADD CONSTRAINT fk_tenant_gift_card_transactions_gift_card__462c13 FOREIGN KEY (business_id, gift_card_id) REFERENCES gift_cards(business_id, id);
ALTER TABLE service_package_items DROP CONSTRAINT IF EXISTS service_package_items_service_package_id_fkey;
ALTER TABLE service_package_items ADD CONSTRAINT fk_tenant_service_package_items_service_pa_23cdbe FOREIGN KEY (business_id, service_package_id) REFERENCES service_packages(business_id, id) ON DELETE CASCADE;
ALTER TABLE service_package_items DROP CONSTRAINT IF EXISTS service_package_items_service_id_fkey;
ALTER TABLE service_package_items ADD CONSTRAINT fk_tenant_service_package_items_service_id_ad999a FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE service_package_items DROP CONSTRAINT IF EXISTS service_package_items_service_variant_id_fkey;
ALTER TABLE service_package_items ADD CONSTRAINT fk_tenant_service_package_items_service_va_558c5e FOREIGN KEY (business_id, service_variant_id) REFERENCES service_variants(business_id, id);
ALTER TABLE customer_package_balances DROP CONSTRAINT IF EXISTS customer_package_balances_business_customer_id_fkey;
ALTER TABLE customer_package_balances ADD CONSTRAINT fk_tenant_customer_package_balances_business_c_c2aa18 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE customer_package_balances DROP CONSTRAINT IF EXISTS customer_package_balances_service_package_id_fkey;
ALTER TABLE customer_package_balances ADD CONSTRAINT fk_tenant_customer_package_balances_service_pa_f76372 FOREIGN KEY (business_id, service_package_id) REFERENCES service_packages(business_id, id);
ALTER TABLE customer_package_balances DROP CONSTRAINT IF EXISTS customer_package_balances_purchase_payment_id_fkey;
ALTER TABLE customer_package_balances ADD CONSTRAINT fk_tenant_customer_package_balances_purchase_p_6dfa48 FOREIGN KEY (business_id, purchase_payment_id) REFERENCES payments(business_id, id);
ALTER TABLE package_redemptions DROP CONSTRAINT IF EXISTS package_redemptions_customer_package_balance_id_fkey;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_tenant_package_redemptions_customer_p_22e196 FOREIGN KEY (business_id, customer_package_balance_id) REFERENCES customer_package_balances(business_id, id);
ALTER TABLE package_redemptions DROP CONSTRAINT IF EXISTS package_redemptions_booking_item_id_fkey;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_tenant_package_redemptions_booking_it_01ef35 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id);
ALTER TABLE package_redemptions DROP CONSTRAINT IF EXISTS package_redemptions_service_id_fkey;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_tenant_package_redemptions_service_id_7d1f6d FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE package_redemptions DROP CONSTRAINT IF EXISTS package_redemptions_redeemed_by_employee_id_fkey;
ALTER TABLE package_redemptions ADD CONSTRAINT fk_tenant_package_redemptions_redeemed_b_14ad15 FOREIGN KEY (business_id, redeemed_by_employee_id) REFERENCES employees(business_id, id);
ALTER TABLE customer_memberships DROP CONSTRAINT IF EXISTS customer_memberships_business_customer_id_fkey;
ALTER TABLE customer_memberships ADD CONSTRAINT fk_tenant_customer_memberships_business_c_543403 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE customer_memberships DROP CONSTRAINT IF EXISTS customer_memberships_membership_plan_id_fkey;
ALTER TABLE customer_memberships ADD CONSTRAINT fk_tenant_customer_memberships_membership_2eb17c FOREIGN KEY (business_id, membership_plan_id) REFERENCES membership_plans(business_id, id);
ALTER TABLE membership_benefits DROP CONSTRAINT IF EXISTS membership_benefits_membership_plan_id_fkey;
ALTER TABLE membership_benefits ADD CONSTRAINT fk_tenant_membership_benefits_membership_8d2166 FOREIGN KEY (business_id, membership_plan_id) REFERENCES membership_plans(business_id, id) ON DELETE CASCADE;
ALTER TABLE membership_benefits DROP CONSTRAINT IF EXISTS membership_benefits_service_id_fkey;
ALTER TABLE membership_benefits ADD CONSTRAINT fk_tenant_membership_benefits_service_id_d1fcab FOREIGN KEY (business_id, service_id) REFERENCES services(business_id, id);
ALTER TABLE membership_transactions DROP CONSTRAINT IF EXISTS membership_transactions_customer_membership_id_fkey;
ALTER TABLE membership_transactions ADD CONSTRAINT fk_tenant_membership_transactions_customer_m_d7c11c FOREIGN KEY (business_id, customer_membership_id) REFERENCES customer_memberships(business_id, id);
ALTER TABLE membership_transactions DROP CONSTRAINT IF EXISTS membership_transactions_payment_id_fkey;
ALTER TABLE membership_transactions ADD CONSTRAINT fk_tenant_membership_transactions_payment_id_883dce FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE loyalty_accounts DROP CONSTRAINT IF EXISTS loyalty_accounts_business_customer_id_fkey;
ALTER TABLE loyalty_accounts ADD CONSTRAINT fk_tenant_loyalty_accounts_business_c_380843 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE loyalty_accounts DROP CONSTRAINT IF EXISTS loyalty_accounts_loyalty_tier_id_fkey;
ALTER TABLE loyalty_accounts ADD CONSTRAINT fk_tenant_loyalty_accounts_loyalty_ti_9f79d5 FOREIGN KEY (business_id, loyalty_tier_id) REFERENCES loyalty_tiers(business_id, id);
ALTER TABLE loyalty_transactions DROP CONSTRAINT IF EXISTS loyalty_transactions_loyalty_account_id_fkey;
ALTER TABLE loyalty_transactions ADD CONSTRAINT fk_tenant_loyalty_transactions_loyalty_ac_8f9112 FOREIGN KEY (business_id, loyalty_account_id) REFERENCES loyalty_accounts(business_id, id);
ALTER TABLE loyalty_redemptions DROP CONSTRAINT IF EXISTS loyalty_redemptions_loyalty_account_id_fkey;
ALTER TABLE loyalty_redemptions ADD CONSTRAINT fk_tenant_loyalty_redemptions_loyalty_ac_ebad27 FOREIGN KEY (business_id, loyalty_account_id) REFERENCES loyalty_accounts(business_id, id);
ALTER TABLE loyalty_redemptions DROP CONSTRAINT IF EXISTS loyalty_redemptions_loyalty_reward_id_fkey;
ALTER TABLE loyalty_redemptions ADD CONSTRAINT fk_tenant_loyalty_redemptions_loyalty_re_b4c9ae FOREIGN KEY (business_id, loyalty_reward_id) REFERENCES loyalty_rewards(business_id, id);
ALTER TABLE promotion_redemptions DROP CONSTRAINT IF EXISTS promotion_redemptions_promotion_id_fkey;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_tenant_promotion_redemptions_promotion__7f65a2 FOREIGN KEY (business_id, promotion_id) REFERENCES promotions(business_id, id);
ALTER TABLE promotion_redemptions DROP CONSTRAINT IF EXISTS promotion_redemptions_business_customer_id_fkey;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_tenant_promotion_redemptions_business_c_aa1baa FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE promotion_redemptions DROP CONSTRAINT IF EXISTS promotion_redemptions_booking_id_fkey;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_tenant_promotion_redemptions_booking_id_d0e25a FOREIGN KEY (business_id, booking_id) REFERENCES bookings(business_id, id);
ALTER TABLE promotion_redemptions DROP CONSTRAINT IF EXISTS promotion_redemptions_sale_id_fkey;
ALTER TABLE promotion_redemptions ADD CONSTRAINT fk_tenant_promotion_redemptions_sale_id_e38fe5 FOREIGN KEY (business_id, sale_id) REFERENCES sales(business_id, id);
ALTER TABLE marketing_campaigns DROP CONSTRAINT IF EXISTS marketing_campaigns_template_id_fkey;
ALTER TABLE marketing_campaigns ADD CONSTRAINT fk_tenant_marketing_campaigns_template_i_9ae0ab FOREIGN KEY (business_id, template_id) REFERENCES marketing_campaign_templates(business_id, id);
ALTER TABLE marketing_campaign_recipients DROP CONSTRAINT IF EXISTS marketing_campaign_recipients_campaign_id_fkey;
ALTER TABLE marketing_campaign_recipients ADD CONSTRAINT fk_tenant_marketing_campaign_recipients_campaign_i_aaf473 FOREIGN KEY (business_id, campaign_id) REFERENCES marketing_campaigns(business_id, id) ON DELETE CASCADE;
ALTER TABLE marketing_campaign_recipients DROP CONSTRAINT IF EXISTS marketing_campaign_recipients_business_customer_id_fkey;
ALTER TABLE marketing_campaign_recipients ADD CONSTRAINT fk_tenant_marketing_campaign_recipients_business_c_ee7ea1 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE marketing_campaign_events DROP CONSTRAINT IF EXISTS marketing_campaign_events_campaign_recipient_id_fkey;
ALTER TABLE marketing_campaign_events ADD CONSTRAINT fk_tenant_marketing_campaign_events_campaign_r_6eb962 FOREIGN KEY (business_id, campaign_recipient_id) REFERENCES marketing_campaign_recipients(business_id, id) ON DELETE CASCADE;
ALTER TABLE marketing_automation_steps DROP CONSTRAINT IF EXISTS marketing_automation_steps_automation_id_fkey;
ALTER TABLE marketing_automation_steps ADD CONSTRAINT fk_tenant_marketing_automation_steps_automation_95ff31 FOREIGN KEY (business_id, automation_id) REFERENCES marketing_automations(business_id, id) ON DELETE CASCADE;
ALTER TABLE marketing_automation_runs DROP CONSTRAINT IF EXISTS marketing_automation_runs_automation_id_fkey;
ALTER TABLE marketing_automation_runs ADD CONSTRAINT fk_tenant_marketing_automation_runs_automation_f29a64 FOREIGN KEY (business_id, automation_id) REFERENCES marketing_automations(business_id, id);
ALTER TABLE marketing_automation_runs DROP CONSTRAINT IF EXISTS marketing_automation_runs_business_customer_id_fkey;
ALTER TABLE marketing_automation_runs ADD CONSTRAINT fk_tenant_marketing_automation_runs_business_c_c2fbc9 FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE ai_interactions DROP CONSTRAINT IF EXISTS ai_interactions_business_customer_id_fkey;
ALTER TABLE ai_interactions ADD CONSTRAINT fk_tenant_ai_interactions_business_c_22111a FOREIGN KEY (business_id, business_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE ai_action_logs DROP CONSTRAINT IF EXISTS ai_action_logs_ai_interaction_id_fkey;
ALTER TABLE ai_action_logs ADD CONSTRAINT fk_tenant_ai_action_logs_ai_interac_52ca7a FOREIGN KEY (business_id, ai_interaction_id) REFERENCES ai_interactions(business_id, id);
ALTER TABLE ai_usage_records DROP CONSTRAINT IF EXISTS ai_usage_records_ai_interaction_id_fkey;
ALTER TABLE ai_usage_records ADD CONSTRAINT fk_tenant_ai_usage_records_ai_interac_6bb348 FOREIGN KEY (business_id, ai_interaction_id) REFERENCES ai_interactions(business_id, id);
ALTER TABLE report_schedules DROP CONSTRAINT IF EXISTS report_schedules_saved_report_id_fkey;
ALTER TABLE report_schedules ADD CONSTRAINT fk_tenant_report_schedules_saved_repo_3f3067 FOREIGN KEY (business_id, saved_report_id) REFERENCES saved_reports(business_id, id) ON DELETE CASCADE;
ALTER TABLE pay_run_items DROP CONSTRAINT IF EXISTS pay_run_items_pay_run_id_fkey;
ALTER TABLE pay_run_items ADD CONSTRAINT fk_tenant_pay_run_items_pay_run_id_e6fc7e FOREIGN KEY (business_id, pay_run_id) REFERENCES pay_runs(business_id, id) ON DELETE CASCADE;
ALTER TABLE pay_run_items DROP CONSTRAINT IF EXISTS pay_run_items_employee_id_fkey;
ALTER TABLE pay_run_items ADD CONSTRAINT fk_tenant_pay_run_items_employee_i_ae12d3 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE pay_run_adjustments DROP CONSTRAINT IF EXISTS pay_run_adjustments_pay_run_item_id_fkey;
ALTER TABLE pay_run_adjustments ADD CONSTRAINT fk_tenant_pay_run_adjustments_pay_run_it_11cf7c FOREIGN KEY (business_id, pay_run_item_id) REFERENCES pay_run_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE pay_run_payments DROP CONSTRAINT IF EXISTS pay_run_payments_pay_run_item_id_fkey;
ALTER TABLE pay_run_payments ADD CONSTRAINT fk_tenant_pay_run_payments_pay_run_it_e3796d FOREIGN KEY (business_id, pay_run_item_id) REFERENCES pay_run_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE pay_run_payments DROP CONSTRAINT IF EXISTS pay_run_payments_payment_id_fkey;
ALTER TABLE pay_run_payments ADD CONSTRAINT fk_tenant_pay_run_payments_payment_id_f09a90 FOREIGN KEY (business_id, payment_id) REFERENCES payments(business_id, id);
ALTER TABLE booking_item_stages DROP CONSTRAINT IF EXISTS booking_item_stages_booking_item_id_fkey;
ALTER TABLE booking_item_stages ADD CONSTRAINT fk_tenant_booking_item_stages_booking_it_23d89b FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_item_stages DROP CONSTRAINT IF EXISTS booking_item_stages_employee_id_fkey;
ALTER TABLE booking_item_stages ADD CONSTRAINT fk_tenant_booking_item_stages_employee_i_a2fe85 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE schedule_reservations DROP CONSTRAINT IF EXISTS schedule_reservations_branch_id_fkey;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_tenant_schedule_reservations_branch_id_bc5817 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE schedule_reservations DROP CONSTRAINT IF EXISTS schedule_reservations_employee_id_fkey;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_tenant_schedule_reservations_employee_i_8f5566 FOREIGN KEY (business_id, employee_id) REFERENCES employees(business_id, id);
ALTER TABLE schedule_reservations DROP CONSTRAINT IF EXISTS schedule_reservations_resource_id_fkey;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_tenant_schedule_reservations_resource_i_4ebc58 FOREIGN KEY (business_id, resource_id) REFERENCES resources(business_id, id);
ALTER TABLE schedule_reservations DROP CONSTRAINT IF EXISTS schedule_reservations_booking_item_stage_id_fkey;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_tenant_schedule_reservations_booking_it_1fc9e6 FOREIGN KEY (business_id, booking_item_stage_id) REFERENCES booking_item_stages(business_id, id) ON DELETE CASCADE;
ALTER TABLE schedule_reservations DROP CONSTRAINT IF EXISTS schedule_reservations_booking_item_resource_id_fkey;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_tenant_schedule_reservations_booking_it_a3fce8 FOREIGN KEY (business_id, booking_item_resource_id) REFERENCES booking_item_resources(business_id, id) ON DELETE CASCADE;
ALTER TABLE schedule_reservations DROP CONSTRAINT IF EXISTS schedule_reservations_booking_hold_id_fkey;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_tenant_schedule_reservations_booking_ho_ee25b1 FOREIGN KEY (business_id, booking_hold_id) REFERENCES booking_holds(business_id, id) ON DELETE CASCADE;
ALTER TABLE schedule_reservations DROP CONSTRAINT IF EXISTS schedule_reservations_calendar_block_id_fkey;
ALTER TABLE schedule_reservations ADD CONSTRAINT fk_tenant_schedule_reservations_calendar_b_718fec FOREIGN KEY (business_id, calendar_block_id) REFERENCES calendar_blocks(business_id, id) ON DELETE CASCADE;
ALTER TABLE business_tax_profiles DROP CONSTRAINT IF EXISTS business_tax_profiles_branch_id_fkey;
ALTER TABLE business_tax_profiles ADD CONSTRAINT fk_tenant_business_tax_profiles_branch_id_783880 FOREIGN KEY (business_id, branch_id) REFERENCES branches(business_id, id);
ALTER TABLE tax_rates DROP CONSTRAINT IF EXISTS tax_rates_tax_profile_id_fkey;
ALTER TABLE tax_rates ADD CONSTRAINT fk_tenant_tax_rates_tax_profil_508778 FOREIGN KEY (business_id, tax_profile_id) REFERENCES business_tax_profiles(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_item_tax_lines DROP CONSTRAINT IF EXISTS booking_item_tax_lines_booking_item_id_fkey;
ALTER TABLE booking_item_tax_lines ADD CONSTRAINT fk_tenant_booking_item_tax_lines_booking_it_2dcdb8 FOREIGN KEY (business_id, booking_item_id) REFERENCES booking_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE booking_item_tax_lines DROP CONSTRAINT IF EXISTS booking_item_tax_lines_tax_rate_id_fkey;
ALTER TABLE booking_item_tax_lines ADD CONSTRAINT fk_tenant_booking_item_tax_lines_tax_rate_i_e365fd FOREIGN KEY (business_id, tax_rate_id) REFERENCES tax_rates(business_id, id);
ALTER TABLE sale_item_tax_lines DROP CONSTRAINT IF EXISTS sale_item_tax_lines_sale_item_id_fkey;
ALTER TABLE sale_item_tax_lines ADD CONSTRAINT fk_tenant_sale_item_tax_lines_sale_item__0558ef FOREIGN KEY (business_id, sale_item_id) REFERENCES sale_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE sale_item_tax_lines DROP CONSTRAINT IF EXISTS sale_item_tax_lines_tax_rate_id_fkey;
ALTER TABLE sale_item_tax_lines ADD CONSTRAINT fk_tenant_sale_item_tax_lines_tax_rate_i_fa9502 FOREIGN KEY (business_id, tax_rate_id) REFERENCES tax_rates(business_id, id);
ALTER TABLE invoice_item_tax_lines DROP CONSTRAINT IF EXISTS invoice_item_tax_lines_invoice_item_id_fkey;
ALTER TABLE invoice_item_tax_lines ADD CONSTRAINT fk_tenant_invoice_item_tax_lines_invoice_it_fa29d5 FOREIGN KEY (business_id, invoice_item_id) REFERENCES invoice_items(business_id, id) ON DELETE CASCADE;
ALTER TABLE invoice_item_tax_lines DROP CONSTRAINT IF EXISTS invoice_item_tax_lines_tax_rate_id_fkey;
ALTER TABLE invoice_item_tax_lines ADD CONSTRAINT fk_tenant_invoice_item_tax_lines_tax_rate_i_c2ea45 FOREIGN KEY (business_id, tax_rate_id) REFERENCES tax_rates(business_id, id);
ALTER TABLE refund_allocations DROP CONSTRAINT IF EXISTS refund_allocations_refund_id_fkey;
ALTER TABLE refund_allocations ADD CONSTRAINT fk_tenant_refund_allocations_refund_id_fd7281 FOREIGN KEY (business_id, refund_id) REFERENCES refunds(business_id, id) ON DELETE CASCADE;
ALTER TABLE refund_allocations DROP CONSTRAINT IF EXISTS refund_allocations_payment_allocation_id_fkey;
ALTER TABLE refund_allocations ADD CONSTRAINT fk_tenant_refund_allocations_payment_al_9efeb4 FOREIGN KEY (business_id, payment_allocation_id) REFERENCES payment_allocations(business_id, id);
ALTER TABLE referrals DROP CONSTRAINT IF EXISTS referrals_referral_program_id_fkey;
ALTER TABLE referrals ADD CONSTRAINT fk_tenant_referrals_referral_p_285c62 FOREIGN KEY (business_id, referral_program_id) REFERENCES referral_programs(business_id, id);
ALTER TABLE referrals DROP CONSTRAINT IF EXISTS referrals_referrer_customer_id_fkey;
ALTER TABLE referrals ADD CONSTRAINT fk_tenant_referrals_referrer_c_2f7501 FOREIGN KEY (business_id, referrer_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE referrals DROP CONSTRAINT IF EXISTS referrals_referred_customer_id_fkey;
ALTER TABLE referrals ADD CONSTRAINT fk_tenant_referrals_referred_c_dfff21 FOREIGN KEY (business_id, referred_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE referrals DROP CONSTRAINT IF EXISTS referrals_qualifying_booking_id_fkey;
ALTER TABLE referrals ADD CONSTRAINT fk_tenant_referrals_qualifying_f5ba49 FOREIGN KEY (business_id, qualifying_booking_id) REFERENCES bookings(business_id, id);
ALTER TABLE referrals DROP CONSTRAINT IF EXISTS referrals_qualifying_sale_id_fkey;
ALTER TABLE referrals ADD CONSTRAINT fk_tenant_referrals_qualifying_ffcf78 FOREIGN KEY (business_id, qualifying_sale_id) REFERENCES sales(business_id, id);
ALTER TABLE referral_rewards DROP CONSTRAINT IF EXISTS referral_rewards_referral_id_fkey;
ALTER TABLE referral_rewards ADD CONSTRAINT fk_tenant_referral_rewards_referral_i_727488 FOREIGN KEY (business_id, referral_id) REFERENCES referrals(business_id, id) ON DELETE CASCADE;
ALTER TABLE referral_rewards DROP CONSTRAINT IF EXISTS referral_rewards_beneficiary_customer_id_fkey;
ALTER TABLE referral_rewards ADD CONSTRAINT fk_tenant_referral_rewards_beneficiar_d93939 FOREIGN KEY (business_id, beneficiary_customer_id) REFERENCES business_customers(business_id, id);
ALTER TABLE referral_reward_transactions DROP CONSTRAINT IF EXISTS referral_reward_transactions_referral_reward_id_fkey;
ALTER TABLE referral_reward_transactions ADD CONSTRAINT fk_tenant_referral_reward_transactions_referral_r_e438a4 FOREIGN KEY (business_id, referral_reward_id) REFERENCES referral_rewards(business_id, id);
ALTER TABLE booking_item_resources DROP CONSTRAINT IF EXISTS fk_booking_item_resources_stage;
ALTER TABLE booking_item_resources ADD CONSTRAINT fk_tenant_booking_item_resources_stage FOREIGN KEY (business_id, booking_item_stage_id) REFERENCES booking_item_stages(business_id, id);


-- Explicit currency snapshots for rows whose monetary amounts previously inherited currency implicitly.
ALTER TABLE payment_authorizations ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE payment_authorizations ALTER COLUMN currency SET NOT NULL;
ALTER TABLE payment_reconciliation_items ADD COLUMN currency char(3) NOT NULL REFERENCES currencies(currency_code);
ALTER TABLE delivery_zones ADD COLUMN currency char(3) NOT NULL REFERENCES currencies(currency_code);
ALTER TABLE product_variants ADD COLUMN cost_currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE product_variants ADD CONSTRAINT ck_product_variants_cost_currency CHECK (cost_price IS NULL OR cost_currency IS NOT NULL);
ALTER TABLE service_pricing_rules ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE service_pricing_rules ALTER COLUMN currency SET NOT NULL;
ALTER TABLE booking_payment_policies ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE booking_payment_policies ALTER COLUMN currency SET NOT NULL;
ALTER TABLE membership_transactions ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE membership_transactions ALTER COLUMN currency SET NOT NULL;
ALTER TABLE inventory_movements ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE inventory_movements ALTER COLUMN currency SET NOT NULL;
ALTER TABLE cash_registers ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE cash_registers ALTER COLUMN currency SET NOT NULL;
ALTER TABLE goods_receipts ADD COLUMN currency char(3) REFERENCES currencies(currency_code);
ALTER TABLE goods_receipts ALTER COLUMN currency SET NOT NULL;



-- Parent-currency precision validation for line items whose currency is inherited from a transaction header.
CREATE OR REPLACE FUNCTION validate_parent_currency_amount_precision() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_row jsonb; v_parent_id uuid; v_currency char(3); v_minor smallint; v_field text; v_amount numeric; i integer;
BEGIN
    v_row := to_jsonb(NEW);
    v_parent_id := NULLIF(v_row->>TG_ARGV[1],'')::uuid;
    IF v_parent_id IS NULL THEN RAISE EXCEPTION 'Parent key % is required for currency validation',TG_ARGV[1]; END IF;
    EXECUTE format('SELECT currency FROM %I WHERE business_id=$1 AND id=$2 FOR KEY SHARE',TG_ARGV[0])
       INTO v_currency USING NEW.business_id,v_parent_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Parent row %/% was not found or has no currency',TG_ARGV[0],v_parent_id; END IF;
    SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=v_currency;
    IF v_minor IS NULL THEN RAISE EXCEPTION 'Unknown parent currency %',v_currency; END IF;
    FOR i IN 2..(TG_NARGS-1) LOOP
        v_field := TG_ARGV[i];
        v_amount := NULLIF(v_row->>v_field,'')::numeric;
        IF v_amount IS NOT NULL AND v_amount <> round(v_amount,v_minor) THEN
            RAISE EXCEPTION 'Amount field % exceeds currency % minor-unit precision (%)',v_field,v_currency,v_minor;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_parent_currency_booking_item_addons BEFORE INSERT OR UPDATE ON booking_item_addons FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('booking_items','booking_item_id','unit_price','total_amount');
CREATE TRIGGER trg_parent_currency_class_enrollments BEFORE INSERT OR UPDATE ON class_enrollments FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('class_sessions','class_session_id','amount_due');
CREATE TRIGGER trg_parent_currency_booking_cancellations BEFORE INSERT OR UPDATE ON booking_cancellations FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('bookings','booking_id','fee_amount','refund_amount');
CREATE TRIGGER trg_parent_currency_sale_items BEFORE INSERT OR UPDATE ON sale_items FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('sales','sale_id','unit_price','discount_amount','tax_amount','line_total');
CREATE TRIGGER trg_parent_currency_quote_items BEFORE INSERT OR UPDATE ON quote_items FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('quotes','quote_id','unit_price','tax_amount','line_total');
CREATE TRIGGER trg_parent_currency_invoice_items BEFORE INSERT OR UPDATE ON invoice_items FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('invoices','invoice_id','unit_price','tax_amount','line_total');
CREATE TRIGGER trg_parent_currency_payment_links BEFORE INSERT OR UPDATE ON payment_links FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('payment_obligations','payment_obligation_id','amount');
CREATE TRIGGER trg_parent_currency_settlement_items BEFORE INSERT OR UPDATE ON settlement_items FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('settlement_batches','settlement_batch_id','gross_amount','fee_amount','net_amount');
CREATE TRIGGER trg_parent_currency_payment_disputes BEFORE INSERT OR UPDATE ON payment_disputes FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('payments','payment_id','disputed_amount');
CREATE TRIGGER trg_parent_currency_purchase_order_items BEFORE INSERT OR UPDATE ON purchase_order_items FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('purchase_orders','purchase_order_id','unit_cost');
CREATE TRIGGER trg_parent_currency_goods_receipt_items BEFORE INSERT OR UPDATE ON goods_receipt_items FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('goods_receipts','goods_receipt_id','unit_cost');
CREATE TRIGGER trg_parent_currency_billing_invoice_items BEFORE INSERT OR UPDATE ON billing_invoice_items FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('billing_invoices','billing_invoice_id','unit_price','line_total');
CREATE TRIGGER trg_parent_currency_gift_card_transactions BEFORE INSERT OR UPDATE ON gift_card_transactions FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('gift_cards','gift_card_id','amount','balance_after');
CREATE TRIGGER trg_parent_currency_pay_run_adjustments BEFORE INSERT OR UPDATE ON pay_run_adjustments FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('pay_run_items','pay_run_item_id','amount');
CREATE TRIGGER trg_parent_currency_cash_register_sessions BEFORE INSERT OR UPDATE ON cash_register_sessions FOR EACH ROW EXECUTE FUNCTION validate_parent_currency_amount_precision('cash_registers','cash_register_id','opening_float','expected_cash','counted_cash','variance');

-- Derive/validate currency on operational records that do not yet have a currency-bearing parent field.
CREATE OR REPLACE FUNCTION validate_cash_register_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT currency INTO v_currency FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Cash register branch does not exist in this business'; END IF;
    IF NEW.currency IS NOT NULL AND NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Cash register currency must match branch currency'; END IF;
    NEW.currency := v_currency;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_cash_register_currency BEFORE INSERT OR UPDATE OF business_id,branch_id,currency ON cash_registers FOR EACH ROW EXECUTE FUNCTION validate_cash_register_currency();

CREATE OR REPLACE FUNCTION validate_goods_receipt_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT currency INTO v_currency FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Goods receipt branch does not exist in this business'; END IF;
    IF NEW.currency IS NOT NULL AND NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Goods receipt currency must match branch currency'; END IF;
    NEW.currency := v_currency;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_goods_receipt_currency BEFORE INSERT OR UPDATE OF business_id,branch_id,currency ON goods_receipts FOR EACH ROW EXECUTE FUNCTION validate_goods_receipt_currency();

CREATE OR REPLACE FUNCTION validate_payment_authorization_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    IF NEW.payment_id IS NOT NULL THEN
        SELECT currency INTO v_currency FROM payments WHERE business_id=NEW.business_id AND id=NEW.payment_id;
        IF v_currency IS NULL THEN RAISE EXCEPTION 'Authorization payment does not exist in this business'; END IF;
        IF NEW.currency IS NOT NULL AND NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Authorization currency must match payment currency'; END IF;
        NEW.currency := v_currency;
    END IF;
    IF NEW.currency IS NULL OR NOT EXISTS (SELECT 1 FROM currencies WHERE currency_code=NEW.currency) THEN
        RAISE EXCEPTION 'Payment authorization requires a valid currency';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_00_validate_payment_authorization_currency BEFORE INSERT OR UPDATE OF business_id,payment_id,currency ON payment_authorizations FOR EACH ROW EXECUTE FUNCTION validate_payment_authorization_currency();

CREATE OR REPLACE FUNCTION validate_payment_reconciliation_item_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    IF NEW.payment_id IS NOT NULL THEN
        SELECT currency INTO v_currency FROM payments WHERE business_id=NEW.business_id AND id=NEW.payment_id;
        IF v_currency IS NULL THEN RAISE EXCEPTION 'Reconciliation payment does not exist in this business'; END IF;
        IF NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Matched reconciliation item currency must match payment currency'; END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_payment_reconciliation_item_currency BEFORE INSERT OR UPDATE OF business_id,payment_id,currency ON payment_reconciliation_items FOR EACH ROW EXECUTE FUNCTION validate_payment_reconciliation_item_currency();

CREATE OR REPLACE FUNCTION validate_inventory_movement_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT currency INTO v_currency FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Inventory movement branch does not exist in this business'; END IF;
    IF NEW.currency IS NOT NULL AND NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Inventory movement cost currency must match branch currency'; END IF;
    NEW.currency := v_currency;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_00_validate_inventory_movement_currency BEFORE INSERT OR UPDATE OF business_id,branch_id,currency ON inventory_movements FOR EACH ROW EXECUTE FUNCTION validate_inventory_movement_currency();

CREATE OR REPLACE FUNCTION validate_business_customer_reporting_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT reporting_currency INTO v_currency FROM businesses WHERE id=NEW.business_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Business reporting currency is missing'; END IF;
    IF NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Customer lifetime value must be maintained in business reporting currency'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_business_customer_reporting_currency BEFORE INSERT OR UPDATE OF business_id,currency ON business_customers FOR EACH ROW EXECUTE FUNCTION validate_business_customer_reporting_currency();

CREATE OR REPLACE FUNCTION validate_pay_run_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT functional_currency INTO v_currency FROM business_legal_entities WHERE business_id=NEW.business_id AND id=NEW.legal_entity_id;
    IF v_currency IS NULL OR NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Payroll currency must match legal-entity functional currency'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_pay_run_currency BEFORE INSERT OR UPDATE OF business_id,legal_entity_id,currency ON pay_runs FOR EACH ROW EXECUTE FUNCTION validate_pay_run_currency();

CREATE OR REPLACE FUNCTION validate_pay_run_item_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT currency INTO v_currency FROM pay_runs WHERE business_id=NEW.business_id AND id=NEW.pay_run_id;
    IF v_currency IS NULL OR NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Pay run item currency must match pay run currency'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_pay_run_item_currency BEFORE INSERT OR UPDATE OF business_id,pay_run_id,currency ON pay_run_items FOR EACH ROW EXECUTE FUNCTION validate_pay_run_item_currency();

CREATE OR REPLACE FUNCTION validate_pay_run_payment_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT currency INTO v_currency FROM pay_run_items WHERE business_id=NEW.business_id AND id=NEW.pay_run_item_id;
    IF v_currency IS NULL OR NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Payroll disbursement currency must match pay run item currency'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_pay_run_payment_currency BEFORE INSERT OR UPDATE OF business_id,pay_run_item_id,currency ON pay_run_payments FOR EACH ROW EXECUTE FUNCTION validate_pay_run_payment_currency();

CREATE OR REPLACE FUNCTION validate_membership_transaction_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3);
BEGIN
    SELECT mp.currency INTO v_currency
      FROM customer_memberships cm JOIN membership_plans mp ON mp.business_id=cm.business_id AND mp.id=cm.membership_plan_id
     WHERE cm.business_id=NEW.business_id AND cm.id=NEW.customer_membership_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Membership plan currency is unavailable'; END IF;
    IF NEW.currency IS NOT NULL AND NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Membership transaction currency must match membership plan currency'; END IF;
    NEW.currency := v_currency;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_00_validate_membership_transaction_currency BEFORE INSERT OR UPDATE OF business_id,customer_membership_id,currency ON membership_transactions FOR EACH ROW EXECUTE FUNCTION validate_membership_transaction_currency();

CREATE OR REPLACE FUNCTION validate_service_pricing_rule_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3); v_minor smallint;
BEGIN
    SELECT currency INTO v_currency FROM service_variants WHERE business_id=NEW.business_id AND id=NEW.service_variant_id;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Service variant currency is unavailable'; END IF;
    IF NEW.currency IS NOT NULL AND NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Service pricing rule currency must match service variant currency'; END IF;
    NEW.currency := v_currency;
    IF upper(NEW.adjustment_type) IN ('FIXED_AMOUNT','AMOUNT','FIXED_PRICE','PRICE') THEN
        SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=v_currency;
        IF NEW.adjustment_value<>round(NEW.adjustment_value,v_minor) THEN RAISE EXCEPTION 'Fixed pricing rule amount exceeds currency minor-unit precision'; END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_service_pricing_rule_currency BEFORE INSERT OR UPDATE OF business_id,service_variant_id,currency,adjustment_type,adjustment_value ON service_pricing_rules FOR EACH ROW EXECUTE FUNCTION validate_service_pricing_rule_currency();

CREATE OR REPLACE FUNCTION validate_booking_payment_policy_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_currency char(3); v_minor smallint;
BEGIN
    IF NEW.branch_id IS NOT NULL THEN
        SELECT currency INTO v_currency FROM branches WHERE business_id=NEW.business_id AND id=NEW.branch_id;
    ELSIF NEW.service_variant_id IS NOT NULL THEN
        SELECT currency INTO v_currency FROM service_variants WHERE business_id=NEW.business_id AND id=NEW.service_variant_id;
    ELSE
        SELECT reporting_currency INTO v_currency FROM businesses WHERE id=NEW.business_id;
    END IF;
    IF v_currency IS NULL THEN RAISE EXCEPTION 'Booking payment policy currency cannot be derived'; END IF;
    IF NEW.currency IS NOT NULL AND NEW.currency IS DISTINCT FROM v_currency THEN RAISE EXCEPTION 'Booking payment policy currency must match its branch, service variant or business reporting currency'; END IF;
    NEW.currency := v_currency;
    SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=v_currency;
    IF upper(COALESCE(NEW.deposit_type,'')) IN ('FIXED','FIXED_AMOUNT','AMOUNT','CURRENCY_AMOUNT') AND NEW.deposit_value IS NOT NULL AND NEW.deposit_value<>round(NEW.deposit_value,v_minor) THEN RAISE EXCEPTION 'Fixed deposit exceeds currency minor-unit precision'; END IF;
    IF upper(COALESCE(NEW.cancellation_fee_type,'')) IN ('FIXED','FIXED_AMOUNT','AMOUNT','CURRENCY_AMOUNT') AND NEW.cancellation_fee_value IS NOT NULL AND NEW.cancellation_fee_value<>round(NEW.cancellation_fee_value,v_minor) THEN RAISE EXCEPTION 'Fixed cancellation fee exceeds currency minor-unit precision'; END IF;
    IF upper(COALESCE(NEW.no_show_fee_type,'')) IN ('FIXED','FIXED_AMOUNT','AMOUNT','CURRENCY_AMOUNT') AND NEW.no_show_fee_value IS NOT NULL AND NEW.no_show_fee_value<>round(NEW.no_show_fee_value,v_minor) THEN RAISE EXCEPTION 'Fixed no-show fee exceeds currency minor-unit precision'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_booking_payment_policy_currency BEFORE INSERT OR UPDATE OF business_id,branch_id,service_variant_id,currency,deposit_type,deposit_value,cancellation_fee_type,cancellation_fee_value,no_show_fee_type,no_show_fee_value ON booking_payment_policies FOR EACH ROW EXECUTE FUNCTION validate_booking_payment_policy_currency();

CREATE OR REPLACE FUNCTION validate_referral_reward_value_precision() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_minor smallint; v_type text;
BEGIN
    v_type := upper(CASE WHEN TG_TABLE_NAME='referral_programs' THEN NEW.reward_type ELSE NEW.reward_type END);
    IF v_type IN ('FIXED_CREDIT','GIFT_CARD','SERVICE_CREDIT') THEN
        SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=NEW.currency;
        IF v_minor IS NULL OR NEW.reward_value<>round(NEW.reward_value,v_minor) THEN
            RAISE EXCEPTION 'Fixed referral reward must respect currency minor-unit precision';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_referral_program_reward_precision BEFORE INSERT OR UPDATE OF reward_type,reward_value,currency ON referral_programs FOR EACH ROW EXECUTE FUNCTION validate_referral_reward_value_precision();
CREATE TRIGGER trg_validate_referral_reward_precision BEFORE INSERT OR UPDATE OF reward_type,reward_value,currency ON referral_rewards FOR EACH ROW EXECUTE FUNCTION validate_referral_reward_value_precision();

CREATE OR REPLACE FUNCTION validate_product_variant_cost_currency() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_minor smallint;
BEGIN
    IF NEW.cost_price IS NULL THEN RETURN NEW; END IF;
    IF NEW.cost_currency IS NULL THEN RAISE EXCEPTION 'Cost currency is required when product cost price is supplied'; END IF;
    SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=NEW.cost_currency;
    IF v_minor IS NULL OR NEW.cost_price<>round(NEW.cost_price,v_minor) THEN RAISE EXCEPTION 'Product cost price exceeds cost currency minor-unit precision'; END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_product_variant_cost_currency BEFORE INSERT OR UPDATE OF cost_price,cost_currency ON product_variants FOR EACH ROW EXECUTE FUNCTION validate_product_variant_cost_currency();

CREATE OR REPLACE FUNCTION validate_delivery_zone_currency_precision() RETURNS trigger
LANGUAGE plpgsql SET search_path = beauty_platform, identity, pg_temp AS $function$
DECLARE v_minor smallint;
BEGIN
    SELECT minor_unit INTO v_minor FROM currencies WHERE currency_code=NEW.currency;
    IF v_minor IS NULL OR NEW.fee<>round(NEW.fee,v_minor) OR (NEW.minimum_order_amount IS NOT NULL AND NEW.minimum_order_amount<>round(NEW.minimum_order_amount,v_minor)) THEN
        RAISE EXCEPTION 'Delivery-zone amounts must respect configured currency minor units';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_validate_delivery_zone_currency_precision BEFORE INSERT OR UPDATE OF currency,fee,minimum_order_amount ON delivery_zones FOR EACH ROW EXECUTE FUNCTION validate_delivery_zone_currency_precision();


-- VERSION 1.2.1 FINAL HARDENING NOTES
-- This clean-install baseline corrects PostgreSQL referenced-key ordering; supports FX-aware
-- payment allocation and refund reversal in both payment and obligation currencies; checks
-- currency minor units on key financial/pricing records; validates service price currency
-- against variant/branch context; and pins trigger-function search_path values.
-- Before production deployment, run this exact script on PostgreSQL 17+ with the required
-- extensions, then execute the acceptance suite in the accompanying v1.2.1 system design.



-- =============================================================================

-- SECURITY RELEASE GATE: Event source v1.0 enforces organisation/event-staff access in application services,
-- but does not define a complete PostgreSQL RLS policy set. Do not deploy Event data to production
-- until tenant ownership for every Event table is classified and RLS or an equivalent database
-- enforcement layer is implemented and cross-organisation/role-scope tests pass. See the
-- consolidated Database Design section 7.2. This comment intentionally prevents this candidate
-- from being mistaken for a production-complete security baseline.


-- SECURITY RELEASE GATE: Event source v1.0 enforces organisation/event-staff access in application services,
-- but does not define a complete PostgreSQL RLS policy set. Do not deploy Event data to production
-- until tenant ownership for every Event table is classified and RLS or an equivalent database
-- enforcement layer is implemented and cross-organisation/role-scope tests pass. See the
-- consolidated Database Design section 7.2. This comment intentionally prevents this candidate
-- from being mistaken for a production-complete security baseline.


-- SECURITY RELEASE GATE: Event source v1.0 enforces organisation/event-staff access in application services,
-- but does not define a complete PostgreSQL RLS policy set. Do not deploy Event data to production
-- until tenant ownership for every Event table is classified and RLS or an equivalent database
-- enforcement layer is implemented and cross-organisation/role-scope tests pass. See the
-- consolidated Database Design section 7.2. This comment intentionally prevents this candidate
-- from being mistaken for a production-complete security baseline.


-- SECURITY RELEASE GATE: Event source v1.0 enforces organisation/event-staff access in application services,
-- but does not define a complete PostgreSQL RLS policy set. Do not deploy Event data to production
-- until tenant ownership for every Event table is classified and RLS or an equivalent database
-- enforcement layer is implemented and cross-organisation/role-scope tests pass. See the
-- consolidated Database Design section 7.2. This comment intentionally prevents this candidate
-- from being mistaken for a production-complete security baseline.

-- EVENT PRODUCT SCHEMA (source baseline: Event Platform v1.0)
-- Shared identity tables resolve from the identity schema. All event-specific
-- organisations, venues, events, ticketing, operations and finance remain here.
-- =============================================================================
-- =============================================================================
-- INSTALLABLE CLEAN-INSTALL SCRIPT - EVENT PLATFORM v1.0
-- Run ONLY against a brand-new, empty PostgreSQL database.
-- PostgreSQL 17+, PostGIS 3.5+, pgcrypto, btree_gist, ltree.
-- This is a greenfield baseline, not an incremental migration.
-- =============================================================================
SET search_path TO event_platform, identity, public;

-- ---------------------------------------------------------------------------
-- 1. Geographic, market, currency, language and calendar reference data
-- ---------------------------------------------------------------------------
CREATE TABLE countries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 iso2 char(2) NOT NULL UNIQUE,
 iso3 char(3) NOT NULL UNIQUE,
 name text NOT NULL,
 default_timezone text,
 is_active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE administrative_areas (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 country_id uuid NOT NULL REFERENCES countries(id) ON DELETE RESTRICT,
 parent_id uuid REFERENCES administrative_areas(id) ON DELETE RESTRICT,
 area_type text NOT NULL,
 name text NOT NULL,
 code text,
 path ltree,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(country_id,parent_id,area_type,name)
);

CREATE TABLE addresses (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 country_id uuid NOT NULL REFERENCES countries(id) ON DELETE RESTRICT,
 administrative_area_id uuid REFERENCES administrative_areas(id) ON DELETE SET NULL,
 address_line_1 text,
 address_line_2 text,
 locality text,
 postal_code text,
 landmark text,
 formatted_address text,
 location geography(Point,4326),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (address_line_1 IS NOT NULL OR locality IS NOT NULL OR location IS NOT NULL)
);

CREATE INDEX addresses_location_gix ON addresses USING gist(location);
CREATE INDEX addresses_country_area_idx ON addresses(country_id,administrative_area_id);

CREATE TABLE currencies (
 code char(3) PRIMARY KEY,
 name text NOT NULL,
 symbol text,
 minor_unit smallint NOT NULL DEFAULT 2 CHECK(minor_unit BETWEEN 0 AND 4),
 is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE languages (
 code varchar(16) PRIMARY KEY,
 english_name text NOT NULL,
 native_name text,
 is_rtl boolean NOT NULL DEFAULT false,
 is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE markets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 country_id uuid NOT NULL UNIQUE REFERENCES countries(id) ON DELETE RESTRICT,
 code text NOT NULL UNIQUE,
 name text NOT NULL,
 default_currency char(3) NOT NULL REFERENCES currencies(code),
 default_language varchar(16) NOT NULL REFERENCES languages(code),
 default_timezone text NOT NULL,
 status text NOT NULL DEFAULT 'PLANNED'
   CHECK(status IN ('PLANNED','ACTIVE','PAUSED','RETIRED')),
 configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
 activated_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE market_currencies (
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE CASCADE,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 is_default boolean NOT NULL DEFAULT false,
 is_enabled_for_sales boolean NOT NULL DEFAULT true,
 PRIMARY KEY(market_id,currency_code)
);

CREATE TABLE market_languages (
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE CASCADE,
 language_code varchar(16) NOT NULL REFERENCES languages(code),
 is_default boolean NOT NULL DEFAULT false,
 PRIMARY KEY(market_id,language_code)
);

CREATE TABLE country_calendar_settings (
 country_id uuid PRIMARY KEY REFERENCES countries(id) ON DELETE CASCADE,
 display_calendar text NOT NULL DEFAULT 'GREGORIAN'
   CHECK(display_calendar IN ('GREGORIAN','ETHIOPIAN','OTHER')),
 fiscal_year_start_month smallint CHECK(fiscal_year_start_month BETWEEN 1 AND 12),
 configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
 updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 2. Identity, profile, authentication and shared media
-- ---------------------------------------------------------------------------
CREATE TABLE media_assets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 storage_provider text NOT NULL DEFAULT 'CLOUDFLARE_R2',
 bucket_name text NOT NULL,
 object_key text NOT NULL UNIQUE,
 media_type text NOT NULL,
 mime_type text,
 byte_size bigint CHECK(byte_size IS NULL OR byte_size >= 0),
 width integer,
 height integer,
 checksum_sha256 text,
 visibility text NOT NULL DEFAULT 'PUBLIC'
   CHECK(visibility IN ('PUBLIC','PRIVATE')),
 processing_status text NOT NULL DEFAULT 'PENDING'
   CHECK(processing_status IN ('PENDING','READY','FAILED','DELETED')),
 created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 3. Organisations, members, market profiles and organiser reputation
-- ---------------------------------------------------------------------------
CREATE TABLE organisations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
 name text NOT NULL,
 slug text NOT NULL UNIQUE,
 description text,
 website_url text,
 email text,
 phone text,
 logo_media_id uuid REFERENCES media_assets(id) ON DELETE SET NULL,
 cover_media_id uuid REFERENCES media_assets(id) ON DELETE SET NULL,
 verification_status text NOT NULL DEFAULT 'UNVERIFIED'
   CHECK(verification_status IN ('UNVERIFIED','PENDING','VERIFIED','REJECTED','SUSPENDED')),
 hosting_since date,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organisation_members (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
 member_role text NOT NULL
   CHECK(member_role IN ('OWNER','ADMIN','EVENT_MANAGER','FINANCE','CHECK_IN','VIEWER')),
 status text NOT NULL DEFAULT 'ACTIVE'
   CHECK(status IN ('INVITED','ACTIVE','SUSPENDED','REMOVED')),
 invited_by uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 joined_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organisation_id,user_id)
);

CREATE TABLE organisation_invitations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 email_normalized text NOT NULL,
 member_role text NOT NULL
   CHECK(member_role IN ('ADMIN','EVENT_MANAGER','FINANCE','CHECK_IN','VIEWER')),
 token_hash text NOT NULL UNIQUE,
 invited_by uuid NOT NULL REFERENCES identity.users(id),
 expires_at timestamptz NOT NULL,
 accepted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organisation_market_profiles (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE RESTRICT,
 local_legal_name text,
 registration_number text,
 tax_identifier text,
 contact_email text,
 contact_phone text,
 verification_status text NOT NULL DEFAULT 'NOT_REQUIRED'
   CHECK(verification_status IN ('NOT_REQUIRED','PENDING','VERIFIED','REJECTED')),
 payout_eligibility_status text NOT NULL DEFAULT 'UNVERIFIED'
   CHECK(payout_eligibility_status IN ('UNVERIFIED','PENDING','ELIGIBLE','INELIGIBLE')),
 local_configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organisation_id,market_id)
);

CREATE TABLE organisation_addresses (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 address_id uuid NOT NULL REFERENCES addresses(id) ON DELETE RESTRICT,
 address_type text NOT NULL DEFAULT 'BUSINESS'
   CHECK(address_type IN ('BUSINESS','REGISTERED','MAILING','OTHER')),
 is_primary boolean NOT NULL DEFAULT false,
 UNIQUE(organisation_id,address_id,address_type)
);

CREATE TABLE organisation_categories (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parent_id uuid REFERENCES organisation_categories(id) ON DELETE SET NULL,
 code text NOT NULL UNIQUE,
 name text NOT NULL,
 is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE organisation_category_assignments (
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 category_id uuid NOT NULL REFERENCES organisation_categories(id) ON DELETE RESTRICT,
 PRIMARY KEY(organisation_id,category_id)
);

CREATE TABLE organisation_translations (
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 language_code varchar(16) NOT NULL REFERENCES languages(code),
 display_name text,
 description text,
 PRIMARY KEY(organisation_id,language_code)
);

CREATE TABLE organiser_followers (
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
 followed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(organisation_id,user_id)
);

CREATE TABLE organisation_badges (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 code text NOT NULL UNIQUE,
 label text NOT NULL,
 description text,
 is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE organisation_badge_assignments (
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 badge_id uuid NOT NULL REFERENCES organisation_badges(id) ON DELETE RESTRICT,
 assigned_by uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 starts_at timestamptz,
 ends_at timestamptz,
 PRIMARY KEY(organisation_id,badge_id),
 CHECK(ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

-- ---------------------------------------------------------------------------
-- 4. Venues, spaces, layouts and seat inventory
-- ---------------------------------------------------------------------------
CREATE TABLE venues (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid REFERENCES organisations(id) ON DELETE SET NULL,
 name text NOT NULL,
 description text,
 address_id uuid REFERENCES addresses(id) ON DELETE RESTRICT,
 venue_type text NOT NULL DEFAULT 'VENUE'
   CHECK(venue_type IN ('VENUE','ONLINE','OTHER')),
 website_url text,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE venue_spaces (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 venue_id uuid NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
 name text NOT NULL,
 capacity integer CHECK(capacity IS NULL OR capacity >= 0),
 admission_type text NOT NULL DEFAULT 'MIXED'
   CHECK(admission_type IN ('STANDING','SEATED','MIXED')),
 UNIQUE(venue_id,name)
);

CREATE TABLE venue_layouts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 venue_space_id uuid NOT NULL REFERENCES venue_spaces(id) ON DELETE CASCADE,
 name text NOT NULL,
 layout_data jsonb NOT NULL,
 is_active boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE venue_seats (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 venue_space_id uuid NOT NULL REFERENCES venue_spaces(id) ON DELETE CASCADE,
 section_name text,
 row_label text,
 seat_label text NOT NULL,
 seat_number integer,
 coordinates jsonb,
 is_accessible boolean NOT NULL DEFAULT false,
 is_active boolean NOT NULL DEFAULT true,
 UNIQUE(venue_space_id,section_name,row_label,seat_label)
);

CREATE TABLE venue_facilities (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 code text NOT NULL UNIQUE,
 name text NOT NULL
);

CREATE TABLE venue_facility_assignments (
 venue_id uuid NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
 facility_id uuid NOT NULL REFERENCES venue_facilities(id) ON DELETE RESTRICT,
 notes text,
 PRIMARY KEY(venue_id,facility_id)
);

CREATE TABLE venue_translations (
 venue_id uuid NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
 language_code varchar(16) NOT NULL REFERENCES languages(code),
 name text,
 description text,
 PRIMARY KEY(venue_id,language_code)
);

-- ---------------------------------------------------------------------------
-- 5. Event catalogue, occurrence scheduling and event content
-- ---------------------------------------------------------------------------
CREATE TABLE event_categories (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 parent_id uuid REFERENCES event_categories(id) ON DELETE SET NULL,
 code text NOT NULL UNIQUE,
 name text NOT NULL,
 is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE event_category_translations (
 category_id uuid NOT NULL REFERENCES event_categories(id) ON DELETE CASCADE,
 language_code varchar(16) NOT NULL REFERENCES languages(code),
 name text NOT NULL,
 description text,
 PRIMARY KEY(category_id,language_code)
);

CREATE TABLE events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE RESTRICT,
 created_by uuid NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
 title text NOT NULL,
 slug text NOT NULL,
 summary text,
 description jsonb NOT NULL DEFAULT '{}'::jsonb,
 source_language varchar(16) NOT NULL REFERENCES languages(code),
 event_type text NOT NULL DEFAULT 'IN_PERSON'
   CHECK(event_type IN ('IN_PERSON','ONLINE','HYBRID')),
 visibility text NOT NULL DEFAULT 'PUBLIC'
   CHECK(visibility IN ('PUBLIC','UNLISTED','PRIVATE','INVITE_ONLY')),
 status text NOT NULL DEFAULT 'DRAFT'
   CHECK(status IN ('DRAFT','PENDING_REVIEW','PUBLISHED','PAUSED','CANCELLED','COMPLETED','ARCHIVED')),
 age_minimum smallint CHECK(age_minimum IS NULL OR age_minimum >= 0),
 minors_require_adult boolean NOT NULL DEFAULT false,
 accompanying_adult_minimum_age smallint,
 refund_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
 content_blocks jsonb NOT NULL DEFAULT '[]'::jsonb,
 settings jsonb NOT NULL DEFAULT '{}'::jsonb,
 published_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organisation_id,slug)
);

CREATE TABLE event_market_assignments (
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE RESTRICT,
 sales_enabled boolean NOT NULL DEFAULT true,
 discovery_enabled boolean NOT NULL DEFAULT true,
 PRIMARY KEY(event_id,market_id)
);

CREATE TABLE event_translations (
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 language_code varchar(16) NOT NULL REFERENCES languages(code),
 title text,
 summary text,
 description jsonb,
 PRIMARY KEY(event_id,language_code)
);

CREATE TABLE event_category_assignments (
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 category_id uuid NOT NULL REFERENCES event_categories(id) ON DELETE RESTRICT,
 is_primary boolean NOT NULL DEFAULT false,
 PRIMARY KEY(event_id,category_id)
);

CREATE TABLE event_tags (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name text NOT NULL UNIQUE
);

CREATE TABLE event_tag_assignments (
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 tag_id uuid NOT NULL REFERENCES event_tags(id) ON DELETE CASCADE,
 PRIMARY KEY(event_id,tag_id)
);

CREATE TABLE event_media (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 media_asset_id uuid NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
 media_role text NOT NULL DEFAULT 'GALLERY'
   CHECK(media_role IN ('COVER','GALLERY','VIDEO','DOCUMENT')),
 display_order integer NOT NULL DEFAULT 0,
 alt_text text
);

CREATE TABLE event_organiser_assignments (
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE RESTRICT,
 assignment_role text NOT NULL DEFAULT 'CO_ORGANISER',
 PRIMARY KEY(event_id,organisation_id)
);

CREATE TABLE event_recurrence_rules (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 recurrence_type text NOT NULL CHECK(recurrence_type IN ('DAILY','WEEKLY','MONTHLY','CUSTOM')),
 interval_count integer NOT NULL DEFAULT 1 CHECK(interval_count > 0),
 days_of_week smallint[] NOT NULL DEFAULT ARRAY[]::smallint[],
 starts_on date NOT NULL,
 ends_on date,
 timezone_name text NOT NULL,
 rule_data jsonb NOT NULL DEFAULT '{}'::jsonb,
 is_active boolean NOT NULL DEFAULT true,
 CHECK(ends_on IS NULL OR ends_on >= starts_on)
);

CREATE TABLE event_occurrences (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 recurrence_rule_id uuid REFERENCES event_recurrence_rules(id) ON DELETE SET NULL,
 venue_id uuid REFERENCES venues(id) ON DELETE RESTRICT,
 venue_space_id uuid REFERENCES venue_spaces(id) ON DELETE RESTRICT,
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 timezone_name text NOT NULL,
 occurrence_status text NOT NULL DEFAULT 'SCHEDULED'
   CHECK(occurrence_status IN ('SCHEDULED','ON_SALE','SOLD_OUT','POSTPONED','CANCELLED','COMPLETED')),
 format text NOT NULL DEFAULT 'IN_PERSON'
   CHECK(format IN ('IN_PERSON','ONLINE','HYBRID')),
 capacity integer CHECK(capacity IS NULL OR capacity >= 0),
 admission_type text NOT NULL DEFAULT 'MIXED'
   CHECK(admission_type IN ('STANDING','SEATED','MIXED')),
 online_join_url text,
 minimum_age_override smallint,
 settings jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(ends_at > starts_at),
 CHECK((format='ONLINE') OR venue_id IS NOT NULL)
);

CREATE INDEX event_occurrences_schedule_idx ON event_occurrences(starts_at,ends_at);
CREATE INDEX events_discovery_idx ON events(status,visibility,published_at);

CREATE TABLE event_occurrence_locations (
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 address_id uuid NOT NULL REFERENCES addresses(id) ON DELETE RESTRICT,
 location_label text,
 PRIMARY KEY(occurrence_id,address_id)
);

CREATE TABLE event_sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 title text NOT NULL,
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 timezone_name text NOT NULL,
 session_type text NOT NULL DEFAULT 'GENERAL',
 settings jsonb NOT NULL DEFAULT '{}'::jsonb,
 CHECK(ends_at > starts_at)
);

CREATE TABLE event_agenda_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 session_id uuid REFERENCES event_sessions(id) ON DELETE SET NULL,
 title text NOT NULL,
 description text,
 starts_at timestamptz,
 ends_at timestamptz,
 display_order integer NOT NULL DEFAULT 0,
 speaker_names jsonb NOT NULL DEFAULT '[]'::jsonb,
 CHECK(ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE event_speakers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 name text NOT NULL,
 title text,
 biography text,
 media_asset_id uuid REFERENCES media_assets(id) ON DELETE SET NULL
);

CREATE TABLE event_sponsors (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 name text NOT NULL,
 website_url text,
 logo_media_id uuid REFERENCES media_assets(id) ON DELETE SET NULL,
 sponsorship_level text
);

CREATE TABLE event_faqs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 question text NOT NULL,
 answer text NOT NULL,
 display_order integer NOT NULL DEFAULT 0
);

CREATE TABLE event_links (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 label text NOT NULL,
 url text NOT NULL,
 link_type text NOT NULL DEFAULT 'OTHER'
);

CREATE TABLE event_access_links (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 token_hash text NOT NULL UNIQUE,
 access_type text NOT NULL DEFAULT 'INVITATION',
 max_uses integer,
 uses_count integer NOT NULL DEFAULT 0,
 expires_at timestamptz,
 created_by uuid REFERENCES identity.users(id),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE event_highlights (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 icon_code text,
 label text NOT NULL,
 detail text,
 display_order integer NOT NULL DEFAULT 0
);

CREATE TABLE event_disclaimers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 disclaimer_type text NOT NULL DEFAULT 'GENERAL',
 content text NOT NULL,
 language_code varchar(16) REFERENCES languages(code),
 display_order integer NOT NULL DEFAULT 0
);

CREATE TABLE event_status_history (
 id bigserial PRIMARY KEY,
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 old_status text,
 new_status text NOT NULL,
 changed_by uuid REFERENCES identity.users(id),
 reason text,
 changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE event_occurrence_status_history (
 id bigserial PRIMARY KEY,
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 old_status text,
 new_status text NOT NULL,
 changed_by uuid REFERENCES identity.users(id),
 reason text,
 changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE event_staff_assignments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
 staff_role text NOT NULL
   CHECK(staff_role IN ('MANAGER','CHECK_IN','BOX_OFFICE','SUPPORT')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(occurrence_id,user_id)
);

-- ---------------------------------------------------------------------------
-- 6. Registration form configuration and responses
-- ---------------------------------------------------------------------------
CREATE TABLE event_registration_forms (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 name text NOT NULL,
 form_schema jsonb NOT NULL DEFAULT '[]'::jsonb,
 is_required boolean NOT NULL DEFAULT true,
 is_active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE registration_responses (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 form_id uuid NOT NULL REFERENCES event_registration_forms(id) ON DELETE CASCADE,
 order_item_id uuid,
 attendee_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 attendee_email text,
 answers jsonb NOT NULL DEFAULT '{}'::jsonb,
 submitted_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 7. Ticket types, prices, inventory, reservations, orders and tickets
-- ---------------------------------------------------------------------------
CREATE TABLE ticket_types (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 name text NOT NULL,
 description text,
 ticket_kind text NOT NULL DEFAULT 'STANDARD'
   CHECK(ticket_kind IN ('STANDARD','VIP','COMPLIMENTARY','DONATION','GROUP','ADD_ON')),
 admission_type text NOT NULL DEFAULT 'GENERAL'
   CHECK(admission_type IN ('GENERAL','RESERVED_SEAT','TIMED_ENTRY')),
 min_per_order integer NOT NULL DEFAULT 1 CHECK(min_per_order > 0),
 max_per_order integer NOT NULL DEFAULT 10 CHECK(max_per_order >= min_per_order),
 sale_starts_at timestamptz,
 sale_ends_at timestamptz,
 is_active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(event_id,name)
);

CREATE TABLE ticket_price_tiers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 ticket_type_id uuid NOT NULL REFERENCES ticket_types(id) ON DELETE CASCADE,
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE RESTRICT,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 name text NOT NULL,
 unit_amount_minor bigint NOT NULL CHECK(unit_amount_minor >= 0),
 sales_start timestamptz,
 sales_end timestamptz,
 quantity_limit integer CHECK(quantity_limit IS NULL OR quantity_limit >= 0),
 priority integer NOT NULL DEFAULT 0,
 is_active boolean NOT NULL DEFAULT true,
 CHECK(sales_end IS NULL OR sales_start IS NULL OR sales_end > sales_start)
);

CREATE TABLE ticket_inventory (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 ticket_type_id uuid NOT NULL REFERENCES ticket_types(id) ON DELETE CASCADE,
 capacity integer NOT NULL CHECK(capacity >= 0),
 sold_quantity integer NOT NULL DEFAULT 0 CHECK(sold_quantity >= 0),
 reserved_quantity integer NOT NULL DEFAULT 0 CHECK(reserved_quantity >= 0),
 version bigint NOT NULL DEFAULT 0,
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(occurrence_id,ticket_type_id),
 CHECK(sold_quantity + reserved_quantity <= capacity)
);

CREATE TABLE ticket_inventory_movements (
 id bigserial PRIMARY KEY,
 inventory_id uuid NOT NULL REFERENCES ticket_inventory(id) ON DELETE RESTRICT,
 movement_type text NOT NULL
   CHECK(movement_type IN ('RESERVE','RELEASE','SELL','REFUND_RETURN','ADJUSTMENT')),
 quantity integer NOT NULL CHECK(quantity > 0),
 reference_type text NOT NULL,
 reference_id uuid,
 note text,
 created_by uuid REFERENCES identity.users(id),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ticket_reservations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE RESTRICT,
 user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 guest_email text,
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE RESTRICT,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 status text NOT NULL DEFAULT 'ACTIVE'
   CHECK(status IN ('ACTIVE','CONVERTED','EXPIRED','CANCELLED')),
 expires_at timestamptz NOT NULL,
 idempotency_key text NOT NULL UNIQUE,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(user_id IS NOT NULL OR guest_email IS NOT NULL)
);

CREATE INDEX ticket_reservations_expiry_idx ON ticket_reservations(status,expires_at);

CREATE TABLE ticket_reservation_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 reservation_id uuid NOT NULL REFERENCES ticket_reservations(id) ON DELETE CASCADE,
 ticket_type_id uuid NOT NULL REFERENCES ticket_types(id) ON DELETE RESTRICT,
 price_tier_id uuid REFERENCES ticket_price_tiers(id) ON DELETE RESTRICT,
 quantity integer NOT NULL CHECK(quantity > 0),
 unit_amount_minor bigint NOT NULL CHECK(unit_amount_minor >= 0),
 line_total_minor bigint NOT NULL CHECK(line_total_minor >= 0),
 seat_id uuid REFERENCES venue_seats(id) ON DELETE RESTRICT,
 UNIQUE(reservation_id,ticket_type_id,seat_id)
);

CREATE TABLE ticket_seat_holds (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 seat_id uuid NOT NULL REFERENCES venue_seats(id) ON DELETE RESTRICT,
 reservation_id uuid NOT NULL REFERENCES ticket_reservations(id) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(occurrence_id,seat_id)
);

CREATE TABLE orders (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_number text NOT NULL UNIQUE,
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE RESTRICT,
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE RESTRICT,
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE RESTRICT,
 buyer_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 buyer_name text,
 buyer_email text,
 buyer_phone text,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 channel text NOT NULL DEFAULT 'ONLINE'
   CHECK(channel IN ('ONLINE','BOX_OFFICE','ADMIN')),
 status text NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','AWAITING_PAYMENT','PAID','PARTIALLY_REFUNDED','REFUNDED','CANCELLED','EXPIRED')),
 subtotal_minor bigint NOT NULL DEFAULT 0,
 discount_minor bigint NOT NULL DEFAULT 0,
 fee_minor bigint NOT NULL DEFAULT 0,
 tax_minor bigint NOT NULL DEFAULT 0,
 total_minor bigint NOT NULL DEFAULT 0,
 idempotency_key text UNIQUE,
 paid_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(total_minor >= 0),
 CHECK(discount_minor >= 0 AND fee_minor >= 0 AND tax_minor >= 0)
);

CREATE INDEX orders_org_created_idx ON orders(organisation_id,created_at DESC);
CREATE INDEX orders_event_status_idx ON orders(event_id,status);

CREATE TABLE order_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 ticket_type_id uuid NOT NULL REFERENCES ticket_types(id) ON DELETE RESTRICT,
 price_tier_id uuid REFERENCES ticket_price_tiers(id) ON DELETE SET NULL,
 description_snapshot text NOT NULL,
 quantity integer NOT NULL CHECK(quantity > 0),
 unit_amount_minor bigint NOT NULL CHECK(unit_amount_minor >= 0),
 discount_minor bigint NOT NULL DEFAULT 0,
 fee_minor bigint NOT NULL DEFAULT 0,
 tax_minor bigint NOT NULL DEFAULT 0,
 line_total_minor bigint NOT NULL CHECK(line_total_minor >= 0),
 seat_id uuid REFERENCES venue_seats(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE registration_responses ADD CONSTRAINT registration_responses_order_item_fk
 FOREIGN KEY(order_item_id) REFERENCES order_items(id) ON DELETE CASCADE;

CREATE TABLE order_status_history (
 id bigserial PRIMARY KEY,
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
 old_status text,
 new_status text NOT NULL,
 changed_by uuid REFERENCES identity.users(id),
 reason text,
 changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tickets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_item_id uuid NOT NULL REFERENCES order_items(id) ON DELETE RESTRICT,
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE RESTRICT,
 ticket_code text NOT NULL UNIQUE,
 qr_payload_hash text NOT NULL UNIQUE,
 attendee_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 attendee_name text,
 attendee_email text,
 seat_id uuid REFERENCES venue_seats(id) ON DELETE RESTRICT,
 status text NOT NULL DEFAULT 'VALID'
   CHECK(status IN ('VALID','TRANSFERRED','CHECKED_IN','CANCELLED','REFUNDED')),
 issued_at timestamptz NOT NULL DEFAULT now(),
 checked_in_at timestamptz
);

CREATE INDEX tickets_occurrence_status_idx ON tickets(occurrence_id,status);

CREATE TABLE ticket_transfers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 ticket_id uuid NOT NULL REFERENCES tickets(id) ON DELETE RESTRICT,
 from_user_id uuid REFERENCES identity.users(id),
 to_user_id uuid REFERENCES identity.users(id),
 recipient_email text,
 transfer_token_hash text NOT NULL UNIQUE,
 status text NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','CANCELLED')),
 expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 accepted_at timestamptz
);

CREATE TABLE discount_codes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 code_normalized text NOT NULL,
 discount_type text NOT NULL CHECK(discount_type IN ('PERCENTAGE','FIXED')),
 discount_value bigint NOT NULL CHECK(discount_value > 0),
 currency_code char(3) REFERENCES currencies(code),
 starts_at timestamptz,
 ends_at timestamptz,
 max_redemptions integer,
 per_user_limit integer,
 redeemed_count integer NOT NULL DEFAULT 0,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','PAUSED','EXPIRED')),
 UNIQUE(organisation_id,code_normalized),
 CHECK(ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE discount_redemptions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 discount_code_id uuid NOT NULL REFERENCES discount_codes(id) ON DELETE RESTRICT,
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 discount_amount_minor bigint NOT NULL CHECK(discount_amount_minor >= 0),
 redeemed_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(discount_code_id,order_id)
);

CREATE TABLE waitlist_entries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE CASCADE,
 ticket_type_id uuid NOT NULL REFERENCES ticket_types(id) ON DELETE CASCADE,
 user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 email text NOT NULL,
 quantity integer NOT NULL DEFAULT 1 CHECK(quantity > 0),
 status text NOT NULL DEFAULT 'WAITING'
   CHECK(status IN ('WAITING','OFFERED','CONVERTED','REMOVED','EXPIRED')),
 joined_at timestamptz NOT NULL DEFAULT now(),
 offered_until timestamptz
);

CREATE TABLE waitlist_offers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 waitlist_entry_id uuid NOT NULL REFERENCES waitlist_entries(id) ON DELETE CASCADE,
 reservation_id uuid REFERENCES ticket_reservations(id) ON DELETE SET NULL,
 offer_token_hash text NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE event_addons (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 name text NOT NULL,
 description text,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 unit_amount_minor bigint NOT NULL CHECK(unit_amount_minor >= 0),
 quantity_limit integer CHECK(quantity_limit IS NULL OR quantity_limit >= 0),
 is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE order_addons (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 event_addon_id uuid NOT NULL REFERENCES event_addons(id) ON DELETE RESTRICT,
 description_snapshot text NOT NULL,
 quantity integer NOT NULL CHECK(quantity > 0),
 unit_amount_minor bigint NOT NULL CHECK(unit_amount_minor >= 0),
 line_total_minor bigint NOT NULL CHECK(line_total_minor >= 0)
);

-- ---------------------------------------------------------------------------
-- 8. Box office, check-in and offline sync
-- ---------------------------------------------------------------------------
CREATE TABLE box_office_sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE RESTRICT,
 opened_by uuid NOT NULL REFERENCES identity.users(id),
 closed_by uuid REFERENCES identity.users(id),
 status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),
 opening_cash_minor bigint NOT NULL DEFAULT 0,
 closing_cash_minor bigint,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 opened_at timestamptz NOT NULL DEFAULT now(),
 closed_at timestamptz
);

CREATE TABLE cash_drawer_movements (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 session_id uuid NOT NULL REFERENCES box_office_sessions(id) ON DELETE RESTRICT,
 movement_type text NOT NULL CHECK(movement_type IN ('CASH_SALE','CASH_REFUND','CASH_IN','CASH_OUT')),
 amount_minor bigint NOT NULL CHECK(amount_minor > 0),
 reference_order_id uuid REFERENCES orders(id) ON DELETE SET NULL,
 note text,
 created_by uuid REFERENCES identity.users(id),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE checkin_devices (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 device_identifier text NOT NULL,
 device_name text,
 last_seen_at timestamptz,
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organisation_id,device_identifier)
);

CREATE TABLE ticket_scan_logs (
 id bigserial PRIMARY KEY,
 ticket_id uuid REFERENCES tickets(id) ON DELETE SET NULL,
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE RESTRICT,
 device_id uuid REFERENCES checkin_devices(id) ON DELETE SET NULL,
 scanned_code_hash text NOT NULL,
 result text NOT NULL CHECK(result IN ('ACCEPTED','ALREADY_USED','INVALID','WRONG_EVENT','REVOKED','UNKNOWN')),
 scanned_at timestamptz NOT NULL,
 received_at timestamptz NOT NULL DEFAULT now(),
 client_scan_id text,
 UNIQUE(device_id,client_scan_id)
);

CREATE TABLE ticket_check_ins (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 ticket_id uuid NOT NULL UNIQUE REFERENCES tickets(id) ON DELETE RESTRICT,
 occurrence_id uuid NOT NULL REFERENCES event_occurrences(id) ON DELETE RESTRICT,
 checked_in_by uuid REFERENCES identity.users(id),
 device_id uuid REFERENCES checkin_devices(id),
 checked_in_at timestamptz NOT NULL DEFAULT now(),
 source text NOT NULL DEFAULT 'SCANNER' CHECK(source IN ('SCANNER','MANUAL','IMPORT'))
);

-- ---------------------------------------------------------------------------
-- 9. Payments, refunds, fees, payouts and accounting ledger
-- ---------------------------------------------------------------------------
CREATE TABLE payment_provider_accounts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE RESTRICT,
 provider_code text NOT NULL,
 account_name text NOT NULL,
 merchant_reference text,
 secret_reference text NOT NULL,
 supported_currencies char(3)[] NOT NULL DEFAULT ARRAY[]::char(3)[],
 status text NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','ACTIVE','SUSPENDED','DISABLED')),
 capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(market_id,provider_code,account_name)
);

CREATE TABLE payment_provider_methods (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 provider_account_id uuid NOT NULL REFERENCES payment_provider_accounts(id) ON DELETE CASCADE,
 method_code text NOT NULL,
 display_name text NOT NULL,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 transaction_flow text NOT NULL DEFAULT 'COLLECT'
   CHECK(transaction_flow IN ('COLLECT','REFUND','PAYOUT')),
 is_enabled boolean NOT NULL DEFAULT true,
 capabilities jsonb NOT NULL DEFAULT '{}'::jsonb,
 UNIQUE(provider_account_id,method_code,currency_code,transaction_flow)
);

CREATE TABLE customer_payment_methods (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
 provider_account_id uuid REFERENCES payment_provider_accounts(id) ON DELETE SET NULL,
 provider_method_reference text NOT NULL,
 method_type text NOT NULL,
 display_label text,
 last_four text,
 expires_month smallint,
 expires_year smallint,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,provider_method_reference)
);

CREATE TABLE payments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 provider_account_id uuid REFERENCES payment_provider_accounts(id) ON DELETE RESTRICT,
 payment_method_id uuid REFERENCES customer_payment_methods(id) ON DELETE SET NULL,
 provider_reference text,
 idempotency_key text NOT NULL UNIQUE,
 amount_minor bigint NOT NULL CHECK(amount_minor > 0),
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 status text NOT NULL DEFAULT 'INITIATED'
   CHECK(status IN ('INITIATED','PENDING','SUCCEEDED','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED')),
 checkout_url text,
 initiated_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 provider_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
 UNIQUE(provider_account_id,provider_reference)
);

CREATE TABLE payment_attempts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
 attempt_number integer NOT NULL CHECK(attempt_number > 0),
 status text NOT NULL,
 request_reference text,
 response_code text,
 error_message text,
 attempted_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(payment_id,attempt_number)
);

CREATE TABLE payment_webhook_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 provider_account_id uuid NOT NULL REFERENCES payment_provider_accounts(id) ON DELETE RESTRICT,
 provider_event_id text NOT NULL,
 event_type text NOT NULL,
 signature_valid boolean NOT NULL,
 payload jsonb NOT NULL,
 processing_status text NOT NULL DEFAULT 'RECEIVED'
   CHECK(processing_status IN ('RECEIVED','PROCESSING','PROCESSED','FAILED','IGNORED')),
 received_at timestamptz NOT NULL DEFAULT now(),
 processed_at timestamptz,
 error_message text,
 UNIQUE(provider_account_id,provider_event_id)
);

CREATE TABLE refunds (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 payment_id uuid REFERENCES payments(id) ON DELETE RESTRICT,
 provider_reference text,
 idempotency_key text NOT NULL UNIQUE,
 amount_minor bigint NOT NULL CHECK(amount_minor > 0),
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 reason text,
 status text NOT NULL DEFAULT 'REQUESTED'
   CHECK(status IN ('REQUESTED','PENDING','SUCCEEDED','FAILED','REJECTED')),
 requested_by uuid REFERENCES identity.users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz
);

CREATE TABLE refund_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 refund_id uuid NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
 ticket_id uuid REFERENCES tickets(id) ON DELETE RESTRICT,
 order_item_id uuid REFERENCES order_items(id) ON DELETE RESTRICT,
 amount_minor bigint NOT NULL CHECK(amount_minor > 0),
 reason text,
 CHECK(ticket_id IS NOT NULL OR order_item_id IS NOT NULL)
);

CREATE TABLE fee_rules (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE CASCADE,
 fee_code text NOT NULL,
 calculation_type text NOT NULL CHECK(calculation_type IN ('FIXED','PERCENTAGE')),
 fixed_amount_minor bigint,
 percentage numeric(8,5),
 currency_code char(3) REFERENCES currencies(code),
 applies_to text NOT NULL CHECK(applies_to IN ('BUYER','ORGANISER','BOTH')),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz,
 is_active boolean NOT NULL DEFAULT true,
 CHECK(ends_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE order_financial_lines (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 line_type text NOT NULL CHECK(line_type IN ('TICKET','DISCOUNT','PLATFORM_FEE','PROCESSING_FEE','TAX','ADJUSTMENT')),
 description text NOT NULL,
 amount_minor bigint NOT NULL,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 tax_code text,
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organiser_payout_accounts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_market_profile_id uuid NOT NULL REFERENCES organisation_market_profiles(id) ON DELETE RESTRICT,
 provider_account_id uuid REFERENCES payment_provider_accounts(id) ON DELETE RESTRICT,
 payout_method text NOT NULL,
 recipient_reference text NOT NULL,
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 verification_status text NOT NULL DEFAULT 'PENDING'
   CHECK(verification_status IN ('PENDING','VERIFIED','REJECTED')),
 secret_reference text,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payouts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 payout_account_id uuid NOT NULL REFERENCES organiser_payout_accounts(id) ON DELETE RESTRICT,
 provider_reference text,
 idempotency_key text NOT NULL UNIQUE,
 amount_minor bigint NOT NULL CHECK(amount_minor > 0),
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 status text NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','PROCESSING','PAID','FAILED','CANCELLED')),
 requested_at timestamptz NOT NULL DEFAULT now(),
 paid_at timestamptz
);

CREATE TABLE payout_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 payout_id uuid NOT NULL REFERENCES payouts(id) ON DELETE CASCADE,
 order_id uuid REFERENCES orders(id) ON DELETE RESTRICT,
 amount_minor bigint NOT NULL,
 description text
);

CREATE TABLE ledger_accounts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 account_code text NOT NULL UNIQUE,
 name text NOT NULL,
 account_type text NOT NULL CHECK(account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE ledger_transactions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 transaction_reference text NOT NULL UNIQUE,
 source_type text NOT NULL,
 source_id uuid NOT NULL,
 description text,
 status text NOT NULL DEFAULT 'POSTED' CHECK(status IN ('DRAFT','POSTED','REVERSED')),
 posted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
 id bigserial PRIMARY KEY,
 transaction_id uuid NOT NULL REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
 account_id uuid NOT NULL REFERENCES ledger_accounts(id) ON DELETE RESTRICT,
 debit_minor bigint NOT NULL DEFAULT 0 CHECK(debit_minor >= 0),
 credit_minor bigint NOT NULL DEFAULT 0 CHECK(credit_minor >= 0),
 currency_code char(3) NOT NULL REFERENCES currencies(code),
 memo text,
 CHECK((debit_minor > 0 AND credit_minor = 0) OR (credit_minor > 0 AND debit_minor = 0))
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries(account_id, id);

CREATE TABLE reconciliation_runs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 provider_account_id uuid NOT NULL REFERENCES payment_provider_accounts(id) ON DELETE RESTRICT,
 started_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 status text NOT NULL DEFAULT 'RUNNING' CHECK(status IN ('RUNNING','COMPLETED','FAILED')),
 summary jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE reconciliation_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 run_id uuid NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
 internal_reference text,
 provider_reference text,
 internal_amount_minor bigint,
 provider_amount_minor bigint,
 currency_code char(3) REFERENCES currencies(code),
 result text NOT NULL CHECK(result IN ('MATCHED','MISSING_INTERNAL','MISSING_PROVIDER','AMOUNT_MISMATCH','CURRENCY_MISMATCH')),
 details jsonb NOT NULL DEFAULT '{}'::jsonb
);

-- ---------------------------------------------------------------------------
-- 10. Notifications, marketing, discovery and analytics
-- ---------------------------------------------------------------------------
CREATE TABLE notification_templates (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 code text NOT NULL,
 channel text NOT NULL CHECK(channel IN ('EMAIL','SMS','PUSH','IN_APP')),
 language_code varchar(16) NOT NULL REFERENCES languages(code),
 subject_template text,
 body_template text NOT NULL,
 is_active boolean NOT NULL DEFAULT true,
 UNIQUE(code,channel,language_code)
);

CREATE TABLE notifications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
 template_id uuid REFERENCES notification_templates(id) ON DELETE SET NULL,
 notification_type text NOT NULL,
 title text NOT NULL,
 body text NOT NULL,
 payload jsonb NOT NULL DEFAULT '{}'::jsonb,
 read_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE notification_deliveries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
 channel text NOT NULL,
 provider_code text,
 provider_message_id text,
 status text NOT NULL DEFAULT 'QUEUED'
   CHECK(status IN ('QUEUED','SENT','DELIVERED','FAILED','BOUNCED')),
 attempts integer NOT NULL DEFAULT 0,
 last_error text,
 sent_at timestamptz,
 delivered_at timestamptz
);

CREATE TABLE user_notification_preferences (
 user_id uuid PRIMARY KEY REFERENCES identity.users(id) ON DELETE CASCADE,
 email_enabled boolean NOT NULL DEFAULT true,
 sms_enabled boolean NOT NULL DEFAULT true,
 push_enabled boolean NOT NULL DEFAULT true,
 marketing_enabled boolean NOT NULL DEFAULT false,
 preferences jsonb NOT NULL DEFAULT '{}'::jsonb,
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE saved_events (
 user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 saved_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,event_id)
);

CREATE TABLE tracking_links (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 code text NOT NULL UNIQUE,
 source text,
 medium text,
 campaign text,
 created_by uuid REFERENCES identity.users(id),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE analytics_events (
 id bigserial PRIMARY KEY,
 event_name text NOT NULL,
 occurred_at timestamptz NOT NULL,
 user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 organisation_id uuid REFERENCES organisations(id) ON DELETE SET NULL,
 event_id uuid REFERENCES events(id) ON DELETE SET NULL,
 market_id uuid REFERENCES markets(id) ON DELETE SET NULL,
 session_id text,
 anonymous_id text,
 properties jsonb NOT NULL DEFAULT '{}'::jsonb,
 received_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX analytics_events_time_idx ON analytics_events(occurred_at);
CREATE INDEX analytics_events_event_idx ON analytics_events(event_id,occurred_at);

CREATE TABLE event_reviews (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 event_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
 occurrence_id uuid REFERENCES event_occurrences(id) ON DELETE SET NULL,
 user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
 rating smallint NOT NULL CHECK(rating BETWEEN 1 AND 5),
 review_text text,
 status text NOT NULL DEFAULT 'PUBLISHED'
   CHECK(status IN ('PENDING','PUBLISHED','HIDDEN','REMOVED')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(event_id,user_id)
);

CREATE TABLE organiser_inquiries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 event_id uuid REFERENCES events(id) ON DELETE SET NULL,
 sender_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 sender_email text NOT NULL,
 subject text,
 message text NOT NULL,
 status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ANSWERED','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE support_tickets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 order_id uuid REFERENCES orders(id) ON DELETE SET NULL,
 category text NOT NULL,
 subject text NOT NULL,
 description text NOT NULL,
 status text NOT NULL DEFAULT 'OPEN'
   CHECK(status IN ('OPEN','IN_PROGRESS','WAITING','RESOLVED','CLOSED')),
 priority text NOT NULL DEFAULT 'NORMAL'
   CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE content_reports (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 reporter_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 target_type text NOT NULL CHECK(target_type IN ('EVENT','ORGANISATION','REVIEW','USER')),
 target_id uuid NOT NULL,
 reason_code text NOT NULL,
 description text,
 status text NOT NULL DEFAULT 'OPEN'
   CHECK(status IN ('OPEN','REVIEWING','ACTIONED','DISMISSED')),
 created_at timestamptz NOT NULL DEFAULT now(),
 resolved_at timestamptz
);

CREATE TABLE admin_audit_logs (
 id bigserial PRIMARY KEY,
 actor_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
 action text NOT NULL,
 target_type text NOT NULL,
 target_id uuid,
 before_state jsonb,
 after_state jsonb,
 ip_address inet,
 created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 11. Platform integrations, subscriptions and legal document versioning
-- ---------------------------------------------------------------------------
CREATE TABLE integration_connections (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid REFERENCES organisations(id) ON DELETE CASCADE,
 market_id uuid REFERENCES markets(id) ON DELETE CASCADE,
 provider_code text NOT NULL,
 external_account_id text,
 secret_reference text,
 configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
 status text NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','ACTIVE','ERROR','DISCONNECTED')),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE webhook_endpoints (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid REFERENCES organisations(id) ON DELETE CASCADE,
 endpoint_url text NOT NULL,
 secret_reference text NOT NULL,
 subscribed_events text[] NOT NULL DEFAULT ARRAY[]::text[],
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE webhook_deliveries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 endpoint_id uuid NOT NULL REFERENCES webhook_endpoints(id) ON DELETE CASCADE,
 event_id text NOT NULL,
 payload jsonb NOT NULL,
 status text NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','DELIVERED','FAILED')),
 attempt_count integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz,
 delivered_at timestamptz,
 UNIQUE(endpoint_id,event_id)
);

CREATE TABLE platform_plans (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 code text NOT NULL UNIQUE,
 name text NOT NULL,
 description text,
 is_active boolean NOT NULL DEFAULT true,
 features jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE organisation_subscriptions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
 market_id uuid NOT NULL REFERENCES markets(id) ON DELETE RESTRICT,
 plan_id uuid NOT NULL REFERENCES platform_plans(id) ON DELETE RESTRICT,
 status text NOT NULL DEFAULT 'ACTIVE'
   CHECK(status IN ('TRIAL','ACTIVE','PAST_DUE','CANCELLED','EXPIRED')),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz,
 external_subscription_reference text,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(ends_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE legal_documents (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 code text NOT NULL UNIQUE,
 scope text NOT NULL CHECK(scope IN ('GLOBAL','MARKET')),
 market_id uuid REFERENCES markets(id) ON DELETE CASCADE,
 document_type text NOT NULL,
 is_active boolean NOT NULL DEFAULT true,
 CHECK((scope='GLOBAL' AND market_id IS NULL) OR (scope='MARKET' AND market_id IS NOT NULL))
);

CREATE TABLE legal_document_versions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL REFERENCES legal_documents(id) ON DELETE CASCADE,
 version_label text NOT NULL,
 language_code varchar(16) NOT NULL REFERENCES languages(code),
 content_uri text NOT NULL,
 effective_from timestamptz NOT NULL,
 effective_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(document_id,version_label,language_code),
 CHECK(effective_until IS NULL OR effective_until > effective_from)
);

-- ---------------------------------------------------------------------------
-- 12. Integrity helpers and update timestamps
-- ---------------------------------------------------------------------------
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.updated_at = now();
 RETURN NEW;
END;
$$;

DO $$
DECLARE r record;
BEGIN
 FOR r IN
  SELECT table_name
  FROM information_schema.columns
  WHERE table_schema='event_platform' AND column_name='updated_at'
 LOOP
  EXECUTE format('CREATE TRIGGER %I_set_updated_at BEFORE UPDATE ON event_platform.%I FOR EACH ROW EXECUTE FUNCTION event_platform.set_updated_at()', r.table_name, r.table_name);
 END LOOP;
END $$;

-- Prevent a ledger transaction from being posted unless debits equal credits.
CREATE FUNCTION assert_balanced_ledger_transaction() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE txn_id uuid;
DECLARE debit_total numeric;
DECLARE credit_total numeric;
DECLARE txn_status text;
BEGIN
 txn_id := COALESCE(NEW.transaction_id, OLD.transaction_id);
 SELECT status INTO txn_status FROM ledger_transactions WHERE id=txn_id;
 IF txn_status='POSTED' THEN
  SELECT COALESCE(sum(debit_minor),0), COALESCE(sum(credit_minor),0)
    INTO debit_total,credit_total
  FROM ledger_entries WHERE transaction_id=txn_id;
  IF debit_total <> credit_total THEN
   RAISE EXCEPTION 'Ledger transaction % is not balanced: debits %, credits %',txn_id,debit_total,credit_total;
  END IF;
 END IF;
 RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balance_check
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_balanced_ledger_transaction();

-- Helpful indexes for common access paths.
CREATE INDEX organisation_members_user_idx ON organisation_members(user_id,status);
CREATE INDEX events_org_status_idx ON events(organisation_id,status);
CREATE INDEX event_market_market_idx ON event_market_assignments(market_id,event_id);
CREATE INDEX ticket_types_event_idx ON ticket_types(event_id,is_active);
CREATE INDEX order_items_order_idx ON order_items(order_id);
CREATE INDEX payments_order_status_idx ON payments(order_id,status);
CREATE INDEX notifications_user_created_idx ON notifications(user_id,created_at DESC);
CREATE INDEX tickets_code_idx ON tickets(ticket_code);


-- Seed core ISO currency/language examples. Extend through controlled migrations.
INSERT INTO event_platform.currencies(code,name,symbol,minor_unit) VALUES
 ('ETB','Ethiopian Birr','Br',2),
 ('KES','Kenyan Shilling','KSh',2),
 ('UGX','Ugandan Shilling','USh',0),
 ('TZS','Tanzanian Shilling','TSh',2),
 ('RWF','Rwandan Franc','RF',0),
 ('USD','US Dollar','$',2),
 ('GBP','Pound Sterling','£',2)
ON CONFLICT DO NOTHING;

INSERT INTO event_platform.languages(code,english_name,native_name) VALUES
 ('en','English','English'),
 ('am','Amharic','አማርኛ'),
 ('ti','Tigrinya','ትግርኛ'),
 ('om','Oromo','Afaan Oromoo'),
 ('sw','Swahili','Kiswahili'),
 ('ar','Arabic','العربية')
ON CONFLICT DO NOTHING;

INSERT INTO event_platform.countries(iso2,iso3,name,default_timezone) VALUES
 ('ET','ETH','Ethiopia','Africa/Addis_Ababa'),
 ('KE','KEN','Kenya','Africa/Nairobi'),
 ('UG','UGA','Uganda','Africa/Kampala'),
 ('TZ','TZA','Tanzania','Africa/Dar_es_Salaam'),
 ('RW','RWA','Rwanda','Africa/Kigali')
ON CONFLICT DO NOTHING;

INSERT INTO event_platform.markets(country_id,code,name,default_currency,default_language,default_timezone,status)
SELECT id,'ET','Ethiopia','ETB','am','Africa/Addis_Ababa','ACTIVE'
FROM event_platform.countries WHERE iso2='ET'
ON CONFLICT DO NOTHING;

INSERT INTO event_platform.market_currencies(market_id,currency_code,is_default)
SELECT m.id,'ETB',true FROM event_platform.markets m WHERE m.code='ET'
ON CONFLICT DO NOTHING;

INSERT INTO event_platform.market_languages(market_id,language_code,is_default)
SELECT m.id,l.code,(l.code='am') FROM event_platform.markets m
CROSS JOIN event_platform.languages l WHERE m.code='ET' AND l.code IN ('am','en','ti','om')
ON CONFLICT DO NOTHING;

-- Install-time assertions: ensure required extensions and schema exist.
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname='postgis') THEN
  RAISE EXCEPTION 'PostGIS extension is required';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname='ltree') THEN
  RAISE EXCEPTION 'ltree extension is required';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema='event_platform' AND table_name='orders') THEN
  RAISE EXCEPTION 'Install failed: core orders table missing';
 END IF;
END $$;




-- Event table dictionary comments added during the shared-platform integration.
COMMENT ON TABLE event_platform.countries IS 'Country reference records used by the event market catalogue.';
COMMENT ON TABLE event_platform.administrative_areas IS 'Country-specific hierarchical administrative geography.';
COMMENT ON TABLE event_platform.addresses IS 'Structured postal/landmark address and PostGIS point location.';
COMMENT ON TABLE event_platform.currencies IS 'ISO currency code, display metadata and minor-unit precision.';
COMMENT ON TABLE event_platform.languages IS 'Supported language catalogue, native names and right-to-left settings.';
COMMENT ON TABLE event_platform.markets IS 'Commercial market activation, defaults and country-specific configuration.';
COMMENT ON TABLE event_platform.market_currencies IS 'Currencies enabled for a market and its default sales currency.';
COMMENT ON TABLE event_platform.market_languages IS 'Languages enabled for a market and its default language.';
COMMENT ON TABLE event_platform.country_calendar_settings IS 'Country calendar presentation and fiscal-year configuration.';
COMMENT ON TABLE event_platform.media_assets IS 'Event-product object-storage metadata, visibility and processing status.';
COMMENT ON TABLE event_platform.organisations IS 'Organiser tenant root, public identity and verification state.';
COMMENT ON TABLE event_platform.organisation_members IS 'User membership and organiser-specific role within an organisation.';
COMMENT ON TABLE event_platform.organisation_invitations IS 'Invitations to join an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_market_profiles IS 'Market-specific legal, tax, verification and payout profile for an organisation.';
COMMENT ON TABLE event_platform.organisation_addresses IS 'Address links and address purpose for an organisation.';
COMMENT ON TABLE event_platform.organisation_categories IS 'Platform-maintained organiser category taxonomy.';
COMMENT ON TABLE event_platform.organisation_category_assignments IS 'Many-to-many mapping of organisations to organiser categories.';
COMMENT ON TABLE event_platform.organisation_translations IS 'Localised organiser names and descriptions.';
COMMENT ON TABLE event_platform.organiser_followers IS 'Users following an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_badges IS 'Platform-defined organiser verification or quality badge catalogue.';
COMMENT ON TABLE event_platform.organisation_badge_assignments IS 'Badges awarded to organiser organisations.';
COMMENT ON TABLE event_platform.venues IS 'Physical or virtual venues owned or managed by an organiser.';
COMMENT ON TABLE event_platform.venue_spaces IS 'Rooms, halls, zones or subspaces within a venue.';
COMMENT ON TABLE event_platform.venue_layouts IS 'Versioned seat-map or layout representation for a venue space.';
COMMENT ON TABLE event_platform.venue_seats IS 'Individual reservable seats and their stable venue-space identity.';
COMMENT ON TABLE event_platform.venue_facilities IS 'Facility catalogue such as parking, accessibility or equipment.';
COMMENT ON TABLE event_platform.venue_facility_assignments IS 'Facilities provided by a venue or venue space.';
COMMENT ON TABLE event_platform.venue_translations IS 'Localised venue names and descriptions.';
COMMENT ON TABLE event_platform.event_categories IS 'Top-level event discovery and classification taxonomy.';
COMMENT ON TABLE event_platform.event_category_translations IS 'Localised event-category labels and descriptions.';
COMMENT ON TABLE event_platform.events IS 'Event aggregate: organiser ownership, public content, publication and access state.';
COMMENT ON TABLE event_platform.event_market_assignments IS 'Markets in which an event is discoverable or sale-enabled.';
COMMENT ON TABLE event_platform.event_translations IS 'Localised event title, description and public event content.';
COMMENT ON TABLE event_platform.event_category_assignments IS 'Many-to-many assignment of events to categories.';
COMMENT ON TABLE event_platform.event_tags IS 'Tag catalogue used for flexible event discovery.';
COMMENT ON TABLE event_platform.event_tag_assignments IS 'Many-to-many assignment of tags to events.';
COMMENT ON TABLE event_platform.event_media IS 'Ordered media references for event pages and promotion.';
COMMENT ON TABLE event_platform.event_organiser_assignments IS 'Additional organiser/co-host organisations assigned to an event.';
COMMENT ON TABLE event_platform.event_recurrence_rules IS 'Rule definition used to generate recurring event occurrences.';
COMMENT ON TABLE event_platform.event_occurrences IS 'Dated event instances with start/end, time zone, capacity and status.';
COMMENT ON TABLE event_platform.event_occurrence_locations IS 'Venue/space or online location assignment for an occurrence.';
COMMENT ON TABLE event_platform.event_sessions IS 'Sub-sessions or timed programme sessions within an occurrence.';
COMMENT ON TABLE event_platform.event_agenda_items IS 'Agenda entries such as talks, performances or programme activities.';
COMMENT ON TABLE event_platform.event_speakers IS 'Speaker/person records associated with an event.';
COMMENT ON TABLE event_platform.event_sponsors IS 'Sponsor records and sponsorship details for an event.';
COMMENT ON TABLE event_platform.event_faqs IS 'Frequently asked questions displayed on event pages.';
COMMENT ON TABLE event_platform.event_links IS 'Public related links such as organiser or partner resources.';
COMMENT ON TABLE event_platform.event_access_links IS 'Restricted joining/access URLs for online or private events.';
COMMENT ON TABLE event_platform.event_highlights IS 'Short featured event selling points or promotional highlights.';
COMMENT ON TABLE event_platform.event_disclaimers IS 'Event-specific disclaimer and attendee notice content.';
COMMENT ON TABLE event_platform.event_status_history IS 'Auditable event lifecycle state transitions.';
COMMENT ON TABLE event_platform.event_occurrence_status_history IS 'Auditable lifecycle transitions for individual occurrences.';
COMMENT ON TABLE event_platform.event_staff_assignments IS 'Event/occurrence-scoped staff assignments and operational roles.';
COMMENT ON TABLE event_platform.event_registration_forms IS 'Configurable attendee registration form schema for an event.';
COMMENT ON TABLE event_platform.registration_responses IS 'Attendee responses to registration form fields, retained with the order attendee.';
COMMENT ON TABLE event_platform.ticket_types IS 'Ticket product definitions such as general admission, VIP or complimentary.';
COMMENT ON TABLE event_platform.ticket_price_tiers IS 'Market/currency-specific ticket prices and effective sale windows.';
COMMENT ON TABLE event_platform.ticket_inventory IS 'Per-occurrence and ticket-type available, reserved and sold quantity state.';
COMMENT ON TABLE event_platform.ticket_inventory_movements IS 'Auditable inventory movements and corrections.';
COMMENT ON TABLE event_platform.ticket_reservations IS 'Temporary inventory reservation header with expiry and checkout state.';
COMMENT ON TABLE event_platform.ticket_reservation_items IS 'Ticket-type and quantity lines held by a reservation.';
COMMENT ON TABLE event_platform.ticket_seat_holds IS 'Temporary occurrence/seat holds for reserved seating.';
COMMENT ON TABLE event_platform.orders IS 'Checkout/order aggregate tied to one event occurrence, market and currency.';
COMMENT ON TABLE event_platform.order_items IS 'Ticket and event add-on line snapshots belonging to an order.';
COMMENT ON TABLE event_platform.order_status_history IS 'Auditable order lifecycle state transitions.';
COMMENT ON TABLE event_platform.tickets IS 'Individually issued attendee admission credentials and ticket status.';
COMMENT ON TABLE event_platform.ticket_transfers IS 'Stateful transfer workflow between ticket holders.';
COMMENT ON TABLE event_platform.discount_codes IS 'Organiser or platform discount-code rules and limits.';
COMMENT ON TABLE event_platform.discount_redemptions IS 'Recorded use of discount codes against orders/users.';
COMMENT ON TABLE event_platform.waitlist_entries IS 'Attendee requests to be notified when an event/ticket inventory becomes available.';
COMMENT ON TABLE event_platform.waitlist_offers IS 'Expiring ticket offers issued to waitlisted attendees.';
COMMENT ON TABLE event_platform.event_addons IS 'Optional event extras or purchasable add-ons.';
COMMENT ON TABLE event_platform.order_addons IS 'Order-level snapshot of selected event add-ons.';
COMMENT ON TABLE event_platform.box_office_sessions IS 'Authorised on-site sales sessions for an occurrence and currency.';
COMMENT ON TABLE event_platform.cash_drawer_movements IS 'Cash in/out, adjustment and reconciliation movements for box-office drawers.';
COMMENT ON TABLE event_platform.checkin_devices IS 'Registered and scoped devices authorised to validate tickets.';
COMMENT ON TABLE event_platform.ticket_scan_logs IS 'Raw/idempotent scan attempt history, including offline scan synchronisation.';
COMMENT ON TABLE event_platform.ticket_check_ins IS 'Authoritative successful ticket admission/check-in record.';
COMMENT ON TABLE event_platform.payment_provider_accounts IS 'Market-specific payment merchant/provider account configuration by secret reference.';
COMMENT ON TABLE event_platform.payment_provider_methods IS 'Payment methods and capabilities supported by a provider account.';
COMMENT ON TABLE event_platform.customer_payment_methods IS 'Tokenised provider payment-method references saved for a user.';
COMMENT ON TABLE event_platform.payments IS 'Payment transaction against an event order, including provider and idempotency state.';
COMMENT ON TABLE event_platform.payment_attempts IS 'Individual provider checkout/payment attempt outcomes.';
COMMENT ON TABLE event_platform.payment_webhook_events IS 'Persisted provider webhook inbox with event identifiers and processing state.';
COMMENT ON TABLE event_platform.refunds IS 'Full or partial refund request and provider outcome for an order/payment.';
COMMENT ON TABLE event_platform.refund_items IS 'Refund allocation to specific order/ticket/add-on lines.';
COMMENT ON TABLE event_platform.fee_rules IS 'Market/organiser fee calculation rules and effective periods.';
COMMENT ON TABLE event_platform.order_financial_lines IS 'Immutable financial line snapshots for ticket price, tax, fees and adjustments.';
COMMENT ON TABLE event_platform.organiser_payout_accounts IS 'Verified organiser payout destination references.';
COMMENT ON TABLE event_platform.payouts IS 'Organiser payout request, processing and settlement state.';
COMMENT ON TABLE event_platform.payout_items IS 'Order/financial items included in an organiser payout.';
COMMENT ON TABLE event_platform.ledger_accounts IS 'Event-finance chart of accounts separated by currency and purpose.';
COMMENT ON TABLE event_platform.ledger_transactions IS 'Immutable event ledger journal transaction headers.';
COMMENT ON TABLE event_platform.ledger_entries IS 'Debit/credit entries belonging to an event ledger transaction.';
COMMENT ON TABLE event_platform.reconciliation_runs IS 'Provider/bank statement reconciliation run headers.';
COMMENT ON TABLE event_platform.reconciliation_items IS 'Individual statement-to-platform reconciliation matches or exceptions.';
COMMENT ON TABLE event_platform.notification_templates IS 'Localised event-platform message templates by channel and code.';
COMMENT ON TABLE event_platform.notifications IS 'In-app event-platform notifications addressed to users.';
COMMENT ON TABLE event_platform.notification_deliveries IS 'Channel-specific delivery state and provider attempt summary.';
COMMENT ON TABLE event_platform.user_notification_preferences IS 'User preferences for event notification channels and categories.';
COMMENT ON TABLE event_platform.saved_events IS 'Events bookmarked by a user.';
COMMENT ON TABLE event_platform.tracking_links IS 'Organiser/event campaign links used for attribution.';
COMMENT ON TABLE event_platform.analytics_events IS 'Event discovery, checkout, conversion and operational analytics events.';
COMMENT ON TABLE event_platform.event_reviews IS 'Attendee reviews and ratings for events/occurrences.';
COMMENT ON TABLE event_platform.organiser_inquiries IS 'Customer/attendee enquiries directed to an organiser.';
COMMENT ON TABLE event_platform.support_tickets IS 'Event-platform support cases, optionally linked to an order and user.';
COMMENT ON TABLE event_platform.content_reports IS 'User reports about events, organisers or platform content.';
COMMENT ON TABLE event_platform.admin_audit_logs IS 'Audit history for privileged event-platform administrative actions.';
COMMENT ON TABLE event_platform.integration_connections IS 'Organiser/market external integration configuration and secret references.';
COMMENT ON TABLE event_platform.webhook_endpoints IS 'Outbound webhook endpoint subscriptions and verification configuration.';
COMMENT ON TABLE event_platform.webhook_deliveries IS 'Outbound webhook delivery attempts and response state.';
COMMENT ON TABLE event_platform.platform_plans IS 'Event-platform organiser subscription plan catalogue.';
COMMENT ON TABLE event_platform.organisation_subscriptions IS 'Organiser subscription lifecycle and plan association.';
COMMENT ON TABLE event_platform.legal_documents IS 'Platform/market legal document definitions.';
COMMENT ON TABLE event_platform.legal_document_versions IS 'Versioned text and publication/effective dates for legal documents.';

-- Unified clean-install assertions. These are structural checks, not a substitute
-- for running integration and concurrency tests against PostgreSQL 17/PostGIS.
DO $unified_assertions$
DECLARE
    v_identity_count integer;
    v_beauty_count integer;
    v_event_count integer;
BEGIN
    SELECT count(*) INTO v_identity_count FROM information_schema.tables
     WHERE table_schema='identity' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_beauty_count FROM information_schema.tables
     WHERE table_schema='beauty_platform' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_event_count FROM information_schema.tables
     WHERE table_schema='event_platform' AND table_type='BASE TABLE';
    IF v_identity_count <> 9 THEN RAISE EXCEPTION 'Expected 9 shared identity tables, found %', v_identity_count; END IF;
    IF v_beauty_count <> 222 THEN RAISE EXCEPTION 'Expected 222 beauty tables, found %', v_beauty_count; END IF;
    IF v_event_count <> 115 THEN RAISE EXCEPTION 'Expected 115 event tables, found %', v_event_count; END IF;
    IF to_regclass('identity.users') IS NULL OR to_regclass('beauty_platform.bookings') IS NULL OR to_regclass('event_platform.orders') IS NULL THEN
        RAISE EXCEPTION 'Unified platform required tables are missing';
    END IF;
END;
$unified_assertions$;


-- Event table dictionary comments added during the shared-platform integration.
COMMENT ON TABLE event_platform.countries IS 'Country reference records used by the event market catalogue.';
COMMENT ON TABLE event_platform.administrative_areas IS 'Country-specific hierarchical administrative geography.';
COMMENT ON TABLE event_platform.addresses IS 'Structured postal/landmark address and PostGIS point location.';
COMMENT ON TABLE event_platform.currencies IS 'ISO currency code, display metadata and minor-unit precision.';
COMMENT ON TABLE event_platform.languages IS 'Supported language catalogue, native names and right-to-left settings.';
COMMENT ON TABLE event_platform.markets IS 'Commercial market activation, defaults and country-specific configuration.';
COMMENT ON TABLE event_platform.market_currencies IS 'Currencies enabled for a market and its default sales currency.';
COMMENT ON TABLE event_platform.market_languages IS 'Languages enabled for a market and its default language.';
COMMENT ON TABLE event_platform.country_calendar_settings IS 'Country calendar presentation and fiscal-year configuration.';
COMMENT ON TABLE event_platform.media_assets IS 'Event-product object-storage metadata, visibility and processing status.';
COMMENT ON TABLE event_platform.organisations IS 'Organiser tenant root, public identity and verification state.';
COMMENT ON TABLE event_platform.organisation_members IS 'User membership and organiser-specific role within an organisation.';
COMMENT ON TABLE event_platform.organisation_invitations IS 'Invitations to join an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_market_profiles IS 'Market-specific legal, tax, verification and payout profile for an organisation.';
COMMENT ON TABLE event_platform.organisation_addresses IS 'Address links and address purpose for an organisation.';
COMMENT ON TABLE event_platform.organisation_categories IS 'Platform-maintained organiser category taxonomy.';
COMMENT ON TABLE event_platform.organisation_category_assignments IS 'Many-to-many mapping of organisations to organiser categories.';
COMMENT ON TABLE event_platform.organisation_translations IS 'Localised organiser names and descriptions.';
COMMENT ON TABLE event_platform.organiser_followers IS 'Users following an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_badges IS 'Platform-defined organiser verification or quality badge catalogue.';
COMMENT ON TABLE event_platform.organisation_badge_assignments IS 'Badges awarded to organiser organisations.';
COMMENT ON TABLE event_platform.venues IS 'Physical or virtual venues owned or managed by an organiser.';
COMMENT ON TABLE event_platform.venue_spaces IS 'Rooms, halls, zones or subspaces within a venue.';
COMMENT ON TABLE event_platform.venue_layouts IS 'Versioned seat-map or layout representation for a venue space.';
COMMENT ON TABLE event_platform.venue_seats IS 'Individual reservable seats and their stable venue-space identity.';
COMMENT ON TABLE event_platform.venue_facilities IS 'Facility catalogue such as parking, accessibility or equipment.';
COMMENT ON TABLE event_platform.venue_facility_assignments IS 'Facilities provided by a venue or venue space.';
COMMENT ON TABLE event_platform.venue_translations IS 'Localised venue names and descriptions.';
COMMENT ON TABLE event_platform.event_categories IS 'Top-level event discovery and classification taxonomy.';
COMMENT ON TABLE event_platform.event_category_translations IS 'Localised event-category labels and descriptions.';
COMMENT ON TABLE event_platform.events IS 'Event aggregate: organiser ownership, public content, publication and access state.';
COMMENT ON TABLE event_platform.event_market_assignments IS 'Markets in which an event is discoverable or sale-enabled.';
COMMENT ON TABLE event_platform.event_translations IS 'Localised event title, description and public event content.';
COMMENT ON TABLE event_platform.event_category_assignments IS 'Many-to-many assignment of events to categories.';
COMMENT ON TABLE event_platform.event_tags IS 'Tag catalogue used for flexible event discovery.';
COMMENT ON TABLE event_platform.event_tag_assignments IS 'Many-to-many assignment of tags to events.';
COMMENT ON TABLE event_platform.event_media IS 'Ordered media references for event pages and promotion.';
COMMENT ON TABLE event_platform.event_organiser_assignments IS 'Additional organiser/co-host organisations assigned to an event.';
COMMENT ON TABLE event_platform.event_recurrence_rules IS 'Rule definition used to generate recurring event occurrences.';
COMMENT ON TABLE event_platform.event_occurrences IS 'Dated event instances with start/end, time zone, capacity and status.';
COMMENT ON TABLE event_platform.event_occurrence_locations IS 'Venue/space or online location assignment for an occurrence.';
COMMENT ON TABLE event_platform.event_sessions IS 'Sub-sessions or timed programme sessions within an occurrence.';
COMMENT ON TABLE event_platform.event_agenda_items IS 'Agenda entries such as talks, performances or programme activities.';
COMMENT ON TABLE event_platform.event_speakers IS 'Speaker/person records associated with an event.';
COMMENT ON TABLE event_platform.event_sponsors IS 'Sponsor records and sponsorship details for an event.';
COMMENT ON TABLE event_platform.event_faqs IS 'Frequently asked questions displayed on event pages.';
COMMENT ON TABLE event_platform.event_links IS 'Public related links such as organiser or partner resources.';
COMMENT ON TABLE event_platform.event_access_links IS 'Restricted joining/access URLs for online or private events.';
COMMENT ON TABLE event_platform.event_highlights IS 'Short featured event selling points or promotional highlights.';
COMMENT ON TABLE event_platform.event_disclaimers IS 'Event-specific disclaimer and attendee notice content.';
COMMENT ON TABLE event_platform.event_status_history IS 'Auditable event lifecycle state transitions.';
COMMENT ON TABLE event_platform.event_occurrence_status_history IS 'Auditable lifecycle transitions for individual occurrences.';
COMMENT ON TABLE event_platform.event_staff_assignments IS 'Event/occurrence-scoped staff assignments and operational roles.';
COMMENT ON TABLE event_platform.event_registration_forms IS 'Configurable attendee registration form schema for an event.';
COMMENT ON TABLE event_platform.registration_responses IS 'Attendee responses to registration form fields, retained with the order attendee.';
COMMENT ON TABLE event_platform.ticket_types IS 'Ticket product definitions such as general admission, VIP or complimentary.';
COMMENT ON TABLE event_platform.ticket_price_tiers IS 'Market/currency-specific ticket prices and effective sale windows.';
COMMENT ON TABLE event_platform.ticket_inventory IS 'Per-occurrence and ticket-type available, reserved and sold quantity state.';
COMMENT ON TABLE event_platform.ticket_inventory_movements IS 'Auditable inventory movements and corrections.';
COMMENT ON TABLE event_platform.ticket_reservations IS 'Temporary inventory reservation header with expiry and checkout state.';
COMMENT ON TABLE event_platform.ticket_reservation_items IS 'Ticket-type and quantity lines held by a reservation.';
COMMENT ON TABLE event_platform.ticket_seat_holds IS 'Temporary occurrence/seat holds for reserved seating.';
COMMENT ON TABLE event_platform.orders IS 'Checkout/order aggregate tied to one event occurrence, market and currency.';
COMMENT ON TABLE event_platform.order_items IS 'Ticket and event add-on line snapshots belonging to an order.';
COMMENT ON TABLE event_platform.order_status_history IS 'Auditable order lifecycle state transitions.';
COMMENT ON TABLE event_platform.tickets IS 'Individually issued attendee admission credentials and ticket status.';
COMMENT ON TABLE event_platform.ticket_transfers IS 'Stateful transfer workflow between ticket holders.';
COMMENT ON TABLE event_platform.discount_codes IS 'Organiser or platform discount-code rules and limits.';
COMMENT ON TABLE event_platform.discount_redemptions IS 'Recorded use of discount codes against orders/users.';
COMMENT ON TABLE event_platform.waitlist_entries IS 'Attendee requests to be notified when an event/ticket inventory becomes available.';
COMMENT ON TABLE event_platform.waitlist_offers IS 'Expiring ticket offers issued to waitlisted attendees.';
COMMENT ON TABLE event_platform.event_addons IS 'Optional event extras or purchasable add-ons.';
COMMENT ON TABLE event_platform.order_addons IS 'Order-level snapshot of selected event add-ons.';
COMMENT ON TABLE event_platform.box_office_sessions IS 'Authorised on-site sales sessions for an occurrence and currency.';
COMMENT ON TABLE event_platform.cash_drawer_movements IS 'Cash in/out, adjustment and reconciliation movements for box-office drawers.';
COMMENT ON TABLE event_platform.checkin_devices IS 'Registered and scoped devices authorised to validate tickets.';
COMMENT ON TABLE event_platform.ticket_scan_logs IS 'Raw/idempotent scan attempt history, including offline scan synchronisation.';
COMMENT ON TABLE event_platform.ticket_check_ins IS 'Authoritative successful ticket admission/check-in record.';
COMMENT ON TABLE event_platform.payment_provider_accounts IS 'Market-specific payment merchant/provider account configuration by secret reference.';
COMMENT ON TABLE event_platform.payment_provider_methods IS 'Payment methods and capabilities supported by a provider account.';
COMMENT ON TABLE event_platform.customer_payment_methods IS 'Tokenised provider payment-method references saved for a user.';
COMMENT ON TABLE event_platform.payments IS 'Payment transaction against an event order, including provider and idempotency state.';
COMMENT ON TABLE event_platform.payment_attempts IS 'Individual provider checkout/payment attempt outcomes.';
COMMENT ON TABLE event_platform.payment_webhook_events IS 'Persisted provider webhook inbox with event identifiers and processing state.';
COMMENT ON TABLE event_platform.refunds IS 'Full or partial refund request and provider outcome for an order/payment.';
COMMENT ON TABLE event_platform.refund_items IS 'Refund allocation to specific order/ticket/add-on lines.';
COMMENT ON TABLE event_platform.fee_rules IS 'Market/organiser fee calculation rules and effective periods.';
COMMENT ON TABLE event_platform.order_financial_lines IS 'Immutable financial line snapshots for ticket price, tax, fees and adjustments.';
COMMENT ON TABLE event_platform.organiser_payout_accounts IS 'Verified organiser payout destination references.';
COMMENT ON TABLE event_platform.payouts IS 'Organiser payout request, processing and settlement state.';
COMMENT ON TABLE event_platform.payout_items IS 'Order/financial items included in an organiser payout.';
COMMENT ON TABLE event_platform.ledger_accounts IS 'Event-finance chart of accounts separated by currency and purpose.';
COMMENT ON TABLE event_platform.ledger_transactions IS 'Immutable event ledger journal transaction headers.';
COMMENT ON TABLE event_platform.ledger_entries IS 'Debit/credit entries belonging to an event ledger transaction.';
COMMENT ON TABLE event_platform.reconciliation_runs IS 'Provider/bank statement reconciliation run headers.';
COMMENT ON TABLE event_platform.reconciliation_items IS 'Individual statement-to-platform reconciliation matches or exceptions.';
COMMENT ON TABLE event_platform.notification_templates IS 'Localised event-platform message templates by channel and code.';
COMMENT ON TABLE event_platform.notifications IS 'In-app event-platform notifications addressed to users.';
COMMENT ON TABLE event_platform.notification_deliveries IS 'Channel-specific delivery state and provider attempt summary.';
COMMENT ON TABLE event_platform.user_notification_preferences IS 'User preferences for event notification channels and categories.';
COMMENT ON TABLE event_platform.saved_events IS 'Events bookmarked by a user.';
COMMENT ON TABLE event_platform.tracking_links IS 'Organiser/event campaign links used for attribution.';
COMMENT ON TABLE event_platform.analytics_events IS 'Event discovery, checkout, conversion and operational analytics events.';
COMMENT ON TABLE event_platform.event_reviews IS 'Attendee reviews and ratings for events/occurrences.';
COMMENT ON TABLE event_platform.organiser_inquiries IS 'Customer/attendee enquiries directed to an organiser.';
COMMENT ON TABLE event_platform.support_tickets IS 'Event-platform support cases, optionally linked to an order and user.';
COMMENT ON TABLE event_platform.content_reports IS 'User reports about events, organisers or platform content.';
COMMENT ON TABLE event_platform.admin_audit_logs IS 'Audit history for privileged event-platform administrative actions.';
COMMENT ON TABLE event_platform.integration_connections IS 'Organiser/market external integration configuration and secret references.';
COMMENT ON TABLE event_platform.webhook_endpoints IS 'Outbound webhook endpoint subscriptions and verification configuration.';
COMMENT ON TABLE event_platform.webhook_deliveries IS 'Outbound webhook delivery attempts and response state.';
COMMENT ON TABLE event_platform.platform_plans IS 'Event-platform organiser subscription plan catalogue.';
COMMENT ON TABLE event_platform.organisation_subscriptions IS 'Organiser subscription lifecycle and plan association.';
COMMENT ON TABLE event_platform.legal_documents IS 'Platform/market legal document definitions.';
COMMENT ON TABLE event_platform.legal_document_versions IS 'Versioned text and publication/effective dates for legal documents.';

-- Unified clean-install assertions. These are structural checks, not a substitute
-- for running integration and concurrency tests against PostgreSQL 17/PostGIS.
DO $unified_assertions$
DECLARE
    v_identity_count integer;
    v_beauty_count integer;
    v_event_count integer;
BEGIN
    SELECT count(*) INTO v_identity_count FROM information_schema.tables
     WHERE table_schema='identity' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_beauty_count FROM information_schema.tables
     WHERE table_schema='beauty_platform' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_event_count FROM information_schema.tables
     WHERE table_schema='event_platform' AND table_type='BASE TABLE';
    IF v_identity_count <> 9 THEN RAISE EXCEPTION 'Expected 9 shared identity tables, found %', v_identity_count; END IF;
    IF v_beauty_count <> 222 THEN RAISE EXCEPTION 'Expected 222 beauty tables, found %', v_beauty_count; END IF;
    IF v_event_count <> 115 THEN RAISE EXCEPTION 'Expected 115 event tables, found %', v_event_count; END IF;
    IF to_regclass('identity.users') IS NULL OR to_regclass('beauty_platform.bookings') IS NULL OR to_regclass('event_platform.orders') IS NULL THEN
        RAISE EXCEPTION 'Unified platform required tables are missing';
    END IF;
END;
$unified_assertions$;


-- Event table dictionary comments added during the shared-platform integration.
COMMENT ON TABLE event_platform.countries IS 'Country reference records used by the event market catalogue.';
COMMENT ON TABLE event_platform.administrative_areas IS 'Country-specific hierarchical administrative geography.';
COMMENT ON TABLE event_platform.addresses IS 'Structured postal/landmark address and PostGIS point location.';
COMMENT ON TABLE event_platform.currencies IS 'ISO currency code, display metadata and minor-unit precision.';
COMMENT ON TABLE event_platform.languages IS 'Supported language catalogue, native names and right-to-left settings.';
COMMENT ON TABLE event_platform.markets IS 'Commercial market activation, defaults and country-specific configuration.';
COMMENT ON TABLE event_platform.market_currencies IS 'Currencies enabled for a market and its default sales currency.';
COMMENT ON TABLE event_platform.market_languages IS 'Languages enabled for a market and its default language.';
COMMENT ON TABLE event_platform.country_calendar_settings IS 'Country calendar presentation and fiscal-year configuration.';
COMMENT ON TABLE event_platform.media_assets IS 'Event-product object-storage metadata, visibility and processing status.';
COMMENT ON TABLE event_platform.organisations IS 'Organiser tenant root, public identity and verification state.';
COMMENT ON TABLE event_platform.organisation_members IS 'User membership and organiser-specific role within an organisation.';
COMMENT ON TABLE event_platform.organisation_invitations IS 'Invitations to join an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_market_profiles IS 'Market-specific legal, tax, verification and payout profile for an organisation.';
COMMENT ON TABLE event_platform.organisation_addresses IS 'Address links and address purpose for an organisation.';
COMMENT ON TABLE event_platform.organisation_categories IS 'Platform-maintained organiser category taxonomy.';
COMMENT ON TABLE event_platform.organisation_category_assignments IS 'Many-to-many mapping of organisations to organiser categories.';
COMMENT ON TABLE event_platform.organisation_translations IS 'Localised organiser names and descriptions.';
COMMENT ON TABLE event_platform.organiser_followers IS 'Users following an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_badges IS 'Platform-defined organiser verification or quality badge catalogue.';
COMMENT ON TABLE event_platform.organisation_badge_assignments IS 'Badges awarded to organiser organisations.';
COMMENT ON TABLE event_platform.venues IS 'Physical or virtual venues owned or managed by an organiser.';
COMMENT ON TABLE event_platform.venue_spaces IS 'Rooms, halls, zones or subspaces within a venue.';
COMMENT ON TABLE event_platform.venue_layouts IS 'Versioned seat-map or layout representation for a venue space.';
COMMENT ON TABLE event_platform.venue_seats IS 'Individual reservable seats and their stable venue-space identity.';
COMMENT ON TABLE event_platform.venue_facilities IS 'Facility catalogue such as parking, accessibility or equipment.';
COMMENT ON TABLE event_platform.venue_facility_assignments IS 'Facilities provided by a venue or venue space.';
COMMENT ON TABLE event_platform.venue_translations IS 'Localised venue names and descriptions.';
COMMENT ON TABLE event_platform.event_categories IS 'Top-level event discovery and classification taxonomy.';
COMMENT ON TABLE event_platform.event_category_translations IS 'Localised event-category labels and descriptions.';
COMMENT ON TABLE event_platform.events IS 'Event aggregate: organiser ownership, public content, publication and access state.';
COMMENT ON TABLE event_platform.event_market_assignments IS 'Markets in which an event is discoverable or sale-enabled.';
COMMENT ON TABLE event_platform.event_translations IS 'Localised event title, description and public event content.';
COMMENT ON TABLE event_platform.event_category_assignments IS 'Many-to-many assignment of events to categories.';
COMMENT ON TABLE event_platform.event_tags IS 'Tag catalogue used for flexible event discovery.';
COMMENT ON TABLE event_platform.event_tag_assignments IS 'Many-to-many assignment of tags to events.';
COMMENT ON TABLE event_platform.event_media IS 'Ordered media references for event pages and promotion.';
COMMENT ON TABLE event_platform.event_organiser_assignments IS 'Additional organiser/co-host organisations assigned to an event.';
COMMENT ON TABLE event_platform.event_recurrence_rules IS 'Rule definition used to generate recurring event occurrences.';
COMMENT ON TABLE event_platform.event_occurrences IS 'Dated event instances with start/end, time zone, capacity and status.';
COMMENT ON TABLE event_platform.event_occurrence_locations IS 'Venue/space or online location assignment for an occurrence.';
COMMENT ON TABLE event_platform.event_sessions IS 'Sub-sessions or timed programme sessions within an occurrence.';
COMMENT ON TABLE event_platform.event_agenda_items IS 'Agenda entries such as talks, performances or programme activities.';
COMMENT ON TABLE event_platform.event_speakers IS 'Speaker/person records associated with an event.';
COMMENT ON TABLE event_platform.event_sponsors IS 'Sponsor records and sponsorship details for an event.';
COMMENT ON TABLE event_platform.event_faqs IS 'Frequently asked questions displayed on event pages.';
COMMENT ON TABLE event_platform.event_links IS 'Public related links such as organiser or partner resources.';
COMMENT ON TABLE event_platform.event_access_links IS 'Restricted joining/access URLs for online or private events.';
COMMENT ON TABLE event_platform.event_highlights IS 'Short featured event selling points or promotional highlights.';
COMMENT ON TABLE event_platform.event_disclaimers IS 'Event-specific disclaimer and attendee notice content.';
COMMENT ON TABLE event_platform.event_status_history IS 'Auditable event lifecycle state transitions.';
COMMENT ON TABLE event_platform.event_occurrence_status_history IS 'Auditable lifecycle transitions for individual occurrences.';
COMMENT ON TABLE event_platform.event_staff_assignments IS 'Event/occurrence-scoped staff assignments and operational roles.';
COMMENT ON TABLE event_platform.event_registration_forms IS 'Configurable attendee registration form schema for an event.';
COMMENT ON TABLE event_platform.registration_responses IS 'Attendee responses to registration form fields, retained with the order attendee.';
COMMENT ON TABLE event_platform.ticket_types IS 'Ticket product definitions such as general admission, VIP or complimentary.';
COMMENT ON TABLE event_platform.ticket_price_tiers IS 'Market/currency-specific ticket prices and effective sale windows.';
COMMENT ON TABLE event_platform.ticket_inventory IS 'Per-occurrence and ticket-type available, reserved and sold quantity state.';
COMMENT ON TABLE event_platform.ticket_inventory_movements IS 'Auditable inventory movements and corrections.';
COMMENT ON TABLE event_platform.ticket_reservations IS 'Temporary inventory reservation header with expiry and checkout state.';
COMMENT ON TABLE event_platform.ticket_reservation_items IS 'Ticket-type and quantity lines held by a reservation.';
COMMENT ON TABLE event_platform.ticket_seat_holds IS 'Temporary occurrence/seat holds for reserved seating.';
COMMENT ON TABLE event_platform.orders IS 'Checkout/order aggregate tied to one event occurrence, market and currency.';
COMMENT ON TABLE event_platform.order_items IS 'Ticket and event add-on line snapshots belonging to an order.';
COMMENT ON TABLE event_platform.order_status_history IS 'Auditable order lifecycle state transitions.';
COMMENT ON TABLE event_platform.tickets IS 'Individually issued attendee admission credentials and ticket status.';
COMMENT ON TABLE event_platform.ticket_transfers IS 'Stateful transfer workflow between ticket holders.';
COMMENT ON TABLE event_platform.discount_codes IS 'Organiser or platform discount-code rules and limits.';
COMMENT ON TABLE event_platform.discount_redemptions IS 'Recorded use of discount codes against orders/users.';
COMMENT ON TABLE event_platform.waitlist_entries IS 'Attendee requests to be notified when an event/ticket inventory becomes available.';
COMMENT ON TABLE event_platform.waitlist_offers IS 'Expiring ticket offers issued to waitlisted attendees.';
COMMENT ON TABLE event_platform.event_addons IS 'Optional event extras or purchasable add-ons.';
COMMENT ON TABLE event_platform.order_addons IS 'Order-level snapshot of selected event add-ons.';
COMMENT ON TABLE event_platform.box_office_sessions IS 'Authorised on-site sales sessions for an occurrence and currency.';
COMMENT ON TABLE event_platform.cash_drawer_movements IS 'Cash in/out, adjustment and reconciliation movements for box-office drawers.';
COMMENT ON TABLE event_platform.checkin_devices IS 'Registered and scoped devices authorised to validate tickets.';
COMMENT ON TABLE event_platform.ticket_scan_logs IS 'Raw/idempotent scan attempt history, including offline scan synchronisation.';
COMMENT ON TABLE event_platform.ticket_check_ins IS 'Authoritative successful ticket admission/check-in record.';
COMMENT ON TABLE event_platform.payment_provider_accounts IS 'Market-specific payment merchant/provider account configuration by secret reference.';
COMMENT ON TABLE event_platform.payment_provider_methods IS 'Payment methods and capabilities supported by a provider account.';
COMMENT ON TABLE event_platform.customer_payment_methods IS 'Tokenised provider payment-method references saved for a user.';
COMMENT ON TABLE event_platform.payments IS 'Payment transaction against an event order, including provider and idempotency state.';
COMMENT ON TABLE event_platform.payment_attempts IS 'Individual provider checkout/payment attempt outcomes.';
COMMENT ON TABLE event_platform.payment_webhook_events IS 'Persisted provider webhook inbox with event identifiers and processing state.';
COMMENT ON TABLE event_platform.refunds IS 'Full or partial refund request and provider outcome for an order/payment.';
COMMENT ON TABLE event_platform.refund_items IS 'Refund allocation to specific order/ticket/add-on lines.';
COMMENT ON TABLE event_platform.fee_rules IS 'Market/organiser fee calculation rules and effective periods.';
COMMENT ON TABLE event_platform.order_financial_lines IS 'Immutable financial line snapshots for ticket price, tax, fees and adjustments.';
COMMENT ON TABLE event_platform.organiser_payout_accounts IS 'Verified organiser payout destination references.';
COMMENT ON TABLE event_platform.payouts IS 'Organiser payout request, processing and settlement state.';
COMMENT ON TABLE event_platform.payout_items IS 'Order/financial items included in an organiser payout.';
COMMENT ON TABLE event_platform.ledger_accounts IS 'Event-finance chart of accounts separated by currency and purpose.';
COMMENT ON TABLE event_platform.ledger_transactions IS 'Immutable event ledger journal transaction headers.';
COMMENT ON TABLE event_platform.ledger_entries IS 'Debit/credit entries belonging to an event ledger transaction.';
COMMENT ON TABLE event_platform.reconciliation_runs IS 'Provider/bank statement reconciliation run headers.';
COMMENT ON TABLE event_platform.reconciliation_items IS 'Individual statement-to-platform reconciliation matches or exceptions.';
COMMENT ON TABLE event_platform.notification_templates IS 'Localised event-platform message templates by channel and code.';
COMMENT ON TABLE event_platform.notifications IS 'In-app event-platform notifications addressed to users.';
COMMENT ON TABLE event_platform.notification_deliveries IS 'Channel-specific delivery state and provider attempt summary.';
COMMENT ON TABLE event_platform.user_notification_preferences IS 'User preferences for event notification channels and categories.';
COMMENT ON TABLE event_platform.saved_events IS 'Events bookmarked by a user.';
COMMENT ON TABLE event_platform.tracking_links IS 'Organiser/event campaign links used for attribution.';
COMMENT ON TABLE event_platform.analytics_events IS 'Event discovery, checkout, conversion and operational analytics events.';
COMMENT ON TABLE event_platform.event_reviews IS 'Attendee reviews and ratings for events/occurrences.';
COMMENT ON TABLE event_platform.organiser_inquiries IS 'Customer/attendee enquiries directed to an organiser.';
COMMENT ON TABLE event_platform.support_tickets IS 'Event-platform support cases, optionally linked to an order and user.';
COMMENT ON TABLE event_platform.content_reports IS 'User reports about events, organisers or platform content.';
COMMENT ON TABLE event_platform.admin_audit_logs IS 'Audit history for privileged event-platform administrative actions.';
COMMENT ON TABLE event_platform.integration_connections IS 'Organiser/market external integration configuration and secret references.';
COMMENT ON TABLE event_platform.webhook_endpoints IS 'Outbound webhook endpoint subscriptions and verification configuration.';
COMMENT ON TABLE event_platform.webhook_deliveries IS 'Outbound webhook delivery attempts and response state.';
COMMENT ON TABLE event_platform.platform_plans IS 'Event-platform organiser subscription plan catalogue.';
COMMENT ON TABLE event_platform.organisation_subscriptions IS 'Organiser subscription lifecycle and plan association.';
COMMENT ON TABLE event_platform.legal_documents IS 'Platform/market legal document definitions.';
COMMENT ON TABLE event_platform.legal_document_versions IS 'Versioned text and publication/effective dates for legal documents.';

-- Unified clean-install assertions. These are structural checks, not a substitute
-- for running integration and concurrency tests against PostgreSQL 17/PostGIS.
DO $unified_assertions$
DECLARE
    v_identity_count integer;
    v_beauty_count integer;
    v_event_count integer;
BEGIN
    SELECT count(*) INTO v_identity_count FROM information_schema.tables
     WHERE table_schema='identity' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_beauty_count FROM information_schema.tables
     WHERE table_schema='beauty_platform' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_event_count FROM information_schema.tables
     WHERE table_schema='event_platform' AND table_type='BASE TABLE';
    IF v_identity_count <> 9 THEN RAISE EXCEPTION 'Expected 9 shared identity tables, found %', v_identity_count; END IF;
    IF v_beauty_count <> 222 THEN RAISE EXCEPTION 'Expected 222 beauty tables, found %', v_beauty_count; END IF;
    IF v_event_count <> 115 THEN RAISE EXCEPTION 'Expected 115 event tables, found %', v_event_count; END IF;
    IF to_regclass('identity.users') IS NULL OR to_regclass('beauty_platform.bookings') IS NULL OR to_regclass('event_platform.orders') IS NULL THEN
        RAISE EXCEPTION 'Unified platform required tables are missing';
    END IF;
END;
$unified_assertions$;


-- Event table dictionary comments added during the shared-platform integration.
COMMENT ON TABLE event_platform.countries IS 'Country reference records used by the event market catalogue.';
COMMENT ON TABLE event_platform.administrative_areas IS 'Country-specific hierarchical administrative geography.';
COMMENT ON TABLE event_platform.addresses IS 'Structured postal/landmark address and PostGIS point location.';
COMMENT ON TABLE event_platform.currencies IS 'ISO currency code, display metadata and minor-unit precision.';
COMMENT ON TABLE event_platform.languages IS 'Supported language catalogue, native names and right-to-left settings.';
COMMENT ON TABLE event_platform.markets IS 'Commercial market activation, defaults and country-specific configuration.';
COMMENT ON TABLE event_platform.market_currencies IS 'Currencies enabled for a market and its default sales currency.';
COMMENT ON TABLE event_platform.market_languages IS 'Languages enabled for a market and its default language.';
COMMENT ON TABLE event_platform.country_calendar_settings IS 'Country calendar presentation and fiscal-year configuration.';
COMMENT ON TABLE event_platform.media_assets IS 'Event-product object-storage metadata, visibility and processing status.';
COMMENT ON TABLE event_platform.organisations IS 'Organiser tenant root, public identity and verification state.';
COMMENT ON TABLE event_platform.organisation_members IS 'User membership and organiser-specific role within an organisation.';
COMMENT ON TABLE event_platform.organisation_invitations IS 'Invitations to join an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_market_profiles IS 'Market-specific legal, tax, verification and payout profile for an organisation.';
COMMENT ON TABLE event_platform.organisation_addresses IS 'Address links and address purpose for an organisation.';
COMMENT ON TABLE event_platform.organisation_categories IS 'Platform-maintained organiser category taxonomy.';
COMMENT ON TABLE event_platform.organisation_category_assignments IS 'Many-to-many mapping of organisations to organiser categories.';
COMMENT ON TABLE event_platform.organisation_translations IS 'Localised organiser names and descriptions.';
COMMENT ON TABLE event_platform.organiser_followers IS 'Users following an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_badges IS 'Platform-defined organiser verification or quality badge catalogue.';
COMMENT ON TABLE event_platform.organisation_badge_assignments IS 'Badges awarded to organiser organisations.';
COMMENT ON TABLE event_platform.venues IS 'Physical or virtual venues owned or managed by an organiser.';
COMMENT ON TABLE event_platform.venue_spaces IS 'Rooms, halls, zones or subspaces within a venue.';
COMMENT ON TABLE event_platform.venue_layouts IS 'Versioned seat-map or layout representation for a venue space.';
COMMENT ON TABLE event_platform.venue_seats IS 'Individual reservable seats and their stable venue-space identity.';
COMMENT ON TABLE event_platform.venue_facilities IS 'Facility catalogue such as parking, accessibility or equipment.';
COMMENT ON TABLE event_platform.venue_facility_assignments IS 'Facilities provided by a venue or venue space.';
COMMENT ON TABLE event_platform.venue_translations IS 'Localised venue names and descriptions.';
COMMENT ON TABLE event_platform.event_categories IS 'Top-level event discovery and classification taxonomy.';
COMMENT ON TABLE event_platform.event_category_translations IS 'Localised event-category labels and descriptions.';
COMMENT ON TABLE event_platform.events IS 'Event aggregate: organiser ownership, public content, publication and access state.';
COMMENT ON TABLE event_platform.event_market_assignments IS 'Markets in which an event is discoverable or sale-enabled.';
COMMENT ON TABLE event_platform.event_translations IS 'Localised event title, description and public event content.';
COMMENT ON TABLE event_platform.event_category_assignments IS 'Many-to-many assignment of events to categories.';
COMMENT ON TABLE event_platform.event_tags IS 'Tag catalogue used for flexible event discovery.';
COMMENT ON TABLE event_platform.event_tag_assignments IS 'Many-to-many assignment of tags to events.';
COMMENT ON TABLE event_platform.event_media IS 'Ordered media references for event pages and promotion.';
COMMENT ON TABLE event_platform.event_organiser_assignments IS 'Additional organiser/co-host organisations assigned to an event.';
COMMENT ON TABLE event_platform.event_recurrence_rules IS 'Rule definition used to generate recurring event occurrences.';
COMMENT ON TABLE event_platform.event_occurrences IS 'Dated event instances with start/end, time zone, capacity and status.';
COMMENT ON TABLE event_platform.event_occurrence_locations IS 'Venue/space or online location assignment for an occurrence.';
COMMENT ON TABLE event_platform.event_sessions IS 'Sub-sessions or timed programme sessions within an occurrence.';
COMMENT ON TABLE event_platform.event_agenda_items IS 'Agenda entries such as talks, performances or programme activities.';
COMMENT ON TABLE event_platform.event_speakers IS 'Speaker/person records associated with an event.';
COMMENT ON TABLE event_platform.event_sponsors IS 'Sponsor records and sponsorship details for an event.';
COMMENT ON TABLE event_platform.event_faqs IS 'Frequently asked questions displayed on event pages.';
COMMENT ON TABLE event_platform.event_links IS 'Public related links such as organiser or partner resources.';
COMMENT ON TABLE event_platform.event_access_links IS 'Restricted joining/access URLs for online or private events.';
COMMENT ON TABLE event_platform.event_highlights IS 'Short featured event selling points or promotional highlights.';
COMMENT ON TABLE event_platform.event_disclaimers IS 'Event-specific disclaimer and attendee notice content.';
COMMENT ON TABLE event_platform.event_status_history IS 'Auditable event lifecycle state transitions.';
COMMENT ON TABLE event_platform.event_occurrence_status_history IS 'Auditable lifecycle transitions for individual occurrences.';
COMMENT ON TABLE event_platform.event_staff_assignments IS 'Event/occurrence-scoped staff assignments and operational roles.';
COMMENT ON TABLE event_platform.event_registration_forms IS 'Configurable attendee registration form schema for an event.';
COMMENT ON TABLE event_platform.registration_responses IS 'Attendee responses to registration form fields, retained with the order attendee.';
COMMENT ON TABLE event_platform.ticket_types IS 'Ticket product definitions such as general admission, VIP or complimentary.';
COMMENT ON TABLE event_platform.ticket_price_tiers IS 'Market/currency-specific ticket prices and effective sale windows.';
COMMENT ON TABLE event_platform.ticket_inventory IS 'Per-occurrence and ticket-type available, reserved and sold quantity state.';
COMMENT ON TABLE event_platform.ticket_inventory_movements IS 'Auditable inventory movements and corrections.';
COMMENT ON TABLE event_platform.ticket_reservations IS 'Temporary inventory reservation header with expiry and checkout state.';
COMMENT ON TABLE event_platform.ticket_reservation_items IS 'Ticket-type and quantity lines held by a reservation.';
COMMENT ON TABLE event_platform.ticket_seat_holds IS 'Temporary occurrence/seat holds for reserved seating.';
COMMENT ON TABLE event_platform.orders IS 'Checkout/order aggregate tied to one event occurrence, market and currency.';
COMMENT ON TABLE event_platform.order_items IS 'Ticket and event add-on line snapshots belonging to an order.';
COMMENT ON TABLE event_platform.order_status_history IS 'Auditable order lifecycle state transitions.';
COMMENT ON TABLE event_platform.tickets IS 'Individually issued attendee admission credentials and ticket status.';
COMMENT ON TABLE event_platform.ticket_transfers IS 'Stateful transfer workflow between ticket holders.';
COMMENT ON TABLE event_platform.discount_codes IS 'Organiser or platform discount-code rules and limits.';
COMMENT ON TABLE event_platform.discount_redemptions IS 'Recorded use of discount codes against orders/users.';
COMMENT ON TABLE event_platform.waitlist_entries IS 'Attendee requests to be notified when an event/ticket inventory becomes available.';
COMMENT ON TABLE event_platform.waitlist_offers IS 'Expiring ticket offers issued to waitlisted attendees.';
COMMENT ON TABLE event_platform.event_addons IS 'Optional event extras or purchasable add-ons.';
COMMENT ON TABLE event_platform.order_addons IS 'Order-level snapshot of selected event add-ons.';
COMMENT ON TABLE event_platform.box_office_sessions IS 'Authorised on-site sales sessions for an occurrence and currency.';
COMMENT ON TABLE event_platform.cash_drawer_movements IS 'Cash in/out, adjustment and reconciliation movements for box-office drawers.';
COMMENT ON TABLE event_platform.checkin_devices IS 'Registered and scoped devices authorised to validate tickets.';
COMMENT ON TABLE event_platform.ticket_scan_logs IS 'Raw/idempotent scan attempt history, including offline scan synchronisation.';
COMMENT ON TABLE event_platform.ticket_check_ins IS 'Authoritative successful ticket admission/check-in record.';
COMMENT ON TABLE event_platform.payment_provider_accounts IS 'Market-specific payment merchant/provider account configuration by secret reference.';
COMMENT ON TABLE event_platform.payment_provider_methods IS 'Payment methods and capabilities supported by a provider account.';
COMMENT ON TABLE event_platform.customer_payment_methods IS 'Tokenised provider payment-method references saved for a user.';
COMMENT ON TABLE event_platform.payments IS 'Payment transaction against an event order, including provider and idempotency state.';
COMMENT ON TABLE event_platform.payment_attempts IS 'Individual provider checkout/payment attempt outcomes.';
COMMENT ON TABLE event_platform.payment_webhook_events IS 'Persisted provider webhook inbox with event identifiers and processing state.';
COMMENT ON TABLE event_platform.refunds IS 'Full or partial refund request and provider outcome for an order/payment.';
COMMENT ON TABLE event_platform.refund_items IS 'Refund allocation to specific order/ticket/add-on lines.';
COMMENT ON TABLE event_platform.fee_rules IS 'Market/organiser fee calculation rules and effective periods.';
COMMENT ON TABLE event_platform.order_financial_lines IS 'Immutable financial line snapshots for ticket price, tax, fees and adjustments.';
COMMENT ON TABLE event_platform.organiser_payout_accounts IS 'Verified organiser payout destination references.';
COMMENT ON TABLE event_platform.payouts IS 'Organiser payout request, processing and settlement state.';
COMMENT ON TABLE event_platform.payout_items IS 'Order/financial items included in an organiser payout.';
COMMENT ON TABLE event_platform.ledger_accounts IS 'Event-finance chart of accounts separated by currency and purpose.';
COMMENT ON TABLE event_platform.ledger_transactions IS 'Immutable event ledger journal transaction headers.';
COMMENT ON TABLE event_platform.ledger_entries IS 'Debit/credit entries belonging to an event ledger transaction.';
COMMENT ON TABLE event_platform.reconciliation_runs IS 'Provider/bank statement reconciliation run headers.';
COMMENT ON TABLE event_platform.reconciliation_items IS 'Individual statement-to-platform reconciliation matches or exceptions.';
COMMENT ON TABLE event_platform.notification_templates IS 'Localised event-platform message templates by channel and code.';
COMMENT ON TABLE event_platform.notifications IS 'In-app event-platform notifications addressed to users.';
COMMENT ON TABLE event_platform.notification_deliveries IS 'Channel-specific delivery state and provider attempt summary.';
COMMENT ON TABLE event_platform.user_notification_preferences IS 'User preferences for event notification channels and categories.';
COMMENT ON TABLE event_platform.saved_events IS 'Events bookmarked by a user.';
COMMENT ON TABLE event_platform.tracking_links IS 'Organiser/event campaign links used for attribution.';
COMMENT ON TABLE event_platform.analytics_events IS 'Event discovery, checkout, conversion and operational analytics events.';
COMMENT ON TABLE event_platform.event_reviews IS 'Attendee reviews and ratings for events/occurrences.';
COMMENT ON TABLE event_platform.organiser_inquiries IS 'Customer/attendee enquiries directed to an organiser.';
COMMENT ON TABLE event_platform.support_tickets IS 'Event-platform support cases, optionally linked to an order and user.';
COMMENT ON TABLE event_platform.content_reports IS 'User reports about events, organisers or platform content.';
COMMENT ON TABLE event_platform.admin_audit_logs IS 'Audit history for privileged event-platform administrative actions.';
COMMENT ON TABLE event_platform.integration_connections IS 'Organiser/market external integration configuration and secret references.';
COMMENT ON TABLE event_platform.webhook_endpoints IS 'Outbound webhook endpoint subscriptions and verification configuration.';
COMMENT ON TABLE event_platform.webhook_deliveries IS 'Outbound webhook delivery attempts and response state.';
COMMENT ON TABLE event_platform.platform_plans IS 'Event-platform organiser subscription plan catalogue.';
COMMENT ON TABLE event_platform.organisation_subscriptions IS 'Organiser subscription lifecycle and plan association.';
COMMENT ON TABLE event_platform.legal_documents IS 'Platform/market legal document definitions.';
COMMENT ON TABLE event_platform.legal_document_versions IS 'Versioned text and publication/effective dates for legal documents.';

-- Unified clean-install assertions. These are structural checks, not a substitute
-- for running integration and concurrency tests against PostgreSQL 17/PostGIS.
DO $unified_assertions$
DECLARE
    v_identity_count integer;
    v_beauty_count integer;
    v_event_count integer;
BEGIN
    SELECT count(*) INTO v_identity_count FROM information_schema.tables
     WHERE table_schema='identity' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_beauty_count FROM information_schema.tables
     WHERE table_schema='beauty_platform' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_event_count FROM information_schema.tables
     WHERE table_schema='event_platform' AND table_type='BASE TABLE';
    IF v_identity_count <> 9 THEN RAISE EXCEPTION 'Expected 9 shared identity tables, found %', v_identity_count; END IF;
    IF v_beauty_count <> 222 THEN RAISE EXCEPTION 'Expected 222 beauty tables, found %', v_beauty_count; END IF;
    IF v_event_count <> 115 THEN RAISE EXCEPTION 'Expected 115 event tables, found %', v_event_count; END IF;
    IF to_regclass('identity.users') IS NULL OR to_regclass('beauty_platform.bookings') IS NULL OR to_regclass('event_platform.orders') IS NULL THEN
        RAISE EXCEPTION 'Unified platform required tables are missing';
    END IF;
END;
$unified_assertions$;


-- Event table dictionary comments added during the shared-platform integration.
COMMENT ON TABLE event_platform.countries IS 'Country reference records used by the event market catalogue.';
COMMENT ON TABLE event_platform.administrative_areas IS 'Country-specific hierarchical administrative geography.';
COMMENT ON TABLE event_platform.addresses IS 'Structured postal/landmark address and PostGIS point location.';
COMMENT ON TABLE event_platform.currencies IS 'ISO currency code, display metadata and minor-unit precision.';
COMMENT ON TABLE event_platform.languages IS 'Supported language catalogue, native names and right-to-left settings.';
COMMENT ON TABLE event_platform.markets IS 'Commercial market activation, defaults and country-specific configuration.';
COMMENT ON TABLE event_platform.market_currencies IS 'Currencies enabled for a market and its default sales currency.';
COMMENT ON TABLE event_platform.market_languages IS 'Languages enabled for a market and its default language.';
COMMENT ON TABLE event_platform.country_calendar_settings IS 'Country calendar presentation and fiscal-year configuration.';
COMMENT ON TABLE event_platform.media_assets IS 'Event-product object-storage metadata, visibility and processing status.';
COMMENT ON TABLE event_platform.organisations IS 'Organiser tenant root, public identity and verification state.';
COMMENT ON TABLE event_platform.organisation_members IS 'User membership and organiser-specific role within an organisation.';
COMMENT ON TABLE event_platform.organisation_invitations IS 'Invitations to join an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_market_profiles IS 'Market-specific legal, tax, verification and payout profile for an organisation.';
COMMENT ON TABLE event_platform.organisation_addresses IS 'Address links and address purpose for an organisation.';
COMMENT ON TABLE event_platform.organisation_categories IS 'Platform-maintained organiser category taxonomy.';
COMMENT ON TABLE event_platform.organisation_category_assignments IS 'Many-to-many mapping of organisations to organiser categories.';
COMMENT ON TABLE event_platform.organisation_translations IS 'Localised organiser names and descriptions.';
COMMENT ON TABLE event_platform.organiser_followers IS 'Users following an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_badges IS 'Platform-defined organiser verification or quality badge catalogue.';
COMMENT ON TABLE event_platform.organisation_badge_assignments IS 'Badges awarded to organiser organisations.';
COMMENT ON TABLE event_platform.venues IS 'Physical or virtual venues owned or managed by an organiser.';
COMMENT ON TABLE event_platform.venue_spaces IS 'Rooms, halls, zones or subspaces within a venue.';
COMMENT ON TABLE event_platform.venue_layouts IS 'Versioned seat-map or layout representation for a venue space.';
COMMENT ON TABLE event_platform.venue_seats IS 'Individual reservable seats and their stable venue-space identity.';
COMMENT ON TABLE event_platform.venue_facilities IS 'Facility catalogue such as parking, accessibility or equipment.';
COMMENT ON TABLE event_platform.venue_facility_assignments IS 'Facilities provided by a venue or venue space.';
COMMENT ON TABLE event_platform.venue_translations IS 'Localised venue names and descriptions.';
COMMENT ON TABLE event_platform.event_categories IS 'Top-level event discovery and classification taxonomy.';
COMMENT ON TABLE event_platform.event_category_translations IS 'Localised event-category labels and descriptions.';
COMMENT ON TABLE event_platform.events IS 'Event aggregate: organiser ownership, public content, publication and access state.';
COMMENT ON TABLE event_platform.event_market_assignments IS 'Markets in which an event is discoverable or sale-enabled.';
COMMENT ON TABLE event_platform.event_translations IS 'Localised event title, description and public event content.';
COMMENT ON TABLE event_platform.event_category_assignments IS 'Many-to-many assignment of events to categories.';
COMMENT ON TABLE event_platform.event_tags IS 'Tag catalogue used for flexible event discovery.';
COMMENT ON TABLE event_platform.event_tag_assignments IS 'Many-to-many assignment of tags to events.';
COMMENT ON TABLE event_platform.event_media IS 'Ordered media references for event pages and promotion.';
COMMENT ON TABLE event_platform.event_organiser_assignments IS 'Additional organiser/co-host organisations assigned to an event.';
COMMENT ON TABLE event_platform.event_recurrence_rules IS 'Rule definition used to generate recurring event occurrences.';
COMMENT ON TABLE event_platform.event_occurrences IS 'Dated event instances with start/end, time zone, capacity and status.';
COMMENT ON TABLE event_platform.event_occurrence_locations IS 'Venue/space or online location assignment for an occurrence.';
COMMENT ON TABLE event_platform.event_sessions IS 'Sub-sessions or timed programme sessions within an occurrence.';
COMMENT ON TABLE event_platform.event_agenda_items IS 'Agenda entries such as talks, performances or programme activities.';
COMMENT ON TABLE event_platform.event_speakers IS 'Speaker/person records associated with an event.';
COMMENT ON TABLE event_platform.event_sponsors IS 'Sponsor records and sponsorship details for an event.';
COMMENT ON TABLE event_platform.event_faqs IS 'Frequently asked questions displayed on event pages.';
COMMENT ON TABLE event_platform.event_links IS 'Public related links such as organiser or partner resources.';
COMMENT ON TABLE event_platform.event_access_links IS 'Restricted joining/access URLs for online or private events.';
COMMENT ON TABLE event_platform.event_highlights IS 'Short featured event selling points or promotional highlights.';
COMMENT ON TABLE event_platform.event_disclaimers IS 'Event-specific disclaimer and attendee notice content.';
COMMENT ON TABLE event_platform.event_status_history IS 'Auditable event lifecycle state transitions.';
COMMENT ON TABLE event_platform.event_occurrence_status_history IS 'Auditable lifecycle transitions for individual occurrences.';
COMMENT ON TABLE event_platform.event_staff_assignments IS 'Event/occurrence-scoped staff assignments and operational roles.';
COMMENT ON TABLE event_platform.event_registration_forms IS 'Configurable attendee registration form schema for an event.';
COMMENT ON TABLE event_platform.registration_responses IS 'Attendee responses to registration form fields, retained with the order attendee.';
COMMENT ON TABLE event_platform.ticket_types IS 'Ticket product definitions such as general admission, VIP or complimentary.';
COMMENT ON TABLE event_platform.ticket_price_tiers IS 'Market/currency-specific ticket prices and effective sale windows.';
COMMENT ON TABLE event_platform.ticket_inventory IS 'Per-occurrence and ticket-type available, reserved and sold quantity state.';
COMMENT ON TABLE event_platform.ticket_inventory_movements IS 'Auditable inventory movements and corrections.';
COMMENT ON TABLE event_platform.ticket_reservations IS 'Temporary inventory reservation header with expiry and checkout state.';
COMMENT ON TABLE event_platform.ticket_reservation_items IS 'Ticket-type and quantity lines held by a reservation.';
COMMENT ON TABLE event_platform.ticket_seat_holds IS 'Temporary occurrence/seat holds for reserved seating.';
COMMENT ON TABLE event_platform.orders IS 'Checkout/order aggregate tied to one event occurrence, market and currency.';
COMMENT ON TABLE event_platform.order_items IS 'Ticket and event add-on line snapshots belonging to an order.';
COMMENT ON TABLE event_platform.order_status_history IS 'Auditable order lifecycle state transitions.';
COMMENT ON TABLE event_platform.tickets IS 'Individually issued attendee admission credentials and ticket status.';
COMMENT ON TABLE event_platform.ticket_transfers IS 'Stateful transfer workflow between ticket holders.';
COMMENT ON TABLE event_platform.discount_codes IS 'Organiser or platform discount-code rules and limits.';
COMMENT ON TABLE event_platform.discount_redemptions IS 'Recorded use of discount codes against orders/users.';
COMMENT ON TABLE event_platform.waitlist_entries IS 'Attendee requests to be notified when an event/ticket inventory becomes available.';
COMMENT ON TABLE event_platform.waitlist_offers IS 'Expiring ticket offers issued to waitlisted attendees.';
COMMENT ON TABLE event_platform.event_addons IS 'Optional event extras or purchasable add-ons.';
COMMENT ON TABLE event_platform.order_addons IS 'Order-level snapshot of selected event add-ons.';
COMMENT ON TABLE event_platform.box_office_sessions IS 'Authorised on-site sales sessions for an occurrence and currency.';
COMMENT ON TABLE event_platform.cash_drawer_movements IS 'Cash in/out, adjustment and reconciliation movements for box-office drawers.';
COMMENT ON TABLE event_platform.checkin_devices IS 'Registered and scoped devices authorised to validate tickets.';
COMMENT ON TABLE event_platform.ticket_scan_logs IS 'Raw/idempotent scan attempt history, including offline scan synchronisation.';
COMMENT ON TABLE event_platform.ticket_check_ins IS 'Authoritative successful ticket admission/check-in record.';
COMMENT ON TABLE event_platform.payment_provider_accounts IS 'Market-specific payment merchant/provider account configuration by secret reference.';
COMMENT ON TABLE event_platform.payment_provider_methods IS 'Payment methods and capabilities supported by a provider account.';
COMMENT ON TABLE event_platform.customer_payment_methods IS 'Tokenised provider payment-method references saved for a user.';
COMMENT ON TABLE event_platform.payments IS 'Payment transaction against an event order, including provider and idempotency state.';
COMMENT ON TABLE event_platform.payment_attempts IS 'Individual provider checkout/payment attempt outcomes.';
COMMENT ON TABLE event_platform.payment_webhook_events IS 'Persisted provider webhook inbox with event identifiers and processing state.';
COMMENT ON TABLE event_platform.refunds IS 'Full or partial refund request and provider outcome for an order/payment.';
COMMENT ON TABLE event_platform.refund_items IS 'Refund allocation to specific order/ticket/add-on lines.';
COMMENT ON TABLE event_platform.fee_rules IS 'Market/organiser fee calculation rules and effective periods.';
COMMENT ON TABLE event_platform.order_financial_lines IS 'Immutable financial line snapshots for ticket price, tax, fees and adjustments.';
COMMENT ON TABLE event_platform.organiser_payout_accounts IS 'Verified organiser payout destination references.';
COMMENT ON TABLE event_platform.payouts IS 'Organiser payout request, processing and settlement state.';
COMMENT ON TABLE event_platform.payout_items IS 'Order/financial items included in an organiser payout.';
COMMENT ON TABLE event_platform.ledger_accounts IS 'Event-finance chart of accounts separated by currency and purpose.';
COMMENT ON TABLE event_platform.ledger_transactions IS 'Immutable event ledger journal transaction headers.';
COMMENT ON TABLE event_platform.ledger_entries IS 'Debit/credit entries belonging to an event ledger transaction.';
COMMENT ON TABLE event_platform.reconciliation_runs IS 'Provider/bank statement reconciliation run headers.';
COMMENT ON TABLE event_platform.reconciliation_items IS 'Individual statement-to-platform reconciliation matches or exceptions.';
COMMENT ON TABLE event_platform.notification_templates IS 'Localised event-platform message templates by channel and code.';
COMMENT ON TABLE event_platform.notifications IS 'In-app event-platform notifications addressed to users.';
COMMENT ON TABLE event_platform.notification_deliveries IS 'Channel-specific delivery state and provider attempt summary.';
COMMENT ON TABLE event_platform.user_notification_preferences IS 'User preferences for event notification channels and categories.';
COMMENT ON TABLE event_platform.saved_events IS 'Events bookmarked by a user.';
COMMENT ON TABLE event_platform.tracking_links IS 'Organiser/event campaign links used for attribution.';
COMMENT ON TABLE event_platform.analytics_events IS 'Event discovery, checkout, conversion and operational analytics events.';
COMMENT ON TABLE event_platform.event_reviews IS 'Attendee reviews and ratings for events/occurrences.';
COMMENT ON TABLE event_platform.organiser_inquiries IS 'Customer/attendee enquiries directed to an organiser.';
COMMENT ON TABLE event_platform.support_tickets IS 'Event-platform support cases, optionally linked to an order and user.';
COMMENT ON TABLE event_platform.content_reports IS 'User reports about events, organisers or platform content.';
COMMENT ON TABLE event_platform.admin_audit_logs IS 'Audit history for privileged event-platform administrative actions.';
COMMENT ON TABLE event_platform.integration_connections IS 'Organiser/market external integration configuration and secret references.';
COMMENT ON TABLE event_platform.webhook_endpoints IS 'Outbound webhook endpoint subscriptions and verification configuration.';
COMMENT ON TABLE event_platform.webhook_deliveries IS 'Outbound webhook delivery attempts and response state.';
COMMENT ON TABLE event_platform.platform_plans IS 'Event-platform organiser subscription plan catalogue.';
COMMENT ON TABLE event_platform.organisation_subscriptions IS 'Organiser subscription lifecycle and plan association.';
COMMENT ON TABLE event_platform.legal_documents IS 'Platform/market legal document definitions.';
COMMENT ON TABLE event_platform.legal_document_versions IS 'Versioned text and publication/effective dates for legal documents.';

-- Unified clean-install assertions. These are structural checks, not a substitute
-- for running integration and concurrency tests against PostgreSQL 17/PostGIS.
DO $unified_assertions$
DECLARE
    v_identity_count integer;
    v_beauty_count integer;
    v_event_count integer;
BEGIN
    SELECT count(*) INTO v_identity_count FROM information_schema.tables
     WHERE table_schema='identity' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_beauty_count FROM information_schema.tables
     WHERE table_schema='beauty_platform' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_event_count FROM information_schema.tables
     WHERE table_schema='event_platform' AND table_type='BASE TABLE';
    IF v_identity_count <> 9 THEN RAISE EXCEPTION 'Expected 9 shared identity tables, found %', v_identity_count; END IF;
    IF v_beauty_count <> 222 THEN RAISE EXCEPTION 'Expected 222 beauty tables, found %', v_beauty_count; END IF;
    IF v_event_count <> 115 THEN RAISE EXCEPTION 'Expected 115 event tables, found %', v_event_count; END IF;
    IF to_regclass('identity.users') IS NULL OR to_regclass('beauty_platform.bookings') IS NULL OR to_regclass('event_platform.orders') IS NULL THEN
        RAISE EXCEPTION 'Unified platform required tables are missing';
    END IF;
END;
$unified_assertions$;


-- Event table dictionary comments added during the shared-platform integration.
COMMENT ON TABLE event_platform.countries IS 'Country reference records used by the event market catalogue.';
COMMENT ON TABLE event_platform.administrative_areas IS 'Country-specific hierarchical administrative geography.';
COMMENT ON TABLE event_platform.addresses IS 'Structured postal/landmark address and PostGIS point location.';
COMMENT ON TABLE event_platform.currencies IS 'ISO currency code, display metadata and minor-unit precision.';
COMMENT ON TABLE event_platform.languages IS 'Supported language catalogue, native names and right-to-left settings.';
COMMENT ON TABLE event_platform.markets IS 'Commercial market activation, defaults and country-specific configuration.';
COMMENT ON TABLE event_platform.market_currencies IS 'Currencies enabled for a market and its default sales currency.';
COMMENT ON TABLE event_platform.market_languages IS 'Languages enabled for a market and its default language.';
COMMENT ON TABLE event_platform.country_calendar_settings IS 'Country calendar presentation and fiscal-year configuration.';
COMMENT ON TABLE event_platform.media_assets IS 'Event-product object-storage metadata, visibility and processing status.';
COMMENT ON TABLE event_platform.organisations IS 'Organiser tenant root, public identity and verification state.';
COMMENT ON TABLE event_platform.organisation_members IS 'User membership and organiser-specific role within an organisation.';
COMMENT ON TABLE event_platform.organisation_invitations IS 'Invitations to join an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_market_profiles IS 'Market-specific legal, tax, verification and payout profile for an organisation.';
COMMENT ON TABLE event_platform.organisation_addresses IS 'Address links and address purpose for an organisation.';
COMMENT ON TABLE event_platform.organisation_categories IS 'Platform-maintained organiser category taxonomy.';
COMMENT ON TABLE event_platform.organisation_category_assignments IS 'Many-to-many mapping of organisations to organiser categories.';
COMMENT ON TABLE event_platform.organisation_translations IS 'Localised organiser names and descriptions.';
COMMENT ON TABLE event_platform.organiser_followers IS 'Users following an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_badges IS 'Platform-defined organiser verification or quality badge catalogue.';
COMMENT ON TABLE event_platform.organisation_badge_assignments IS 'Badges awarded to organiser organisations.';
COMMENT ON TABLE event_platform.venues IS 'Physical or virtual venues owned or managed by an organiser.';
COMMENT ON TABLE event_platform.venue_spaces IS 'Rooms, halls, zones or subspaces within a venue.';
COMMENT ON TABLE event_platform.venue_layouts IS 'Versioned seat-map or layout representation for a venue space.';
COMMENT ON TABLE event_platform.venue_seats IS 'Individual reservable seats and their stable venue-space identity.';
COMMENT ON TABLE event_platform.venue_facilities IS 'Facility catalogue such as parking, accessibility or equipment.';
COMMENT ON TABLE event_platform.venue_facility_assignments IS 'Facilities provided by a venue or venue space.';
COMMENT ON TABLE event_platform.venue_translations IS 'Localised venue names and descriptions.';
COMMENT ON TABLE event_platform.event_categories IS 'Top-level event discovery and classification taxonomy.';
COMMENT ON TABLE event_platform.event_category_translations IS 'Localised event-category labels and descriptions.';
COMMENT ON TABLE event_platform.events IS 'Event aggregate: organiser ownership, public content, publication and access state.';
COMMENT ON TABLE event_platform.event_market_assignments IS 'Markets in which an event is discoverable or sale-enabled.';
COMMENT ON TABLE event_platform.event_translations IS 'Localised event title, description and public event content.';
COMMENT ON TABLE event_platform.event_category_assignments IS 'Many-to-many assignment of events to categories.';
COMMENT ON TABLE event_platform.event_tags IS 'Tag catalogue used for flexible event discovery.';
COMMENT ON TABLE event_platform.event_tag_assignments IS 'Many-to-many assignment of tags to events.';
COMMENT ON TABLE event_platform.event_media IS 'Ordered media references for event pages and promotion.';
COMMENT ON TABLE event_platform.event_organiser_assignments IS 'Additional organiser/co-host organisations assigned to an event.';
COMMENT ON TABLE event_platform.event_recurrence_rules IS 'Rule definition used to generate recurring event occurrences.';
COMMENT ON TABLE event_platform.event_occurrences IS 'Dated event instances with start/end, time zone, capacity and status.';
COMMENT ON TABLE event_platform.event_occurrence_locations IS 'Venue/space or online location assignment for an occurrence.';
COMMENT ON TABLE event_platform.event_sessions IS 'Sub-sessions or timed programme sessions within an occurrence.';
COMMENT ON TABLE event_platform.event_agenda_items IS 'Agenda entries such as talks, performances or programme activities.';
COMMENT ON TABLE event_platform.event_speakers IS 'Speaker/person records associated with an event.';
COMMENT ON TABLE event_platform.event_sponsors IS 'Sponsor records and sponsorship details for an event.';
COMMENT ON TABLE event_platform.event_faqs IS 'Frequently asked questions displayed on event pages.';
COMMENT ON TABLE event_platform.event_links IS 'Public related links such as organiser or partner resources.';
COMMENT ON TABLE event_platform.event_access_links IS 'Restricted joining/access URLs for online or private events.';
COMMENT ON TABLE event_platform.event_highlights IS 'Short featured event selling points or promotional highlights.';
COMMENT ON TABLE event_platform.event_disclaimers IS 'Event-specific disclaimer and attendee notice content.';
COMMENT ON TABLE event_platform.event_status_history IS 'Auditable event lifecycle state transitions.';
COMMENT ON TABLE event_platform.event_occurrence_status_history IS 'Auditable lifecycle transitions for individual occurrences.';
COMMENT ON TABLE event_platform.event_staff_assignments IS 'Event/occurrence-scoped staff assignments and operational roles.';
COMMENT ON TABLE event_platform.event_registration_forms IS 'Configurable attendee registration form schema for an event.';
COMMENT ON TABLE event_platform.registration_responses IS 'Attendee responses to registration form fields, retained with the order attendee.';
COMMENT ON TABLE event_platform.ticket_types IS 'Ticket product definitions such as general admission, VIP or complimentary.';
COMMENT ON TABLE event_platform.ticket_price_tiers IS 'Market/currency-specific ticket prices and effective sale windows.';
COMMENT ON TABLE event_platform.ticket_inventory IS 'Per-occurrence and ticket-type available, reserved and sold quantity state.';
COMMENT ON TABLE event_platform.ticket_inventory_movements IS 'Auditable inventory movements and corrections.';
COMMENT ON TABLE event_platform.ticket_reservations IS 'Temporary inventory reservation header with expiry and checkout state.';
COMMENT ON TABLE event_platform.ticket_reservation_items IS 'Ticket-type and quantity lines held by a reservation.';
COMMENT ON TABLE event_platform.ticket_seat_holds IS 'Temporary occurrence/seat holds for reserved seating.';
COMMENT ON TABLE event_platform.orders IS 'Checkout/order aggregate tied to one event occurrence, market and currency.';
COMMENT ON TABLE event_platform.order_items IS 'Ticket and event add-on line snapshots belonging to an order.';
COMMENT ON TABLE event_platform.order_status_history IS 'Auditable order lifecycle state transitions.';
COMMENT ON TABLE event_platform.tickets IS 'Individually issued attendee admission credentials and ticket status.';
COMMENT ON TABLE event_platform.ticket_transfers IS 'Stateful transfer workflow between ticket holders.';
COMMENT ON TABLE event_platform.discount_codes IS 'Organiser or platform discount-code rules and limits.';
COMMENT ON TABLE event_platform.discount_redemptions IS 'Recorded use of discount codes against orders/users.';
COMMENT ON TABLE event_platform.waitlist_entries IS 'Attendee requests to be notified when an event/ticket inventory becomes available.';
COMMENT ON TABLE event_platform.waitlist_offers IS 'Expiring ticket offers issued to waitlisted attendees.';
COMMENT ON TABLE event_platform.event_addons IS 'Optional event extras or purchasable add-ons.';
COMMENT ON TABLE event_platform.order_addons IS 'Order-level snapshot of selected event add-ons.';
COMMENT ON TABLE event_platform.box_office_sessions IS 'Authorised on-site sales sessions for an occurrence and currency.';
COMMENT ON TABLE event_platform.cash_drawer_movements IS 'Cash in/out, adjustment and reconciliation movements for box-office drawers.';
COMMENT ON TABLE event_platform.checkin_devices IS 'Registered and scoped devices authorised to validate tickets.';
COMMENT ON TABLE event_platform.ticket_scan_logs IS 'Raw/idempotent scan attempt history, including offline scan synchronisation.';
COMMENT ON TABLE event_platform.ticket_check_ins IS 'Authoritative successful ticket admission/check-in record.';
COMMENT ON TABLE event_platform.payment_provider_accounts IS 'Market-specific payment merchant/provider account configuration by secret reference.';
COMMENT ON TABLE event_platform.payment_provider_methods IS 'Payment methods and capabilities supported by a provider account.';
COMMENT ON TABLE event_platform.customer_payment_methods IS 'Tokenised provider payment-method references saved for a user.';
COMMENT ON TABLE event_platform.payments IS 'Payment transaction against an event order, including provider and idempotency state.';
COMMENT ON TABLE event_platform.payment_attempts IS 'Individual provider checkout/payment attempt outcomes.';
COMMENT ON TABLE event_platform.payment_webhook_events IS 'Persisted provider webhook inbox with event identifiers and processing state.';
COMMENT ON TABLE event_platform.refunds IS 'Full or partial refund request and provider outcome for an order/payment.';
COMMENT ON TABLE event_platform.refund_items IS 'Refund allocation to specific order/ticket/add-on lines.';
COMMENT ON TABLE event_platform.fee_rules IS 'Market/organiser fee calculation rules and effective periods.';
COMMENT ON TABLE event_platform.order_financial_lines IS 'Immutable financial line snapshots for ticket price, tax, fees and adjustments.';
COMMENT ON TABLE event_platform.organiser_payout_accounts IS 'Verified organiser payout destination references.';
COMMENT ON TABLE event_platform.payouts IS 'Organiser payout request, processing and settlement state.';
COMMENT ON TABLE event_platform.payout_items IS 'Order/financial items included in an organiser payout.';
COMMENT ON TABLE event_platform.ledger_accounts IS 'Event-finance chart of accounts separated by currency and purpose.';
COMMENT ON TABLE event_platform.ledger_transactions IS 'Immutable event ledger journal transaction headers.';
COMMENT ON TABLE event_platform.ledger_entries IS 'Debit/credit entries belonging to an event ledger transaction.';
COMMENT ON TABLE event_platform.reconciliation_runs IS 'Provider/bank statement reconciliation run headers.';
COMMENT ON TABLE event_platform.reconciliation_items IS 'Individual statement-to-platform reconciliation matches or exceptions.';
COMMENT ON TABLE event_platform.notification_templates IS 'Localised event-platform message templates by channel and code.';
COMMENT ON TABLE event_platform.notifications IS 'In-app event-platform notifications addressed to users.';
COMMENT ON TABLE event_platform.notification_deliveries IS 'Channel-specific delivery state and provider attempt summary.';
COMMENT ON TABLE event_platform.user_notification_preferences IS 'User preferences for event notification channels and categories.';
COMMENT ON TABLE event_platform.saved_events IS 'Events bookmarked by a user.';
COMMENT ON TABLE event_platform.tracking_links IS 'Organiser/event campaign links used for attribution.';
COMMENT ON TABLE event_platform.analytics_events IS 'Event discovery, checkout, conversion and operational analytics events.';
COMMENT ON TABLE event_platform.event_reviews IS 'Attendee reviews and ratings for events/occurrences.';
COMMENT ON TABLE event_platform.organiser_inquiries IS 'Customer/attendee enquiries directed to an organiser.';
COMMENT ON TABLE event_platform.support_tickets IS 'Event-platform support cases, optionally linked to an order and user.';
COMMENT ON TABLE event_platform.content_reports IS 'User reports about events, organisers or platform content.';
COMMENT ON TABLE event_platform.admin_audit_logs IS 'Audit history for privileged event-platform administrative actions.';
COMMENT ON TABLE event_platform.integration_connections IS 'Organiser/market external integration configuration and secret references.';
COMMENT ON TABLE event_platform.webhook_endpoints IS 'Outbound webhook endpoint subscriptions and verification configuration.';
COMMENT ON TABLE event_platform.webhook_deliveries IS 'Outbound webhook delivery attempts and response state.';
COMMENT ON TABLE event_platform.platform_plans IS 'Event-platform organiser subscription plan catalogue.';
COMMENT ON TABLE event_platform.organisation_subscriptions IS 'Organiser subscription lifecycle and plan association.';
COMMENT ON TABLE event_platform.legal_documents IS 'Platform/market legal document definitions.';
COMMENT ON TABLE event_platform.legal_document_versions IS 'Versioned text and publication/effective dates for legal documents.';

-- Unified clean-install assertions. These are structural checks, not a substitute
-- for running integration and concurrency tests against PostgreSQL 17/PostGIS.
DO $unified_assertions$
DECLARE
    v_identity_count integer;
    v_beauty_count integer;
    v_event_count integer;
BEGIN
    SELECT count(*) INTO v_identity_count FROM information_schema.tables
     WHERE table_schema='identity' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_beauty_count FROM information_schema.tables
     WHERE table_schema='beauty_platform' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_event_count FROM information_schema.tables
     WHERE table_schema='event_platform' AND table_type='BASE TABLE';
    IF v_identity_count <> 9 THEN RAISE EXCEPTION 'Expected 9 shared identity tables, found %', v_identity_count; END IF;
    IF v_beauty_count <> 222 THEN RAISE EXCEPTION 'Expected 222 beauty tables, found %', v_beauty_count; END IF;
    IF v_event_count <> 115 THEN RAISE EXCEPTION 'Expected 115 event tables, found %', v_event_count; END IF;
    IF to_regclass('identity.users') IS NULL OR to_regclass('beauty_platform.bookings') IS NULL OR to_regclass('event_platform.orders') IS NULL THEN
        RAISE EXCEPTION 'Unified platform required tables are missing';
    END IF;
END;
$unified_assertions$;


-- Event table dictionary comments added during the shared-platform integration.
COMMENT ON TABLE event_platform.countries IS 'Country reference records used by the event market catalogue.';
COMMENT ON TABLE event_platform.administrative_areas IS 'Country-specific hierarchical administrative geography.';
COMMENT ON TABLE event_platform.addresses IS 'Structured postal/landmark address and PostGIS point location.';
COMMENT ON TABLE event_platform.currencies IS 'ISO currency code, display metadata and minor-unit precision.';
COMMENT ON TABLE event_platform.languages IS 'Supported language catalogue, native names and right-to-left settings.';
COMMENT ON TABLE event_platform.markets IS 'Commercial market activation, defaults and country-specific configuration.';
COMMENT ON TABLE event_platform.market_currencies IS 'Currencies enabled for a market and its default sales currency.';
COMMENT ON TABLE event_platform.market_languages IS 'Languages enabled for a market and its default language.';
COMMENT ON TABLE event_platform.country_calendar_settings IS 'Country calendar presentation and fiscal-year configuration.';
COMMENT ON TABLE event_platform.media_assets IS 'Event-product object-storage metadata, visibility and processing status.';
COMMENT ON TABLE event_platform.organisations IS 'Organiser tenant root, public identity and verification state.';
COMMENT ON TABLE event_platform.organisation_members IS 'User membership and organiser-specific role within an organisation.';
COMMENT ON TABLE event_platform.organisation_invitations IS 'Invitations to join an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_market_profiles IS 'Market-specific legal, tax, verification and payout profile for an organisation.';
COMMENT ON TABLE event_platform.organisation_addresses IS 'Address links and address purpose for an organisation.';
COMMENT ON TABLE event_platform.organisation_categories IS 'Platform-maintained organiser category taxonomy.';
COMMENT ON TABLE event_platform.organisation_category_assignments IS 'Many-to-many mapping of organisations to organiser categories.';
COMMENT ON TABLE event_platform.organisation_translations IS 'Localised organiser names and descriptions.';
COMMENT ON TABLE event_platform.organiser_followers IS 'Users following an organiser organisation.';
COMMENT ON TABLE event_platform.organisation_badges IS 'Platform-defined organiser verification or quality badge catalogue.';
COMMENT ON TABLE event_platform.organisation_badge_assignments IS 'Badges awarded to organiser organisations.';
COMMENT ON TABLE event_platform.venues IS 'Physical or virtual venues owned or managed by an organiser.';
COMMENT ON TABLE event_platform.venue_spaces IS 'Rooms, halls, zones or subspaces within a venue.';
COMMENT ON TABLE event_platform.venue_layouts IS 'Versioned seat-map or layout representation for a venue space.';
COMMENT ON TABLE event_platform.venue_seats IS 'Individual reservable seats and their stable venue-space identity.';
COMMENT ON TABLE event_platform.venue_facilities IS 'Facility catalogue such as parking, accessibility or equipment.';
COMMENT ON TABLE event_platform.venue_facility_assignments IS 'Facilities provided by a venue or venue space.';
COMMENT ON TABLE event_platform.venue_translations IS 'Localised venue names and descriptions.';
COMMENT ON TABLE event_platform.event_categories IS 'Top-level event discovery and classification taxonomy.';
COMMENT ON TABLE event_platform.event_category_translations IS 'Localised event-category labels and descriptions.';
COMMENT ON TABLE event_platform.events IS 'Event aggregate: organiser ownership, public content, publication and access state.';
COMMENT ON TABLE event_platform.event_market_assignments IS 'Markets in which an event is discoverable or sale-enabled.';
COMMENT ON TABLE event_platform.event_translations IS 'Localised event title, description and public event content.';
COMMENT ON TABLE event_platform.event_category_assignments IS 'Many-to-many assignment of events to categories.';
COMMENT ON TABLE event_platform.event_tags IS 'Tag catalogue used for flexible event discovery.';
COMMENT ON TABLE event_platform.event_tag_assignments IS 'Many-to-many assignment of tags to events.';
COMMENT ON TABLE event_platform.event_media IS 'Ordered media references for event pages and promotion.';
COMMENT ON TABLE event_platform.event_organiser_assignments IS 'Additional organiser/co-host organisations assigned to an event.';
COMMENT ON TABLE event_platform.event_recurrence_rules IS 'Rule definition used to generate recurring event occurrences.';
COMMENT ON TABLE event_platform.event_occurrences IS 'Dated event instances with start/end, time zone, capacity and status.';
COMMENT ON TABLE event_platform.event_occurrence_locations IS 'Venue/space or online location assignment for an occurrence.';
COMMENT ON TABLE event_platform.event_sessions IS 'Sub-sessions or timed programme sessions within an occurrence.';
COMMENT ON TABLE event_platform.event_agenda_items IS 'Agenda entries such as talks, performances or programme activities.';
COMMENT ON TABLE event_platform.event_speakers IS 'Speaker/person records associated with an event.';
COMMENT ON TABLE event_platform.event_sponsors IS 'Sponsor records and sponsorship details for an event.';
COMMENT ON TABLE event_platform.event_faqs IS 'Frequently asked questions displayed on event pages.';
COMMENT ON TABLE event_platform.event_links IS 'Public related links such as organiser or partner resources.';
COMMENT ON TABLE event_platform.event_access_links IS 'Restricted joining/access URLs for online or private events.';
COMMENT ON TABLE event_platform.event_highlights IS 'Short featured event selling points or promotional highlights.';
COMMENT ON TABLE event_platform.event_disclaimers IS 'Event-specific disclaimer and attendee notice content.';
COMMENT ON TABLE event_platform.event_status_history IS 'Auditable event lifecycle state transitions.';
COMMENT ON TABLE event_platform.event_occurrence_status_history IS 'Auditable lifecycle transitions for individual occurrences.';
COMMENT ON TABLE event_platform.event_staff_assignments IS 'Event/occurrence-scoped staff assignments and operational roles.';
COMMENT ON TABLE event_platform.event_registration_forms IS 'Configurable attendee registration form schema for an event.';
COMMENT ON TABLE event_platform.registration_responses IS 'Attendee responses to registration form fields, retained with the order attendee.';
COMMENT ON TABLE event_platform.ticket_types IS 'Ticket product definitions such as general admission, VIP or complimentary.';
COMMENT ON TABLE event_platform.ticket_price_tiers IS 'Market/currency-specific ticket prices and effective sale windows.';
COMMENT ON TABLE event_platform.ticket_inventory IS 'Per-occurrence and ticket-type available, reserved and sold quantity state.';
COMMENT ON TABLE event_platform.ticket_inventory_movements IS 'Auditable inventory movements and corrections.';
COMMENT ON TABLE event_platform.ticket_reservations IS 'Temporary inventory reservation header with expiry and checkout state.';
COMMENT ON TABLE event_platform.ticket_reservation_items IS 'Ticket-type and quantity lines held by a reservation.';
COMMENT ON TABLE event_platform.ticket_seat_holds IS 'Temporary occurrence/seat holds for reserved seating.';
COMMENT ON TABLE event_platform.orders IS 'Checkout/order aggregate tied to one event occurrence, market and currency.';
COMMENT ON TABLE event_platform.order_items IS 'Ticket and event add-on line snapshots belonging to an order.';
COMMENT ON TABLE event_platform.order_status_history IS 'Auditable order lifecycle state transitions.';
COMMENT ON TABLE event_platform.tickets IS 'Individually issued attendee admission credentials and ticket status.';
COMMENT ON TABLE event_platform.ticket_transfers IS 'Stateful transfer workflow between ticket holders.';
COMMENT ON TABLE event_platform.discount_codes IS 'Organiser or platform discount-code rules and limits.';
COMMENT ON TABLE event_platform.discount_redemptions IS 'Recorded use of discount codes against orders/users.';
COMMENT ON TABLE event_platform.waitlist_entries IS 'Attendee requests to be notified when an event/ticket inventory becomes available.';
COMMENT ON TABLE event_platform.waitlist_offers IS 'Expiring ticket offers issued to waitlisted attendees.';
COMMENT ON TABLE event_platform.event_addons IS 'Optional event extras or purchasable add-ons.';
COMMENT ON TABLE event_platform.order_addons IS 'Order-level snapshot of selected event add-ons.';
COMMENT ON TABLE event_platform.box_office_sessions IS 'Authorised on-site sales sessions for an occurrence and currency.';
COMMENT ON TABLE event_platform.cash_drawer_movements IS 'Cash in/out, adjustment and reconciliation movements for box-office drawers.';
COMMENT ON TABLE event_platform.checkin_devices IS 'Registered and scoped devices authorised to validate tickets.';
COMMENT ON TABLE event_platform.ticket_scan_logs IS 'Raw/idempotent scan attempt history, including offline scan synchronisation.';
COMMENT ON TABLE event_platform.ticket_check_ins IS 'Authoritative successful ticket admission/check-in record.';
COMMENT ON TABLE event_platform.payment_provider_accounts IS 'Market-specific payment merchant/provider account configuration by secret reference.';
COMMENT ON TABLE event_platform.payment_provider_methods IS 'Payment methods and capabilities supported by a provider account.';
COMMENT ON TABLE event_platform.customer_payment_methods IS 'Tokenised provider payment-method references saved for a user.';
COMMENT ON TABLE event_platform.payments IS 'Payment transaction against an event order, including provider and idempotency state.';
COMMENT ON TABLE event_platform.payment_attempts IS 'Individual provider checkout/payment attempt outcomes.';
COMMENT ON TABLE event_platform.payment_webhook_events IS 'Persisted provider webhook inbox with event identifiers and processing state.';
COMMENT ON TABLE event_platform.refunds IS 'Full or partial refund request and provider outcome for an order/payment.';
COMMENT ON TABLE event_platform.refund_items IS 'Refund allocation to specific order/ticket/add-on lines.';
COMMENT ON TABLE event_platform.fee_rules IS 'Market/organiser fee calculation rules and effective periods.';
COMMENT ON TABLE event_platform.order_financial_lines IS 'Immutable financial line snapshots for ticket price, tax, fees and adjustments.';
COMMENT ON TABLE event_platform.organiser_payout_accounts IS 'Verified organiser payout destination references.';
COMMENT ON TABLE event_platform.payouts IS 'Organiser payout request, processing and settlement state.';
COMMENT ON TABLE event_platform.payout_items IS 'Order/financial items included in an organiser payout.';
COMMENT ON TABLE event_platform.ledger_accounts IS 'Event-finance chart of accounts separated by currency and purpose.';
COMMENT ON TABLE event_platform.ledger_transactions IS 'Immutable event ledger journal transaction headers.';
COMMENT ON TABLE event_platform.ledger_entries IS 'Debit/credit entries belonging to an event ledger transaction.';
COMMENT ON TABLE event_platform.reconciliation_runs IS 'Provider/bank statement reconciliation run headers.';
COMMENT ON TABLE event_platform.reconciliation_items IS 'Individual statement-to-platform reconciliation matches or exceptions.';
COMMENT ON TABLE event_platform.notification_templates IS 'Localised event-platform message templates by channel and code.';
COMMENT ON TABLE event_platform.notifications IS 'In-app event-platform notifications addressed to users.';
COMMENT ON TABLE event_platform.notification_deliveries IS 'Channel-specific delivery state and provider attempt summary.';
COMMENT ON TABLE event_platform.user_notification_preferences IS 'User preferences for event notification channels and categories.';
COMMENT ON TABLE event_platform.saved_events IS 'Events bookmarked by a user.';
COMMENT ON TABLE event_platform.tracking_links IS 'Organiser/event campaign links used for attribution.';
COMMENT ON TABLE event_platform.analytics_events IS 'Event discovery, checkout, conversion and operational analytics events.';
COMMENT ON TABLE event_platform.event_reviews IS 'Attendee reviews and ratings for events/occurrences.';
COMMENT ON TABLE event_platform.organiser_inquiries IS 'Customer/attendee enquiries directed to an organiser.';
COMMENT ON TABLE event_platform.support_tickets IS 'Event-platform support cases, optionally linked to an order and user.';
COMMENT ON TABLE event_platform.content_reports IS 'User reports about events, organisers or platform content.';
COMMENT ON TABLE event_platform.admin_audit_logs IS 'Audit history for privileged event-platform administrative actions.';
COMMENT ON TABLE event_platform.integration_connections IS 'Organiser/market external integration configuration and secret references.';
COMMENT ON TABLE event_platform.webhook_endpoints IS 'Outbound webhook endpoint subscriptions and verification configuration.';
COMMENT ON TABLE event_platform.webhook_deliveries IS 'Outbound webhook delivery attempts and response state.';
COMMENT ON TABLE event_platform.platform_plans IS 'Event-platform organiser subscription plan catalogue.';
COMMENT ON TABLE event_platform.organisation_subscriptions IS 'Organiser subscription lifecycle and plan association.';
COMMENT ON TABLE event_platform.legal_documents IS 'Platform/market legal document definitions.';
COMMENT ON TABLE event_platform.legal_document_versions IS 'Versioned text and publication/effective dates for legal documents.';

-- Unified clean-install assertions. These are structural checks, not a substitute
-- for running integration and concurrency tests against PostgreSQL 17/PostGIS.
DO $unified_assertions$
DECLARE
    v_identity_count integer;
    v_beauty_count integer;
    v_event_count integer;
BEGIN
    SELECT count(*) INTO v_identity_count FROM information_schema.tables
     WHERE table_schema='identity' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_beauty_count FROM information_schema.tables
     WHERE table_schema='beauty_platform' AND table_type='BASE TABLE';
    SELECT count(*) INTO v_event_count FROM information_schema.tables
     WHERE table_schema='event_platform' AND table_type='BASE TABLE';
    IF v_identity_count <> 9 THEN RAISE EXCEPTION 'Expected 9 shared identity tables, found %', v_identity_count; END IF;
    IF v_beauty_count <> 222 THEN RAISE EXCEPTION 'Expected 222 beauty tables, found %', v_beauty_count; END IF;
    IF v_event_count <> 115 THEN RAISE EXCEPTION 'Expected 115 event tables, found %', v_event_count; END IF;
    IF to_regclass('identity.users') IS NULL OR to_regclass('beauty_platform.bookings') IS NULL OR to_regclass('event_platform.orders') IS NULL THEN
        RAISE EXCEPTION 'Unified platform required tables are missing';
    END IF;
END;
$unified_assertions$;

COMMIT;
