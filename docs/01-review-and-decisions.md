# 01 — Review of v1 and redesign decisions

**Status:** v2 baseline, 19 Nov 2026. Supersedes the v1 documents in [`archive/v1/`](archive/v1/).
**Read with:** [03 architecture](03-system-architecture.md), [04 payments](04-payments-and-money.md), [05 data model](05-data-model.md).

## 1. What v1 was

v1 combined two independently designed products — *Beauty & Wellness v1.2.1* and *Event Management & Ticketing v1.0* — into one PostgreSQL database with a shared identity schema:

| v1 property | Value |
|---|---|
| Client applications | 7 (Beauty customer app, Beauty business app, Event customer app, Event organiser app, Beauty web, Event web, shared admin) |
| Schemas | `identity` (9 tables), `beauty_platform` (222), `event_platform` (115) — 346 tables |
| Tenant roots | `beauty_platform.businesses` **and** `event_platform.organisations` |
| Money | Beauty `numeric(14,2)` major units; Event `bigint` minor units |
| Tenant isolation | Beauty: RLS + composite keys. Event: application checks only (v1 itself calls this a release blocker) |
| Validation | "Static integration review"; never executed against PostgreSQL |

v1 states its own principle as *"share infrastructure only where the concepts are genuinely common."* The problem is that most of the concepts **are** common. A salon and an event organiser both have a tenant, members with roles, places, customers, a catalogue, a checkout, payments, refunds, payouts, fees, taxes, reviews, waitlists, promotions, notifications and a ledger. Keeping them separate doubled the system without adding capability.

## 2. Findings

Severity: **Critical** = wrong money, data leakage or legal exposure. **High** = missing core capability or a design that blocks scale. **Medium** = cost/complexity without benefit.

