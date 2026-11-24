# 02 — Product scope and feature parity (Fresha + Eventbrite → Beavent)

**Audience:** product managers and engineering leads.
**Purpose:** define what Beavent must do, prove it covers what Fresha and Eventbrite offer in 2026, show where the unified design is deliberately better, and record what is out of scope and why.

Every row maps a feature to the **schema module** that implements it (`db/migration/V1_*.sql`) and to the **app surface(s)**:

| Code | Surface |
|---|---|
| **APP** | Mobile apps (Expo): `public-mobile` for customers; *Business mode* = the `business-mobile` app for owners, staff and organizers |
| **WEB** | Web marketplace (Next.js): discovery, booking, checkout, account, embeds, mini-sites |
| **PP** | Partner portal (Next.js): one workspace for salons, spas, freelancers and event organizers |
| **ADM** | Admin console (Next.js, internal) |
| **API** | `beavent-api` only: background job, adapter or public partner API |

**Phases:** **P0** Foundation · **P1** Ethiopia launch (Addis Ababa) · **P2** Growth · **P3** East Africa (Kenya, then Uganda, Tanzania, Rwanda) · **P4** Pan-Africa. Phase scope is defined in [03-system-architecture.md](03-system-architecture.md).

---

## 1. The two reference platforms

### Fresha: booking and business software for beauty and wellness

