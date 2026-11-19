# Beavent

One platform for booking services (salons, spas, barbers, freelancers; Fresha-like) and selling and running events (Eventbrite-like), starting in Ethiopia. Design documents: [`docs/`](docs/README.md). Implementation order: [`docs/07-implementation-plan.md`](docs/07-implementation-plan.md).

## Repository layout

| Path             | What                                                                                                         | Stack                                  |
| ---------------- | ------------------------------------------------------------------------------------------------------------ | -------------------------------------- |
| `backend/`       | API + workers (one Spring Boot modular monolith)                                                             | Java 21, Spring Boot 4.1, Gradle       |
| `mobile/`        | Consumer app + Business mode                                                                                 | Expo SDK 58, React Native, Expo Router |
| `web/`           | Web marketplace (port 3000)                                                                                  | Next.js 16                             |
| `business/`      | Business / organizer portal (port 3001)                                                                      | Next.js 16                             |
| `admin-console/` | Internal admin console (port 3002)                                                                           | Next.js 16                             |
| `packages/`      | Shared TypeScript: `api-client`, `ui`, `i18n`, `calendar` (Ethiopian calendar), `money`, `config`            | TypeScript                             |
| `db/`            | Database baseline (Flyway migrations), invariant tests, validation scripts — the single source of the schema | PostgreSQL 17 + PostGIS                |
| `docs/`          | Design and plan                                                                                              | Markdown                               |

## Prerequisites

- Node 26 (`.nvmrc`) and npm 11
- Java 21
- Docker (for PostGIS, Redis, RabbitMQ, MinIO, Mailpit and the tests)
- `psql` client 17 (for `db/scripts/validate.sh`)

## Common commands

```bash
npm install                 # install all JS workspaces (one root lockfile)
docker compose up -d        # local services: PostGIS :5433, Redis :6379, RabbitMQ :5672/:15672, MinIO :9000/:9001, Mailpit :8025

npm run db:validate         # schema: throwaway PostGIS container → migrations → tests → race test
npm run api:build           # backend: compile, format check, tests (Testcontainers; Flyway applies db/migration)
cd backend && ./gradlew bootRun   # run the API against docker compose services

npm run dev                 # all JS apps in dev mode (or: npm run dev -w @beavent/web)
npm run lint                # lint all JS workspaces
npm run typecheck           # typecheck all JS workspaces
npm run build               # production builds
npm run format              # Prettier (JS/TS/JSON/MD outside backend, db, docs)
cd backend && ./gradlew spotlessApply   # Java formatting (Palantir Java Format)
cd mobile && npx expo start # run the mobile app
```

## Conventions

- The schema lives only in `db/migration`; the backend packages those files for Flyway at build time. Every change must keep `npm run db:validate` green (see `docs/05-data-model.md` §7).
- Money is integer minor units + currency; time is UTC `timestamptz` + IANA zone; tenant data always carries `org_id` (RLS).
- Each pull request follows the definition of done in `docs/07-implementation-plan.md` §2.
