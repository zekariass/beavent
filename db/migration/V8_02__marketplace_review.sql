-- =============================================================================
-- 8.1: approving businesses for the marketplace
--      a business asks to be listed (HIDDEN -> PENDING_REVIEW), platform staff list it, send it back or take it off
--      (LISTED / HIDDEN / DELISTED). The reason of the last decision is shown to the business.
-- =============================================================================

ALTER TABLE tenant.organizations
  ADD COLUMN marketplace_note       text,
  ADD COLUMN marketplace_changed_at timestamptz;

COMMENT ON COLUMN tenant.organizations.marketplace_note IS
  'Why staff sent the request back or took the business off the marketplace; shown to the business.';

INSERT INTO iam.permissions (code, scope, module, description, is_sensitive) VALUES
  ('admin.marketplace.review', 'PLATFORM', 'admin', 'List businesses on the marketplace, send requests back and take them off', false);

INSERT INTO iam.staff_role_permissions (role_code, permission_code) VALUES
  ('SUPER_ADMIN',    'admin.marketplace.review'),
  ('OPS_ADMIN',      'admin.marketplace.review'),
  ('MODERATOR',      'admin.marketplace.review'),
  ('MARKET_MANAGER', 'admin.marketplace.review');
