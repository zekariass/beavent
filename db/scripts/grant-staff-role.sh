#!/usr/bin/env bash
# Grants a platform staff role (admin console access) to an existing account, identified by its verified phone.
# Run by an operator with database-owner access; the grant is written to the audit log.
#
#   db/scripts/grant-staff-role.sh +251911234567 RISK_ANALYST ET     # one market
#   db/scripts/grant-staff-role.sh +251911234567 SUPER_ADMIN         # all markets
#
# Connection: PG* environment variables (local docker compose: PGHOST=localhost PGPORT=5433 PGUSER=beavent
# PGDATABASE=beavent PGPASSWORD=beavent). The person then sets up an authenticator app in their account and signs
# in to the admin console. Roles: SUPER_ADMIN, OPS_ADMIN, SUPPORT_AGENT, FINANCE, RISK_ANALYST, MODERATOR,
# MARKET_MANAGER, READ_ONLY.
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  sed -n '2,13p' "$0" >&2
  exit 2
fi
PHONE="$1" ROLE="$2" MARKET="${3:-}"

psql -v ON_ERROR_STOP=1 -X -q -v phone="$PHONE" -v role="$ROLE" -v market="$MARKET" <<'SQL'
\set QUIET on
BEGIN;
SELECT coalesce((SELECT id::text FROM iam.users WHERE phone_e164 = :'phone' AND phone_verified_at IS NOT NULL), '')
       AS user_id \gset
SELECT :'user_id' = '' AS missing \gset
\if :missing
  \echo 'No account has verified this phone number. The person must sign in once first.'
  ROLLBACK;
  \quit
\endif
WITH granted AS (
  INSERT INTO iam.user_staff_roles (user_id, role_code, market_id)
  VALUES (:'user_id', :'role', (SELECT id FROM ref.markets WHERE code = NULLIF(:'market', '')))
  RETURNING id, role_code, market_id
)
INSERT INTO platform.audit_log (actor_kind, action, target_type, target_id, after_state, reason)
SELECT 'SYSTEM', 'staff_role.granted', 'iam.user', :'user_id',
       jsonb_build_object('roleCode', role_code, 'marketId', market_id), 'Granted with db/scripts/grant-staff-role.sh'
FROM granted;
COMMIT;
\echo 'Granted.'
SQL