| # | Finding | Severity | Evidence in v1 |
|---|---|---|---|
| F1 | **Two systems, not one.** Duplicate concept pairs: `businesses`/`organisations`, `business_memberships`/`organisation_members`, two `payments`, two `payment_provider_accounts`, two `refunds`, two `payouts`, two ledgers, two `notification_*` stacks, `file_assets`/`media_assets`, two `support_tickets`, `audit_logs`/`admin_audit_logs`, `reviews`/`event_reviews`, two waitlists, `promotions`/`discount_codes`, `cash_registers`/`box_office_sessions`, `subscription_plans`/`platform_plans`, two `countries`/`currencies`/`languages`/`administrative_areas` with different key types. | High | Section 5 of the v1 DB design; §11 of the v1 system design keeps every one separate by rule. |
| F2 | **Event tenant isolation missing.** No RLS and no composite tenant keys in `event_platform`, so a ticket, order or payout row can point at another organiser's data. | Critical | v1 DB design §7.2, system design §9.2. |
| F3 | **Inconsistent money.** `numeric(14,2)` cannot represent 3-decimal currencies, silently accepts sub-unit amounts for 0-decimal currencies (UGX, RWF, XOF) without extra triggers, and forces conversions at every boundary. | Critical | v1 system design §11 table, "Money representation". |
| F4 | **No merchant-of-record / collection model.** Beauty provider accounts belong to a business; Event provider accounts belong to a market. Nothing says *who legally collects the money*, which is the central regulatory question in Ethiopia (NBE payment-instrument / system-operator licensing) and in every target market. | Critical | `beauty_platform.payment_provider_accounts.business_id` vs `event_platform.payment_provider_accounts.market_id`. |
| F5 | **Payments not built for African rails.** No routing or failover between providers; no model for asynchronous push prompts (USSD/STK) with polling; nothing for a customer approving two prompts; no manually verified bank transfer with proof upload; no fallback when a provider cannot refund via API (common for mobile money). | High | `payment_attempts` and `payments` in both schemas. |
| F6 | **Polymorphic references.** `payments.source_type/source_id` and `payment_obligations.source_type/source_id` have no foreign keys, so money can point at nothing. | High | Beauty `payments`, `payment_obligations`. |
| F7 | **Regulatory scope creep.** `business_wallets`/`wallet_transactions` (stored value), a full per-business general ledger (`financial_accounts`, `financial_journals`, `legal_entity_fiscal_years`) and statutory-payroll structures. Each is a licensed or professionally regulated product per country. | High | Beauty finance and payroll tables. |
| F8 | **Event inventory gaps vs Eventbrite.** Occurrence capacity is not enforced across ticket types (no shared pools); no organiser holds; no time- or quantity-based price phases; a ticket admits to exactly one occurrence (no multi-day/series passes); no session capacity; counters depend on application code. | High | `ticket_inventory`, `tickets.occurrence_id NOT NULL`. |
| F9 | **Classes duplicated.** Beauty `class_sessions`/`class_enrollments` and Event occurrences/tickets model the same thing. An instructor's class and their appointments are in different ledgers, so double-booking between them is not prevented. | High | Beauty scheduling vs Event authoring. |
| F10 | **Over-normalised presentation content.** `event_faqs`, `event_links`, `event_highlights`, `event_disclaimers`, `event_speakers`, `event_sponsors`, `event_agenda_items` are display content stored as transactional rows. | Medium | v1 Event authoring tables. |
| F11 | **Missing Africa-specific capabilities.** No fiscal e-invoicing integration (Ethiopia's MoR clearance e-invoicing, Kenya eTIMS, Uganda EFRIS …), no KYB/KYC workflow with national ID, no Telegram/WhatsApp channels, no Ethiopian local-time convention, no at-home service areas or travel fees, no diaspora presentment currency. | High | Absent. |
| F12 | **Seven apps.** Four mobile apps and two product web apps double release, store-review, QA and support cost for two audiences that heavily overlap (the same person books a braid and buys a concert ticket). | Medium | v1 system design §2. |
| F13 | **Never executed.** The 9,700-line SQL was never run; integrity rested on review. | High | v1 header notes. |

## 3. Decisions

| # | Decision | Rationale | Fixes |
|---|---|---|---|
| D1 | **One product, one tenant model.** `tenant.organizations` with `kind` and **capabilities** (APPOINTMENTS, CLASSES, EVENTS, RESERVED_SEATING, RETAIL, AT_HOME_SERVICES, MEMBERSHIPS …). A spa can sell a yoga retreat; an organiser can sell merchandise. | Same person, same money, same customer; features are toggled, not forked. | F1, F9 |
| D2 | **Deliverables:** backend, mobile apps, web platform. *Revised 24 Nov 2026 by D20: two mobile apps and one web platform.* Originally: one mobile app (consumer + Business mode), web marketplace, partner portal, admin console. | Matches the requested structure; Business mode keeps the field tools (calendar, scanner, quick checkout) on phones. | F12 |
| D20 | **Two mobile apps, one web platform** (owner decision, 24 Nov 2026). `apps/public-mobile` serves customers; `apps/business-mobile` serves business owners, staff and event organizers (the "Business mode" of doc 03 §1.1 is that app). One web platform `apps/web` holds the public site, the business and organizer console and the admin console (secret path). Both mobile apps share `@beavent/native`. | Same split as Fresha (Fresha + Fresha for Business) and Eventbrite (attendee + Organizer app): each app stays simple and can be released and reviewed separately, while one codebase of shared primitives keeps the design consistent. One web platform avoids three deployments and duplicated components. | — |
| D3 | **Modular monolith, one schema per module** (20 schemas). A module writes only its own schema; cross-module writes go through module APIs or domain events. | Clear ownership with one deployable; modules can be extracted later. | F1 |
| D4 | **One order model** (`commerce.orders` / `order_lines`) for services, tickets, products, gift cards, memberships, packages, tips and fees, with typed foreign keys and DB-checked arithmetic. | Mixed baskets, one checkout, one refund path, one tax engine. | F1, F6 |
| D5 | **Provider-agnostic payments with explicit collection modes** — `PLATFORM_COLLECTS`, `PROVIDER_SPLIT`, `SELLER_DIRECT`, chosen per organization and market; a provider/method capability matrix; a routing table with failover; intents and attempts covering push-prompt, redirect, QR, card, cash, internal tenders and manual bank transfer; refund-by-payout fallback. | Lets Ethiopia launch legally on day one (`SELLER_DIRECT`/`PROVIDER_SPLIT`) and switch on `PLATFORM_COLLECTS` where licensed, without schema change. | F4, F5 |
| D6 | **One double-entry ledger** (`ledger.*`), balanced per currency at commit, immutable, with maintained balances and scheduled fund releases. No per-seller general ledger; no consumer wallet. | One source of truth for seller balances, payouts and platform revenue; out of regulated scope. | F1, F7 |
| D7 | **Integer minor units everywhere** (`*_minor bigint` + `currency_code`), with `ref.currencies.minor_units` and cash rounding. | Exact, uniform, works for 0/2/3-decimal currencies. | F3 |
| D8 | **Database-enforced tenancy everywhere.** Every tenant row carries `org_id`; parents expose `UNIQUE (org_id, id)`; children use composite FKs; RLS is generated for every `org_id` table; consumer self-access policies; least-privilege roles. | Defence in depth across *all* modules, not just one. | F2 |
| D9 | **One staff/resource reservation ledger** (`scheduling.schedule_reservations`) for appointment stages, checkout holds, calendar blocks **and** class/event instructor or room assignments, guarded by GiST exclusion constraints. | Double-booking is impossible across verticals. | F9 |
| D10 | **Inventory buckets + an append-only movement ledger** (`events.inventory_buckets`, `events.inventory_movements`). Every capacity constraint (occurrence total, ticket type, custom pool, session) is a bucket; a trigger is the only writer of counters; a CHECK is the final oversell guard; lifecycle rules stop double-release and release-after-confirm. | Eventbrite-grade capacity features with a provable invariant (race-tested). | F8 |
| D11 | **One forms engine** (`crm.forms` / `form_versions` / `form_submissions`) for consultation, intake and consent forms and for event order and attendee questions. | Same need, one implementation. | F1 |
| D12 | **Out of scope:** consumer stored-value wallets, per-seller general ledgers, statutory payroll, BNPL, merchant lending. Provide exports and accounting integrations instead. | Regulated in every target market; no differentiation. | F7 |
| D13 | **Presentation content as validated JSONB** (`events.events.content`, `*_i18n` columns); transactional facts stay relational. | Fewer tables; flexible pages; no loss of integrity. | F10 |
| D14 | **UUIDv7 keys** (`platform.uuid_v7()`; native `uuidv7()` on PG18). | Index locality and natural ordering. | — |
| D15 | **Single reference-data model** (`ref.*`): countries, currencies, languages, admin areas (ltree), markets, market currencies, languages and features, categories, tax rates (non-overlapping by exclusion constraint), legal documents. | One truth for expansion. | F1 |
| D16 | **Adapters for every external, country-specific dependency** — payments, messaging (SMS/WhatsApp/Telegram/email/push), KYC (Fayda, Smile ID), fiscal e-invoicing (ET MoR, KE eTIMS, UG EFRIS, TZ VFD, RW EBM). | New country = new adapter + config rows. | F11 |
| D17 | **Executable, tested baseline.** Migrations in Flyway order, installed into a throwaway database and checked by invariant tests and a concurrency race test. | Turns review claims into evidence. | F13 |
| D18 | **Sign-in by one-time code (phone or email) or Google; Apple only in the iOS app.** No passwords, no authenticator apps; sensitive actions need a fresh code (step-up). (Owner decision, 2026-11-19.) | Matches how people in the launch markets sign in, with nothing to remember; Apple requires its option when Google is offered on iOS. |
| D19 | **Cash and pay-at-venue stay, recorded at POS; optional online deposits per business.** (Owner decision, 2026-11-19.) | Cash is still how many people pay; deposits cut no-shows; fees on cash sales are recovered from later Chapa payments. |

## 4. What changed, in numbers

| | v1 | v2 |
|---|---|---|
| Applications | 7 | 5 (backend, mobile, web, partner portal, admin console) |
| Schemas | 3 | 20 (one per module) |
| Tables | 346 | 266 — fewer tables, more features |
| Tenant roots | 2 | 1 |
| Ledgers | 2 product ledgers + a per-business GL | 1 platform double-entry ledger |
| Tables with RLS | Beauty only | 212 of 266 (the rest are reference, identity-service-only or system-only tables, each with explicit grants) |
| Executed and tested | No | Yes: `db/scripts/validate.sh` installs and runs invariant tests plus a 40-session race test |

### v1 → v2 mapping (by area)

| v1 area | v2 home | Notes |
|---|---|---|
| `businesses`, `organisations`, memberships, invitations, roles | `tenant.*` | One org; capabilities; custom roles cloned from system templates; one permission catalogue shared with admin roles. |
| Beauty `branches`, Event `venues`/`venue_spaces` | `tenant.places` + `tenant.locations` | Places are shared (public venues have no owner); locations are where an org operates. |
| Beauty `business_legal_entities`, Event `organisation_market_profiles` | `tenant.legal_entities` | Seller of record for orders, events, invoices, payouts and fiscal registration. |
| Employees, shifts, time, compensation, commissions, pay runs | `team.*` | Pay runs compute earnings; statutory payroll is exported. |
| Services, variants, add-ons, prices, resources | `catalog.*` | Stage templates for processing time; level/location/staff pricing with non-overlapping validity. |
| Beauty `class_sessions`, Event events/occurrences | `events.*` (+ `scheduling.occurrence_assignments`) | Classes are events of kind `CLASS_SERIES`; instructors are in the shared reservation ledger. |
| Ticket inventory, reservations, seat holds | `events.inventory_buckets`, `inventory_movements`, `occurrence_seats`, `inventory_holds` | Pools, holds, price phases, passes, sessions. |
| Bookings, booking items, holds, stages, schedule reservations | `scheduling.*` | Same proven model, extended to events. |
| Beauty sales, quotes, invoices, Event orders | `commerce.orders` / `order_lines` / `invoices` | One order model; obligations become `commerce.payment_schedule` with real FKs. |
| Two payment stacks | `payments.*` | Provider-agnostic; see doc 04. |
| Two ledgers, Beauty GL, business wallets | `ledger.*` | One ledger; no GL; no wallets. |
| Gift cards, packages, memberships, loyalty | `catalog.*` definitions + `retention.*` instances | Ledgered balances guarded by triggers. |
| Inventory, suppliers, online store | `stock.*` | Movement-driven stock levels. |
| Campaigns, automations, referrals, tracking links | `marketing.*` | Plus marketplace ads. |
| Two notification stacks, conversations, AI | `comms.*` | Provider-agnostic channels; AI action log. |
| Reviews, favourites, followers, saved events, search analytics | `discovery.*` | Verified reviews only; one listings read model for "places to book" and "things to do". |
| Verification docs, content reports, admin audit | `trust.*`, `platform.audit_log` | KYB/KYC cases with Fayda / Smile ID adapters; risk-driven payout controls. |
| Plans, subscriptions, billing, fee rules | `billing.*` | One plan catalogue; fee rules non-overlapping per scope. |
| — (absent) | `fiscal.*` | E-invoicing / fiscal device adapters per country. |

## 5. Business, legal and commercial decisions

Decided on 19 Nov 2026 (details in [07 implementation plan §1](07-implementation-plan.md#1-decisions-this-plan-is-built-on)): **Chapa collects and holds the money and splits it at source (`PROVIDER_SPLIT` for every seller); Chapa is the only payment provider for now; hosting is decided later.**

| Topic | Options | Recommendation | Owner |
|---|---|---|---|
| Collection model at launch in Ethiopia | `SELLER_DIRECT`, `PROVIDER_SPLIT`, `PLATFORM_COLLECTS` | **Decided: `PROVIDER_SPLIT` via Chapa subaccounts for every seller; Chapa only.** `SELLER_DIRECT` and `PLATFORM_COLLECTS` disabled. Counsel still confirms no platform licence is needed (plan D3). | Legal + finance |
| Hosting region and data residency | Single regional cloud region vs in-country hosting | **Deferred.** Staging hosting is needed before Stage 3 of the plan, production before Stage 9. Recommendation unchanged: single region first (see doc 06). | Engineering + legal |
| Fee and plan prices per market | — | Configure in `billing.fee_rules` / `billing.plan_prices`; nothing is hard-coded. | Commercial |
| Business mode inside the consumer app | ~~One app~~ **Decided 24 Nov 2026 (D20): a separate Business app** | Done: `apps/business-mobile`. Open: which `ClientApp` value the API records for it (currently `MOBILE_APP`) | Product |
| Diaspora USD presentment | Enable or not | Keep disabled (`DIASPORA_PRESENTMENT`) until a licensed provider and NBE-compliant FX flow are confirmed. | Legal + finance |
