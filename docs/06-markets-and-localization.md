# 06 — Markets, localization and compliance

**Audience:** engineering leads, product, and the operations / legal / finance people who activate each market.
**Scope:** how Beavent launches in Ethiopia, expands across East Africa and then Africa, and what must be true — in data and in law — before a market goes live.
**Schema references:** `db/migration/V1_01__reference_and_markets.sql`, `V1_21__seed_reference_data.sql`. Payments detail lives in [04-payments-and-money.md](04-payments-and-money.md) and is not repeated here.

> **Verification legend.** ⚠️ **VERIFY** marks a legal, tax, regulatory or provider fact that comes from desk research and must be confirmed by a local adviser or a live provider test before it drives behaviour in production. The schema enforces this for the riskiest facts: tax rates are seeded `is_verified = false`, and payment capabilities cannot be `LIVE` without `verified_at`.

---

## 1. Model: countries, markets, features

| Concept | Table | Meaning |
|---|---|---|
| Country | `ref.countries` | Geography + conventions: calling code, default currency/language/time zone, `display_calendar`, `local_time_convention`, `address_format`, `uses_postal_codes`. Existing does **not** mean enabled. |
| Market | `ref.markets` | Commercial activation of one country. `status`: `PLANNED → PILOT → LIVE` (`PAUSED`, `RETIRED`). Holds `prices_include_tax`, `support_contacts`, `settings`. |
| Market currencies | `ref.market_currencies` | `can_price` (sellers may price in it), `can_settle` (payouts), `can_present` (buyers may be charged in it). ET seeds ETB (all three) and **USD present-only** for diaspora buyers. |
| Market languages | `ref.market_languages` | Enabled UI/content languages and the default; `BETA` for languages whose translations are not yet reviewed. |
| Market features | `ref.market_features` | Per-market switches (`DISABLED` / `BETA` / `ENABLED`) such as `AT_HOME_SERVICES`, `RESERVED_SEATING`, `PLATFORM_COLLECTION`, `PROVIDER_SPLIT`, `SELLER_DIRECT`, `PAY_AT_VENUE`, `MANUAL_BANK_TRANSFER`, `DIASPORA_PRESENTMENT`, `INSTANT_PAYOUT`, `AI_CONCIERGE`, `TELEGRAM_CHANNEL`, `WHATSAPP_CHANNEL`. |

Seeded state: **ET = `PILOT`**; KE, UG, TZ, RW = `PLANNED`. Other African countries exist as `ref.countries` rows only.

Ethiopia's seeded feature switches: appointments, events, at-home services, **provider split (Chapa)**, pay-at-venue and Telegram are `ENABLED`. Reserved seating, AI concierge and WhatsApp are `BETA`. Seller-direct collection, manual bank transfer, platform collection, diaspora presentment and instant payout are `DISABLED` (Chapa holds the money and is the only provider for now; see doc 07 §1).

Rule for engineers: **no code branches on country codes.** Behaviour comes from these rows, `ref.countries` conventions, payment routes/capabilities, tax rates and fiscal schemes. A new market is a data change plus adapters, not a fork of the product.

---

## 2. Ethiopia launch specifics

### 2.1 Calendar and time

**Storage is always UTC `timestamptz` + an IANA zone (`Africa/Addis_Ababa`, UTC+3, no DST).** Calendars and clock conventions are presentation only.

**Ethiopian calendar** (`ref.countries.display_calendar = 'ETHIOPIAN'`; user override `iam.users.display_calendar`):
- 12 months of 30 days, then **Pagume** with 5 days (6 in the Ethiopian leap year, the year before a Gregorian leap year).
- New year, **Meskerem 1**, falls on 11 September (12 September before a Gregorian leap year). The year number is 7 years behind Gregorian from Meskerem 1 to 31 December, and 8 years behind from 1 January.
- UI shows both, Ethiopian first for `am`/`ti` users, e.g. `ታኅሣሥ 6, 2019 ዓ.ም. (15 Dec 2026)`. Date pickers offer either calendar. The value sent to the API is always an ISO-8601 instant or date.
- Business documents (receipts, invoices) print the calendar the seller's legal entity chooses. Fiscal submissions use whatever the tax authority's schema requires (⚠️ VERIFY in the MoR e-invoicing spec).
- Conversion lives in one shared package, `@beavent/calendar`, for both web and mobile, plus a Java twin for PDFs and messages. Property-test both against each other over 1900–2100. Never hand-roll a conversion in a screen.

