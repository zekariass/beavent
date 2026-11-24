# Beavent

One platform for booking services (salons, spas, barbers, freelancers; Fresha-like) and selling and running events (Eventbrite-like), starting in Ethiopia. Design documents: [`docs/`](docs/README.md). Implementation order: [`docs/07-implementation-plan.md`](docs/07-implementation-plan.md).

## Repository layout

| Path                    | What                                                                                                                                                   | Stack                                  |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------- |
| `services/backend/`     | API + workers (one Spring Boot modular monolith)                                                                                                       | Java 21, Spring Boot 4.1, Gradle       |
| `apps/public-mobile/`   | Consumer app (customers booking services and buying tickets)                                                                                           | Expo SDK 58, React Native, Expo Router |
| `apps/business-mobile/` | Business app for owners, staff and organizers (Schedule and More tabs; Business mode lives here)                                                       | Expo SDK 58, React Native, Expo Router |
| `apps/web/`             | The one web platform (port 3000): public site `/[lang]`, business and organizer console `/[lang]/business`, admin console at the secret `/$ADMIN_PATH` | Next.js 16                             |
| `packages/`             | Shared TypeScript: `api-client`, `ui`, `i18n`, `calendar` (Ethiopian calendar), `money`, `config`                                                      | TypeScript                             |
| `tools/chapa-stub/`     | Local stand-in for the Chapa API (port 8090) — development and CI only; see its README                                                                 | Node 26 (TypeScript)                   |
| `docker/`               | Container image definition shared by the Next.js apps                                                                                                  | Docker                                 |
| `db/`                   | Database baseline (Flyway migrations), invariant tests, validation scripts — the single source of the schema                                           | PostgreSQL 17 + PostGIS                |
| `docs/`                 | Design and plan                                                                                                                                        | Markdown                               |

## Prerequisites

- Node 26 (`.nvmrc`) and npm 11
- Java 21
- Docker (for PostGIS, Redis, RabbitMQ, SeaweedFS (S3), Mailpit and the tests)
- `psql` client 17 (for `db/scripts/validate.sh`)

## Common commands

```bash
npm install                 # install all JS workspaces (one root lockfile)
cp .env.example .env        # local settings (never commit .env)
docker compose up -d        # PostGIS :5433, Redis :6379, RabbitMQ :5672/:15672, SeaweedFS S3 :8333, Mailpit :8025, Chapa stub :8090

npm run db:validate         # schema: throwaway PostGIS container → migrations → tests → race test
npm run api:build           # backend: compile, format check, tests (Testcontainers; Flyway applies db/migration)
cd backend && ./gradlew bootRun   # run the API (profile "local") against docker compose services

npm run dev                 # all JS apps in dev mode (or: npm run dev -w @beavent/web)
                            # web platform: http://localhost:3000 (admin console at /ops locally)
npm run lint                # lint all JS workspaces
npm run typecheck           # typecheck all JS workspaces
npm run test                # JS tests (e.g. the Chapa stub, the API client)
npm run api:spec            # lint + bundle the OpenAPI contract, regenerate packages/api-client (commit the result)
npm run build               # production builds
npm run format              # Prettier (JS/TS/JSON/MD outside backend, db, docs)
npm run e2e                 # browser tests against a running stack (API_LOG=<api log file>; see tools/e2e)
cd backend && ./gradlew spotlessApply   # Java formatting (Palantir Java Format)
cd apps/public-mobile && npx expo start # run the consumer app
```

## Admin console access

Staff sign in at http://localhost:3000/ops (the `ADMIN_PATH` prefix; default `ops` locally, required in production) with a code sent to their phone or email. Grant the first staff role to an
account that has signed in once:

```bash
PGHOST=localhost PGPORT=5433 PGUSER=beavent PGDATABASE=beavent PGPASSWORD=beavent \
  db/scripts/grant-staff-role.sh +251911234567 SUPER_ADMIN      # or e.g. RISK_ANALYST ET
```

Locally, one-time codes appear in the API log (`SMS_PROVIDER=log`).

## Container images

```bash
(cd backend && ./gradlew bootJar) && docker build -f services/backend/Dockerfile -t beavent-api .
docker build -f docker/next-app.Dockerfile -t beavent-web .   # the web platform; set ADMIN_PATH when running it
docker build -t beavent-chapa-stub tools/chapa-stub
```

## Continuous integration

`.github/workflows/ci.yml` runs on every pull request: JS (format, lint, typecheck, test, build), backend (Gradle build
with Testcontainers), database (`validate.sh --docker`) and all container images. Dependabot proposes weekly updates.

## Beavent UI/UX Design Standard

Every Beavent interface — mobile, web marketplace, partner portal, admin console — follows one design system, and it
is **mandatory** for people and AI agents alike:

- [`AGENTS.md`](AGENTS.md) — the standing rules (read by coding agents; short enough for humans too).
- [`docs/design/beavent/`](docs/design/beavent/) — philosophy and decisions DD1–DD14, tokens, components, screen
  specs for Services and Events, navigation, mobile guidelines, screen standards.
- [`docs/design/beavent/AI_AGENT_DESIGN_INSTRUCTIONS.md`](docs/design/beavent/AI_AGENT_DESIGN_INSTRUCTIONS.md) —
  checklists, component APIs and the definition of done for UI work.

In code: tokens live in `packages/ui` (`npm run generate -w @beavent/ui` rebuilds `tokens.css` for the web apps; a
test checks contrast and that the CSS is current); React Native primitives and app shell in `packages/native` (`@beavent/native`, used by both mobile apps); how a venue page reads (prices, hours, policies) in `packages/venue` (`@beavent/venue`, web and mobile); visual QA
with `npm run visual -w @beavent/e2e` against a running stack.

## Conventions

- The schema lives only in `db/migration`; the backend packages those files for Flyway at build time. Every change must keep `npm run db:validate` green (see `docs/05-data-model.md` §7).
- Money is integer minor units + currency; time is UTC `timestamptz` + IANA zone; tenant data always carries `org_id` (RLS).
- Each pull request follows the definition of done in `docs/07-implementation-plan.md` §2.