- **Users.** Consumers book salons, barbers, spas and clinics through the Fresha marketplace app and web. Businesses run their whole operation in Fresha for Business (calendar, POS, team, inventory, marketing). Independents and teams are both served.
- **Business model** ([fresha.com/pricing](https://www.fresha.com/pricing)):
  - Subscription: *Independent* is a flat monthly fee for one team member; *Team* is charged per bookable team member; *Enterprise* is custom.
  - Marketplace: a **20% one-time commission on new clients** acquired through the marketplace, with a per-client minimum. Returning clients and clients from direct, Facebook or Instagram bookings carry no fee.
  - Optional Fresha payments, with processing fees for online, in-person, Tap to Pay and Klarna BNPL. Card terminals are rented or bought.
  - Paid add-ons: Client Loyalty, Insights, Google Rating Boost, Smart Website, Team Connect, Xero, Data Connector, and AI Concierge (messaging, or calls plus messaging).
  - Per-message fees for marketing email, SMS and WhatsApp beyond the free allowance.
- **2026 roadmap** ([fresha.com/blog/Up-Next-2026](https://www.fresha.com/blog/Up-Next-2026), all *planned*, none confirmed shipped): two-way messaging across SMS, email and WhatsApp; advanced memberships; Fresha Capital funding; AI receptionist; new calendar views; one-time packages; multi-resource services; AI blast messaging with segmentation; venue portfolios; custom client labels; expense tracking; granular permissions; target tracking; quotes and invoices; richer client records; virtual queue and self check-in.
- Third-party reviews ([GlossGenius](https://glossgenius.com/blog/fresha-pricing), [Pabau](https://pabau.com/blog/fresha-pricing/), both competitors) say that new-client fees, per-text charges and add-ons raise Fresha's real cost. Treat these as commercial claims.

### Eventbrite: self-service ticketing and event marketing

- **Users.** Attendees discover and buy tickets on web and app. Organizers range from free meetups to large paid events, and use the organizer web dashboard plus the Organizer app for check-in and on-site sales.
- **Business model:**
  - Free events carry no ticket fees.
  - Paid tickets carry a per-ticket service fee plus payment processing. The organizer either passes fees to the buyer or absorbs them.
  - Paid organizer plans add marketing tools and support.
  - **Eventbrite Ads** sells promoted placement on the marketplace.
- **Feature list** ([eventbrite.com/organizer/features/all-features](https://www.eventbrite.com/organizer/features/all-features/)): reserved seating, custom and PDF tickets, early-bird and group tickets, promo codes, merchandise add-ons, embedded checkout, custom order forms, Ads, email marketing, audience targeting, Collections, Facebook/Instagram/TikTok selling, tracking pixels and links, timed entry, the Organizer check-in app, Tap to Pay, instant payouts, Zoom/YouTube/Facebook Live integration, branded confirmation emails, and AI copy tools.
- **2026 roadmap** ([eventbrite.com/product-updates/roadmap-2026](https://www.eventbrite.com/product-updates/roadmap-2026)).
  - *Shipped:* time- or sales-based automatic price changes; move a whole order to a new date; cross-event sales view; branded and post-event emails; automatic emails on date or location change; live queue position, waiting rooms and a bot check for high-demand on-sales; scheduled sold-out notifications; auto-follow organizers after purchase; ad sales attribution; add-ons as their own checkout step.
  - *Planned:* SMS campaigns, automated waitlist offers, anti-scalping tools, linked tickets and add-ons, rebuilt reserved seating, redesigned back office.
  - The roadmap also mentions bulk attendee transfers and timed-entry support for up to 2,000 active slots.

### The opportunity

The two platforms share most of their machinery: discovery, capacity-limited sellable time, checkout, payments, payouts, CRM, messaging, reviews and promotion. In practice their customers overlap too: spas run retreats, gyms run classes, salons run masterclasses, and event venues sell services. Beavent builds that machinery once and expresses both products as **capabilities of one organization** (`tenant.organization_capabilities`). It is built Africa-first: mobile money, phone-first identity, landmark addresses, local calendars and fiscal rules are part of the core design, not added later.

---

## 2. Unified feature matrix

✅ = has it · 🟡 = partial or roadmap · — = absent. Fresha and Eventbrite columns reflect the public sources above.

### 2.1 Discovery and marketplace

| Feature | Fresha | Eventbrite | Beavent design (module → surfaces) | Phase |
|---|---|---|---|---|
| Search places to book and things to do in one feed | Places only | Events only | `discovery.listings` holds both kinds, with geo, category, price-from, rating, next slot or next start → WEB, APP | P1 |
| Map and "near me" search | ✅ | ✅ | PostGIS `geo` plus admin-area `ltree` (sub-city, woreda) → WEB, APP | P1 |
| Category taxonomy | ✅ | ✅ | One tree, `ref.categories` (kinds BUSINESS, SERVICE, EVENT, PRODUCT) | P0 |
| Filters: price, date, audience (women/men/kids), at-home, language spoken, accepts mobile money | 🟡 | 🟡 | `listings.attributes` jsonb, `catalog.services.audience`, `team.staff.languages` | P1 |
| Rich business/organizer profile page | ✅ | ✅ | `tenant.organizations`, `catalog.gallery_items`, `discovery.reviews` → WEB, APP | P1 |
| Collections (curated lists) | — | ✅ | `discovery.collections` / `collection_items`, owned by the platform, an organization or a user → ADM, WEB | P2 |
| Save or favourite places, events and professionals | ✅ | ✅ | `discovery.saved_items` (listing or staff) → APP, WEB | P1 |
| Follow an organizer; auto-follow after purchase | — | ✅ | `discovery.follows.source` = MANUAL / AFTER_PURCHASE / AFTER_VISIT | P1 |
| Personalized recommendations | 🟡 | 🟡 | Ranks from `discovery.search_events`, follows and purchase history; `listings.quality_score` | P2 |
| Discovery inside AI assistants (e.g. ChatGPT city search) | — | ✅ | Public listings API plus structured data on WEB pages | P2 |
| Organization mini-site and custom domain | 🟡 Smart Website add-on | 🟡 | `tenant.custom_domains`; WEB renders the mini-site by host | P2 |
| Multi-location brand page ("venue portfolios") | 🟡 planned | — | Organization → many `tenant.locations`; brand page aggregates them | P1 |
| Venue directory shared across organizers | — | 🟡 | `tenant.places.is_public_venue` with merge (`merged_into_id`) → ADM curates | P1 |
| Sponsored placement / Ads | 🟡 Rating Boost | ✅ Eventbrite Ads | `marketing.ad_campaigns` (CPC/CPM/flat) and `ad_daily_stats` → PP, ADM review | P2 |
| Waiting room / queue for high-demand on-sales, bot check | — | ✅ | Edge queue token plus bot challenge before `commerce.orders` creation; signals in `trust.fraud_signals` (BOT_TRAFFIC, BULK_TICKET_BUYING) | P2 |

### 2.2 Booking and calendar (appointments)

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Real-time availability and online booking | ✅ | — | API availability engine over `team.working_patterns`, `shifts`, `time_off`, `tenant.location_hours/closures` and `scheduling.schedule_reservations` → APP, WEB | P1 |
| Double-booking prevention | ✅ (app-level) | — | GiST exclusion on `scheduling.schedule_reservations`, tested with 40 concurrent sessions | P0 |
| Services with variants and add-ons | ✅ | — | `catalog.services`, `service_variants`, `addons`, `service_addons` | P1 |
| Processing and finishing time (staff free mid-service) | ✅ | — | `catalog.service_variant_stages` → `scheduling.appointment_item_stages` (`staff_busy`) | P1 |
| Resources (rooms, chairs, beds), including several per service | ✅ / 🟡 multi planned | — | `catalog.resources` (multi-unit) and `service_resource_requirements`; per-unit reservations | P1 |
| Buffers before and after | ✅ | — | `service_variants.buffer_before_min/after_min` | P1 |
| Per-staff and per-level pricing and duration | ✅ | — | `catalog.service_prices` (staff, pricing level, location, effective-dated; overlaps excluded) | P1 |
| Bundles (several services at one price) | 🟡 planned | — | `catalog.bundles` / `bundle_items` (sequential or parallel) | P1 |
| Group bookings / booking for someone else | ✅ | — | `scheduling.appointment_groups`, `appointment_items.guest_name` | P1 |
| Recurring appointments | ✅ | — | `scheduling.appointment_series` (RRULE) | P2 |
| Request-to-book / manual approval | 🟡 | — | `booking_policies.requires_approval` → status REQUESTED | P1 |
| Deposits, no-show and late-cancel fees | ✅ | — | `scheduling.booking_policies`, frozen in `appointments.policy_snapshot`; `commerce.payment_schedule` (DEPOSIT/BALANCE) | P1 |
| Pay at venue | ✅ | — | `booking_policies.allow_pay_at_venue`, `payment_schedule.collect_at = AT_VENUE` | P1 |
| Waitlist with automatic offers | ✅ | 🟡 planned | `commerce.waitlist_entries` (`preferred_windows` multirange) and `waitlist_offers` with a held slot | P1 |
| Patch tests enforced before booking | ✅ | — | `catalog.services.requires_patch_test`, `crm.patch_tests.valid_until` | P1 |
| Walk-ins, virtual queue, self check-in | 🟡 planned | — | `appointments.source = WALK_IN`, status ARRIVED; queue/kiosk mode in APP Business mode | P2 |
| At-home / mobile services | 🟡 | — | `services.delivery_modes` AT_CUSTOMER, `tenant.service_areas` (radius or admin area, travel fee and time), address snapshot | P1 |
| Calendar views, drag-to-move, staff columns, blocks | ✅ | — | `scheduling.calendar_blocks`, realtime updates → PP, APP Business mode | P1 |
| Forced overlap by front desk | ✅ | — | `schedule_reservations.allow_overlap`; never set by online booking | P1 |
| Reserve with Google / Meta / Instagram booking | ✅ | — | `platform.integrations` (GOOGLE_RESERVE, META_BOOKING); channel recorded in `appointments.source` | P2 |

### 2.3 Events and ticketing

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Single, recurring and multi-day events | — | ✅ | `events.events.kind`, `recurrence_rules` (RRULE), `occurrences` | P1 |
| Timed entry / time slots | — | ✅ | Slot occurrences (`occurrences.slot_label`), one payout across slots | P1 |
| Classes and workshops by a business (instructor and room reserved) | 🟡 | 🟡 | Event kind CLASS_SERIES plus `scheduling.occurrence_assignments`, which write to the **same staff/resource ledger** as appointments | P1 |
| Online and hybrid events, links revealed to ticket holders only | — | ✅ | `events.online_access` (secret ref, `reveal_at`); Zoom/YouTube/Telegram providers | P1 |
| Conference sessions and agenda | — | 🟡 | `events.sessions` (own capacity and check-in); speakers and FAQs in `events.content` | P2 |
| Paid, free, donation and pay-what-you-want tickets | — | ✅ | `ticket_types.pricing`, `min_price_minor` | P1 |
| Early bird and time- or sales-based price phases | — | ✅ | `events.price_phases` (time window and/or `quantity_limit`) | P1 |
| Sequential release (next tier opens when one sells out) | — | 🟡 | `ticket_types.release_after_ticket_type_id` | P1 |
| Shared capacity pools across ticket types | — | ✅ | `events.inventory_buckets` (OCCURRENCE / TICKET_TYPE / POOL / SESSION) and `pool_ticket_types` | P1 |
| No oversell under load | — | ✅ | Movement ledger `events.inventory_movements`; trigger-maintained counters with CHECK guard (race-tested) | P0 |
| Organizer holds (sponsors, press, artists) | — | ✅ | `events.inventory_holds`, movement kinds HOLD / UNHOLD / HOLD_SELL | P1 |
| Hidden tickets and access codes | — | ✅ | `ticket_types.visibility`, `commerce.promo_codes.kind` ACCESS plus `promo_code_unlocks` | P1 |
| Group / table tickets (one unit, N admissions) | — | ✅ | `ticket_types.admissions_per_unit` → N `events.tickets` rows | P1 |
| Series and multi-day passes | — | 🟡 | `ticket_types.validity` (ALL / SELECTED_OCCURRENCES) and `ticket_type_occurrences`; admission per occurrence | P2 |
| Add-ons and merchandise (own checkout step) | — | ✅ | PRODUCT order lines with `occurrence_id` for pickup at the event; `stock.fulfilments` PICKUP_AT_EVENT | P1 |
| Reserved seating and seat-map builder | — | ✅ (being rebuilt) | `events.seat_maps` / `seat_map_versions` / `seats`; per-occurrence `occurrence_seats` with uniqueness | P2 |
| Custom order and attendee questions | — | ✅ | One forms engine: `crm.forms` (ORDER_QUESTIONS / ATTENDEE_QUESTIONS), `form_assignments` | P1 |
| Age restrictions | — | ✅ | `events.min_age` | P1 |
| Ticket transfer and name change | — | ✅ | `events.ticket_transfers`; QR credential rotated (`credential_version`) | P1 |
| Bulk attendee transfers; move a whole order to a new date | — | ✅ | Organization-initiated transfers plus RETURN/SELL movements in one transaction; `occurrences.postponed_from` | P2 |
| Automatic attendee emails on date, time or location change | — | ✅ | Outbox event `occurrence.rescheduled` → comms templates | P1 |
| Postpone or cancel with mass refunds | — | ✅ | `events.status`, refunds with reason EVENT_CANCELLED / EVENT_POSTPONED | P1 |
| Sold-out notifications and live queue position | — | ✅ | Bucket thresholds → outbox; queue token at the edge | P2 |
| PDF, wallet and **SMS** tickets | — | ✅ (PDF) | QR carries an opaque secret (`credential_hash`); short `serial` for SMS and manual lookup | P1 |
| Anti-scalping | — | 🟡 planned | Per-order and per-buyer limits, bot signals, named transfer only, no resale (see §5) | P2 |

### 2.4 Check-in and on-site

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Check-in app with QR scanning | — | ✅ | APP Business mode; `events.checkin_devices` (scoped, revocable) | P1 |
| **Offline** scanning with later sync | — | 🟡 | Signed manifest of credential hashes per device; `events.scans` idempotent on `(device_id, client_scan_id)` | P1 |
| Exactly one admission per ticket, with re-entry | — | ✅ | `events.admissions` unique per ticket/occurrence/session; IN/OUT state when `allow_reentry` | P1 |
| Event-scoped door staff (no org-wide access) | — | ✅ | `events.staff_assignments` (CHECK_IN, BOX_OFFICE …) | P1 |
| Box office sales and cash drawer | — | ✅ | Order channel BOX_OFFICE; `commerce.cash_drawer_sessions` with `occurrence_id` | P1 |
| Badge printing | — | 🟡 | Export plus print template from attendee list → PP | P3 |

### 2.5 Checkout and payments

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| One checkout for mixed baskets from one seller | — | — | `commerce.orders` with typed `order_lines` (SERVICE, TICKET, PRODUCT, GIFT_CARD, MEMBERSHIP, PACKAGE, TIP, fees) | P1 |
| Guest checkout, account claimed later | 🟡 | ✅ | Buyer snapshot on the order; IAM challenge CLAIM_GUEST_ORDERS | P1 |
| Embedded checkout and booking widget | ✅ | ✅ | WEB `/embed/*` routes; order channel WIDGET | P1 |
| Card payments | ✅ | ✅ | Provider adapters (`payments.providers`); local cards via Chapa in ET, Flutterwave/Paystack in KE | P1 / P3 |
| **Mobile money (telebirr, M-PESA, MTN, Airtel …)** | — | — | Push prompt or redirect; `payment_attempts` with polling (`next_poll_at`) plus webhooks | P1 |
| Manual bank transfer with proof upload | — | — | `payment_attempts.interaction = MANUAL_REFERENCE`, `proof_file_id`, `verified_by` | Later (Chapa-only for now) |
| Split tender (gift card + cash + mobile money) | ✅ | 🟡 | Several `payment_intents` per order; internal tenders are `payments.methods` | P1 |
| Payment links (deposit or balance by SMS/WhatsApp) | ✅ | — | `payments.payment_links` | P1 |
| Saved instruments and recurring charges | ✅ | ✅ | `payments.instruments` where the provider tokenizes; otherwise renewal by payment link | P2 |
| BNPL (Klarna) | ✅ | — | Not in scope (see §5) | — |
| Pass fees to buyer or absorb | — | ✅ | `ticket_types.fee_handling`, BUYER_FEE lines, `billing.fee_assessments` | P1 |
| Refunds: full, partial, by line | ✅ | ✅ | `payments.refunds` / `refund_lines`; DB cap equals captured amount | P1 |
| Refund requests and approval flow | — | ✅ | Refund status REQUESTED → APPROVED, `requested_by` | P1 |
| **Refund via payout** when the provider has no refund API | — | — | `refunds.method = PAYOUT` → `payments.payouts` (REFUND_DISBURSEMENT) | P1 |
| Duplicate-payment protection | — | — | One SUCCEEDED attempt per intent; extras marked SUCCEEDED_DUPLICATE and auto-refunded | P0 |
| Payouts: scheduled, instant, pre-event | ✅ | ✅ instant | `payments.payouts`, `org_settings.payout_schedule`, `trust.risk_profiles.pre_event_payout_bps` | P1 / P2 |
| Multi-merchant: pay independent members or chair renters directly | ✅ | — | `team.staff.employment_type = CHAIR_RENTER`, staff payouts / SELLER_DIRECT accounts per member | P2 |
| Chargebacks and disputes | ✅ | ✅ | `payments.disputes` | P1 |
| Provider failover and routing | — | — | `payments.routes` (priority, weight, health, limits) | P1 |
| Diaspora buyers paying in USD for ETB listings | — | — | Presentment currency plus `ref.fx_rates` snapshot on the intent (licensed provider only) | P2 |

### 2.6 POS, invoicing and finance

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| POS checkout (services, products, tips, discounts) | ✅ | 🟡 | Order channel PARTNER_POS → APP Business mode, PP | P1 |
| Card terminals / Tap to Pay | ✅ | ✅ | Terminal adapters via provider (method CARD, interaction IN_PERSON) where available in-market | P3 |
| Cash drawer with float and variance | ✅ | 🟡 | `commerce.cash_drawers`, `cash_drawer_sessions` (one open per drawer), append-only `cash_movements` | P1 |
| Receipts with gapless numbers | ✅ | ✅ | `commerce.receipts`, `document_sequences` per legal entity | P1 |
| Quotes, invoices, credit notes | 🟡 planned | 🟡 (offline invoices) | `commerce.invoices` (QUOTE / INVOICE / CREDIT_NOTE), buyer TIN | P2 |
| Tax-authority e-invoicing / fiscal receipts | — | — | `fiscal.schemes` / `registrations` / `documents` (ET MoR, KE eTIMS, UG EFRIS, TZ VFD, RW EBM) | P2 |
| Balance, fees and payout statements | ✅ | ✅ | `ledger.*` double-entry, `ledger.balances`, `scheduled_releases` → PP Finance | P1 |
| Expense tracking | 🟡 planned | — | Deferred; exported to accounting (see §5) | — |
| Accounting integration (Xero etc.) | ✅ add-on | 🟡 | `platform.integrations` (XERO, QUICKBOOKS, PEACHTREE_EXPORT) plus journal export | P2 |

### 2.7 Customers, CRM and forms

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Customer profiles with history and spend | ✅ | 🟡 attendees | `crm.customers`, one per (org, person), across bookings **and** tickets | P1 |
| Notes, alerts, allergies | ✅ | — | `crm.customer_notes.kind` (ALERT, ALLERGY, MEDICAL), visibility | P1 |
| Labels / tags | 🟡 planned | — | `crm.tags`, `customer_tags` | P1 |
| Photos (before/after) and documents | ✅ | — | `crm.customer_files` (SENSITIVE files, signed URLs) | P1 |
| Treatment records (colour formulas, body charts) | ✅ | — | `crm.treatment_records` | P2 |
| Consultation, intake and consent forms with e-signature | ✅ | — | `crm.forms` / `form_versions` / `form_submissions` (signature file) | P1 |
| Marketing consent per channel with evidence | ✅ | ✅ | Append-only `crm.customer_consents` | P1 |
| Duplicate merge; block customer | ✅ | — | `customers.merged_into_id`, `is_blocked` | P1 |
| Import and export | ✅ | ✅ | `platform.data_exports`; import jobs | P1 |

### 2.8 Team

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Team members, roles, permissions | ✅ (advanced planned) | ✅ | `tenant.roles` (templates plus custom), `role_permissions`, `members`, `member_locations` | P1 |
| Staff without a login | ✅ | — | `team.staff.member_id` nullable | P1 |
| Regular shifts, rotas, time off | ✅ | — | `team.working_patterns` (N-week), `shifts` (no overlap), `time_off` | P1 |
| Timesheets / clock in and out | ✅ | — | `team.time_entries` (no overlapping entries) | P2 |
| Commissions (incl. tiered, requested-staff bonus) | ✅ | — | `team.commission_rules`, append-only `commission_entries` reversed on refund | P2 |
| Wages and pay runs | ✅ | — | `team.compensation`, `pay_runs` / `items` / `adjustments` / `payments`; statutory payroll exported | P2 |
| Tips and tip splitting | ✅ | — | TIP lines plus `team.tip_allocations` | P1 |
| Targets | 🟡 planned | — | `team.targets` | P2 |
| Team chat | ✅ add-on | — | `comms.conversations.kind = TEAM` | P2 |

### 2.9 Inventory and online store

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Products, variants, SKU and barcode | ✅ | — | `catalog.products`, `product_variants` | P1 |
| Stock per location, never negative | ✅ | — | `stock.levels`, maintained only by append-only `stock.movements` | P1 |
| Professional (backbar) usage | ✅ | — | Movement kind PROFESSIONAL_USE linked to `appointment_item_id` | P2 |
| Suppliers, purchase orders, receiving | ✅ | — | `stock.suppliers`, `purchase_orders` / `lines` | P2 |
| Transfers and stocktakes | ✅ | — | `stock.transfers`, `stocktakes` / `lines` | P2 |
| Online shop, pickup, local delivery | ✅ | 🟡 merch | `catalog.products.sell_online`, `stock.delivery_zones`, `fulfilments` | P2 |

### 2.10 Retention: gift cards, memberships, packages, loyalty

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Gift cards (digital, scheduled delivery, partial redemption) | ✅ | — | `catalog.gift_card_programs`, `retention.gift_cards` (hashed code) plus entry ledger | P1 |
| Gift cards redeemable on tickets as well as services | — | — | `gift_card_programs.redeemable_on` includes TICKET | P1 |
| Prepaid packages | ✅ | — | `catalog.package_plans` → `retention.entitlements` (`quantity_used` ≤ total, trigger-maintained) | P2 |
| Memberships (recurring credits, discounts, priority, event access) | ✅ (advanced planned) | — | `catalog.membership_plans` / `benefits`, `retention.customer_memberships` | P2 |
| Class credits used for class-series bookings | 🟡 | — | Entitlement scoped to `event_id` | P2 |
| Loyalty points, tiers, rewards | ✅ add-on | — | `retention.loyalty_programs` / `tiers` / `rewards` / `accounts` / `entries` | P2 |
| Customer balance / store credit | ✅ | — | `retention.customer_balances` plus entries; refund method STORE_CREDIT | P1 |
| Referral program | ✅ | — | `marketing.referral_programs`, `referrals` | P2 |

### 2.11 Marketing and growth

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Promotions, deals, discount codes | ✅ | ✅ | `commerce.promotions` / `promotion_targets` / `promo_codes` / `redemptions` | P1 |
| Off-peak / happy-hour pricing | ✅ | — | `promotions.time_windows`, auto-apply | P2 |
| Platform-funded marketplace promotions | 🟡 | 🟡 | `promotions.org_id IS NULL`, `funded_by` PLATFORM / SHARED, `platform_share_bps` | P2 |
| Blast campaigns: email, SMS, WhatsApp, **Telegram**, push | ✅ | ✅ email / 🟡 SMS planned | `marketing.campaigns` / `campaign_recipients`, consent-checked | P1 (SMS, Telegram) / P2 |
| Segments (dynamic and static) | 🟡 planned | ✅ | `marketing.segments`, `segment_members` | P1 |
| Automations (rebook, birthday, lapsed, abandoned checkout, post-event thank-you) | ✅ | ✅ | `marketing.automations` / `steps` / `runs` (deduplicated per trigger) | P2 |
| Review requests | ✅ | 🟡 | Automation trigger REVIEW_REQUEST | P1 |
| Tracking links and attribution | 🟡 | ✅ | `marketing.tracking_links`, `tracking_link_daily`, `orders.attribution` | P1 |
| Meta, Google and TikTok pixels | ✅ | ✅ | `platform.integrations` (META_PIXEL, GOOGLE_ANALYTICS, TIKTOK_PIXEL) | P2 |
| Sell through social (Facebook, Instagram, TikTok) | ✅ booking buttons | ✅ | Deep links, widget, Reserve with Google / Meta integrations | P2 |
| Email to followers and past attendees | — | ✅ | Campaign audience FOLLOWERS / EVENT_ATTENDEES | P1 |
| AI copywriting | 🟡 | ✅ | Partner portal assistant (no schema impact) | P2 |

### 2.12 Messaging and AI

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Transactional notifications (confirm, remind, ticket, change) | ✅ | ✅ | `comms.templates` (platform defaults plus org overrides, per language), `notifications`, `deliveries` | P0 |
| Two-way customer messaging, incl. WhatsApp / Telegram / SMS bridge | 🟡 planned | 🟡 inquiries | `comms.conversations` (`origin_channel`), `participants`, `messages` | P1 (app) / P2 (bridges) |
| Quiet hours, preferences, STOP handling | ✅ | ✅ | `comms.preferences`, `comms.suppressions` | P1 |
| AI concierge / receptionist (chat, then voice) | ✅ add-on | — | `comms.ai_agents` / `ai_knowledge` / `ai_sessions`; every action audited in `ai_actions` | P2 beta |

### 2.13 Reviews and reputation

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Verified reviews only | ✅ | 🟡 | `discovery.reviews` requires `appointment_id` **or** `order_id`; one per author per visit or order | P1 |
| Staff-level and event ratings, sub-ratings | 🟡 | 🟡 | `reviews.staff_id`, `event_id`, `sub_ratings` | P1 |
| Business responses and moderation | ✅ | 🟡 | `response_body`; `trust.content_reports`, `moderation_actions` | P1 |
| Google rating boost | ✅ add-on | — | Review-request automation with a Google link | P2 |

### 2.14 Reporting

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Sales, appointments, attendance, team, inventory reports | ✅ | ✅ | Read models on replica (`beavent_readonly`) → PP | P1 core / P2 advanced |
| Cross-event sales view | — | ✅ | Organization-wide event reporting → PP | P1 |
| Real-time dashboard | ✅ | ✅ | Projections fed by outbox events | P1 |
| Payout and fee statements | ✅ | ✅ | `ledger.*`, `billing.fee_assessments` | P1 |
| Data connector / BI export | ✅ add-on | 🟡 | `platform.data_exports`; partner API | P2 |

### 2.15 Integrations and API

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Public API and API keys | 🟡 | ✅ | `platform.api_clients` (hashed keys, scopes) | P2 |
| Outbound webhooks | 🟡 | ✅ | `platform.webhook_endpoints` / `webhook_deliveries` (from `platform.outbox`) | P2 |
| Zoom / YouTube / Facebook Live | — | ✅ | `events.online_access.provider`, `platform.integrations` | P1 (links) / P2 (native) |
| Calendar sync (Google) | 🟡 | ✅ add-to-calendar | GOOGLE_CALENDAR integration; ICS downloads | P2 |

### 2.16 Trust and safety

| Feature | Fresha | Eventbrite | Beavent design | Phase |
|---|---|---|---|---|
| Business verification (KYB) and owner KYC | ✅ (via payments) | ✅ | `trust.verification_cases` / `documents` (trade licence, TIN, Fayda ID …), providers MANUAL / FAYDA / SMILE_ID | P1 |
| New-event review for risky organizers | — | ✅ | Event status PENDING_REVIEW, case subject EVENT_REVIEW | P1 |
| Payout risk controls (delay, reserve, pre-event cap, manual review) | ✅ | ✅ | `trust.risk_profiles` | P1 |
| Payout-account change cooling-off | 🟡 | 🟡 | `payout_accounts.payouts_allowed_after` (72 h default) plus step-up OTP | P1 |
| Fraud signals (velocity, device reuse, bots, bulk buying) | 🟡 | ✅ | `trust.fraud_signals` | P2 |
| Content reports | ✅ | ✅ | `trust.content_reports` (scam, fake event …) | P1 |

### 2.17 Admin and platform

| Capability | Beavent design | Phase |
|---|---|---|
| Market activation (status, currencies, languages, features) | `ref.markets`, `market_currencies`, `market_languages`, `market_features` → ADM | P0 |
| Payment provider, capability and route management | `payments.providers`, `provider_capabilities` (LIVE requires `verified_at`), `provider_accounts`, `routes` → ADM | P1 |
| Fee rules and plans per market | `billing.fee_rules` (no overlapping rules), `plans`, `plan_prices`, `plan_entitlements` → ADM | P1 |
| Tax rates, effective-dated and adviser-verified | `ref.tax_rates` (`is_verified`) → ADM | P1 |
| Support desk spanning consumers and partners | `platform.support_cases` / `support_messages` → ADM, APP, PP | P1 |
| Impersonation with consent, fully audited | `platform.audit_log.impersonator_user_id` | P1 |
| Reconciliation against provider statements | `payments.provider_statements` / `statement_lines` → ADM Finance | P1 |
| Feature flags | `platform.feature_flags` / overrides (market, org, user) | P0 |
| CMS (home hero, banners, help) | `platform.cms_entries` | P1 |
| Privacy requests (access, erasure …) | `platform.data_subject_requests` | P1 |

---

## 3. Where the unified model beats either platform

1. **One staff and resource ledger for appointments and classes.** A stylist teaching a Saturday braiding masterclass is reserved in `scheduling.schedule_reservations` exactly like her appointments. Neither Fresha nor Eventbrite can stop a class from colliding with a booked client. Here a database exclusion constraint does (`db/tests/01_core_invariants.sql`).
2. **One CRM per organization across bookings and tickets.** A spa that runs a wellness retreat sees retreat attendees and facial clients in the same `crm.customers` record, with one consent history, one set of segments and one follow graph.
3. **One forms engine.** Fresha's consultation forms and Eventbrite's checkout and attendee questions are both `crm.forms` with versions and submissions, so features like conditional logic, e-signature and multilingual labels are built once.
4. **One order for mixed baskets from one seller.** "Book a blow-dry, buy two tickets to the salon's launch party and add a hair serum" is one `commerce.orders` row, paid with one intent, refunded line by line.
5. **One waitlist, with automatic offers.** It works for sold-out occurrences and for fully booked time windows alike (`commerce.waitlist_entries` / `waitlist_offers`).
6. **Promotions and access codes unified.** Discount, hidden-ticket unlock, or both, all modelled by `commerce.promo_codes.kind`. Platform-funded marketplace promotions share the same model.
7. **Stored value works across verticals within a seller.** Gift cards, customer balance and membership credits can pay for services, products **and** tickets at the same organization.
8. **One payments, ledger and payout core.** One reconciliation, one risk model, one payout schedule per seller, whatever they sell.
9. **One app.** A consumer who books a nail appointment on Friday and scans into a concert on Saturday uses one app and one account. A salon owner who also promotes events manages both in one portal and one Business mode.
10. **Correctness is enforced by the database, not trusted to code:** no oversell, no double booking, no over-refund, no double ticket issuance, balanced ledger, tenant isolation (RLS on 212 tables). All are tested, including a 40-session race test.

---

## 4. Africa-first additions (neither platform has these)

| Need | Why it matters | Design |
|---|---|---|
| Mobile-money push payments with polling | Most digital payments are wallet-based; confirmations are asynchronous and webhooks can be unreliable | `payment_attempts` (PUSH_PROMPT / REDIRECT, `next_poll_at`, status UNKNOWN), provider routing and failover |
| Manual bank transfer with proof upload | Common for B2B, larger tickets and corporate bookings | MANUAL_REFERENCE attempts, AWAITING_VERIFICATION, statement matching |
| Pay at venue / cash | Trust and habit; deposits optional | `payment_schedule.collect_at`, cash drawers |
| Refund by payout | Many wallets lack a refund API | `refunds.method = PAYOUT` → `payments.payouts` |
| Customer approves two prompts | Duplicate debits happen in practice | SUCCEEDED_DUPLICATE plus automatic refund |
| SMS tickets with short serials | Feature phones, poor data | `events.tickets.serial`; QR secret never sent in clear |
| Telegram and WhatsApp channels | Dominant messaging apps (Telegram in Ethiopia, WhatsApp in Kenya) | `comms.provider_accounts`, conversations bridged by `origin_channel` |
| Ethiopian calendar and Ethiopian local time display | Users read dates and times this way | `ref.countries.display_calendar` / `local_time_convention`, user preference; UTC storage |
| Landmark-first addresses, admin hierarchy (sub-city, woreda, kebele) | Postal addresses are rare | `landmark`, `ref.administrative_areas` (ltree), geo point |
| At-home services with service areas and travel fees | A large share of beauty work is home visits | `tenant.service_areas`, TRAVEL_FEE lines, TRAVEL stage in the reservation |
| Diaspora gifting and presentment currency | Diaspora buys gift cards and tickets for family | USD presentment on ETB intents with FX snapshot (licensed provider only) |
| Offline check-in | Venue connectivity is unreliable | Device manifest, idempotent scan sync, conflict result |
| Low-bandwidth mode | Data cost and speed | Lightweight list endpoints, image variants, cached catalog (see doc 03) |
| Fiscal e-invoicing adapters | Mandatory or coming in ET, KE, UG, TZ, RW | `fiscal.*`, one adapter per scheme |
| National ID / KYC (Fayda, Smile ID) | KYB/KYC before payouts | `trust.verification_cases.provider` |
| Multilingual content (Amharic, Afaan Oromo, Tigrinya, Somali, Swahili …) | Many official languages | `*_i18n` jsonb, `ref.market_languages` with BETA status |
| Phone-first identity with OTP via SMS, WhatsApp or Telegram | Phone numbers before email | `iam.users.phone_e164`, `auth_challenges.channel` |

---

## 5. Excluded or deferred, with reasons

| Item | Decision | Reason |
|---|---|---|
| Platform-wide consumer wallet / balance usable at any seller | **Excluded** | Stored value usable across merchants is e-money, a licensed activity under National Bank of Ethiopia payment-instrument directives and their equivalents. Stored value stays per-organization (`retention.*`). |
| PLATFORM_COLLECTS (platform holds sellers' funds) | **Deferred to markets where licensed or partnered (P4, or earlier with a licensed partner)** | Holding and redistributing third-party funds needs a licence or a licensed PSP partner. P1 uses SELLER_DIRECT and PROVIDER_SPLIT. |
| BNPL | Excluded | Credit regulation, low provider coverage, default risk. |
| Merchant cash advance (Fresha Capital-style) | Excluded | Lending licence; not core. Ledger history could support a partner later. |
| Secondary ticket resale / marketplace | Deferred | Anti-scalping rules first. Named transfers cover the real need. |
| Statutory payroll (income tax, pension, payslips) | Excluded | Country-specific compliance. Pay runs compute earnings and are exported to local payroll software. |
| Seller general ledger / bookkeeping / expense tracking | Excluded | Sellers use accounting software. We provide journal exports and integrations (Xero, QuickBooks, Peachtree export). |
| Card terminals / Tap to Pay | Deferred (P3) | Depends on in-market acquirer certification; mobile money and QR cover P1 in-person payments. |
| Native video streaming | Excluded | Integrate Zoom, YouTube and Telegram live instead. |
| Seat-map builder | Deferred (P2) | General admission, time slots and pools cover most launch events. |

---

## 6. Monetization model

Beavent's revenue streams, all configurable per market from the admin console. **No rates are fixed in the schema.** Plans are seeded without prices, and `billing.fee_rules` (with overlap protection and per-org overrides) carries every rate.

| Stream | Mechanism | Schema |
|---|---|---|
| Subscriptions | Tiers **FREE, SOLO, TEAM** (per bookable staff), **EVENTS_PRO, ENTERPRISE** (per location); add-ons such as AI concierge, custom domain, advanced reports | `billing.plans`, `plan_prices`, `plan_entitlements`, `subscriptions`, `subscription_addons` |
| Marketplace new-client commission | One-time fee on a customer first acquired through the marketplace; never on direct or widget bookings; charged at most once per order (unique index) | `crm.customers.marketplace_acquired_at`, fee code MARKETPLACE_NEW_CLIENT |
| Ticket service fee | Per paid ticket; buyer pays or organizer absorbs; free events stay free | fee code TICKET_SERVICE_FEE, `ticket_types.fee_handling` |
| Payment processing | Pass-through of provider cost, optionally with a markup | PAYMENT_PROCESSING |
| Payout fees | Standard and instant payouts | PAYOUT_FEE, INSTANT_PAYOUT_FEE |
| Messaging usage | SMS, WhatsApp and marketing email beyond plan allowances; AI minutes and messages | `billing.usage_records`, fee codes SMS / WHATSAPP / MARKETING_EMAIL / AI_* |
| Featured placements / Ads | CPC, CPM or flat daily | `marketing.ad_campaigns`, FEATURED_PLACEMENT |

Collection follows the seller's collection mode. With PROVIDER_SPLIT, fees are split at source. With PLATFORM_COLLECTS, fees are netted before payout. With SELLER_DIRECT there are no platform payouts, so fees are invoiced monthly and auto-charged or paid by link (`billing.invoices.collection`); see [doc 04 §3](04-payments-and-money.md#3-collection-modes-who-holds-the-money). Every fee charged is an immutable `billing.fee_assessments` row with its rule, base and VAT, so sellers can always see exactly what they paid and why.
