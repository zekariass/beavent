#!/usr/bin/env bash
# Runs every CI check locally (same commands as .github/workflows/ci.yml). Stops at the first failure.
#   scripts/check.sh            # everything
#   scripts/check.sh --fast     # skip the backend Gradle build and the database validation
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

step() { printf '\n== %s\n' "$1"; }

step "format"
npx --no-install prettier --check . --log-level warn

step "API contract"
npm run --silent api:spec > /dev/null
git diff --exit-code --stat -- packages/api-client || { echo "packages/api-client is stale: commit the regenerated files"; exit 1; }

step "JS lint, typecheck, test, build"
npx --no-install turbo run lint typecheck test build --output-logs=errors-only

if [[ "${1:-}" != "--fast" ]]; then
  step "backend"
  (cd services/backend && ./gradlew build --console=plain -q)
  step "database"
  db/scripts/validate.sh --docker | tail -1
fi
printf '\nALL CHECKS PASSED\n'