**Ethiopian local time** (`local_time_convention = 'ETHIOPIAN'`): hours are counted from 06:00, so 07:00 is "1 o'clock in the morning" (`ጠዋት 1:00`) and 18:00 is `ማታ 12:00`. Reminder SMS, appointment cards and tickets show both, e.g. `ጠዋት 3:00 (9:00 AM)`. This avoids the most common no-show cause: a customer reading 3:00 as 3 AM/PM in the other convention. Partner calendars use the 24-hour international clock, with the local reading on hover.

**Public holidays** (`ref.public_holidays`): fixed-date holidays (Genna, Timket, Adwa, Labour Day, Patriots' Victory Day, Derg downfall, Enkutatash, Meskel) plus movable ones: Fasika and Siklet follow the Orthodox computus; Eid al-Fitr, Eid al-Adha and Mawlid follow the lunar calendar. Seed each year ahead of time, with `is_estimated = true` for lunar dates until the official announcement, then update. Holidays drive default closures, availability warnings and payout-day shifts. They do not close businesses automatically.

### 2.2 Languages, scripts and text

| Language | Code | Script | Seeded status (ET) |
|---|---|---|---|
| Amharic | `am` | Ge'ez / Ethiopic (`Ethi`) | default, ENABLED |
| English | `en` | Latin | ENABLED |
| Afaan Oromo | `om` | Latin (Qubee) | ENABLED |
| Tigrinya | `ti` | Ge'ez | BETA |
| Somali | `so` | Latin | BETA |
| Afar | `aa` | Latin | country language only |

- **Fonts:** ship an Ethiopic-capable font (e.g. Noto Sans Ethiopic / Noto Serif Ethiopic) in both apps. Low-end Android system fonts render Ge'ez inconsistently. Subset per screen on web, and check line height: Ge'ez glyphs need more vertical room than Latin at the same size.
- **Input:** customers type Amharic through phonetic Ge'ez keyboards. Search must therefore tolerate Latin transliteration ("Bole", "ቦሌ") and mixed scripts (see §5.5).
- **Numbers:** Western Arabic digits everywhere. Ge'ez numerals are not used for prices or times. Currency is shown as `Br 1,500.00` or `1,500.00 ብር` per locale through ICU.
- **Plurals and gender:** ICU MessageFormat. Never concatenate strings. Amharic verbs inflect for gender, so messages addressed to a person need a gender-neutral form or an ICU `select` on the recipient's stated gender (which is optional in `iam.user_profiles`).
- **Names:** Ethiopians use given name + father's name (+ grandfather's), with no inherited family surname. Store `first_name` = given name and `last_name` = father's name. Address people and sort lists by **given name** ("W/ro Selam", not "Ms Tadesse"). Do not require a "surname" field, and do not de-duplicate customers by last name.

### 2.3 Identity, phones and addresses

