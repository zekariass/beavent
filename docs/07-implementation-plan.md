# 07 — Implementation plan

**Status:** v3 of the plan (reviewed 24 Nov 2026). **Scope:** from an empty repository to a pilot in Addis Ababa, then what follows.
**How to use it:** work through the stages **in order**. A stage starts when the previous stage's "done when" checks pass. Steps inside a stage are listed in a sensible order but can overlap if more people are available. There are no dates: move on when ready.
**Read with:** [03 architecture](03-system-architecture.md), [04 payments](04-payments-and-money.md), [05 data model](05-data-model.md), [06 markets](06-markets-and-localization.md), [08 availability engine](08-availability-engine.md), and for every screen the design set in [`design/beavent/`](design/beavent/) (benchmarks: Fresha for services, Eventbrite for events).

## 0. Where we are, and what the v3 review changed

### 0.1 Status

| Stage | State | Notes |
|---|---|---|
| 0 Foundation | ✅ Done | API (identity, tenancy, files, admin), design system, two mobile apps and one web platform (D20). Hosted CI runs on PR #8: all green |
| 1 Catalogue and team | ✅ Done | Business console screens (menu, team, rota, policies), business app "My schedule" |
| 2 Appointments | **In progress** | 2.1–2.5 done: availability, venue page, booking with messages, booking screens on web and app, the business calendar (console and business app) with live updates. 2.6 done: clients, forms and patch tests, waitlist, front-desk groups and series. Stage 2 exit checks pass on the local stack (one journey test, live update 0.8 s, availability median 50 ms); **only the rehearsal with real phones remains, blocked on D4 (SMS provider)** |
| 3 Commerce and online payments | **In progress** | Re-cut 24 Nov 2026: ArifPay is the default provider (Beavent collects, pays sellers out, refunds by hand), Chapa switchable (§1, L1–L2). 3.1 (provider choice and adapters), 3.2 (orders, tax and fees) and 3.3 (deposits and prepayment at booking) done; next 3.4 seller "Get paid" |
| 4–10 | Not started | Unchanged except where noted in §0.2 |

### 0.2 Review findings (what was wrong or missing in v2, and what v3 does)

| # | Finding | Change in v3 |
|---|---|---|
| R1 | Stage 2 was ordered by *feature* (engine, booking, calendar, CRM, consumer UI, waitlist). The consumer UI came fifth, so nothing could be seen or tried end to end until late, and UI quality was left to the end. | Stage 2 is re-cut into **vertical slices**. Each slice ships API + UI + tests + visual QA and ends with something demonstrable. Every slice names the benchmark screens (Fresha-based IDs from [SERVICES_MARKETPLACE_DESIGN](design/beavent/SERVICES_MARKETPLACE_DESIGN.md)). |
| R2 | **No public read surface.** The design set needs public endpoints for a venue profile (gap G1), but v2 only builds discovery in Stage 8. Customers could not reach a venue to book. The design says direct links and QR codes are the main way in during Stages 2–7. | New step **2.2 Public venue page** (read API, web page, share link and QR, app deep link) before booking. Search and Explore stay in Stage 8. |
| R3 | Booking needs a customer record in the business (`crm.customers`), but CRM was step 2.4, after booking. | Booking (2.3) creates or links a minimal customer. Notes, alerts, tags, merge and forms are step 2.6. |
| R4 | Confirmations, reminders and waitlist offers are in Stage 2's "done when", but notifications were Stage 8.4. | A **minimal transactional messaging** layer ships in 2.3 (templates in Amharic and English, SMS and email through the existing gateways). Stage 8.4 extends it (push, quiet hours, preferences, campaigns). |
| R5 | A durable scheduler was promised "with the first time-based job (Stage 2)" but not placed. Hold expiry, reminders and waitlist-offer expiry all need it. | Step 2.3 builds it without a generic jobs table (decided while building): time-outs are idempotent sweeps claimed with `SKIP LOCKED`, and messages due later are rows in `comms.notifications` (`send_after`), delivered with leases and retries. No new dependency. |
| R6 | Realtime (WebSocket) was a bullet inside calendar operations, but it is shared infrastructure (calendar, payment status, check-in counters). | Built once in 2.5 as a platform capability (authenticated channels per location, Redis fan-out), reused by Stages 3 and 7. |
| R7 | The business app signs in as `MOBILE_APP`. Tokens carry the client app as audience, so business and consumer sessions are not told apart. | ✅ Done in 2.5: `BUSINESS_APP` in the `ClientApp` contract, token audiences and `iam.sessions` (`V2_07`); the business app uses it. |
| R8 | Wording had gone stale: "mobile Business mode", "partner portal", `apps/mobile`, separate admin-console app. | Updated: **business app** (`apps/business-mobile`), **business console** (`apps/web`, `/[lang]/business`), **admin console** (`apps/web`, secret `ADMIN_PATH`). |
| R9 | UI work had no per-step design reference or definition of done, and gaps such as the Saved tab, account screens and the map were not placed. | §2 adds UI rules to the definition of done; each UI step lists its screens; the Saved tab, account screens and map are placed in Stage 8 with their dependency installs (§0.3). |
| R10 | The SMS gateway (D4) is only a log provider, but Stage 2 now sends real confirmations. | D4 now blocks Stage 2 *acceptance with real phones*, not only step 0.7. |
| R11 | Risk list had nothing about the new surfaces. | Added risks: public booking abuse (bots holding slots), realtime fan-out, translation quality. |

### 0.3 Dependencies by step

Approved (2026-11-19). Installed only in the step that builds the screen, with `npx expo install`:

| Package | Installed in | Used for |
|---|---|---|
| `expo-haptics`, Noto Sans Ethiopic | ✅ installed | Feedback; Ge'ez text |
| `expo-camera` | 7.3 | Ticket scanner (business app) |
| `expo-brightness` | 6.5 | Ticket pass screen |
| `expo-maps` (mobile), MapLibre GL JS (web) | 8.1 | Venue map and search map; tile provider and keys decided first (G8) |

Anything else needs approval first. No new backend dependency is planned for Stage 2.

---

## 1. Decisions this plan is built on

| # | Decision | Consequence |
|---|---|---|
| L1 | **The payment provider is chosen per deployment: ArifPay (default) or Chapa** (`PAYMENT_PROVIDER` = `ARIFPAY` \| `CHAPA`; decided 24 Nov 2026, replacing "Chapa only"). One provider is active at a time; both adapters are built and tested. | All online methods (telebirr, CBE Birr, M-PESA, cards, bank apps …) go through the active provider. SantimPay, direct telebirr and manual bank transfer stay out. |
| L2 | **Who holds the money follows the provider.** **ArifPay: Beavent collects** (`PLATFORM_COLLECTS`): customer payments land in Beavent's ArifPay merchant account and Beavent pays each seller out (decided 24 Nov 2026; ArifPay's public documentation does not confirm per-payment splits to seller accounts, §1.2). **Chapa: Chapa splits at source** (`PROVIDER_SPLIT`): each seller is a Chapa subaccount and Chapa settles the seller's share to their bank. | ArifPay mode needs seller balances, payout runs and payout accounts (doc 04 §9–10); payouts start as a bank batch that finance executes and confirms, automated later through ArifPay's wallet-to-bank API. **Refunds in ArifPay mode are made by hand by finance** (ArifPay publishes no refund API): the system records each refund owed, queues it in the admin console, and finance marks it paid. Chapa mode keeps the refund API and the receivable/recovery rules of §3.2. **Beavent holding customer money needs legal confirmation (D3) before ArifPay mode takes real money.** |
| L3 | **Hosting is decided later.** | Everything is containerised and cloud-neutral (PostgreSQL + PostGIS, Redis, RabbitMQ, S3-compatible storage) and runs on Docker Compose until then. A hosted staging environment is needed **before Stage 3 certification** (real provider notifications need a public URL); production before Stage 9. |

Decided (2026-11-19): **cash and pay-at-venue stay available**, recorded at POS and the box office (no provider involved, so no conflict with L1). Each business can require an **online deposit** through the active provider (`scheduling` deposit settings; Stages 3–4) to cut no-shows; platform fees on cash sales become a seller receivable recovered from later Chapa payments (§3). Event tickets sold online are paid online; cash for events only at the box office (Stage 7).

Decided (2026-11-19): **sign-in is by a one-time code to a phone or email, or with Google** (plus Apple in the iOS app, which Apple requires when Google sign-in is offered). No passwords and no authenticator apps, for anyone; sensitive actions ask for a fresh code (step-up).

### 1.1 What Chapa's documentation says, and what it means for us (Chapa mode)

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

### 1.2 What ArifPay's documentation says, and what it means for us (ArifPay mode, the default)

From ArifPay's official [TypeScript SDK](https://github.com/Arifpay-net/typescript-sdk), [PHP SDK](https://github.com/Arifpay-net/phpsdk) and Postman collections ([production](https://developer.arifpay.net/postman/ArifPay-Production.postman_collection.json), [sandbox](https://developer.arifpay.net/postman/ArifPay-Sandbox.postman_collection.json)), read 24 Nov 2026; the developer portal's split, webhook and lifecycle pages could not be opened. The sources disagree on hosts and paths, so **every item is re-confirmed in ArifPay's sandbox and in writing with ArifPay (D2)** before a capability is marked `LIVE`.

