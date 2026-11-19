# Beavent: platform design (v2)

One platform for **booking services** (salons, barbers, spas, clinics, gyms, freelancers and at-home professionals; Fresha-like) and **creating, selling and running events** (concerts, conferences, workshops, classes, timed-entry attractions; Eventbrite-like). It launches in Ethiopia, then East Africa, then the rest of Africa.

| Deliverable | What it is |
|---|---|
| Backend | `beavent-api`: one Java/Spring Boot modular monolith (API + worker processes), PostgreSQL + PostGIS |
| Mobile app | One Expo app: consumer experience + **Business mode** for organization members (calendar, check-in scanner, POS) |
| Web marketplace | Next.js: discovery, booking, ticket checkout, account, embeddable widgets, mini-sites |
| Partner portal | Next.js: one workspace for salons, spas, freelancers **and** event organizers |
| Admin console | Next.js: internal operations, risk, finance, markets, moderation, support |

## Documents

| # | Document | For |
|---|---|---|
| 01 | [Review of v1 and redesign decisions](01-review-and-decisions.md) | Why the design changed: findings, decisions, v1 → v2 mapping, open business decisions |
| 02 | [Product scope and feature parity](02-product-and-feature-parity.md) | Fresha and Eventbrite analysis; unified feature matrix with phases; Africa-first additions; exclusions; monetization |
| 03 | [System architecture](03-system-architecture.md) | The five deliverables, backend modules, API conventions, workflows, infrastructure, security, SLOs, roadmap |
| 04 | [Payments and money](04-payments-and-money.md) | Provider-agnostic payments, collection modes, routing/failover, mobile-money flows, refunds, payouts, ledger postings, fees, reconciliation |
| 05 | [Data model](05-data-model.md) | Conventions, schema map, table dictionary, RLS model, invariants and tests, migration practice |
| 06 | [Markets, localization and compliance](06-markets-and-localization.md) | Ethiopia specifics, expansion playbook, market activation checklist, i18n engineering, data residency |
| 07 | [Implementation plan](07-implementation-plan.md) | Step-by-step plan to the Addis Ababa pilot (Chapa-only, Chapa holds the money, hosting TBD), dependencies, risks |

The database baseline is in [`../db/migration`](../db/migration) (Flyway order). Validate it with:

```bash
npm run db:validate        # = db/scripts/validate.sh --docker
```

This starts a throwaway PostGIS container, applies all migrations, runs the invariant, tenant-isolation and schema-lint tests plus a 40-session race test, then removes the container. How to run everything else: the [root README](../README.md).

The superseded v1 documents are kept in [`archive/v1/`](archive/v1/) for reference.
