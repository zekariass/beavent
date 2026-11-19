# 07 — Implementation plan

**Status:** v2 of the plan. **Scope:** from an empty repository to a pilot in Addis Ababa, then what follows.
**How to use it:** work through the stages **in order**. A stage starts when the previous stage's "done when" checks pass. Steps inside a stage are listed in a sensible order but can overlap if more people are available. There are no dates: move on when ready.
**Read with:** [03 architecture](03-system-architecture.md), [04 payments](04-payments-and-money.md), [05 data model](05-data-model.md), [06 markets](06-markets-and-localization.md).

---

## 1. Decisions this plan is built on

| # | Decision | Consequence |
|---|---|---|
| L1 | **Chapa holds the money.** Customer payments are collected by Chapa; each seller is a **Chapa subaccount** tied to the seller's bank account; Chapa settles the seller's share directly to that bank account and keeps the platform's share in the platform's Chapa balance. | Collection mode `PROVIDER_SPLIT` for every seller. The platform never holds seller funds and runs **no seller payouts**. `PLATFORM_COLLECTS` and `SELLER_DIRECT` stay disabled. |
| L2 | **Chapa is the only payment provider for now.** | All online methods (telebirr, CBE Birr, M-PESA, cards, bank apps and whatever else Chapa supports) go through Chapa. No SantimPay, ArifPay, direct telebirr or manual bank-transfer flow. The provider-agnostic adapter layer is still built, so adding providers later is a configuration + adapter task. |
| L3 | **Hosting is decided later.** | Everything is containerised and cloud-neutral (PostgreSQL + PostGIS, Redis, RabbitMQ, S3-compatible storage) and runs on Docker Compose until then. A hosted staging environment is needed **before Stage 3** (real Chapa webhooks need a public URL); production before Stage 9. |

Decided (2026-11-19): **cash and pay-at-venue stay available**, recorded at POS and the box office (no provider involved, so no conflict with L2). Each business can require an **online deposit** through Chapa (`scheduling` deposit settings; Stages 3–4) to cut no-shows; platform fees on cash sales become a seller receivable recovered from later Chapa payments (§3). Event tickets sold online are paid online; cash for events only at the box office (Stage 7).

Decided (2026-11-19): **sign-in is by a one-time code to a phone or email, or with Google** (plus Apple in the iOS app, which Apple requires when Google sign-in is offered). No passwords and no authenticator apps, for anyone; sensitive actions ask for a fresh code (step-up).

### 1.1 What Chapa's documentation says, and what it means for us