- **Phone-first identity.** `iam.users.phone_e164` is the primary login and OTP channel; email is optional. Mobile numbers are `+251 9…` (Ethio telecom) and `+251 7…` (Safaricom Ethiopia), 9 digits after the country code. Validate with libphonenumber metadata, not regexes in the UI. ⚠️ VERIFY numbering ranges yearly.
- **OTP delivery fallback:** SMS → Telegram → voice (`iam.auth_challenges.channel`). The SMS sender ID must be registered with the operator / gateway before launch (§4, gate 7).
- **Addresses are landmark-first** (`address_format` for ET requires `admin_area` + `landmark`; `uses_postal_codes = false`). Capture: administrative area → free-text line → **landmark** ("behind Edna Mall, 2nd gate") → map pin (`geo`). At-home services rely on pin + landmark + instructions. Customer addresses are snapshotted onto the appointment at booking.
- **Administrative hierarchy** (`ref.administrative_areas`, `ltree` paths): 12 regional states + 2 chartered cities (Addis Ababa, Dire Dawa) at level 1. Addis Ababa's 11 sub-cities are seeded at level 2. Zones, woredas and kebeles are loaded from an official source before at-home services expand outside Addis (⚠️ VERIFY source and licence; regional boundaries changed in 2020–2023).
- **KYC / KYB** (`trust.verification_cases`): provider `FAYDA` (Ethiopia's national digital ID, eKYC for owners/individuals), `MANUAL` (trade licence, TIN certificate, VAT certificate, kebele ID), `BANK_NAME_ENQUIRY` (payout account holder name). `SMILE_ID` is the pan-African fallback for later markets. Fayda integration terms and API access must be agreed with the National ID Program (⚠️ VERIFY).

### 2.4 Messaging channels

- **Telegram is the dominant messaging app in Ethiopia.** Treat it as a first-class channel (`TELEGRAM_CHANNEL` ENABLED): a platform bot for OTP fallback, confirmations, tickets (QR image + serial) and reminders, plus optional Telegram-bridged customer↔business chat (`comms.conversations.origin_channel = 'TELEGRAM'`). Users opt in by starting the bot. Store the link as an `iam.user_identities` `TELEGRAM` row.
- **SMS** is the universal fallback, including for feature phones. Tickets degrade to an SMS carrying the ticket serial (`events.tickets.serial`), which door staff look up manually.
- **WhatsApp** is `BETA` in ET and becomes primary in KE/NG/GH/ZA. Business templates must be pre-approved (`comms.templates.provider_template_ref`).
- **Marketing consent** is per channel (`crm.customer_consents`). Honour STOP keywords (`comms.suppressions.reason = 'STOP_KEYWORD'`).

### 2.5 Connectivity and devices

Design for intermittent 3G/4G, prepaid data budgets and low-end Android (2–3 GB RAM):
- **Offline-first tickets:** the mobile app stores issued tickets (QR secret, serial, event details) encrypted on-device, and they render without a network. Door scanners run offline from a signed manifest (`events.checkin_devices.offline_enabled`, `manifest_version`) and sync scans idempotently (`events.scans.client_scan_id`).
- **Budgets:** Android app download ≤ 25 MB; marketplace listing page ≤ 300 KB on first load; images served as WebP/AVIF through the CDN at device-appropriate sizes; no autoplay video on cellular.
- **Long payment confirmations:** a mobile-money push or bank transfer can take minutes. Holds (`scheduling.holds`, inventory `RESERVE`) must outlast that window, and the UI must survive the app being backgrounded mid-payment.

### 2.6 Money culture, tax and regulation

**Cash and bank transfers are normal.** `PAY_AT_VENUE` is enabled. `MANUAL_BANK_TRANSFER` is built into the model but disabled while Chapa is the only provider; when enabled, the customer transfers, uploads proof, and the seller or finance team verifies it against the statement (`payments.payment_attempts.interaction = 'MANUAL_REFERENCE'`, status `AWAITING_VERIFICATION`). Deposits replace card-on-file no-show protection, which barely exists here.

**Tax (all ⚠️ VERIFY with a local tax adviser before LIVE; seeded `is_verified = false`):**
- VAT 15% for VAT-registered sellers (VAT Proclamation No. 1341/2024).
- Turnover Tax (TOT) for smaller non-VAT sellers: seeded at 2% goods / 10% services under Proclamation No. 308/2002. **Confirm whether TOT continues and in what form after the 2024 VAT reform.**
- The seller's regime lives on `tenant.legal_entities.tax_regime` (`VAT`/`TOT`/…). Order lines keep immutable tax snapshots, and prices are shown tax-inclusive (`ref.markets.prices_include_tax = true`).
- Gift cards are multi-purpose vouchers, taxed at redemption rather than at sale (`VOUCHER_MULTI_PURPOSE`). Donations sit outside VAT scope.
- The platform's own fees to sellers carry VAT (`PLATFORM_FEE`; `billing.fee_rules.vat_applies`).

**E-invoicing:** the Ministry of Revenue's Electronic Invoicing System Administration **Directive No. 1142/2026** sets up a clearance model. Invoices are registered centrally, carry a registration number and QR, sales-register software needs accreditation, and the Authority publishes the rollout schedule ([vatupdate](https://www.vatupdate.com/2026/09/03/ethiopia-establishes-legal-and-operational-framework-for-electronic-invoicing/), [lookuptax](https://lookuptax.com/tax-changes/ethiopia/electronic-invoicing-directive-1142-2026), [EDICOM](https://edicomgroup.com/blog/mandatory-electronic-invoicing-ethiopia)). Modelled as `fiscal.schemes` `ET_MOR_EINVOICE` (status `PLANNED`). ⚠️ VERIFY which taxpayers are in scope, the deadlines, the technical spec, and whether Beavent must be accredited itself or integrates with accredited systems.

**Payments regulation (NBE)** — context only; design in doc 04:
- Payment instrument issuers and payment system operators are licensed by the National Bank of Ethiopia. Gateways such as Chapa and SantimPay operate under NBE licences ([Shega](https://shega.co/news/chapa-officially-launches-payment-gateway-service), [Shega — SantimPay](https://mediav3-dev.shega.co/news/santimpay-officially-joins-fintech-scene-with-three-products)).
- The 2025 amendment (reported as **ONPS/10/2025**, May 2025) adds shareholder caps (40% for persons other than government, telecoms or payment licensees; 60% single-person cap). It also requires wallet-to-wallet interoperability through the national switch, plus six-monthly security audits ([Addis Insight](https://addisinsight.net/2025/05/27/national-bank-of-ethiopia-issues-new-directive-to-strengthen-digital-payment-ecosystem/), [NBE](https://nbe.gov.et/nbe_news/the-national-bank-of-ethiopia-has-issued-a-revised-directive-for-mobile-money-providers-to-promote-safety-competition-and-innovation/)). ⚠️ VERIFY dates and applicability against the official text.
- **Consequence for the design:** Beavent starts in `PROVIDER_SPLIT` mode with Chapa (decided), with funds collected by Chapa and settled to sellers' bank accounts. `PLATFORM_COLLECTS` (the platform holding customer funds) stays `DISABLED` until counsel confirms a licensed structure. Platform-wide stored value (a cross-merchant wallet) is out of scope, because it would be e-money. Gift cards and balances are scoped to one organization.

**Foreign exchange and diaspora:** diaspora relatives buying tickets or gift cards for family in Ethiopia is a real use case. Sellers are priced and settled only in ETB. USD is `can_present = true` only, so a buyer may be charged in USD by a licensed provider, with an FX snapshot (`payments.payment_intents.presentment_*`, `fx_rate_id`), while the seller receives ETB. `DIASPORA_PRESENTMENT` stays `DISABLED` until a provider and counsel confirm the flow complies with NBE foreign-exchange rules, including the post-2024 market-based exchange regime. ⚠️ VERIFY.

**Data protection:** Personal Data Protection Proclamation **No. 1321/2024**. ⚠️ VERIFY the supervisory authority, registration duties, cross-border transfer conditions, breach-notification deadlines and data-subject-request timelines. Requests are tracked in `platform.data_subject_requests` with a `due_at` derived from the confirmed deadline.

---

## 3. Expansion playbook: East Africa → Africa

Order: **ET (pilot) → KE → UG / TZ / RW → NG, GH, ZA, EG → francophone West Africa (SN, CI).** Pick each next market by partner demand (organizers touring the region, salon chains) and provider coverage. Pan-African aggregators shorten the path: pawaPay reports mobile-money coverage in 19 countries, including KE, UG, TZ and RW ([pawaPay](https://pawapay.io/index.html)), and Flutterwave documents mobile-money collections in KE, UG, TZ, RW and more ([Flutterwave docs](https://developer.flutterwave.com/v3.0/docs/mobile-money-1)).

**Every cell below except currency codes and minor units needs verification before activation.**

| Market | Currency (minor units) | Languages | Dominant rails | Candidate providers | Fiscal scheme (`fiscal.schemes`) | Data protection | Notable differences |
|---|---|---|---|---|---|---|---|
| **KE** Kenya | KES (2) | en, sw | M-PESA (STK push), Airtel Money, cards | M-PESA Daraja, pawaPay, Flutterwave, Paystack | `KE_KRA_ETIMS` — mandatory for VAT and non-VAT businesses ⚠️ | Data Protection Act 2019 (ODPC registration) | WhatsApp > Telegram; refunds via B2C payouts; postal codes used; VAT 16% ⚠️ |
| **UG** Uganda | **UGX (0)** | en, sw, lg | MTN MoMo, Airtel Money | pawaPay, Flutterwave, MTN MoMo API | `UG_URA_EFRIS` ⚠️ | Data Protection and Privacy Act 2019 | Zero-decimal currency; Paystack coverage weak ([Afriex comparison](https://dev.to/afriex/afriex-vs-flutterwave-vs-paystack-which-one-should-you-use-for-payouts-and-disbursements-20jc)) ⚠️; VAT 18% ⚠️ |
| **TZ** Tanzania | TZS (2; cents unused in practice) | sw, en | M-Pesa (Vodacom), Mixx by Yas, Airtel Money, HaloPesa | pawaPay, Flutterwave, local aggregators ⚠️ | `TZ_TRA_VFD` (VFD/EFD devices) ⚠️ | Personal Data Protection Act 2022 ⚠️ | Swahili-first UI; whole-shilling cash rounding; VAT 18% ⚠️ |
| **RW** Rwanda | **RWF (0)** | rw, en, fr, sw | MTN MoMo, Airtel Money | pawaPay, Flutterwave, IremboPay ⚠️ | `RW_RRA_EBM` ⚠️ | Law No. 058/2021 on personal data ⚠️ | Trilingual UI; strict data-localisation conditions possible ⚠️; VAT 18% ⚠️ |
| **NG** Nigeria | NGN (2) | en, ha, yo | Cards, bank transfer (NIP), USSD | Paystack, Flutterwave, Moniepoint ⚠️ | FIRS e-invoicing (rolling out) ⚠️ | NDPA 2023 | Card-heavy and fraud-heavy (stronger risk defaults); VAT 7.5% ⚠️ |
| **GH** Ghana | GHS (2) | en | MTN MoMo, Telecel Cash, AT Money, cards | Paystack, Flutterwave, Hubtel ⚠️ | GRA E-VAT ⚠️ | Data Protection Act 2012 | VAT plus statutory levies, reformed 2025 ⚠️ |
| **ZA** South Africa | ZAR (2) | en, zu (+ others) | Cards, EFT / instant EFT, QR wallets | Paystack, Peach Payments, Yoco ⚠️ | No clearance e-invoicing today ⚠️ | POPIA 2013 | Card-on-file no-show fees viable; postal addresses; VAT 15% ⚠️ |
| **EG** Egypt | EGP (2) | **ar (RTL)**, en | Cards (incl. Meeza), InstaPay, wallets (e.g. Vodafone Cash) | Paymob, Fawry ⚠️ | ETA e-invoice + e-receipt ⚠️ | Law 151/2020 (PDPL) ⚠️ | First RTL market; Arabic numerals choice; VAT 14% ⚠️ |
| **SN / CI** | **XOF (0)** | fr (+ wo, local) | Orange Money, Wave, MTN MoMo (CI), Moov | Wave, CinetPay, PayDunya, pawaPay ⚠️ | CI e-invoicing (FNE) / SN ⚠️ | SN Law 2008-12; CI Law 2013-450 ⚠️ | Shared currency across UEMOA (one settlement currency, many countries); VAT 18% ⚠️ |

How each column maps to configuration:
- **Currency / minor units:** `ref.currencies.minor_units` (UGX, RWF, XOF, XAF, BIF, DJF = 0). Every amount is `BIGINT` minor units, so a zero-decimal currency needs no code change, but UI input masks and provider adapters must respect it (Chapa-style "amount with decimals" APIs vs integer APIs).
- **Rails / providers:** rows in `payments.providers`, `payments.provider_capabilities` (seeded `PLANNED`), `payments.routes`. The router picks per (market, currency, method, channel) with failover. See doc 04.
- **Fiscal:** `fiscal.schemes` + adapter; per seller, `fiscal.registrations` (device / branch id).
- **Languages:** `ref.country_languages`, `ref.market_languages`, translation bundles.

---

## 4. Market activation checklist (gates)

A market moves `PLANNED → PILOT` when gates 1–10 pass for a closed group of partners. It moves `PILOT → LIVE` when all gates pass and the sign-off owners below approve. Each gate maps to concrete rows the admin console checks; the market-readiness screen computes them.

| # | Gate | Evidence in the system | Owner |
|---|---|---|---|
| 1 | **Legal presence & licences**: operating entity, business licence, payments structure confirmed (no `PLATFORM_COLLECTION` without a licensed arrangement) | Counsel memo attached to the market record; `ref.market_features` for `PLATFORM_COLLECTION`/`PROVIDER_SPLIT`/`SELLER_DIRECT` set to match | Legal |
| 2 | **Terms per language**: consumer terms, partner terms, privacy notice, refund policy, merchant agreement, DPA | `ref.legal_documents` (`market_id`) + `ref.legal_document_versions` for each enabled language, `published_at` set | Legal |
| 3 | **Tax rates verified** for every seller regime and tax category in use | All `ref.tax_rates` for the country have `is_verified = true` and a precise `source_reference`; `ref.markets.prices_include_tax` confirmed | Finance + tax adviser |
| 4 | **Fiscal scheme**: sandbox certified → live | `fiscal.schemes.status = 'LIVE'`; a pilot seller's `fiscal.registrations` row `ACTIVE`; accepted `fiscal.documents` for sale, refund and credit note | Finance + Eng |
| 5 | **Payment capabilities proven live**: collect, refund (API or refund-by-payout), payout, webhook + polling, duplicate-success handling | `payments.provider_capabilities.status = 'LIVE'` (DB requires `verified_at`); test evidence per capability | Payments Eng |
| 6 | **Routes with failover** for every enabled method and channel | ≥ 1 active `payments.routes` row per (method, channel), and a second account for the top method; health checks reporting into `payments.provider_accounts.health` | Payments Eng |
| 7 | **Messaging senders approved**: SMS sender ID, WhatsApp business number + templates, Telegram bot, email domain (SPF/DKIM/DMARC) | `comms.provider_accounts` `ACTIVE` per channel for the market; `comms.templates` in each enabled language, WhatsApp `provider_template_ref` approved | Ops |
| 8 | **Support**: hours, phone, email, Telegram/WhatsApp handle, escalation; staff with market-scoped roles | `ref.markets.support_contacts` filled; `iam.user_staff_roles` with `market_id` for support, finance, risk | Ops |
| 9 | **Geography & calendar**: administrative areas loaded to the depth used for search and at-home services; this year's and next year's holidays seeded | `ref.administrative_areas` counts per level; `ref.public_holidays` rows (lunar ones `is_estimated`) | Ops data |
| 10 | **Translations reviewed** by native speakers for every `ENABLED` language (UI bundles, templates, legal) | `ref.market_languages.status = 'ENABLED'` only after review; others stay `BETA` | Product |
| 11 | **Risk defaults**: payout delay, reserves, pre-event payout share, manual review thresholds for new sellers | Default `trust.risk_profiles` values for the market (seed `tier = 'NEW'`, `payout_delay_days`, `reserve_bps`, `pre_event_payout_bps`) | Risk |
| 12 | **Commercials**: plan prices and fee rules | `billing.plan_prices` per plan in the market currency; `billing.fee_rules` for `MARKETPLACE_NEW_CLIENT`, `TICKET_SERVICE_FEE`, `PAYMENT_PROCESSING`, `PAYOUT_FEE`, messaging (exclusion constraint prevents overlaps) | Finance + Product |
| 13 | **Data protection**: registration with the authority if required, residency decision (§6), DSR deadlines configured | Registration reference on the market record; hosting decision recorded | Legal + Eng |
| 14 | **Reconciliation dry run**: one full cycle of statement import and matching for each live provider account | `payments.provider_statements` reconciled with zero unexplained `statement_lines` | Finance |
| 15 | **Go-live** | `ref.markets.status = 'LIVE'`, `launched_at` set; flags flipped in `ref.market_features` | Exec sponsor |

Pausing a market (`PAUSED`) hides new listings and stops new checkouts in that market, while honouring existing appointments, tickets, refunds and payouts.

---

## 5. Localization engineering

### 5.1 Text and content
- **UI strings:** ICU MessageFormat bundles in `@beavent/i18n`, shared by mobile, web marketplace, partner portal and admin console. CI fails on a missing key in any `ENABLED` language, and falls back to English (with a warning) for `BETA` languages.
- **User-generated content:** `*_i18n jsonb` columns, e.g. `{"am": "...", "en": "..."}` on services, categories, plans, rewards, collections. Events use `events.event_translations` rows with `is_machine_translated`, so machine translation is labelled in the UI and never overwrites an organizer's own text.
- **Fallback chain**, everywhere: user's `preferred_language` → content's source / organization default → market default (`ref.market_languages.is_default`) → `en`. The API returns the resolved language with each field, so clients can show "translated" badges.
- **Notifications** resolve the template by (org override → platform default) × channel × language (`comms.templates`). Partners can override wording but not the variables or legal footers.

### 5.2 Right-to-left readiness (Arabic markets: EG, MA)
Use logical CSS properties (`margin-inline-start`) and `I18nManager` on React Native from day one, plus bidi isolation for mixed Arabic/Latin names and numbers and mirrored icons where meaning depends on direction. `ref.languages.direction` drives the layout. Arabic gets no extra cost until Egypt, as long as the rule is kept from the start.

### 5.3 Money formatting
- Amounts arrive as `{amount_minor, currency_code}`. Clients format with `ref.currencies.minor_units` and ICU currency patterns, and never parse floats. Input fields accept major units and convert exactly.
- **Cash rounding** at POS and box office uses `ref.currencies.cash_rounding_minor` (seeded e.g. KES 100 = KSh 1, UGX 50; ⚠️ VERIFY each market's smallest circulating coin). Rounding is recorded as an explicit adjustment, never by silently changing line prices.
- Prices are shown tax-inclusive where `prices_include_tax`; receipts break the tax out from `commerce.order_line_taxes`.

### 5.4 Time
Time zones are per location and per occurrence (IANA), and availability is computed in the location's zone. All African zones in scope currently have no DST, but the code must not assume that (Morocco, Egypt). Calendar and local-clock display come from `@beavent/calendar` (§2.1), which is test-covered for Pagume, leap years and the 11/12 September new-year shift.

### 5.5 Search
- The marketplace read model `discovery.listings.search_document` uses PostgreSQL's `simple` text-search configuration: no stemming, which suits Ge'ez and mixed scripts. Add `pg_trgm` trigram indexes on titles for fuzzy and partial matches.
- **Transliteration aliases:** the projector adds Latin transliterations of Ge'ez names and Ge'ez forms of common Latin terms (e.g. "Bole ↔ ቦሌ", "salon ↔ ሳሎን") to the document. A curated synonym list lives in `platform.settings`.
- Ge'ez has character variants that sound the same (e.g. ሀ/ሐ/ኀ, ሰ/ሠ, አ/ዐ, ጸ/ፀ). Normalise them to one form in both the indexed document and the query. Keep the original for display.
- Move to OpenSearch / Meilisearch only when measured latency or relevance requires it. The read model makes that a projector change, not a domain change.

### 5.6 Validation per country
- **Phones:** libphonenumber, using the country from `ref.countries.calling_code` and the user's or market's country. E.164 is the stored form.
- **Names:** no forced surname (Ethiopia, §2.2); allow long, multi-part names and any script.
- **Addresses:** `ref.countries.address_format` declares field order and required fields; postal code is shown only where `uses_postal_codes`.
- **Tax IDs:** TIN / VAT formats are validated per country by a pluggable validator (check-digit rules ⚠️ VERIFY), never by hard-coded regexes in clients.

---

## 6. Data residency and hosting (decision required)

The schema is region-agnostic. Where it runs is a business and legal decision, to be recorded per market at gate 13.

| Option | Description | Pros | Cons |
|---|---|---|---|
| **A. Single regional cloud** (recommended for pilot) | One primary region with the lowest latency to Addis and Nairobi and a mature managed PostgreSQL offering (e.g. a Middle East, South Africa or Europe region), plus a CDN for static assets and media, and a read replica + PITR in a second region | Simplest operations, one database, cheapest; CDN hides most latency for public pages | Possible conflict with any local-residency rule; ~60–150 ms API latency to East Africa |
| **B. Regional primary + in-country processing where required** | Keep A, and add in-country components only for data a law requires to stay local (e.g. specific financial or identity records) | Satisfies targeted rules without splitting the product | Added integration complexity; per-country runbooks |
| **C. Per-country deployment (cells)** | A full stack per country, with cross-market features via APIs | Strongest residency story | Multiplies cost and operations; breaks cross-market accounts unless identity is federated; too early for the pilot |

Decision inputs (⚠️ VERIFY per market): Ethiopia's PDPP cross-border transfer rules and any NBE expectations for payment data; Kenya's Data Protection (General) Regulations on processing tied to strategic interests; Rwanda's data-localisation provisions; Nigeria's NDPA transfer rules. Recommendation: start with **A** and design for **B**. Every tenant row already carries `org_id`, and each organization a `home_market_id`, so a later per-market extraction is a data-movement project, not a redesign. Payment card data never touches Beavent servers (provider tokens only), which keeps PCI scope minimal in every option.