| ArifPay fact | Design consequence |
|---|---|
| Header `x-arifpay-key`; production `https://gateway.arifpay.net`, sandbox `https://gateway.arifpay.org`; create `POST /api/checkout/session` (sandbox `/api/sandbox/c2b/session`); the SDK uses `/v0/checkout/session` | Base URL and paths are configuration; the adapter is tested against a local stub (`tools/arifpay-stub`) and the sandbox. |
| Session request: `nonce` (unique; a duplicate is refused), `items[] {name, quantity, price}`, `notifyUrl`, `successUrl`, `cancelUrl`, `errorUrl`, `expireDate`, `phone`, `email`, `lang`; production fills `paymentMethods` and `beneficiaries` from the merchant account. Response `sessionId`, `paymentUrl`, `totalAmount` | `nonce` = our `payment_attempts.id`, so a retried create can never open a second session; before retrying, the adapter looks the session up by nonce (`GET /api/checkout/getSessionByNonce/{nonce}`). One item per order with the exact total. |
| Amounts are **decimal birr** (ETB only); a default maximum of 43,200 ETB per session (third-party report) | Exact `amount_minor` ↔ decimal conversion; the route's `max_amount_minor` carries the limit. |
| `GET /api/checkout/session/{sessionId}` returns the session with `transaction.transactionStatus` (`SUCCESS`, `PENDING`, …; the full list is undocumented); `POST /checkout/session/cancel/{id}` cancels an unpaid session | Fulfil only after this lookup with our key matches status, amount and nonce. Unknown statuses stay `PROCESSING` and are polled. |
| `notifyUrl` webhooks are **not documented as signed** and reportedly **not retried**; one may arrive before the session updates | `POST /v1/hooks/arifpay` treats the body only as a hint: it is stored in `payments.webhook_events`, then the session is looked up with our key. Polling (doc 04 §6.2) is the main path, not a fallback. |
| **No refund or reversal endpoint** in either collection | Capability `can_refund_api = false`; refunds are recorded and paid by hand by finance (L2). |
| Direct payment routes per rail (telebirr USSD, M-PESA, CBE, Awash …) with a phone number; payouts (B2C) and wallet-to-bank need production access with an allow-listed IP | Web and app start with hosted checkout (`REDIRECT`); push prompts in the app and automated payouts come after certification. |

## 2. Rules for every step (definition of done)

- Code reviewed; unit and integration tests; OpenAPI spec updated and the TypeScript client regenerated.
- `db/scripts/validate.sh` green on PostGIS in CI (new tables follow doc 05 §7; the schema-lint test enforces it).
- Amharic and English strings; Ethiopian date/time display where dates are shown.
- Audit events for sensitive actions; metrics/alerts for new background jobs.
- Docs updated when behaviour differs from docs 03–06.
- **Every UI step** (also see [`AGENTS.md`](../AGENTS.md)):
  - Built from `@beavent/ui` tokens and `@beavent/native` components, to the screen spec named in the step. The screen specs follow Fresha for services and Eventbrite for events; when a spec is missing, write it in `design/beavent/` first.
  - Every state is designed: loading skeleton, empty, error with retry, offline, signed out, success.
  - Checked in light and dark, Amharic and English, phone and desktop widths, with screenshots reviewed (`npm run visual -w @beavent/e2e`), and the journey covered by a Playwright test in `tools/e2e`.
  - `npm run bundle:check` passes for the mobile apps.
- **Every slice ends demonstrable:** it is run on the local stack, and the result is reported with what was and was not verified.

## 3. Money rules

### 3.1 ArifPay mode: Beavent collects (`PLATFORM_COLLECTS`)

**Flow.** Customer pays ArifPay → Beavent's ArifPay merchant account. The seller's share becomes a seller balance in the ledger (`SELLER_PENDING`, released to `SELLER_AVAILABLE` after the visit plus a delay); platform fees are netted; payouts move `SELLER_AVAILABLE` to the seller's verified bank account. Postings follow [doc 04 §10, `PLATFORM_COLLECTS`](04-payments-and-money.md#10-ledger-postings).

**Refunds.** Recorded with method `MANUAL_BANK_TRANSFER` (or `CASH` at the counter): `REFUND_INITIATED` reserves the money from the seller balance, the refund appears in the admin console's finance queue with the customer's wallet or bank details, finance pays it outside Beavent and marks it paid (step-up code, audited), which settles it. A refund after the seller was paid drives `SELLER_AVAILABLE` negative and is recovered from the next payout.

**Payouts.** Eligibility, schedules, approvals and cooling-off per doc 04 §9. Execution starts as a payout batch in the admin console (bank file and list) that finance pays and confirms; ArifPay's wallet-to-bank API replaces the manual step once production access is granted.

### 3.2 Chapa mode: Chapa splits at source (`PROVIDER_SPLIT`)

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

Confirm these rules with the active provider and counsel before the pilot (dependencies D2, D3).

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
- Layout (doc 03 §1): `services/backend/`, `apps/public-mobile/` (customers), `apps/business-mobile/` (business owners, staff, organizers), `apps/web/` (public site, business console, admin console), `packages/{api-client,ui,native,i18n,calendar,money,config}`, `db/`, `docs/`.
- npm workspaces + Turborepo for TypeScript; Gradle for the API (Flyway packages `db/migration`; jOOQ, Spring Modulith, AMQP, Actuator, Testcontainers).
- Pinned: Java 21 LTS (move to 25 later without code change); Node 26 / npm 11; Spring Boot 4.1; Expo SDK 58; Next.js 16.
- Formatters and linters (Prettier, ESLint, Expo lint, Spotless/Palantir Java Format), PR template with the definition of done; CODEOWNERS once GitHub usernames are known.

*Done when:* `npm run build`, `npm run lint`, `npm run typecheck`, `npm run api:build` and `npm run db:validate` work from a clean clone.

**0.4 CI and local environment.** ✅ *Done* — `.github/workflows/ci.yml` (runs once the branch is pushed), `tools/chapa-stub`, `.env.example`, container images.
- CI: format, lint, typecheck, tests (incl. the Chapa stub), builds, DB validation on PostGIS, backend build with Testcontainers and Spring Modulith boundary tests, all container images. The OpenAPI breaking-change check is added with the first spec (step 0.6).
- Docker Compose dev stack: PostGIS, Redis, RabbitMQ, SeaweedFS (S3-compatible storage; MinIO stopped publishing images, replaced in 0.9), Mailpit, plus the **Chapa stub** (initialize, direct charge, verify, refund, subaccount, signed webhooks, outcome by phone suffix, fault injection) so payment work can start before Chapa test keys arrive.
- Secrets: `.env` locally (from `.env.example`), a secret manager once hosted. The DB stores only `*_secret_ref`.
- Container images: backend, Chapa stub, and one Next.js image definition (standalone output) for the web platform. Dependabot weekly.

*Done when:* every PR runs the full pipeline. (Verified locally: every CI command passes and the workflow passes actionlint; it runs on GitHub after the first push.)