From [developer.chapa.co](https://developer.chapa.co/): [split payments](https://developer.chapa.co/integrations/split-payment), [webhooks](https://developer.chapa.co/integrations/webhooks), [refunds](https://developer.chapa.co/refund), [accept payments](https://developer.chapa.co/integrations/accept-payments). Re-confirm each item in Chapa's test mode (Stage 3, step 3.1).

| Chapa fact | Design consequence |
|---|---|
| `POST /v1/subaccount` with `bank_code`, `account_number`, `account_name`, `business_name`, `split_type` (`percentage`/`flat`), `split_value`; returns a subaccount id | Created when a seller's bank account is verified. Stored as `payments.provider_accounts` (owner `ORGANIZATION`, `parent_account_id` = platform Chapa account, `sub_account_reference` = Chapa id). |
| `split_value` is the **platform's** commission; the subaccount gets the rest; values can be **overridden per transaction** | We compute the exact platform fee per order with our fee engine (`billing.fee_rules`) and send it as a **flat** override on every payment. The subaccount's default split is 0 as a safety net. |
| Subaccounts settle in **ETB** | Sellers are priced and settled in ETB. |
| `POST /v1/transaction/initialize` → `checkout_url`; amount sent as a decimal string; `tx_ref` unique; `callback_url`, `return_url`; ETB and USD | The adapter converts `amount_minor` ↔ decimal exactly. `tx_ref` = our `payment_attempts.id`. USD stays off. |
| Direct charge `POST /v1/charges?type={method}` | In the mobile app, telebirr/M-PESA/CBE Birr can use a **push prompt without a browser redirect**. Web uses hosted checkout. |
| Verify transaction by `tx_ref` | Fulfil only after a server-side verify that matches status, amount, currency, `tx_ref` and mode. Also used for polling. |
| Webhooks: `charge.success`, `charge.failed/cancelled`, `charge.refunded`, `charge.reversed`, `payout.*`; `x-chapa-signature` = HMAC-SHA256 of the payload with the secret; retried every 10 min, up to 72 h | `POST /v1/hooks/chapa` verifies the signature on the raw body, stores the event in `payments.webhook_events`, then processes it idempotently. |
| Refunds `POST /v1/refund/{tx_ref}` (full or partial, our unique `reference`); `GET /v1/refund/{ref_id}/verify`; statuses `initiated → processing → refunded \| reversed`; **refunds are deducted from the platform's Chapa balance; Chapa charges are not refunded** | **The platform funds refunds even though the seller already has the money.** We need a refund float, a seller receivable in the ledger and a recovery mechanism (§3). |
| Disputes/chargebacks are taken from the platform's account; the platform must know its subaccounts | KYB is required before a seller can take online payments. Disputes become seller receivables. |
| Transfers API and balance endpoints exist | Not needed now; usable later for refund-by-payout or recovery. |
| Test mode with test cards and test mobile numbers | Certification suite runs against test mode before going live. |

## 2. Rules for every step (definition of done)

- Code reviewed; unit and integration tests; OpenAPI spec updated and the TypeScript client regenerated.
- `db/scripts/validate.sh` green on PostGIS in CI (new tables follow doc 05 §7; the schema-lint test enforces it).
- Amharic and English strings; Ethiopian date/time display where dates are shown.
- Audit events for sensitive actions; metrics/alerts for new background jobs.
- Docs updated when behaviour differs from docs 03–06.

## 3. Money rules under "Chapa holds the money"

**Flow.** Customer pays Chapa → Chapa splits: **seller share → seller's bank account**, **platform share → platform's Chapa balance**. The platform share = buyer fees + seller-paid platform fees (+ VAT on fees) for that order, sent as a flat per-transaction split.

**Ledger postings**

| Event | Postings |
|---|---|
| Payment succeeded | Dr `PROVIDER_CLEARING` (platform Chapa balance) platform share / Cr `PLATFORM_FEE_REVENUE` + `TAX_PAYABLE`. The seller share is not platform money; it is recorded in `commerce` and `billing.fee_assessments` for statements. |
| Chapa fee | Dr `PLATFORM_PROCESSING_COST` / Cr `PROVIDER_CLEARING` (or charged to the customer, per Chapa terms) |
| Refund (seller share s, platform share p refunded per policy) | Dr `SELLER_RECEIVABLE` s, Dr `PLATFORM_FEE_REVENUE`/`TAX_PAYABLE` p / Cr `REFUNDS_PAYABLE` s+p → on `refunded`: Dr `REFUNDS_PAYABLE` / Cr `PROVIDER_CLEARING` |
| Recovery from seller | Dr `PROVIDER_CLEARING` / Cr `SELLER_RECEIVABLE` |
| Platform fee on a cash sale | Dr `SELLER_RECEIVABLE` / Cr `PLATFORM_FEE_REVENUE` + `TAX_PAYABLE` |

**Refund funding and recovery**
1. **Float.** Keep a refund float in the platform's Chapa balance, with an alert when it is low.
2. **Recovery** (`payments.org_settings.recovery_mode`):
   - `SPLIT_TOP_UP` (default): the seller's next transactions carry an extra recovery amount in the split, capped per transaction (`recovery_cap_bps`), until the receivable is cleared.
   - `INVOICE`: a platform invoice the seller pays through a Chapa link.
3. **Exposure limits** (`trust.risk_profiles`): a per-seller cap on outstanding receivables. Above it, refunds need seller pre-funding or admin approval.
4. **Events.** With split settlement, organizers get ticket money before the event. Controls:
   - New organizers are reviewed before publishing (`PENDING_REVIEW`).
   - Per-organizer caps on tickets sold before their first event completes.
   - Bulk-cancellation refunds above the float require organizer pre-funding.
5. Chargebacks/disputes follow the same receivable path.

Confirm these rules with Chapa and counsel before the pilot (dependencies D2, D3).

---

## Stage 0 — Foundation

**Goal:** a working skeleton every later stage builds on: repository, CI, backend structure, identity, organizations, files, admin shell, design system.

**0.1 Lock the decisions into the database baseline.** ✅ *Done.* Version 1 is unreleased, so edit the V1 files directly.
- Seed (`V1_21`), Ethiopia market features: `PROVIDER_SPLIT` → `ENABLED`; `SELLER_DIRECT`, `MANUAL_BANK_TRANSFER`, `PLATFORM_COLLECTION`, `DIASPORA_PRESENTMENT`, `INSTANT_PAYOUT` → `DISABLED`.
- Keep the non-Chapa providers in the catalogue as `PLANNED` (no routes). Chapa in ET: `can_refund_api`, `can_partial_refund`, `can_split`, `can_payout` = true; status `SANDBOX` until step 3.9.
- Add `interaction` to the `provider_capabilities` unique key (Chapa offers both hosted checkout and direct-charge push).
- Ledger: add account code `SELLER_RECEIVABLE` and transaction kinds `SELLER_RECOVERY`, `REFUND_FUNDED_BY_PLATFORM`.
- `payments.org_settings`: add `recovery_mode` (`SPLIT_TOP_UP` | `INVOICE`) and `recovery_cap_bps`.
- Add a split-mode refund → seller receivable test to `02_money_invariants.sql`.
- Mark the decisions as taken in doc 01 §5 and doc 04 §3/§17.

*Done when:* `validate.sh` is green and docs and seed agree.

**0.2 Validate on real PostGIS.** ✅ *Done* — `npm run db:validate` (PostGIS 3.5.2 container).
- Add `docker-compose.yml` with a PostGIS image (pin the version to match production once hosting is chosen).
- Give `validate.sh` a `--docker` mode and make CI use it.

*Done when:* the full suite passes on PostGIS locally and in CI.

**0.3 Monorepo and toolchain.** ✅ *Done* (CODEOWNERS pending GitHub usernames).
- Layout (doc 03 §1): `backend/`, `mobile/`, `web/`, `business/`, `admin-console/`, `packages/{api-client,ui,i18n,calendar,money,config}`, `db/`, `docs/`.
- npm workspaces + Turborepo for TypeScript; Gradle for the API (Flyway packages `db/migration`; jOOQ, Spring Modulith, AMQP, Actuator, Testcontainers).
- Pinned: Java 21 LTS (move to 25 later without code change); Node 26 / npm 11; Spring Boot 4.1; Expo SDK 58; Next.js 16.
- Formatters and linters (Prettier, ESLint, Expo lint, Spotless/Palantir Java Format), PR template with the definition of done; CODEOWNERS once GitHub usernames are known.

*Done when:* `npm run build`, `npm run lint`, `npm run typecheck`, `npm run api:build` and `npm run db:validate` work from a clean clone.

**0.4 CI and local environment.** ✅ *Done* — `.github/workflows/ci.yml` (runs once the branch is pushed), `tools/chapa-stub`, `.env.example`, container images.
- CI: format, lint, typecheck, tests (incl. the Chapa stub), builds, DB validation on PostGIS, backend build with Testcontainers and Spring Modulith boundary tests, all container images. The OpenAPI breaking-change check is added with the first spec (step 0.6).
- Docker Compose dev stack: PostGIS, Redis, RabbitMQ, SeaweedFS (S3-compatible storage; MinIO stopped publishing images, replaced in 0.9), Mailpit, plus the **Chapa stub** (initialize, direct charge, verify, refund, subaccount, signed webhooks, outcome by phone suffix, fault injection) so payment work can start before Chapa test keys arrive.
- Secrets: `.env` locally (from `.env.example`), a secret manager once hosted. The DB stores only `*_secret_ref`.
- Container images: backend, Chapa stub, and one shared Next.js image definition (standalone output) for web, business and admin-console. Dependabot weekly.

*Done when:* every PR runs the full pipeline. (Verified locally: every CI command passes and the workflow passes actionlint; it runs on GitHub after the first push.)

**0.5 Backend skeleton.** ✅ *Done* — `backend/src/main/java/com/beavent/backend/platform` (24 backend tests).
- Spring Boot + Spring Modulith; the `platform` module carries the cross-cutting pieces below, its public API in the base package and internals in sub-packages. Domain modules (one per schema, doc 03 §2.2) are added as their stages start; `ModularityTests` enforces boundaries.
- Flyway runs `db/migration`; jOOQ classes are generated at build time from a throwaway PostGIS migrated with the same files (`com.beavent.db.<schema>`, outside the module root; explicit joins only).
- Four pools: schema owner (Flyway only) and one per runtime role — `beavent_app` (primary, RLS), `beavent_identity` (`@IdentityDb`), `beavent_system` (`@SystemDb`). The app transaction manager calls `platform.set_request_context(user, org)` at the start of every transaction from `RequestContext`; no context means no tenant rows (fails closed). Local role logins are created by a Flyway callback in the `local` profile only; production logins come from infrastructure-as-code.
- Problem+json errors with stable codes and SQLSTATE mapping (23P01 → `SLOT_TAKEN`, bucket 23514 → `SOLD_OUT`, 42501 → `FORBIDDEN`, …); `Idempotency` service (`Idempotency-Key`); `ETags` (If-Match) and `Cursors`; `X-Request-Id` + trace ids in logs (OpenTelemetry; OTLP export off until a collector exists); deny-by-default security until 0.7.
- Transactional outbox (`Outbox.publish` in the caller's transaction) → relay (`FOR UPDATE SKIP LOCKED`, publisher confirms) → RabbitMQ topic exchange `beavent.events` (+ dead-letter exchange); `ProcessedMessages.runOnce` for idempotent consumers. Jobs use `@Scheduled` with `SKIP LOCKED` claims for now; a durable scheduler is added with the first time-based business job (Stage 2).
- The backend image packages the jar built by Gradle (code generation needs Docker, so it can't run inside `docker build`).

*Done when:* an integration test (Testcontainers + PostGIS) writes a row + outbox event under RLS and a consumer handles it exactly once. ✅ (`OutboxTests`, plus `RequestContextIsolationTests`, `IdempotencyTests`.)

**0.6 API contract workflow.** ✅ *Done* — `backend/src/main/openapi` (identity, tenancy, reference specs for steps 0.7–0.8), `packages/api-client`.
- Spec-first OpenAPI 3.1, one folder per module (`common`, `identity`, `tenancy`, `reference`), linted with Redocly (`redocly.yaml`) and bundled to `packages/api-client/openapi.yaml`.
- `packages/api-client`: generated types (`openapi-typescript`), typed fetch client (`openapi-fetch`) adding auth, language and `X-Request-Id`, React Query hooks (`openapi-react-query`), idempotency-key and problem helpers; used by web, business, admin-console and mobile (`src/lib/api.ts`).
- Backend serves the bundle at `/v1/openapi.yaml`; a test keeps `ProblemCode` identical to the contract's error codes. Request/response validation against the spec is added with the first endpoints (0.7: `swagger-request-validator-mockmvc` checks every request and response in the API tests).
- CI job `api-contract`: lint + regenerate, fail if the committed client is stale, and on pull requests fail on breaking changes against the base branch (`oasdiff`).
- Rule: every module starts with its spec, reviewed by backend, web and mobile.

*Done when:* the auth + tenancy spec is merged and used by the app shells. ✅

**0.7 Identity.** ✅ *Done* — `backend/src/main/java/com/beavent/backend/identity` (42 backend tests in total).
- Sign-in by one-time code to a phone or an email address (email codes added 2026-11-19): one `SIGN_IN` step for new and existing people (responses never reveal whether an account exists). Proving an inbox never opens another account that merely typed the same address unverified (the unverified copy is released). Codes and phone numbers are stored only as HMACs; 5 attempts per code, 30 s resend window, per-phone and per-IP hourly limits in Redis (`429 RATE_LIMITED`). Codes go through the `SmsGateway` port; locally and in CI the `log` provider records them — a real provider needs D4.
- No passwords and no authenticator apps (decision 2026-11-19; `V2_03` drops their tables).
- Google sign-in (Apple in the iOS app): ID tokens verified against the providers' keys (issuer, audience, nonce, signature); a verified provider email joins the account that verified the same address. Returns `404` until client ids are configured (D12).
- Sessions: ES256 access tokens (10 min; audience = client app; `sid` and `aal` claims), JWKS at `/.well-known/jwks.json` (key rotation via retired public keys), opaque refresh tokens stored as hashes with rotation; reusing an old refresh token revokes the whole session family. Sessions can be listed and revoked; logout revokes one or all.
- Step-up (`/v1/auth/step-up`): a fresh code to the account's verified phone or email issues a 5-minute `STEP_UP` token; `Assurance.requireStepUp()` guards sensitive actions (`403 STEP_UP_REQUIRED`).
- Profile (`GET/PATCH /v1/me`, merge-patch), device registration (push tokens), consent capture against published legal document versions, audit entries for security events.
- Moved: guest-order claiming needs orders and is built with checkout (step 6.4; the `CLAIM_GUEST_ORDERS` code purpose already exists).

*Done when:* every auth flow, including brute-force limits and token-reuse detection, has integration tests. ✅ (`IdentityApiTests`, `ProviderSignInTests`, `IdTokenVerifierTest`.)

**0.8 Organizations and access.** ✅ *Done* — `backend/src/main/java/com/beavent/backend/tenancy`, `.../reference` (48 backend tests in total).
- Onboarding API: create an organization (kind, business category, market, language; the creator becomes owner; slug from the name or chosen, reserved words refused), profile updates with `If-Match`, capabilities checked against the market's feature switches, legal entities (TIN, VAT/TOT regime, one default per country, currency from the country; changing verified details resets verification), locations with their premises place (landmark-first address following the country's address format, map pin, opening hours with split shifts).
- Members, invitations by SMS (email through the `log` provider until Stage 8), system role templates plus custom roles, location scope (members limited to some locations see only those).
- Access checks: org from the `/v1/orgs/{orgId}` path → active membership (system role) → `OrgAccess` permission check → database context narrowed to that org (RLS). Non-members get `404`, suspended organizations are read-only. Nobody can grant permissions they do not hold; granting sensitive permissions, changing a member's role, suspending or removing a member needs step-up; the owner's access cannot be changed (ownership transfer comes later).
- Invitation links work only for the person who verified the invited phone number or email, once.
- KYB/KYC: verification cases with documents (files must be the organization's `KYC`/`DOCUMENT` files); one open case per subject; owner identity only by the owner. Upload itself is step 0.9; decisions are made in the admin console (0.10).
- Reference API: markets (languages, currencies, feature switches), languages, categories, administrative areas (public, cached 5 minutes) and the permission catalogue (signed in).

*Done when:* API-level tests prove an org A token can never read or write org B data. ✅ (`TenancyApiTests`: B's ids and paths are `404`/`422` from A's workspace, including for a person who belongs to both.)

**0.9 Files and media.** ✅ *Done* — `backend/src/main/java/com/beavent/backend/files` (52 backend tests in total).
- Direct uploads: the API creates the file record and a pre-signed PUT (15 minutes) to the private bucket; the client uploads, then calls `complete`, which checks the stored size and type. Each purpose (logo, cover, gallery, event media, KYC, document, avatar) fixes who may upload, allowed types (JPEG/PNG, PDF) and size.
- Processing worker (`file.uploaded` via the outbox): content-type check from the file's bytes, malware scan (`MalwareScanner`: ClamAV `clamd` in production — needs hosting, D5; an EICAR-only scanner locally), and for public images re-encoding (drops EXIF/GPS), size limits against decompression bombs, and `w320`/`w960` variants. Only then are public images copied to the public bucket (CDN); rejected files are deleted and audited.
- Private and `SENSITIVE` files are only reachable through signed URLs (5 minutes; 60 seconds for sensitive) after the purpose's permission check; each sensitive access is audited. Logos, covers and avatars can only reference processed files of the same organization/person; KYB cases only uploaded, not-rejected documents.
- Local storage is SeaweedFS (S3 API; MinIO no longer publishes images); production uses any S3-compatible service (Cloudflare R2 or AWS S3, hosting decision D5).
- Later: clean-up job for uploads never completed (indexed), more purposes as modules need them (customer photos, receipts, exports).

*Done when:* the logo, gallery and KYB document flows work, and sensitive files are inaccessible without authorisation. ✅ (`FilesApiTests`.)

**0.10 Admin console shell.** ✅ *Done* — `backend/src/main/java/com/beavent/backend/admin`, `admin-console/` (56 backend tests in total).
- Staff access: admin-console tokens are issued only to accounts with an active staff role (sign-in by phone/email code or Google; sessions last at most 12 hours); every `/v1/admin` call re-reads the staff roles, so a revoked role stops working at once. Permissions come from staff roles and are scoped to markets (a market-limited reviewer does not see other markets). First staff accounts are granted with `db/scripts/grant-staff-role.sh` (audited). Opening KYB documents, deciding cases and changing flags need a fresh code ("confirm it's you", valid 5 minutes).
- Every change carries a reason and is audited (`PLATFORM_STAFF`); opening a KYB document creates a 60-second signed link and an audit entry; staff cannot decide verification for an organization they belong to.
- API and screens: overview, verification queue and case review (documents, approve / reject / ask for information), organization search and 360, people search (phone in local or international format, email, name), audit log, feature flags (changing defaults needs global market management), markets (read-only).
- The console keeps the session server-side: tokens live in httpOnly cookies and never reach the browser; `proxy.ts` rotates them before expiry (never on prefetches, because refresh-token reuse ends the session).

*Done when:* an operator approves a KYB case and it appears in the audit log. ✅ (`AdminApiTests`; also checked end to end on the dev stack: upload → scan → submit → staff sign-in by code → review → approve → audit log page.)

**0.11 Design system, languages and calendar.** ✅ *Done* except the wireframes (D9) — `packages/{ui,i18n,calendar,money}`, `backend/.../platform/EthiopianCalendar.java`.
- `packages/ui`: design tokens for web (CSS variables) and React Native (numbers), Ethiopic typography rules (Noto Sans Ethiopic, line height 1.7, nothing below 14 px) and the low-end Android performance budget. Values are provisional until the visual design (D9); shared components are added as screens are designed. The Expo template's Android bundle is already 3.9 MB, close to the 4 MB budget — trim it before Stage 1 screens grow it.
- `packages/i18n`: Amharic and English catalogues complete for the shells, Afaan Oromo started (missing keys fall back to English); ICU-style placeholders and plurals, Accept-Language negotiation, localized-text fallback. The Amharic and Afaan Oromo copy needs review by native-speaking editors before launch.
- `packages/calendar`: Ethiopian ⇄ Gregorian conversion, Ethiopian local time (hours from 06:00/18:00) and formatting in am/en/om; tested on shared vectors (holidays, leap years) and every day from 1900 to 2200. The Java twin `EthiopianCalendar` runs the same vectors.
- `packages/money`: exact decimal parsing/printing, localized formatting, allocation without lost minor units, basis-point percentages rounded half up, cash rounding.
- Shells: web and partner portal under `/am`, `/en`, `/om` (language from the URL, remembered in a cookie, otherwise the browser's languages, defaulting to Amharic) and the mobile home screen (device language, switchable) show today's Ethiopian date and Ethiopian/international time for Addis Ababa.
- Open: wireframes for the pilot journeys (D9).

*Done when:* the web, partner and mobile shells render in Amharic and English with Ethiopian dates and times. ✅

**Checkpoint A (end of Stage 0):** *(API side reached: covered by the identity, tenancy, files and admin tests and the end-to-end run in 0.10; the partner-portal onboarding screens come with Stage 1's designs (D9). CI on GitHub runs once a pull request is opened.)* a user signs up by SMS OTP, creates an organization, completes onboarding, uploads KYB documents and invites a colleague; an admin approves KYB; it all works in Amharic or English, and CI is green on PostGIS.

---

## Stage 1 — Catalogue and team

**Goal:** a business can describe what it sells and who does it.

**1.1 Services.** ✅ *Done* (API + partner portal) — `backend/.../catalog`.
- Menu sections; services with variants (duration, buffers, price type), stage templates (e.g. apply / process / rinse; the stages must add up to the variant's duration), resource needs, add-ons, bundles (sum, fixed price or percent off).
- Price overrides per location, team member, pricing level or combinations, effective-dated (history kept). `ServicePricing` resolves the most specific price and duration for booking.
- Resources (rooms, chairs, beds, stations; parallel units); galleries using processed public images.
- Partner portal: menu with sections, service editor (Amharic and English names, options, durations, prices in birr, locations), staff-specific prices.

**1.2 Team.** ✅ *Done* (API + partner portal; mobile "My schedule" next) — `backend/.../team`.
- Team profiles with or without a login (one profile per login), locations, services performed, pricing levels.
- Regular hours as weekly or multi-week rotations, effective-dated; one-off shifts replace the pattern for a day; time off recorded by managers or requested by the person and then decided.
- `Rota` computes working intervals per person and local day; managers and people who see every calendar see everyone, others only themselves.
- Partner portal: team list, add a team member, regular hours editor, weekly rota (Ethiopian dates and Ethiopian time in Amharic).

**1.3 Booking policies.** ✅ *Done* (API + partner portal) — `backend/.../scheduling`.
- Notice period, booking horizon, slot interval, request-to-book, pay at venue.
- Deposit rules (none/percent/fixed/full; who they apply to), cancellation window, no-show and late-cancel fees, policy text.
- Most-specific-wins resolution (service + location, service, location, organization, platform default) via `BookingPolicies`.

**1.4 Locations and at-home services.** ✅ *Done* (API) — tenancy and reference.
- Opening hours (0.8), closures, a "closed on public holidays" switch; Ethiopian public holidays 2026–2027 as reference data (to be confirmed by a local adviser like other legal facts).
- Service areas (administrative area or circle) with travel fee and travel time.

*Done when:* a salon builds a menu with staff-specific prices and a weekly rota for its team in the partner portal. ✅ (`tools/e2e/tests/partner-portal.spec.ts`: a real browser signs in by code, onboards a business, builds the menu, adds a stylist with regular hours, sets her price and checks the rota in English and Amharic; plus `CatalogApiTests`, `TeamApiTests`, `PoliciesAndLocationsApiTests`.)

---

## Stage 2 — Appointments (pay at venue)

**Goal:** end-to-end booking without online payment, so the scheduling core is proven before money is involved.

**2.1 Availability engine.**
- Write a half-page spec first (inputs, algorithm, edge cases), then implement.
- Inputs: patterns, shifts, time off, closures, holidays, existing reservations, stage occupancy, resources, buffers, travel time, service areas.
- Cache with invalidation on calendar changes.

*Done when:* property tests show no offered slot ever overlaps a reservation, and one location/day with 10 staff returns in under 300 ms.

**2.2 Booking.**
- Hold → order (pay at venue) → appointment → reservations, in one transaction; a conflict returns `SLOT_TAKEN`.
- Hold-expiry job; reschedule; cancellation with policy evaluation; group bookings; booking for someone else.

*Done when:* two users racing for the same slot through the API get exactly one booking.

**2.3 Calendar operations.**
- Day/week calendar by staff and resource; drag-to-move; walk-ins; blocks; staff-only forced overlap.
- Statuses: arrived/started/completed/no-show.
- Realtime updates over WebSocket, in the partner portal and in mobile Business mode.

**2.4 CRM basics.**
- Customer profiles, notes/alerts/allergies, tags, history, merge.
- Consultation/consent forms with e-signature; patch-test enforcement.

**2.5 Consumer booking UI.**
- Web marketplace: location page and booking flow.
- Mobile app: same flow, plus "my appointments" (reschedule/cancel).

**2.6 Waitlist.**
- Waitlist for time windows; an automatic offer with a held slot when an appointment is cancelled.

*Done when:* a customer books a pay-at-venue appointment on web or mobile, it appears on the stylist's calendar instantly, the front desk completes it, and a cancellation triggers a waitlist offer.

---

## Stage 3 — Commerce and Chapa payments

**Goal:** online payments through Chapa with split settlement, starting with appointment deposits and full prepayment.

*Prerequisites:* D1 (Chapa account and test keys) and D5 (hosted staging with a public webhook URL). Until then, steps 3.2–3.4 can be built against the Chapa stub.

**3.1 Chapa onboarding and facts.**
- Configure the platform's Chapa account (split enabled, webhook URL and secret).
- Confirm with Chapa (D2): fee payer, subaccount settlement timing, refunds on split transactions, chargebacks, float requirement.
- Record the answers in `docs/` and update §1.1 and §3 if anything differs.

**3.2 Orders, tax and fees.**
- Mixed-basket orders with line arithmetic, header totals and document numbering.
- Promotions and promo/access codes.
- Tax engine: VAT/TOT × tax category → immutable snapshots; tax-inclusive display.
- Fee engine: `billing.fee_rules` → `fee_assessments`, with `BUYER_FEE` lines or seller-absorbed fees. The order's **platform share** is the Chapa split amount.

*Done when:* property tests pass for rounding, inclusive/exclusive tax and fee caps.

**3.3 Chapa adapter.** Implements `PaymentProviderAdapter` (doc 04 §4):
- Hosted checkout (web) and direct charge (app), with a flat per-transaction subaccount split.
- Verify by `tx_ref`, with strict matching.
- Webhook signature verification over the raw body.
- Refund and refund verify.
- Subaccount creation from Chapa's bank list.
- Transaction export for reconciliation.
- Exact decimal ↔ minor-unit conversion.

Contract tests run against the stub and a test suite runs against Chapa test mode.

**3.4 Payment orchestration.**
- Intents and attempts, with routing (one route today: Chapa).
- Webhook inbox + worker; polling via verify until expiry; `UNKNOWN` resolution.
- Duplicate success → `SUCCEEDED_DUPLICATE` → automatic refund.
- Late success after a hold expires: re-confirm if the slot is still free, otherwise refund.
- On success: confirm the order and appointment, convert the hold, post the ledger (§3), record fees, notify.
- Payment UX: web "checking payment…" page; app push-prompt waiting screen; survives the app being backgrounded.

*Done when:* the fault-injection suite passes: duplicate and out-of-order webhooks, missing webhook (poll path), timeout, customer cancels, double approval, late success, and a Chapa error on initialize.

**3.5 Seller "Get paid" onboarding.**
- Partner portal flow: legal entity → bank account (Chapa bank list; name must match) → step-up OTP → Chapa subaccount created → online payments enabled.
- Gate: online payments only after KYB is approved **and** the subaccount exists. Until then, pay-at-venue only.
- Changing the bank account creates a new subaccount, starts a 72 h cooling-off before routing to it, and notifies the owner.

**3.6 Deposits and balances.**
- Deposit or full prepayment at booking, per the policy snapshot; balance collected at the venue (`payment_schedule`).
- No-show/late-cancel: deposit forfeited up to the policy fee, the rest refunded.

**3.7 Payment links.**
- Links for balances and phone bookings, sent by SMS/email (Telegram later).

**3.8 Gift cards and customer balance.**
- Sell gift cards online; deliver them, including on a schedule.
- Redeem as a payment method; refund to customer balance as an option.

**3.9 Reconciliation and go-live certification.**
- Daily import of Chapa transactions and refunds; matching by reference.
- Exceptions queue in the admin console; Chapa fees posted.
- Certification checklist in test mode → a live low-value test → capability `LIVE` with `verified_at`.

*Done when:* a full day of test-mode traffic reconciles with zero unexplained lines.

**Checkpoint B:** a customer books with a telebirr deposit; Chapa splits the payment between seller and platform; the booking confirms; reconciliation matches.

---

## Stage 4 — POS and in-person sales

**Goal:** the front desk can take every payment at the counter.

**4.1 POS.**
- In mobile Business mode and the partner portal: services, products, gift cards, tips (with staff allocation), discounts, split tender.

**4.2 Payment methods at the counter.**
- **Chapa**: a direct-charge prompt to the customer's phone, or a payment link/QR the customer opens.
- **Cash**: drawer sessions with float and variance.
- **Gift card** and **customer balance**.

**4.3 Receipts.**
- By SMS/email; printable; gapless numbering per legal entity.

**4.4 Platform fees on cash sales.**
- E.g. the marketplace new-client commission on a cash-paid visit becomes a seller receivable (§3).

**4.5 Products.**
- Basic retail products sold at POS, with stock levels and movements. Full purchasing comes later.

*Done when:* a visit with a deposit already paid online is completed at the counter with the balance split between cash and Chapa, a tip and a product; the receipt is correct, and the drawer closes with zero variance.

---

## Stage 5 — Refunds and seller recovery

**Goal:** money can flow back correctly, and the platform recovers what it funds.

**5.1 Refund requests and approvals.**
- Customer requests (appointment cancellation window, later the event policy) → organization approval.
- Organization- and system-initiated refunds; full, partial and per line.

**5.2 Chapa refunds.**
- Call the refund API; track status via verify and webhooks.
- `reversed` → failed, with an alert.

**5.3 Funding and recovery.**
- Post `REFUND_FUNDED_BY_PLATFORM` and a `SELLER_RECEIVABLE` entry.
- Recover by split top-up or invoice.
- Exposure caps; refund-float monitoring and alert.

**5.4 Fee rules on refunds.**
- Which buyer and platform fees are refunded, per market policy (doc 04 §8).

**5.5 Partner finance screen.**
- Sales, Chapa settlements as reported, platform fees, receivables and recoveries, statements.

*Done when:* in Chapa test mode, a partial and a full refund each produce the correct seller receivable, the next sale's split recovers it within the cap, and the ledger balances.

---

## Stage 6 — Events and ticketing

**Goal:** organizers create events and sell tickets.

**6.1 Event authoring.**
- Wizard: details, Amharic/English translations, category, cover, content blocks (agenda, FAQs).
- Venue: a shared public venue or own location.
- Online access links revealed only to ticket holders.
- New-organizer review before publishing.

**6.2 Schedule.**
- Single, multi-day, recurring (RRULE generation) and timed-entry slots.

**6.3 Tickets and capacity.**
- Paid, free, donation and pay-what-you-want tickets.
- Visibility, limits, sales windows, sequential release, early-bird price phases.
- Inventory buckets (occurrence, type, pools); organizer holds.

**6.4 Checkout.**
- Reserve movements in bucket-id order.
- Attendee questions (forms), promo and access codes, merchandise add-ons for pickup at the event.
- Free orders without payment; Chapa payment with split.
- Guest checkout by phone, and claiming guest orders after sign-in (verified by a one-time code to the same number; moved from 0.7).
- Reservation-expiry job; per-organizer pre-event sales caps (§3).

**6.5 Tickets and wallet.**
- One ticket per admission; opaque QR secret; SMS serial.
- Offline wallet in the app; delivery by SMS/email.
- Transfers with QR rotation.

**6.6 Organizer operations.**
- Attendee list and export; sales dashboard; publish, pause, postpone, cancel.
- Cancel triggers bulk refunds (Stage 5), with pre-funding when the float is insufficient.

**6.7 Classes.**
- `CLASS_SERIES` events at the organization's location, with instructors and rooms in the shared reservation ledger.

**6.8 Consumer UI.**
- Event page, ticket checkout, and ticket wallet on web and mobile.

*Done when:* an organizer publishes a recurring event with early-bird and VIP tickets sharing a capacity pool; an API race test shows no oversell; an organizer cancellation refunds everyone and creates the matching receivable.

---

## Stage 7 — Check-in and box office

**Goal:** run the door on event day, including without internet.

**7.1 Devices.**
- Register scanner devices, scoped to an event or occurrence; revocable; event-scoped staff roles.

**7.2 Offline manifest.**
- A signed manifest of credential hashes, updated incrementally.

**7.3 Scanning.**
- Camera scanner in mobile Business mode.
- Idempotent scan sync; authoritative admissions; re-entry; conflict flagging.
- Search by name, phone or serial.

**7.4 Live counters.**
- Per gate and per ticket type, over WebSocket.

**7.5 Box office.**
- POS (Stage 4) with the order channel `BOX_OFFICE` and a cash drawer tied to the occurrence.

*Done when:* two devices stay offline for a session, then sync, producing correct admissions and flagged conflicts; a transferred ticket's old QR is rejected.

---

## Stage 8 — Discovery, reviews, notifications

**Goal:** customers find businesses and events, and everyone is kept informed.

**8.1 Discovery projector and search.**
- Projector builds `discovery.listings` for locations and events.
- Search API: full-text + trigram + map, with Amharic/Latin transliteration aliases.
- Web: home, city and category landing pages (SEO) and search with map. Mobile: discover tab.

**8.2 Profiles.**
- Organization and organizer profile pages; follow organizer, plus auto-follow after purchase.

**8.3 Reviews.**
- Verified reviews after completed visits and events; organization responses; moderation queue in the admin console.

**8.4 Notifications.**
- Amharic/English templates for every transactional event.
- Channels: SMS, email and push (Telegram bot when ready).
- Reminders 24 h and 2 h before, showing Ethiopian local time.
- Quiet hours and preferences.

**8.5 Basic marketing.**
- SMS campaigns to consenting customers; followers and past-attendee audiences; tracking links.

**8.6 Embeds.**
- Booking and ticket widgets (`/embed/*`) for partners' own sites and social links.

*Done when:* a new user finds a salon and an event through search, books and buys, receives confirmations and reminders, and leaves verified reviews afterwards.

---

## Stage 9 — Hardening and launch readiness

*Prerequisites:* D3, D6, D7, D8, D11.

**9.1 Load tests.**
- Search, availability, ticket on-sale burst, webhook storm and check-in sync, against the targets in doc 03 §6.

**9.2 Security review.**
- OWASP ASVS L2 checklist; dependency and secrets scanning.
- Penetration test of auth, tenancy/RLS, payments and file access.
- Step-up codes enforced for every sensitive action (payouts, refunds, member access, exports, KYB, flags); admin sessions at most 12 hours.

**9.3 Operations.**
- Dashboards: payment success rate, webhook lag, polling backlog, refund float, receivables, reconciliation exceptions, hold-expiry lag, SMS delivery.
- Alerts and on-call.
- Runbooks: Chapa outage, refund reversed, float low, duplicate payment, scanner offline at the door.

**9.4 Resilience.**
- Backup and restore drill; migration rollback plan.

**9.5 Market activation gates for Ethiopia as `PILOT`** (doc 06 §4):
- Terms published.
- Tax rates verified.
- Chapa capabilities `LIVE`.
- SMS sender approved.
- Support contacts set.
- Holidays seeded.
- Translations reviewed.
- Risk defaults and fee/plan prices configured.
- Reconciliation dry run complete.

**9.6 Store releases.**
- App Store / Play Store builds; production web deployments.

*Done when:* every activation gate is green in the admin console's market-readiness view.

---

## Stage 10 — Pilot

**10.1 Onboarding.**
- 10–20 businesses (salons, spas, at-home professionals) and 3–5 organizers, with white-glove onboarding (D10).

**10.2 Operating rhythm.**
- Daily support triage; weekly reconciliation review; refund-float and receivables review.

**10.3 Feedback.**
- Feed pilot findings into the after-pilot backlog.

**Pilot exit:**
- Four consecutive weekly reconciliations with zero unexplained differences.
- Payment success rate at or above the agreed target.
- No unresolved Sev-1 incidents.
- Partner feedback collected.
- The market moves to `LIVE`.

---

## After the pilot (in order)

| Stage | Highlights | Payment note |
|---|---|---|
| 11 Growth | Memberships, packages, loyalty, automations, Telegram/WhatsApp campaigns and chat bridges, reserved seating, sessions, passes, ads/featured placements, AI concierge beta, team pay, purchasing and online store, partner API and webhooks, fiscal e-invoicing as the MoR schedule requires | Still Chapa only. Renewals by payment link unless Chapa supports saved instruments |
| 12 East Africa | Kenya first, then Uganda, Tanzania, Rwanda; Swahili; eTIMS/EFRIS/VFD/EBM adapters | **Needs a second provider** (Chapa is Ethiopia-focused): M-PESA Daraja and/or an aggregator such as pawaPay or Flutterwave, through the existing adapter interface; choose the collection mode per market |
| 13 Pan-Africa | West, North and Southern Africa; Arabic RTL; licensed `PLATFORM_COLLECTS` where available | Per-market providers and licensing |

---

## Dependencies and decisions

| # | Item | Owner | Needed before |
|---|---|---|---|
| D1 | Chapa business account, split enabled, test keys | Founders/finance | Stage 3 (start early; the stub covers development meanwhile) |
| D2 | Chapa terms: fee payer, subaccount settlement timing, refunds on split transactions, chargebacks, float requirement | Finance + Chapa | Stage 3 go-live certification (3.9) |
| D3 | Legal confirmation that the split model needs no platform payment licence; consumer and partner terms (am/en) | Counsel | Stage 9 |
| D4 | SMS gateway for OTP and notifications; sender ID registration | Ops | Stage 0, step 0.7 |
| D5 | Hosting provider and region for **staging** | Founders + engineering | Stage 3 (public webhook URL) |
| D6 | Production hosting, backups/DR, data-protection review | Founders + counsel | Stage 9 |
| D7 | Tax rates and regimes verified (VAT/TOT); receipt rules; whether pilot sellers need MoR e-invoicing | Tax adviser | Stage 9 |
| D8 | Fee rates and plan prices for Ethiopia | Commercial | Stage 3 (configuration), final before Stage 9 |
| D9 | Wireframes and visual design for the pilot journeys | Designer | Stage 0, step 0.11, then ahead of each stage's UI |
| D10 | Pilot partner list and their bank accounts | Business development | Stage 10 |
| D11 | Apple/Google developer accounts; app names in Amharic and English | Ops | Stage 9 |
| D12 | Google OAuth client ids (web, Android, iOS) and Apple Services ID for the iOS app | Ops | Before the first app release with Google sign-in (Stage 9 at the latest) |

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Chapa split/refund behaviour differs from its docs | Payments rework | Confirm early (3.1); adapter isolates changes; test-mode suite |
| Single provider, no failover | Lost sales during a Chapa outage | Health monitoring; pay-at-venue fallback for appointments; status banner; confirmation retries; a second Ethiopian provider kept ready to enable |
| Refund float exhausted (e.g. a large event cancelled) | Delayed refunds | Float alerts, per-organizer exposure caps, pre-funding for bulk refunds, organizer review |
| Organizer takes the money and cancels | Platform absorbs refunds | KYB before online sales; caps before the first completed event; recovery via split top-up; terms making organizers liable |
| Hosting decision not made | Stage 3 can't receive real webhooks | Build against the Chapa stub; D5 is a hard prerequisite for Stage 3 certification |
| SMS cost and deliverability | OTP failures | Telegram channel; delivery monitoring; per-message cost tracking |
| Scope creep into growth features | Pilot slips | Stages 0–10 are the pilot scope; new requests go to Stage 11 unless a pilot partner is blocked |
| Availability engine complexity | Wrong slots offered | Spec first; property tests; DB exclusion constraint as the backstop |