**0.5 Backend skeleton.** ✅ *Done* — `services/backend/src/main/java/com/beavent/backend/platform` (24 backend tests).
- Spring Boot + Spring Modulith; the `platform` module carries the cross-cutting pieces below, its public API in the base package and internals in sub-packages. Domain modules (one per schema, doc 03 §2.2) are added as their stages start; `ModularityTests` enforces boundaries.
- Flyway runs `db/migration`; jOOQ classes are generated at build time from a throwaway PostGIS migrated with the same files (`com.beavent.db.<schema>`, outside the module root; explicit joins only).
- Four pools: schema owner (Flyway only) and one per runtime role — `beavent_app` (primary, RLS), `beavent_identity` (`@IdentityDb`), `beavent_system` (`@SystemDb`). The app transaction manager calls `platform.set_request_context(user, org)` at the start of every transaction from `RequestContext`; no context means no tenant rows (fails closed). Local role logins are created by a Flyway callback in the `local` profile only; production logins come from infrastructure-as-code.
- Problem+json errors with stable codes and SQLSTATE mapping (23P01 → `SLOT_TAKEN`, bucket 23514 → `SOLD_OUT`, 42501 → `FORBIDDEN`, …); `Idempotency` service (`Idempotency-Key`); `ETags` (If-Match) and `Cursors`; `X-Request-Id` + trace ids in logs (OpenTelemetry; OTLP export off until a collector exists); deny-by-default security until 0.7.
- Transactional outbox (`Outbox.publish` in the caller's transaction) → relay (`FOR UPDATE SKIP LOCKED`, publisher confirms) → RabbitMQ topic exchange `beavent.events` (+ dead-letter exchange); `ProcessedMessages.runOnce` for idempotent consumers. Jobs use `@Scheduled` with `SKIP LOCKED` claims for now; a durable scheduler is added with the first time-based business job (Stage 2).
- The backend image packages the jar built by Gradle (code generation needs Docker, so it can't run inside `docker build`).

*Done when:* an integration test (Testcontainers + PostGIS) writes a row + outbox event under RLS and a consumer handles it exactly once. ✅ (`OutboxTests`, plus `RequestContextIsolationTests`, `IdempotencyTests`.)

**0.6 API contract workflow.** ✅ *Done* — `services/backend/src/main/openapi` (identity, tenancy, reference specs for steps 0.7–0.8), `packages/api-client`.
- Spec-first OpenAPI 3.1, one folder per module (`common`, `identity`, `tenancy`, `reference`), linted with Redocly (`redocly.yaml`) and bundled to `packages/api-client/openapi.yaml`.
- `packages/api-client`: generated types (`openapi-typescript`), typed fetch client (`openapi-fetch`) adding auth, language and `X-Request-Id`, React Query hooks (`openapi-react-query`), idempotency-key and problem helpers; used by the web platform and both mobile apps (`src/lib/api.ts`).
- Backend serves the bundle at `/v1/openapi.yaml`; a test keeps `ProblemCode` identical to the contract's error codes. Request/response validation against the spec is added with the first endpoints (0.7: `swagger-request-validator-mockmvc` checks every request and response in the API tests).
- CI job `api-contract`: lint + regenerate, fail if the committed client is stale, and on pull requests fail on breaking changes against the base branch (`oasdiff`).
- Rule: every module starts with its spec, reviewed by backend, web and mobile.

*Done when:* the auth + tenancy spec is merged and used by the app shells. ✅

**0.7 Identity.** ✅ *Done* — `services/backend/src/main/java/com/beavent/backend/identity` (42 backend tests in total).
- Sign-in by one-time code to a phone or an email address (email codes added 2026-11-19): one `SIGN_IN` step for new and existing people (responses never reveal whether an account exists). Proving an inbox never opens another account that merely typed the same address unverified (the unverified copy is released). Codes and phone numbers are stored only as HMACs; 5 attempts per code, 30 s resend window, per-phone and per-IP hourly limits in Redis (`429 RATE_LIMITED`). Codes go through the `SmsGateway` port; locally and in CI the `log` provider records them — a real provider needs D4.
- No passwords and no authenticator apps (decision 2026-11-19; `V2_03` drops their tables).
- Google sign-in (Apple in the iOS app): ID tokens verified against the providers' keys (issuer, audience, nonce, signature); a verified provider email joins the account that verified the same address. Returns `404` until client ids are configured (D12).
- Sessions: ES256 access tokens (10 min; audience = client app; `sid` and `aal` claims), JWKS at `/.well-known/jwks.json` (key rotation via retired public keys), opaque refresh tokens stored as hashes with rotation; reusing an old refresh token revokes the whole session family. Sessions can be listed and revoked; logout revokes one or all.
- Step-up (`/v1/auth/step-up`): a fresh code to the account's verified phone or email issues a 5-minute `STEP_UP` token; `Assurance.requireStepUp()` guards sensitive actions (`403 STEP_UP_REQUIRED`).
- Profile (`GET/PATCH /v1/me`, merge-patch), device registration (push tokens), consent capture against published legal document versions, audit entries for security events.
- Moved: guest-order claiming needs orders and is built with checkout (step 6.4; the `CLAIM_GUEST_ORDERS` code purpose already exists).

*Done when:* every auth flow, including brute-force limits and token-reuse detection, has integration tests. ✅ (`IdentityApiTests`, `ProviderSignInTests`, `IdTokenVerifierTest`.)

**0.8 Organizations and access.** ✅ *Done* — `services/backend/src/main/java/com/beavent/backend/tenancy`, `.../reference` (48 backend tests in total).
- Onboarding API: create an organization (kind, business category, market, language; the creator becomes owner; slug from the name or chosen, reserved words refused), profile updates with `If-Match`, capabilities checked against the market's feature switches, legal entities (TIN, VAT/TOT regime, one default per country, currency from the country; changing verified details resets verification), locations with their premises place (landmark-first address following the country's address format, map pin, opening hours with split shifts).
- Members, invitations by SMS (email through the `log` provider until Stage 8), system role templates plus custom roles, location scope (members limited to some locations see only those).
- Access checks: org from the `/v1/orgs/{orgId}` path → active membership (system role) → `OrgAccess` permission check → database context narrowed to that org (RLS). Non-members get `404`, suspended organizations are read-only. Nobody can grant permissions they do not hold; granting sensitive permissions, changing a member's role, suspending or removing a member needs step-up; the owner's access cannot be changed (ownership transfer comes later).
- Invitation links work only for the person who verified the invited phone number or email, once.
- KYB/KYC: verification cases with documents (files must be the organization's `KYC`/`DOCUMENT` files); one open case per subject; owner identity only by the owner. Upload itself is step 0.9; decisions are made in the admin console (0.10).
- Reference API: markets (languages, currencies, feature switches), languages, categories, administrative areas (public, cached 5 minutes) and the permission catalogue (signed in).

*Done when:* API-level tests prove an org A token can never read or write org B data. ✅ (`TenancyApiTests`: B's ids and paths are `404`/`422` from A's workspace, including for a person who belongs to both.)

**0.9 Files and media.** ✅ *Done* — `services/backend/src/main/java/com/beavent/backend/files` (52 backend tests in total).
- Direct uploads: the API creates the file record and a pre-signed PUT (15 minutes) to the private bucket; the client uploads, then calls `complete`, which checks the stored size and type. Each purpose (logo, cover, gallery, event media, KYC, document, avatar) fixes who may upload, allowed types (JPEG/PNG, PDF) and size.
- Processing worker (`file.uploaded` via the outbox): content-type check from the file's bytes, malware scan (`MalwareScanner`: ClamAV `clamd` in production — needs hosting, D5; an EICAR-only scanner locally), and for public images re-encoding (drops EXIF/GPS), size limits against decompression bombs, and `w320`/`w960` variants. Only then are public images copied to the public bucket (CDN); rejected files are deleted and audited.
- Private and `SENSITIVE` files are only reachable through signed URLs (5 minutes; 60 seconds for sensitive) after the purpose's permission check; each sensitive access is audited. Logos, covers and avatars can only reference processed files of the same organization/person; KYB cases only uploaded, not-rejected documents.
- Local storage is SeaweedFS (S3 API; MinIO no longer publishes images); production uses any S3-compatible service (Cloudflare R2 or AWS S3, hosting decision D5).
- Later: clean-up job for uploads never completed (indexed), more purposes as modules need them (customer photos, receipts, exports).

*Done when:* the logo, gallery and KYB document flows work, and sensitive files are inaccessible without authorisation. ✅ (`FilesApiTests`.)

**0.10 Admin console shell.** ✅ *Done* — `services/backend/src/main/java/com/beavent/backend/admin`, `apps/web/src/app/(ops)` (56 backend tests in total).
- Staff access: admin-console tokens are issued only to accounts with an active staff role (sign-in by phone/email code or Google; sessions last at most 12 hours); every `/v1/admin` call re-reads the staff roles, so a revoked role stops working at once. Permissions come from staff roles and are scoped to markets (a market-limited reviewer does not see other markets). First staff accounts are granted with `db/scripts/grant-staff-role.sh` (audited). Opening KYB documents, deciding cases and changing flags need a fresh code ("confirm it's you", valid 5 minutes).
- Every change carries a reason and is audited (`PLATFORM_STAFF`); opening a KYB document creates a 60-second signed link and an audit entry; staff cannot decide verification for an organization they belong to.
- API and screens: overview, verification queue and case review (documents, approve / reject / ask for information), organization search and 360, people search (phone in local or international format, email, name), audit log, feature flags (changing defaults needs global market management), markets (read-only).
- The console keeps the session server-side: tokens live in httpOnly cookies and never reach the browser; `proxy.ts` rotates them before expiry (never on prefetches, because refresh-token reuse ends the session).

*Done when:* an operator approves a KYB case and it appears in the audit log. ✅ (`AdminApiTests`; also checked end to end on the dev stack: upload → scan → submit → staff sign-in by code → review → approve → audit log page.)

**0.11 Design system, languages and calendar.** ✅ *Done* except the wireframes (D9) — `packages/{ui,i18n,calendar,money}`, `backend/.../platform/EthiopianCalendar.java`.
- `packages/ui`: design tokens for web (CSS variables) and React Native (numbers), Ethiopic typography rules (Noto Sans Ethiopic, line height 1.7, nothing below 14 px) and the low-end Android performance budget. Values are provisional until the visual design (D9); shared components are added as screens are designed. The Android bundle after Stage 1 is 4.1 MB of Hermes bytecode, 1.7 MB compressed (budget: 4 MB compressed).
- `packages/i18n`: Amharic and English catalogues complete for the shells, Afaan Oromo started (missing keys fall back to English); ICU-style placeholders and plurals, Accept-Language negotiation, localized-text fallback. The Amharic and Afaan Oromo copy needs review by native-speaking editors before launch.
- `packages/calendar`: Ethiopian ⇄ Gregorian conversion, Ethiopian local time (hours from 06:00/18:00) and formatting in am/en/om; tested on shared vectors (holidays, leap years) and every day from 1900 to 2200. The Java twin `EthiopianCalendar` runs the same vectors.
- `packages/money`: exact decimal parsing/printing, localized formatting, allocation without lost minor units, basis-point percentages rounded half up, cash rounding.
- Shells: the web platform (public site and business console) under `/am`, `/en`, `/om` (language from the URL, remembered in a cookie, otherwise the browser's languages, defaulting to Amharic) and the mobile home screen (device language, switchable) show today's Ethiopian date and Ethiopian/international time for Addis Ababa.
- Open: wireframes for the pilot journeys (D9).

*Done when:* the web, partner and mobile shells render in Amharic and English with Ethiopian dates and times. ✅

**Checkpoint A (end of Stage 0):** *(API side reached: covered by the identity, tenancy, files and admin tests and the end-to-end run in 0.10; the partner-portal onboarding screens come with Stage 1's designs (D9). CI on GitHub runs once a pull request is opened.)* a user signs up by SMS OTP, creates an organization, completes onboarding, uploads KYB documents and invites a colleague; an admin approves KYB; it all works in Amharic or English, and CI is green on PostGIS.

---

## Stage 1 — Catalogue and team

**Goal:** a business can describe what it sells and who does it.

**1.1 Services.** ✅ *Done* (API + business console) — `backend/.../catalog`.
- Menu sections; services with variants (duration, buffers, price type), stage templates (e.g. apply / process / rinse; the stages must add up to the variant's duration), resource needs, add-ons, bundles (sum, fixed price or percent off).
- Price overrides per location, team member, pricing level or combinations, effective-dated (history kept). `ServicePricing` resolves the most specific price and duration for booking.
- Resources (rooms, chairs, beds, stations; parallel units); galleries using processed public images.
- Business console: menu with sections, service editor (Amharic and English names, options, durations, prices in birr, locations), staff-specific prices.

**1.2 Team.** ✅ *Done* (API, business console, business app) — `backend/.../team`; "My schedule" is now the Schedule tab of `apps/business-mobile` (moved from the consumer app, 24 Nov 2026).
- Team profiles with or without a login (one profile per login), locations, services performed, pricing levels.
- Regular hours as weekly or multi-week rotations, effective-dated; one-off shifts replace the pattern for a day; time off recorded by managers or requested by the person and then decided.
- `Rota` computes working intervals per person and local day; managers and people who see every calendar see everyone, others only themselves.
- Business console: team list, add a team member, regular hours editor, weekly rota (Ethiopian dates and Ethiopian time in Amharic).
- Business app: sign-in by phone or email code (tokens in the phone's secure storage, one refresh at a time), "My schedule" for this week and next from the team profile linked to the login (`GET /staff/me`).

**1.3 Booking policies.** ✅ *Done* (API + business console) — `backend/.../scheduling`.
- Notice period, booking horizon, slot interval, request-to-book, pay at venue.
- Deposit rules (none/percent/fixed/full; who they apply to), cancellation window, no-show and late-cancel fees, policy text.
- Most-specific-wins resolution (service + location, service, location, organization, platform default) via `BookingPolicies`.

**1.4 Locations and at-home services.** ✅ *Done* (API) — tenancy and reference.
- Opening hours (0.8), closures, a "closed on public holidays" switch; Ethiopian public holidays 2026–2027 as reference data (to be confirmed by a local adviser like other legal facts).
- Service areas (administrative area or circle) with travel fee and travel time.

*Done when:* a salon builds a menu with staff-specific prices and a weekly rota for its team in the business console. ✅ (`tools/e2e/tests/partner-portal.spec.ts`: a real browser signs in by code, onboards a business, builds the menu, adds a stylist with regular hours, sets her price and checks the rota in English and Amharic; `mobile-schedule.spec.ts`: a team member signs in on the mobile app (web build) and sees two weeks of their hours; plus `CatalogApiTests`, `TeamApiTests`, `PoliciesAndLocationsApiTests`.)

---

## Stage 2 — Appointments (pay at venue)

**Goal:** end-to-end booking without online payment, so the scheduling core is proven before money is involved. **Benchmark:** Fresha. A person who has never used Beavent goes from a shared link to a confirmed appointment in under a minute, with no account made in advance ([design §1](design/beavent/SERVICES_MARKETPLACE_DESIGN.md#1-journey)); the front desk sees it on the calendar at once.

**How the stage is cut.** Six slices, each vertical (database → API → UI → tests → visual QA) and each ending with something you can run. Screen IDs refer to [SERVICES_MARKETPLACE_DESIGN](design/beavent/SERVICES_MARKETPLACE_DESIGN.md) (S-xx consumer, P-xx business console, B-x business app).

| Slice | Delivers | Screens (benchmark) | Surfaces |
|---|---|---|---|
| 2.1 | Availability engine and API | — (API only; slot picker uses it in 2.4) | backend |
| 2.2 | Public venue page and share link | S-04 venue, S-05 service sheet, S-17 professional sheet | web, consumer app |
| 2.3 | Booking core | — (API; first UI in 2.4) | backend |
| 2.4 | Consumer booking flow and "my appointments" | S-06 … S-10b, S-11 … S-13, C-4 | web, consumer app |
| 2.5 | Calendar operations | P-12 calendar, B-1 Today, B-2 Calendar | business console, business app |
| 2.6 | Clients, forms, waitlist, front-desk bookings | P-13 clients, S-15 waitlist | business console, business app, web, consumer app |

Order matters: 2.2 needs 2.1's next-available times; 2.4 needs 2.2 and 2.3; 2.5 needs bookings to exist; 2.6 builds on all of them. Steps inside a slice can overlap.

**2.1 Availability engine.** ✅ *Done* — spec: [08](08-availability-engine.md).
- Slice 1 ✅ (24 Nov 2026): the algorithm as a pure function, 28 hand-built tests and 3 property tests (oracle, safety, monotonicity), mutation-checked.
- Slice 2 ✅ (24 Nov 2026): **facts loaded from the database** (rota, location hours, closures, holidays, live reservations, resources, policy and travel) behind the public `scheduling.Availability` interface; the **public endpoint** `POST /v1/public/locations/{locationId}/availability` (OpenAPI, no sign-in, rate limited) and `tenancy.PublicLocations`; API tests against PostGIS (hours, closures, holidays, blocks, live and expired holds, processing stages, add-ons, resources, several services, at-home travel, refusals). Details and decisions: [08 §9](08-availability-engine.md#9-the-database-loader-and-the-endpoint-slice-2).
- **Cache: not built, on purpose.** Measured uncached: 10 people, 60 reservations, a 3-service booking, median about 60 ms (budget 300 ms). A cache with outbox invalidation is added when a measurement needs it (Stage 8 search by time window). `next_available_at` per location comes with the venue page (2.2).

*Done when:* ✅ property tests show no offered slot ever overlaps a reservation, and one location/day with 10 staff returns in under 300 ms including loading (an API test against PostGIS measures it).

**2.2 Public venue page.** ✅ *Done* (24 Nov 2026), with the deviations below. The first thing a customer sees; Fresha's venue profile.
- Backend: `GET /v1/public/venues/{business}/{location}` (and `…/{business}/locations`) in the new `marketplace` module: profile, photos, landmark-first address, hours, upcoming closures, services grouped as on the menu with price ranges (standard price and any lower per-person, level or location price) and durations, add-ons, bookable team, the effective policy, other locations. Only active, online-bookable services offered at the location are exposed; a business that is suspended or closed, or an archived location, is `404`. Reads run in the business's context (RLS); cacheable for 30 s; 240 requests per minute per address. API tests cover content, hidden services, multi-branch, phone-only and removed places.
- Web: the S-04 page at `/{lang}/b/{business}/{location}` (a link without a language follows the browser): gallery or initial, title, open status in both time conventions, anchor tabs, service rows that open in place (variants, add-ons, aftercare, patch-test note), team, about (hours with today in bold, closures, policy sentences), other locations, Call action, loading skeleton, "not on Beavent" and error states, noindex unless the business is approved for the marketplace. The business console has a **Booking link** page (copy link, open page).
- Consumer app: the same screen at `/b/{business}/{location}` (`beavent://b/…`), with the same wording from the shared `@beavent/venue` package.
- Tests: unit tests of the shared formatting (`packages/venue`), Playwright for the web page (both languages), the console link and the app screen; screenshots reviewed in both languages, both themes, phone and desktop.
- **Deviations from the plan and what moves where:**
  - No **Book now** button yet: the booking flow is 2.4; the action is **Call** until then. The sticky booking box on desktop and the bar on phones are ready for it.
  - **QR code** for the booking link needs a QR library: not on the approved list. Ask for approval, then add it to the Booking link page in 2.4 (the link and copy work now).
  - **S-05 service sheet and S-17 professional sheet** arrive with the basket in 2.4 (rows open in place until then). `next_available_at` and "Next available today" also need a chosen service, so they come with 2.4.
  - Open status uses hours and announced closures; **public holidays** are not applied to the "Open" label yet (availability does apply them).
  - Reviews, rating and the map preview wait for Stage 8; the Explore tab still shows no venues.

**2.3 Booking core.** ✅ *Done* (24 Nov 2026) except the `.ics` file, moved to 2.4.
- Done: `POST /v1/public/locations/{id}/holds` (public, 10-minute hold, re-checked by the engine, rate limited), `DELETE …/holds/{holdId}` (holder key), `POST /v1/me/appointments` (signed-in person; hold → appointment, items, add-ons, stages, resource units and reservations in one transaction; `REQUESTED` when the business confirms each booking; repeat returns the same appointment) and `GET /v1/me/appointments`. The business's customer record is created or linked (an earlier walk-in with the same verified phone is reused). Terms are frozen in the appointment (strictest rule across services, exact free-cancellation time). A person's own back-to-back services are one merged reservation. Expired holds are released before a new hold. Problem code `HOLD_EXPIRED`. API tests include an 8-way race (exactly one hold) and a mutation check of the merge.
- Decisions: guests book by the inline phone code (DD10), which signs them in, so there is no separate guest-claim flow. No `commerce.orders` row yet: orders arrive with tax, fees and payments in Stage 3, and pay-at-venue visits are settled at the counter (Stage 4). `SLOT_TAKEN` returns no alternatives; the client asks availability again (cheap, about 60 ms).
- Slice 2 done: `GET /v1/me/appointments/{id}` with a **cancellation preview** (gap G4: allowed, free, free-until, fee), `POST …/cancel` (free before the frozen free-cancellation time or while a request is unanswered; otherwise the late fee on the all-in total is recorded; the time is freed at once) and `POST …/reschedule` (same services, same chosen person, prices kept as booked; one transaction frees the old time first, so a 15-minute move with the same person works; a taken time changes nothing; allowed until the free-cancellation time, at-home visits by phone for now).
- Slice 3 done: **request-to-book answers** (`POST /v1/orgs/{orgId}/appointments/{id}/accept|decline`, `appointments.manage`); **sweeps** every 30 s across businesses (holds that ran out; requests not answered within 24 h or 2 h before the visit expire and free the time); a new **`comms` module**: booking events become notifications for the customer (confirmed, request received, declined, expired, cancelled, moved; reminders 24 h and 2 h before), withdrawn when the visit moves or is cancelled, suppressed when stale, delivered by SMS and email with leases and five attempts, in the person's language (else the business's) with Ethiopian and international time (`EthiopianCalendar.formatTime`, same output as the TypeScript formatter). Tests cover the whole message flow, both languages and the sweep.
- Still open: the Amharic message texts need the native-speaker review (doc 06); a real SMS provider (D4) before testing with real phones; `.ics` moves to 2.4 (it needs a link that works without a sign-in header).

- **Hold → appointment → reservations in one transaction**, pay at venue; a lost race returns `SLOT_TAKEN` from the database exclusion constraints, and the API re-runs the engine once to suggest the nearest alternatives. Request-to-book creates a held `REQUESTED` appointment that the business confirms or declines (hold lasts until the policy's response time).
- Booking identity: signed-in customers, or **guest by phone** (inline one-time code, DD10; the guest is claimed to the account on first sign-in with the same verified number). A **minimal customer record** (`crm.customers`: name, phone, source `DIRECT_LINK` or `MARKETPLACE`) is created or matched in the business (R3).
- **Durable jobs** (R5): `platform.jobs` claimed with `SKIP LOCKED`, idempotent handlers, metrics and alerts. First jobs: hold expiry, appointment reminders (24 h and 2 h), request-to-book response deadline.
- **Minimal transactional messaging** (R4): template catalogue (am/en) for booking confirmed, request received/approved/declined, reminder, cancelled, rescheduled; sent by SMS and email through the existing gateways, with Ethiopian date and time next to the international one; delivery log. Real SMS provider before acceptance with real phones (D4).
- Cancellation with policy evaluation (free until the deadline, late-cancel fee as a recorded amount, no money moves until Stage 3), a **cancellation preview** endpoint (G4), reschedule (new hold, old slot released in the same transaction), and booking for someone else (guest name and phone separate from the booker).
- Calendar file: `.ics` endpoint for "Add to calendar" (G6: no new dependency).
- Abuse controls: per-IP and per-phone limits on holds, at most N live holds per person, short hold time (policy-driven, default 10 minutes).

*Done when:* two people racing for the same slot through the API get exactly one booking and one `SLOT_TAKEN` (concurrency test); hold expiry frees the slot; a booking with a guest phone creates exactly one customer.

**2.4 Consumer booking flow and "my appointments".** ✅ *Done* (24 Nov 2026) on web and in the consumer app, except "Add to calendar" (`.ics`) and Book again with the same services preselected (S-14).
- Done (web): **Book now** on the venue page opens `/{lang}/b/{business}/{location}/book`: services (S-06: add several, choose an option and add-ons), professional (S-07, skipped when only one person does every chosen service), time (S-08: a week of days with the reader's calendar first, times grouped into morning, afternoon and evening in both time conventions, opening on the first day with times, "Times are in Addis Ababa time" when the device's zone differs, the next free day when a day is empty), review (S-10: items with the person, all-in total, pay at the venue, the exact free-cancellation time, the late fee, request-to-book wording, a 10-minute hold with its end time, note) with **inline sign-in by phone code** (DD10; customer sessions in httpOnly cookies, client app `WEB_MARKETPLACE`, kept alive by the proxy like the console's), confirmation (S-10b: reference, time, people, what happens next). A taken time shows a message and fresh times; a run-out hold sends the person back to choose again. Summary column on desktop, bar on phones. Playwright: a newcomer books with the inline code and books again signed in; screenshots of every step in both languages, both themes, phone and desktop were reviewed (they caught afternoon times repeating the morning's, and names shown in the business's language instead of the reader's; both fixed).
- Done (web, slice 2): **Bookings** (C-4) at `/{lang}/account/bookings` (sign in inline when signed out; Upcoming and Past, grouped by day in the reader's calendar, a status label when not simply confirmed; sign out), **appointment detail** (S-11: status, banner for requests, declines, expiry and cancellation, date and time in both conventions, duration, reference, the place with Call, items with the person and add-ons, total, the exact free-cancellation time; Reschedule when allowed, otherwise "Changes this close to the visit need a call"; Cancel stating the cost first (S-13); Book again or Find another time when it is over), **reschedule** (S-12: the same time picker, "Move to …?", prices kept, a taken time refreshes the list). Service names come from the place's menu in the reader's language (the list API now returns the variant). A "See my bookings" action on the confirmation; the site header has Bookings and a language switch that keeps the page. Playwright: book → list → detail → reschedule → cancel → Past; screenshots in both languages and themes, phone and desktop, reviewed (header crowding on phones fixed).
- Done (consumer app, slice 3): **Book now** on the venue screen opens the same flow as a full-screen modal (DD4): services with options and add-ons, professional when more than one, a time picker with the same rules (`src/components/time-picker.tsx`), review with the shared phone-code panel (DD10), confirmation with "See my bookings". The **Bookings tab** (C-4) lists upcoming and past appointments (refreshed whenever the tab comes into view); **appointment detail** (S-11) with Call, cancel after seeing the cost (S-13), reschedule (S-12) and Book again. Day and part-of-day rules moved into `@beavent/venue` so web and app count days in the venue's zone the same way (unit-tested). Playwright on the app's web build: book (signing in on the way), see, move, cancel; screenshots of the booking steps in both languages and themes reviewed. Bundle 1,697 KB gzip (budget 4,000 KB).
- Local development: `application-local.properties` raises the per-address hold and sign-in-code limits (every local request comes from one address); production keeps 30 holds an hour per address.
- Left for later: `.ics` "Add to calendar" (needs a signed link that works without a sign-in header); Book again opening at the time step with the same services (S-14; today it opens the services step); native iOS/Android rendering checked only through the web build.

- Web and consumer app: Services (S-06, multiple services in sequence, add-ons) → Professional (S-07, "any available" default; skipped when there is only one) → Time (S-08: week strip, dual Ethiopian/international time, "Times are in Addis Ababa time" caption when the device differs, nearest-day suggestions when a day is empty, waitlist entry point) → Details (S-09: at-home address and travel estimate G5, notes; skipped when not needed) → Review and confirm (S-10: all-in price, policy sentences as dates and amounts, cancellation deadline, request-to-book wording) → Confirmation (S-10b: add to calendar, directions, share).
- Inline sign-in (DD10) by phone code at the review step; guests supported. Deposit and prepayment steps appear in Stage 3 (the screens leave room; the flow already branches on the policy).
- **Bookings tab** (C-4: upcoming, past) and **appointment detail** S-11 with reschedule (S-12) and cancel (S-13) using the preview endpoint; Book again (S-14).
- States: no slots (waitlist link until 2.6), slot just taken (inline message and nearest times, never a dead end), offline, expired hold.

*Done when:* a Playwright journey books, reschedules and cancels a pay-at-venue appointment on web, and the same flow runs on the consumer app's web build; screenshots reviewed in both languages, both themes, phone and desktop.

**2.5 Calendar operations.** ✅ *Done* (24 Nov 2026) except drag-to-move and the business app's day view opening at the current time.
- Done: `GET /v1/orgs/{orgId}/calendar` (a location, one to seven days: each team member's working time, appointments with their stages and customer, blocks; `calendar.view_all` sees everyone, `calendar.view_own` only their own column; phone numbers only with `customers.contact_details`; cancelled, expired and declined visits left out; current menu names per language next to the booked name) and `POST …/appointments/{id}/status` (arrived, started, completed, no-show; `appointments.manage`, or `manage_own` for appointments that are all the person's own; no-show only after the start; every change in the history and the outbox). Business console **Calendar** (P-12, first in the menu): day view with a column per team member on a 15-minute grid, working time on the page colour and time off on the surface colour, appointments with a leading bar (requests in the warning tint, processing time lighter, completed muted), blocks labelled, a "now" line on today; a panel with the customer (tap to call), services, note, and the next steps, or Accept/Decline for requests; 24-hour clock with Ethiopian time beneath for Amharic readers (doc 06 §2.1). The booking-link page now shows a **QR code** to print or download (`qrcode`, approved 24 Nov 2026), always dark on light so cameras read it in any theme. API tests (views per role, status steps and refusals) and a Playwright journey (a booking appears; arrived, started, completed; QR shown); screenshots in both languages and themes reviewed (they caught columns not filling the width, block text centred, Amharic times hard to read and service names in the business's language; all fixed).
- Slice 2 done: `POST /v1/orgs/{orgId}/appointments` (walk-in or phone, an existing customer or a new one, a known phone reused; front-desk rules: any five-minute start, no notice or horizon, services hidden from online booking allowed; working time, opening hours and the calendar apply; **Book anyway** writes deliberate overlaps, `allow_overlap`, that the database guard lets through; a team-only note; "send the customer a text message" switch, reminders follow anyway), `POST …/appointments/{id}/move` (new time and optionally another person, prices kept, the old time freed first, book anyway), `POST/DELETE …/blocks` (breaks, meetings; refused over a booking), `GET …/customers?q=` (name or phone digits; phone numbers only with contact details). Professionals (`manage_own`) act only in their own column. Console: click an empty slot to book or block there, a block to remove it, **Move** in the appointment panel; a busy time offers Book anyway. API tests and a Playwright journey (walk-in from a slot, block and remove, move); screenshots reviewed.
- Slice 3 done: **`BUSINESS_APP`** client app (R7: contract, token audience, `iam.sessions` check in `V2_07`; the business app signs in with it). Business app tabs are now **Today** (B-1: today's visits in time order with a "Now" divider, 24-hour times with Ethiopian time beneath for Amharic readers, the people and services, a status when not simply confirmed; a visit opens with Call and its next steps: accept or decline, arrived, start, complete, no-show), **Calendar** (B-2: the day with a column per person, about two on a phone, processing time lighter, requests in the warning tint, blocks labelled, day stepper with both calendars) and **More** ("My schedule", preferences); **Walk-in** books someone who is here now (person, service, name, optional phone, "text the customer" off by default, Book anyway when the person is busy). Playwright on the app's web build (Today, a visit arrived, a walk-in, the day view; "My schedule" from More); screenshots in both languages and themes reviewed. Bundle 1,668 KB gzip.
- Slice 4 done: **live updates** (R6). Decided while building: **server-sent events** instead of WebSocket (no new dependency, one-way is all a calendar needs, works through proxies). `GET /v1/orgs/{orgId}/calendar/stream?locationId=` sends `ready`, then `changed` after every appointment or block change at the location (small messages without personal data; screens reload), a heartbeat every 25 s, closes after 30 minutes. Each API instance has its own short-lived RabbitMQ queue bound to the outbox's `appointment.#` and `calendar.#` events, so changes through any instance reach every screen. The console relays the stream through a Next route (the token stays on the server) and refreshes the board; the business app checks again every 15 seconds while Today or Calendar is open (React Native has no built-in event stream; the plan's polling fallback). Blocks now publish `calendar.block_changed`. A test caught that any owner could open the stream for another business's location; locations are now checked against the business. Tests: the stream hears bookings and blocks; Playwright shows a booking made elsewhere appearing on an open console calendar.
- Left for later: drag-to-move (Move in the panel covers it); the business app's day view opening at the current time; WebSocket only if a two-way need appears (inbox, Stage 8).

- Calendar API: day and week by staff and resource, create appointment (walk-in or by phone), move (drag), resize, change status (arrived, started, completed, no-show; every change in `appointment_status_history`), blocks and breaks, staff-only forced overlap (`allow_overlap` after an explicit "Book anyway"), "notify the customer" switch on every change.
- **Realtime** (R6): authenticated WebSocket channel per location with Redis fan-out; event = "calendar changed" with the affected ids (clients refetch the range); reconnect with backfill; fallback to polling every 15 s.
- Business console **P-12 Calendar**: day view with a column per team member (15-minute grid, 'now' line, processing stages drawn lighter, requested bookings in `warningSubtle`, blocks), week view, drag to move, click empty slot to create, appointment drawer with customer, services, status actions, history.
- Business app **B-1 Today** and **B-2 Calendar**: timeline with a 'now' line, status actions by swipe or row actions, walk-in button, 2-column staff day view, cached view offline with queued status changes labelled "Waiting to sync".
- **`BUSINESS_APP` client app** (R7): added to the contract; business sessions use it.
- Permissions: seeing everyone's calendar vs own, per the existing roles and location scope.

*Done when:* a booking made on the consumer web appears on the stylist's calendar in the console and the business app within a second without refreshing; the front desk moves it, marks it arrived and completed; the customer gets the reschedule message; two staff editing the same day never double-book unless the forced overlap is chosen.

**2.6 Clients, forms, waitlist, front-desk groups.** ✅ *Done* (24 Nov 2026), in four slices: 1 clients ✅, 2 forms and patch tests ✅, 3 waitlist ✅, 4 front-desk groups and series ✅.
- Slice 1 done (24 Nov 2026): a **`crm` module** (Spring Modulith "Clients"). `GET /v1/orgs/{orgId}/customers` lists clients by name a page at a time, or with `q` returns the 20 best name or phone-digit matches (it replaces the front desk's search at the same path); each row has the last visit, completed visits, tags and an alert count. `POST …/customers` adds a client by hand (source `MANUAL`, migration `V2_08`; local numbers such as 0911… become +251…; a phone the business already has is refused). `GET/PATCH …/customers/{id}` is the client's page (contact, notes pinned first, tags, upcoming and past visits with the menu names, visit counts, no-shows, cancellations by the client; what the caller may do) and edits it; a merged record answers with the client it was merged into. Notes (`POST/DELETE …/notes`) are general, alert, allergy, medical or preference; manager-only and private notes stay hidden from others. `PUT …/tags` replaces tags (new names become the business's tags), `GET …/customer-tags` lists them with counts. **Merge** (`POST …/merge`) moves appointments, notes, files, patch tests, consents, forms, groups, series and waitlist entries to the kept client, combines tags, fills blank contact details, keeps the duplicate as `MERGED`, publishes `customer.merged` and writes the audit log; it is refused for two different Beavent accounts and, until Stages 3, 4 and 8 decide how balances combine, for a duplicate with orders, balances, memberships or marketing rows. A test compares every reference to a client in the database with the merge's lists, so a new table must be handled. **Import** (`POST …/import`) reads CSV (comma, semicolon or tab; quotes; English or Amharic headings, and the "First Name / Last Name / Mobile Number" columns other systems export), previews new, already-known and problem rows (problems as codes the screens word), then adds the new ones. **Blocked** clients cannot book online (`403`), the team still can. The calendar now carries each client's alerts. Console: **Clients** in the menu (list with search and tag filter, add, import with a preview table, the client page with an alert banner, notes, tags, details and block, merge with a side-by-side comparison and an "it can't be undone" warning); the calendar panel shows the alerts and links to the client, and blocks with an alert carry a marker. Business app: **Client details** from a visit (alerts, contact, upcoming and past visits, notes with "add note", tags), and the visit shows the alerts. Tests: API tests (list and pages, search, permissions, alerts on the calendar, notes and tags, block, merge and its refusals, the reference check, import), Playwright (console: alert on the calendar, merge, import; business app: client from a visit, add a note), screenshots in both languages and themes, phone and desktop.
- Slice 2 done (24 Nov 2026): **forms** (D11's one forms engine, `crm.forms` / `form_versions` / `form_assignments` / `form_submissions`). `GET/POST /v1/orgs/{orgId}/forms`, `GET/PUT/DELETE …/forms/{id}`: a title and introduction per language, questions (short and long text, yes or no, one or several choices, date, agreement tick box; each per language, required or not), an optional typed signature, the services it is attached to and how often it is asked (before every visit, once, or again after N days). Changing the questions makes a new version; answers keep the questions they answered; archiving stops asking. A form is **due** for a visit when it is attached to one of its services (`crm.AppointmentForms`, used by the calendar). Clients fill forms in **after booking, before the visit** (`GET /v1/me/appointments/{id}/forms`, `GET/POST …/forms/{formId}`; answers checked against the questions; sending again replaces the earlier answers, which are kept as superseded) — Fresha's behaviour, and it keeps the booking itself under a minute (S-09 listed forms inside the booking flow; recorded as a deviation). The calendar shows each visit's forms as filled in or not; the client page lists answered forms and `GET …/customers/{id}/form-submissions/{id}` shows the answers with the signature. **Patch tests**: the service editor sets "needs a patch test" and how long a pass counts (default 180 days); the team records tests on the client (`POST/DELETE …/customers/{id}/patch-tests`); online booking and online moving of such a service need a pass valid on the day (`PATCH_TEST_REQUIRED`, 422), the front desk is not held to it. Console: **Forms** in the menu (list, editor), forms and patch tests on the client page, the answers page, form status in the calendar panel, the patch-test setting in the service editor. Web and consumer app: "Before your visit" on the appointment and the form screen (new `CheckRow` in `@beavent/native` for several choices and agreements). Business app: the visit shows each form's status. Tests: API tests (answers checked, versions, frequencies, archive, permissions, patch-test refusals and the desk exception), Playwright (a form built in the console, filled in by the client on the web, seen on the calendar and the client page; the patch-test setting and a recorded test), screenshots reviewed.
- Slice 3 done (24 Nov 2026): the **waitlist** (S-15) on `commerce.waitlist_entries` / `waitlist_offers` (migration `V2_10`: an offer holds the time with a scheduling hold and an accepted offer is an appointment; a person reads their own entries across businesses). `POST /v1/me/waitlist` joins a location for one service (anyone or one professional) on up to seven local days in the next 60, for mornings, afternoons and/or evenings (at most five active entries per business; the service must be bookable online); `GET /v1/me/waitlist`, `GET/DELETE …/{entryId}`, `POST …/accept` (the held time becomes an appointment with source `WAITLIST`), `POST …/decline` ("Not this time": the time goes to the next person, the entry keeps waiting). **Matching** runs in the booking sweep every 30 seconds (R5: no jobs table; decided while building instead of reacting to each cancellation event): offers that ran out move on, entries whose windows passed expire, and waiting entries, oldest first, are offered the earliest free time inside their windows that was never offered to them before, held for 15 minutes; it catches every way a time frees up (cancellation, move, removed block, new hours). The offer goes out by text message with a link (`beavent.web.public-url`, `PUBLIC_WEB_URL`), and is dropped if the offer closed before sending. Web: "Join the waitlist" on a day without times, the join page (service, professional, a two-week day strip with up to seven days, times of day, inline sign-in), Waitlists under Bookings → Upcoming, the entry page (the offer with its held time: Book this time / Not this time; Leave waitlist). Consumer app: the same three screens. Console: the location's waitlist, oldest first, from the calendar. Tests: API tests (offer after a cancellation, held from others, booked; decline and expiry go to the next person and are never offered again; joining rules, limits and leaving), Playwright (a full day, joining on the web, a cancellation, the offer, booking it), screenshots reviewed (they caught the join page scrolling sideways on phones and a Latin full stop after an Amharic date).
- Slice 4 done (24 Nov 2026): **front-desk groups and series** on the existing `scheduling.appointment_groups` / `appointment_series`. `POST /v1/orgs/{orgId}/appointments` takes `guests` (up to nine more people at the same time, each with their own services and professional; one appointment each, all booked in one transaction or none, so two guests cannot take the same person) or `repeat` (every 1–4 weeks, 2–12 visits at the same local time; `skipTaken` leaves out dates that are not free and reports them; only the first visit sends a confirmation, every visit keeps its reminders); not both at once. `POST …/appointments/{id}/cancel` lets the desk cancel (no fee: the business cancels) this visit, this and the following ones of a series (the series ends; one message to the customer), or the whole group; the cancellation message now follows the desk's "text the customer" choice. The calendar carries each visit's group and its place in a series ("Repeats every 2 weeks · 1 of 4"). Console: "Add another person" and Repeat (with "Leave out weeks that are not free" and a note naming the skipped weeks) in the new-appointment panel; the appointment panel shows the group's names and the series line, and Cancel visit with the scope. Tests: API tests (a group booked together, a clash refused with nothing booked, group cancel; a weekly series, one confirmation, skipped weeks, "this and the following"; groups and series not mixed; professionals stay in their column), Playwright (a group and a series in the console, then cancelling the rest of the series), screenshots reviewed (they caught an awkward Amharic "1 of 4").
- Left for later in groups and series: consumer-side group booking (after the pilot, as planned); moving a whole series or group at once; repeats other than every few weeks (monthly); the business app's new-appointment screen offering groups and repeats (the walk-in screen books one person).
- Left for later in the waitlist: offers for several services at once (an entry is for one service); push notifications (with the app's notification work); the business offering a time by hand from the waitlist; the business app's waitlist screen; waitlists for sold-out events (Stage 5, same tables).
- Left for later in forms: conditional questions; a drawn signature (the typed name is the signature for now); a date picker in the app (dates are typed as year-month-day; needs a dependency); sending the client a text with a link to their forms (comms); filling a form at the desk on the business app ("hand the phone to the client"); a form inside the booking flow when a business wants it before confirming; patch tests needing to be done a number of hours before the visit.
- Left for later in clients: client files and photos (upload exists in `files`; the screens come with forms), date of birth and gender, editing a note in place, exporting (`customers.export`, Stage 8), and consents shown on the client (Stage 8 marketing).
- **Clients (P-13)**: profile, notes, alerts and allergies (warning banner on the customer and their appointments), tags, history, merge duplicates, import from CSV. Business app: client screen from an appointment.
- **Consultation and consent forms** with e-signature and patch-test enforcement (blocks online booking of a service until a valid patch test is on file).
- **Waitlist (S-15)**: customer joins for a time window; on a cancellation the engine finds the match, the customer gets an offer with a held slot (SMS + in-app, a few minutes to accept); expiry moves to the next person (durable jobs).
- **Group bookings** (several people, same time or parallel items) and series (repeat weekly) from the front desk; consumer-side group booking only after the pilot feedback.

*Done when:* a customer books a pay-at-venue appointment on web or mobile, it appears on the stylist's calendar instantly, the front desk completes it, a cancellation triggers a waitlist offer that another customer accepts, and the allergy alert shows on that customer's next booking.

**Stage 2 exit:** the journey above runs end to end on the local stack with real SMS in a rehearsal (D4), the 300 ms availability budget is met, and there are no open Sev-1 bugs.

*Status (24 Nov 2026): everything but the real-SMS rehearsal is done.*
- **The journey as one test** (`tools/e2e/tests/stage2-exit.spec.ts`): a newcomer books on the web from the shared link; the booking shows on the stylist's open calendar without a reload (0.8 s after "You're booked"); the confirmation text with the reference reaches the SMS gateway; the desk checks her in, starts and completes the visit and notes an allergy; on a full day another customer joins the waitlist, a cancellation frees the time, the offer text carries a link, she books from it and the desk sees it; the first customer books again and the allergy shows on that booking. Messages are checked at the gateway (`log` provider).
- **Availability budget:** median 50 ms for 10 staff, 60 reservations, 3 services (budget 300 ms; `AvailabilityApiTests`).
- **Bug sweep:** the whole Playwright suite (18 tests) and the backend build pass. Found and fixed: newcomers on the web and in the consumer app were never asked their name, so the front desk saw a phone number. **C-6 step 3** is now built: after the code, someone without a name on file gives a given name (father's name optional) before the booking, waitlist or sign-in continues (web: the session is held in a ten-minute httpOnly cookie until then, the booking review asks in place; app: `SignInPanel askName`). Three tests that raced the page (leaving before a save, tapping during a screen change, late-night "today") were fixed. No Sev-1 bugs open.
- **Left: the rehearsal with real phones (D4).** Once ops choose the SMS provider and register the sender ID: add its `SmsGateway` adapter (`beavent.sms.provider`), set `PUBLIC_WEB_URL` to an address the phones can open, then run the journey above by hand with three real phones (customer, waitlisted customer, owner) in Amharic and English, checking that every message arrives (sign-in codes, confirmation, waitlist offer with a working link, reminders, cancellation), reads well (native-speaker review of the Amharic texts, doc 06), and shows both time conventions.

---

## Stage 3 — Commerce and online payments (ArifPay by default, Chapa switchable)

**Goal:** online payments through the active provider (L1), starting with appointment deposits and full prepayment. ArifPay mode: Beavent collects and pays sellers out, refunds by hand (L2, §3.1). Chapa mode: split at source (§3.2). Later stages that say "Chapa" mean the active provider; where the two modes differ, the step says so.

*Prerequisites:* D1 (merchant accounts and sandbox keys), D5 (hosted staging with a public webhook URL) for certification, and **D3 before ArifPay mode takes real money**. Until then everything is built against local stubs (`tools/arifpay-stub`, `tools/chapa-stub`) and ArifPay's public sandbox.

**How the stage is cut.** Vertical slices, as in Stage 2; each ends with something you can run.

| Slice | Delivers | Surfaces |
|---|---|---|
| 3.1 | Provider choice and adapters: `PaymentProviderAdapter`, ArifPay and Chapa adapters, `PAYMENT_PROVIDER` (default `ARIFPAY`), the ArifPay stub, webhook intake for both | backend, tools |
| 3.2 | Orders, tax and fees | backend |
| 3.3 | Deposits and prepayment at booking: intents and attempts, hosted checkout, "checking payment", polling, late and duplicate payments | backend, web, consumer app |
| 3.4 | Seller "Get paid": ArifPay mode → payout bank account (verified, 72 h cooling-off, step-up); Chapa mode → subaccount; online payments gated on KYB | business console |
| 3.5 | Ledger, seller balances and payouts (ArifPay mode): postings, release after the visit, payout runs, finance batch and "mark paid" in the admin console, seller statement | backend, console, admin console |
| 3.6 | Refunds for deposits: customer cancels in the free window, no-show forfeits, duplicate and late payments. ArifPay mode: the finance refund queue (paid by hand). Chapa mode: the refund API | backend, console, admin console |
| 3.7 | Payment links | backend, web |
| 3.8 | Gift cards and customer balance | backend, web, consumer app |
| 3.9 | Reconciliation and go-live certification | backend, admin console |

**3.1 Provider choice and adapters.** ✅ *Done* (24 Nov 2026).
- Done: a **`payments` module** with the adapter contract (`PaymentProviderAdapter`, normalised states and failure codes, exact minor ↔ decimal conversion). **ArifPay adapter**: sessions with our attempt id as `nonce`, a retried create finds the session by nonce instead of opening another, lookups by session and nonce, cancel, undocumented statuses kept "in progress" (never "paid"), notifications parsed as hints only, refunds unsupported, no splits; paths, payment methods and the `expireDate` zone are configuration. **Chapa adapter**: initialize with the seller's subaccount and the platform share as a flat split, verify, HMAC signatures checked in constant time, refund and refund verify. `beavent.payments.provider` (`PAYMENT_PROVIDER`, default `ARIFPAY`) picks the active adapter at startup and fails fast when it is not configured; the platform's account and online routes for the active provider are registered at startup and other providers' routes switched off (migration `V3_01`: ArifPay's capabilities in ET, sandbox, 43,200 ETB maximum; `PLATFORM_COLLECTION` = `BETA` until D3). `POST /v1/hooks/arifpay|chapa` stores the raw body in `payments.webhook_events`, once per delivery (body hash), refusing a wrong Chapa signature (401) and anything that is not a JSON object (400). `tools/arifpay-stub` (port 8091, Docker Compose and CI image) mirrors ArifPay's checkout with simulated outcomes: failed, never paid, duplicate, missing and early notifications.
- Tests: adapter contract tests against an in-test server with the providers' documented shapes (what goes over the wire, retries by nonce, refusals and outages, status mapping, signatures); API tests (default provider, routes, notification intake and replays, signatures); the stub's own tests; the stub paid on its page notified the running API, which stored the notification.
- Not verified: anything against ArifPay's real sandbox or Chapa test mode (no keys here; D1). The notify payload and status values are guesses until then.

- `payments` module with the adapter contract (doc 04 §4) and normalised attempt states and failure codes.
- **ArifPay adapter** (§1.2): checkout session with `nonce` = attempt id, lookup by session and by nonce, cancel, unsigned notify treated as a hint and confirmed by lookup, no refund API. **Chapa adapter** (§1.1): initialize, verify, signed webhook, refund and refund verify, subaccounts.
- `beavent.payments.provider` (`PAYMENT_PROVIDER`, default `ARIFPAY`) selects the active adapter, collection mode and capabilities; base URLs and keys are configuration.
- `tools/arifpay-stub`: a local stand-in for ArifPay's checkout (create, look up by session and nonce, cancel, a pay page, notify calls including missing, early and duplicate ones), like the Chapa stub.
- `POST /v1/hooks/arifpay` and `POST /v1/hooks/chapa`: raw body stored in `payments.webhook_events` first, deduplicated, processed by a worker.
- Contract tests for both adapters against in-test servers with the providers' documented shapes, and the stubs' own tests.

**3.2 Orders, tax and fees.** ✅ *Done* (25 Nov 2026), backend only.
- Done: a **`commerce` module** (`Orders`: `quote` and `place`) and a **`billing` module** (`Fees`, the pure `FeeEngine`). Pricing is pure arithmetic in integer minor units, half-up rounding once per line (`MoneyMath`): **promotions** on the gross line prices, then **tax** per line on what is left, then the platform's **fees** per line on what the buyer pays for it. A quote and the order placed from it use the same code.
  - **Tax** (`TaxEngine`): the seller's regime picks the rate (VAT-registered sellers VAT, turnover-tax sellers TOT; presumptive and exempt none) by the item's tax category and the day of the sale, in the market's inclusive or exclusive convention (Ethiopia: inclusive, so the price is all-in, DD6). Placing writes an immutable `order_line_taxes` row with the rate used. The seeded rates stay unverified until D7.
  - **Promotions** (`PromotionEngine`): percent off, amount off (shared exactly across the targeted lines, largest remainder), fixed price and free item; targets by service or category and by location; minimum order, maximum discount, off-peak windows (`time_windows`, in the location's time), automatic offers and typed codes, eligibility everyone / new / returning clients, per-client and total limits. Fees, tips and donations are never discounted. The best offer applies alone unless offers are stackable; a typed code that does not apply is named (`UNKNOWN`, `EXPIRED`, `USED_UP`, `NOT_ELIGIBLE`, `NOT_APPLICABLE`) and never changes the price. Platform-funded and shared offers record `platform_funded_minor`. Uses are counted when the order is placed, last, in the order's transaction (the platform's own offers through the system role, as tenants may only read them); two buyers racing for the last use: one wins, the other's order, number and counts roll back.
  - **Fees** (`FeeEngine`): `billing.fee_rules` for booking, ticket service and payment-processing fees: percentage plus fixed, then minimum and maximum, plus the platform's VAT on the fee; an organization's own rule beats the market default. **Buyer-paid fees become `BUYER_FEE` order lines** (platform VAT inside, tax category `PLATFORM_FEE`) so the price shown is all-in; seller-paid fees are recorded for payout netting (ArifPay mode) or the split (Chapa mode). Every fee is a `billing.fee_assessments` row pointing at the line it was charged on.
  - **Orders**: header, lines (service, add-on, fee), tax snapshots, redemptions, a status-history row and fee assessments in one transaction under the database's own arithmetic checks (line totals, header totals); **gapless order numbers per legal entity** (`commerce.next_document_number`, rolled back with a failed order).
- Tests: property tests of rounding and splitting (5,000 random cases each: parts add up, half-up, no part over its weight), tax (10,000 cases: inclusive and exclusive lines satisfy the line rule), fee caps and monotonicity (10,000), promotions (5,000 random baskets: never below zero, never above a line's price, fees never discounted) and whole orders (5,000: every line and header satisfies the database's arithmetic); API tests against PostGIS (placing with the tax snapshot and its immutability, VAT and TOT sellers, numbering per legal entity, buyer fee lines and seller assessments, rule precedence, codes and their limits, time windows, the last-use race, refusals).
- Decisions while building: the **fee base is what the buyer pays for the line** (after discounts, with tax); placing **refuses** (409) a typed code that stopped applying since the quote, instead of placing at another price; items other than services, add-ons and fees (tickets, products, gift cards, memberships) are priced by the same engines, but their catalogue references arrive with Stages 4, 6 and 11.
- Left for later: the **marketplace new-client commission** (charged on the first completed visit, docs/04 §11) with Stage 4; promotion and code **management screens** and API (the tables and the pricing exist; creating offers is part of marketing, Stage 8 — until then offers are created in the database); member, follower and segment eligibility; access codes for hidden tickets (Stage 6); expiring unpaid orders (3.3).

*Done when:* property tests pass for rounding, inclusive/exclusive tax and fee caps. ✅

**3.3 Deposits and prepayment at booking.** ✅ *Done* (25 Nov 2026) in ArifPay mode, on web and in the consumer app; collection in Chapa mode waits for 3.4 (it needs the seller's subaccount, so starting a payment answers `PAYMENT_UNAVAILABLE` there).
- **Who pays what up front** (`DepositRules`, pure): the booking policy's deposit (a percentage of the all-in price, a fixed amount, or the whole price) for everyone, new customers, marketplace customers or flagged customers (two no-shows with the business); a service that cannot be paid at the venue is paid in full online whoever the customer is; the platform's buyer fees are always collected online with the deposit; the deposit never exceeds the price. The review step shows it (`payment` on the hold: a person not signed in yet counts as new; confirming decides for real). The front desk is never asked for a deposit.
- **Booking with a deposit:** confirming makes the appointment `PENDING_PAYMENT` with an order (3.2) and a payment schedule (a `DEPOSIT` online and a `BALANCE` at the venue, or one `FULL`); the time stays reserved for the payment window (`beavent.booking.payment-window`, 15 minutes) and nothing is sent to the customer until it is paid. `POST /v1/me/appointments/{id}/payment` starts or resumes the payment (an open attempt is returned as it is; after a declined one a new attempt starts; a provider that cannot be reached answers `503 PAYMENT_UNAVAILABLE` and the time stays held) and returns the provider's page; the provider sends the person back to `/{lang}/account/bookings/{id}/payment`, which asks `POST …/payment/check`.
- **The payment state machine** (`PaymentProcessor`, the only place a payment is marked paid): every transition starts from a server-side lookup with our key whose reference, amount and currency match our records; a redirect or an (unsigned) notification only says where to look. Transitions only move forward. The worker (`PaymentWorker`, every 2 s) turns stored notifications into lookups and polls open attempts on the schedule 5, 10, 15, 30, 60 s, so a missing notification (ArifPay does not retry) or an early one costs seconds. **Outcomes:** paid → attempt, intent, schedule row and order paid totals (a database trigger) → the appointment is confirmed (request-to-book: requested) and the confirmation text goes out; declined → the person tries again; an unclear create (provider unreachable) is resolved by looking the session up by our nonce; a wrong amount or reference is never acted on (the attempt waits for a person); **late payment** (after the booking expired or was cancelled) confirms it again if the time is still free (the database's exclusion constraint decides), otherwise a refund of the whole payment is recorded (`APPROVED`, to be paid by hand by finance, 3.6); **duplicate payment** (a second approval for the same intent) is recorded as `SUCCEEDED_DUPLICATE` with a `REQUESTED` refund (never counted as captured).
- **Unpaid and cancelled:** a sweep expires bookings whose payment window ended (the time goes back to the calendar, the order and intents close, the provider session is cancelled where it allows, no message is sent); the customer can cancel an unpaid booking at no cost, which closes its order and payment; waitlist offers that need a deposit land on the appointment page, which has the **Pay** button.
- **Screens:** the review step (what is paid now and what is left; **Pay deposit and book**); the **payment page** (checking, booked, declined, cancelled, still waiting, released; every state from Beavent's answer); the booking page (banner and **Pay Br 360** under it, deposit lines); in the consumer app the provider's page opens outside the app and the booking screen waits and checks every 4 s; the console calendar (unpaid bookings tinted with "Payment needed", the panel says "Deposit paid Br 360. Br 1,440 to pay at the venue." or "Waiting for the deposit… held until 10:15") and the business app's visit screen. Both languages, both themes, phone and desktop were checked on screenshots.
- **Tests:** deposit rules (unit and 5,000 random prices); 11 API tests against PostGIS and an in-process ArifPay (review terms, paid on the provider's word only, return without a notification, polling, decline and retry, duplicate, expiry then a late payment confirming again, a late payment for a time someone else took going back, cancelling, provider down, wrong amount); Playwright journeys on the web (pay on the provider's page and be confirmed, decline then retry, abandon and cancel) and in the consumer app, against the ArifPay stub notifying the running API; the existing suite passes with the seed's deposit now opt-in.
- **Decisions while building:** the seed salon's deposit is opt-in (it used to be ignored); the adapter leaves out a payer number that is not an Ethiopian mobile (ArifPay refuses it, and it is optional); an unpaid appointment reserves its time as `CONFIRMED` (the schema allows `HELD` only for holds) and the sweep releases it; the payment schedule is written in the booking's own transaction, the provider is called outside any transaction.
- **Left for later:** refunding a paid deposit when the customer cancels in time, forfeiting it on a no-show, and paying the refunds (3.6); ledger postings and seller balances (3.5; the order's paid total is already kept); the seller-readiness gate on taking online payments (3.4: until then any business can set a deposit, which is safe only in the sandbox); a promo code field on the review step (pricing supports it); the front desk cancelling an unpaid booking by hand (it expires by itself); returning to the app by link after paying (the provider's return page is on the web); everything against ArifPay's real sandbox (no keys here, D1), including its notification fields and status values.

**3.4 Seller "Get paid".**
- Business console: legal entity → bank account (name must match) → step-up OTP → ArifPay mode: payout account `VERIFIED` after the cooling-off; Chapa mode: Chapa subaccount created → online payments enabled.
- Gate: online payments only after KYB is approved and the payout account or subaccount exists. Until then, pay-at-venue only.
- Changing the bank account starts a 72 h cooling-off before payouts or routing use it, and notifies the owner.

**3.5 Ledger, seller balances and payouts (ArifPay mode).**
- Postings for capture, fees, release, payout and refunds (doc 04 §10); invariants tested.
- Payout runs per schedule; approvals for first payouts, large amounts and recent account changes; a payout batch finance pays and marks paid (step-up, audited); failures reverse the in-transit posting.
- Seller statement in the console: sales, fees, refunds, payouts, balance.

**3.6 Refunds for deposits.**
- Customer cancellation inside the free window refunds the deposit; a late cancellation or no-show forfeits it up to the policy fee; duplicate and late payments are refunded in full.
- ArifPay mode: refunds wait in the admin console's finance queue with the customer's payout details; finance pays and marks them paid. Chapa mode: refund API with status polling.

**3.7 Payment links.** Links for balances and phone bookings, sent by SMS and email.

**3.8 Gift cards and customer balance.** Sell gift cards online, deliver them (also on a schedule), redeem as a payment method; refund to customer balance as an option.

**3.9 Reconciliation and go-live certification.**
- Daily import of the provider's transactions (API where available, otherwise a statement upload in the admin console); matching by reference; exceptions queue; provider fees posted.
- Certification checklist in the sandbox → a live low-value test → capability `LIVE` with `verified_at`.

*Done when:* a full day of sandbox traffic reconciles with zero unexplained lines.

**Checkpoint B:** a customer books with a telebirr deposit through ArifPay; the seller's balance shows it, it is released after the visit and paid out in a batch; a cancellation in the free window lands in the finance refund queue and is paid; reconciliation matches. The same journey passes in Chapa mode with the split.

---

## Stage 4 — POS and in-person sales

**Goal:** the front desk can take every payment at the counter.

**4.1 POS.**
- In the business app and the business console: services, products, gift cards, tips (with staff allocation), discounts, split tender.

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
- Camera scanner in the business app (`expo-camera`, installed in this step).
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
- Projector builds `discovery.listings` for locations and events (venue pages already exist from 2.2; this makes them findable).
- Search API: full-text + trigram + map, with Amharic/Latin transliteration aliases; availability-aware "when" filter (G2) using the 2.1 engine and `next_available_at`.
- Web: home, city and category landing pages (SEO) and search with map (MapLibre GL JS). Consumer app: Explore tab (S-01, S-02) and map (S-03, `expo-maps`), installed in this step.

**8.2 Profiles and saved.**
- Organization and organizer profile pages (brand page S-18 for multi-location businesses); follow organizer, plus auto-follow after purchase.
- The **Saved** tab (DD2) in the consumer app and on web, backed by `discovery.saved_items`; Book again shelf (S-01) from appointment history.

**8.3 Reviews.**
- Verified reviews after completed visits and events; organization responses; moderation queue in the admin console.

**8.4 Notifications.** Extends the minimal messaging built in step 2.3.
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
| D1 | ArifPay merchant account and sandbox and production API keys (the default provider); Chapa business account with split enabled and test keys (the alternative) | Founders/finance | Stage 3 (start early; the stubs and ArifPay's public sandbox cover development meanwhile) |
| D2 | Provider terms in writing. ArifPay: status values, notification signing and retries, settlement timing, fees and who pays them, session limits, refunds (none by API), split support, wallet-to-bank payout access. Chapa: fee payer, subaccount settlement timing, refunds on split transactions, chargebacks, float requirement | Finance + provider | Stage 3 go-live certification (3.9) |
| D3 | Legal confirmation for the money model: **ArifPay mode has Beavent collect customer money and pay sellers (`PLATFORM_COLLECTS`), which NBE rules may treat as a licensed activity**; Chapa mode's split; consumer and partner terms (am/en) | Counsel | **Before ArifPay mode takes real money** (Stage 3 certification); terms by Stage 9 |
| D4 | SMS gateway for OTP and notifications; sender ID registration | Ops | Only the `log` provider exists today. Needed before Stage 2 acceptance with real phones (2.3 sends confirmations and reminders), and before any pilot |
| D5 | Hosting provider and region for **staging** | Founders + engineering | Stage 3 (public webhook URL) |
| D6 | Production hosting, backups/DR, data-protection review | Founders + counsel | Stage 9 |
| D7 | Tax rates and regimes verified (VAT/TOT); receipt rules; whether pilot sellers need MoR e-invoicing | Tax adviser | Stage 9 |
| D8 | Fee rates and plan prices for Ethiopia | Commercial | Stage 3 (configuration), final before Stage 9 |
| D9 | Wireframes and visual design for the pilot journeys | Designer | Stage 0, step 0.11, then ahead of each stage's UI. Until a designer is engaged the screen specs in `design/beavent/` (Fresha- and Eventbrite-benchmarked) are the reference; each UI slice is reviewed on rendered screenshots |
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
| Public booking abuse (bots holding slots, fake bookings) | Lost sales, annoyed businesses | Short holds, per-IP and per-phone limits, a cap on live holds per person, phone verification before a booking is confirmed, business can block a customer |
| Realtime fan-out (calendar, payment status, check-in) | Stale calendars, load spikes | One platform channel with Redis fan-out, refetch-on-notify (small messages), polling fallback, load test in 9.1 |
| Amharic and Afaan Oromo copy quality | Mistrust of messages and screens | Native-speaking editor review before pilot; templates kept in `@beavent/i18n` with fallbacks |
