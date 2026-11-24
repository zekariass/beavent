# Beavent design philosophy

**Status:** v1, 19 Nov 2026. Proposed design foundation for plan dependency **D9** (wireframes and visual design, [doc 07](../../07-implementation-plan.md#dependencies-and-decisions)).
**Applies to:** the mobile app (consumer mode and Business mode), the one web platform (`apps/web/`: public site, business and organizer console). The admin console (`apps/admin-console/`) follows the tokens but not the marketplace patterns.
**Read with:** every other file in this folder. This one sets the direction and records the decisions; the others turn it into rules.

| File | What it settles |
|---|---|
| `DESIGN_PHILOSOPHY.md` (this) | Who we design for, principles, visual direction, audit of the current code, design decisions |
| [`DESIGN_TOKENS.md`](DESIGN_TOKENS.md) | Colour, type, spacing, radii, elevation, motion, themes — exact values |
| [`COMPONENT_GUIDELINES.md`](COMPONENT_GUIDELINES.md) | Every reusable component: anatomy, variants, states, rules |
| [`MARKETPLACE_NAVIGATION.md`](MARKETPLACE_NAVIGATION.md) | Tabs, routes, deep links, back behaviour, shared screens (Explore, Search, Bookings, Account, Sign-in, Payment) |
| [`SERVICES_MARKETPLACE_DESIGN.md`](SERVICES_MARKETPLACE_DESIGN.md) | Services journey, screen by screen, plus partner-portal catalogue and Business mode requirements |
| [`EVENTS_MARKETPLACE_DESIGN.md`](EVENTS_MARKETPLACE_DESIGN.md) | Events journey, screen by screen, plus organizer and check-in requirements |
| [`MOBILE_UI_GUIDELINES.md`](MOBILE_UI_GUIDELINES.md) | Layout, touch, forms, lists, images, offline, accessibility, localisation, performance |
| [`SCREEN_DESIGN_STANDARDS.md`](SCREEN_DESIGN_STANDARDS.md) | Screen templates, composition rules, state standards, visual QA checklist |
| [`AI_AGENT_DESIGN_INSTRUCTIONS.md`](AI_AGENT_DESIGN_INSTRUCTIONS.md) | The rules an implementing agent must follow, and its definition of done |
| [`DESIGN_REFERENCE_CATALOG.md`](DESIGN_REFERENCE_CATALOG.md) | Fresha, Eventbrite, Airbnb, Vinted, Revolut: what to learn, what not to copy |

---

## 1. What Beavent is, in design terms

Beavent is **one product** for two kinds of plans: *booking a service* (a braid, a massage, a haircut) and *going to something* (a concert, a workshop, a class). The backend already treats them as one system — one organization model with capabilities, one order, one checkout, one listings read model (`discovery.listings` with `kind` `LOCATION | EVENT`), one reservation ledger ([doc 01, D1–D4](../../01-review-and-decisions.md#3-decisions)). The design must make that visible: **one app, one account, one place for everything you have planned.**

The two verticals have different rhythms, and the design respects that instead of forcing one layout on both:

| | Services | Events |
|---|---|---|
| The question the customer is answering | *Who* and *when* — which place, which professional, which time | *What* and *when* — which event, which date, how many tickets |
| Inventory | Time slots computed from staff, resources and policies; changes minute to minute | Fixed occurrences with capacity buckets; sells out |
| Decision drivers | Rating, price, distance, availability, the professional | Date, the line-up/organizer, price, the venue, friends going |
| The thing you hold afterwards | An appointment (time, place, professional, balance due) | A ticket (QR, serial, entry time), possibly offline at a door |
| Benchmark | Fresha | Eventbrite |

**Shared skeleton, different organs.** Discovery, search, profile headers, price display, checkout, payment, confirmation, the Bookings tab and reviews are the *same* components across both. The selection step in the middle (professional + time slot vs. occurrence + ticket quantities) is where each vertical gets its own purpose-built interaction.

## 2. Who we design for

Grounded in [doc 06](../../06-markets-and-localization.md) (Ethiopia launch) and [doc 03 §1.1](../../03-system-architecture.md#11-mobile-app-one-app-two-modes).

| Person | Context that changes the design |
|---|---|
| **Consumer in Addis Ababa** | Reads Amharic first (default language `am`), dates in the Ethiopian calendar, often time in Ethiopian local time (07:00 = "ጠዋት 1:00"). Low-end Android (2–3 GB RAM), prepaid data, intermittent 3G/4G. Pays by telebirr / CBE Birr / M-PESA through Chapa, or cash at the venue. Uses Telegram more than email. Addresses are landmark-first ("behind Edna Mall, 2nd gate"). |
| **Diaspora buyer** | Buys tickets or gift cards for family; reads English; Gregorian calendar; may be on a high-end phone abroad. USD presentment is disabled for now. |
| **Independent professional** (stylist, makeup artist, at-home therapist) | Is both a consumer and a seller in the same app (Business mode). One location or mobile-only. Needs a calendar on the phone, not a desktop. |
| **Salon / spa front desk** | Partner portal on a desktop or tablet and Business mode on a phone. Speed and correctness under interruption: walk-ins, phone bookings, cash. |
| **Event organizer** | Creates events in the partner portal; checks people in at the door on a phone, often without signal. |
| **Door staff** | Scanner in Business mode, event-scoped role, may never see anything else in the app. |

**Design consequence:** the baseline device for every consumer screen is a **360 × 640 dp Android phone on a slow network, set to Amharic**. A screen is not done until it works there (see [MOBILE_UI_GUIDELINES.md](MOBILE_UI_GUIDELINES.md)).

## 3. Principles

Each principle has a practical rule and something it rules out. When two principles conflict, the earlier one wins.

### P1. Trust before delight
Money and time must be unambiguous. Every price shown at a point of decision is the price paid (tax-inclusive as the market requires, `ref.markets.prices_include_tax`); buyer fees are shown before the pay step, never introduced on it. Every time shown on an appointment or ticket is shown in both conventions where the user's market uses Ethiopian time (`ጠዋት 3:00 (9:00)`), because misreading the clock is the most common no-show cause ([doc 06 §2.1](../../06-markets-and-localization.md#21-calendar-and-time)).
*Rules out:* "+ fees" surprises at payment; times without a day period; prices that change between card and checkout; celebratory animation before the payment is verified.

### P2. One product, two rhythms
Same skeleton everywhere (§1). A person who learned to book a haircut can buy a ticket without learning a new app: same search, same profile header, same sticky action bar, same checkout, same Bookings tab.
*Rules out:* separate colour themes for Services and Events; separate tabs per vertical for things that are the same (saved items, bookings history, payment); bespoke one-off components where a shared one exists.

### P3. Content is the colour
The interface is a warm neutral frame. Colour comes from photography (venues, work, event covers) and from one brand colour used for actions and selection. Hierarchy comes from type size, weight and space — not from boxes, borders and shadows.
*Rules out:* coloured section backgrounds, gradients, decorative illustrations, card-in-card, a border and a shadow on the same element.

### P4. Ge'ez-first typography
Amharic is the default language. Ethiopic glyphs need line height 1.7 and never go below 14 px ([`packages/ui`](../../../packages/ui/README.md)). Ge'ez has no letter case, so **case is never a hierarchy device** (no all-caps labels — the hierarchy would vanish in Amharic). Layouts assume Amharic strings are taller and English strings are often longer; nothing is fixed-height that holds text.
*Rules out:* uppercase overlines, letter-spaced eyebrow labels, text truncated to one line where the meaning would be lost, text baked into images.

### P5. Built for the real network
Every screen has a designed loading, empty, error and offline state. Tickets and today's appointments render without a network ([doc 06 §2.5](../../06-markets-and-localization.md#25-connectivity-and-devices)). Lists use the 320 px image variant; full-size images only on detail screens. Payment confirmation can take minutes and must survive the app being backgrounded.
*Rules out:* spinners as the only loading state, blank screens on error, auto-playing video, image carousels in result lists, features that silently fail offline.

### P6. One clear next step
Each screen has at most one primary action, and in flows it lives in a sticky bottom bar within thumb reach. Secondary actions are visually quieter (secondary or tertiary buttons, rows, menus).
*Rules out:* two filled buttons side by side, primary actions in the top-right corner of a mobile screen, floating action buttons in consumer mode.

### P7. Calm density
Show what the decision needs and no more: a venue row shows name, rating, area/landmark and two or three priced services; an event row shows date, title, venue and price-from. Metadata is written as a sentence of facts separated by `·`, not as a row of badges. **At most one status label per card**, and only when it changes what the user does (Sold out, Few left, Cancelled, Request to book).
*Rules out:* badge rows, icon-per-fact rows, "NEW!" stickers, dashboards on consumer screens.

### P8. Respect the platform
Native tabs (`expo-router/native-tabs`, already in use), native back gestures, system font scale, system dark mode, platform symbols via `expo-symbols`. Beavent's identity lives in colour, type, spacing and content — not in re-implementing platform controls.
*Rules out:* custom tab bars on mobile, hijacked back gestures, custom fonts for Latin text on mobile (see [DESIGN_TOKENS.md §3](DESIGN_TOKENS.md#3-typography)).

## 4. Visual direction

**Name of the direction:** *Warm canvas, emerald ink, Adey gold.*

- **Warm canvas.** White pages with a warm off-white surface (`#F5F4F1`) for grouped content and fields; warm greys for secondary text. Warmth reads as hospitality and keeps photography of skin tones and hair looking natural (cool grey frames make warm photography look muddy).
- **Emerald ink** (`#0B6B53` light / `#5CC8A1` dark) is the single brand colour: primary buttons, selected states, links, focus, the active tab. It is deliberately **not** violet (Fresha's territory and the current provisional `#5b3fd9`) and **not** orange (Eventbrite's). Deep green reads as calm, premium and trustworthy — appropriate for a product that handles people's money and appointments — and has strong contrast with white in both directions (6.5:1).
- **Adey gold** (`#F0B323`) — named after the *Adey Abeba*, the yellow Meskel daisy that marks the Ethiopian New Year — is the accent. It is used sparingly: rating stars, the brand mark, and at most one highlight per screen (e.g. the "Next up" marker in Bookings). Never for text on white (1.9:1); text placed on gold is ink.
- **Photography first.** Venue photos at 3:2, event covers at 2:1, people as circles. No stock imagery, no illustrations of people, no emoji in UI chrome.
- **Typography.** Platform system font for Latin on mobile (SF Pro / Roboto), Noto Sans for Latin on web (already loaded), Noto Sans Ethiopic for Ge'ez everywhere. A compact scale (28 / 22 / 18 / 16 / 14 / 12); the largest size appears once per screen at most. Weights 400 / 500 / 600 only. Tabular figures for prices, times and counts.
- **Shape.** 12 px radius for buttons, inputs and images; full pills for chips and avatars; 16 px top corners on sheets. Hairline dividers between rows instead of cards around rows.

### What makes it recognisably Beavent (and not a Fresha or Eventbrite clone)

| Signature | Where it shows up |
|---|---|
| Dual date/time lines: Ethiopian first for Amharic and Afaan Oromo readers, Gregorian in muted text (`ቅዳሜ፣ ታኅሣሥ 6 · ማታ 2:00 (20:00)`) | Every appointment, ticket, event card, slot picker |
| Landmark-first addresses (`Behind Edna Mall, 2nd gate · Bole`) | Venue header, appointment detail, at-home booking |
| One Bookings tab that interleaves appointments and tickets on one timeline | Bookings tab ([MARKETPLACE_NAVIGATION.md §5.4](MARKETPLACE_NAVIGATION.md#54-c-4-bookings)) |
| The "approve on your phone" payment wait, designed as a first-class screen (mobile money push via Chapa) | Shared checkout ([MARKETPLACE_NAVIGATION.md §5.7](MARKETPLACE_NAVIGATION.md#57-c-7-payment-and-payment-waiting)) |
| Emerald actions on a warm canvas, gold used once | Everywhere |
| Offline-ready ticket with a short SMS serial under the QR | Ticket pass ([EVENTS_MARKETPLACE_DESIGN.md E-11](EVENTS_MARKETPLACE_DESIGN.md#e-11-ticket-pass-qr)) |

## 5. Anti-patterns (banned unless a doc in this folder makes an exception)

These are the failure modes typical of generated UI. Each has a concrete replacement.

| Don't | Do instead |
|---|---|
| Wrap every group of content in a card with a border and a shadow | Use sections separated by 32 dp of space and a title; use rows with hairline dividers. Cards are only for tappable objects with an image (venue, event) — [SCREEN_DESIGN_STANDARDS.md §3](SCREEN_DESIGN_STANDARDS.md#3-when-to-use-a-card) |
| Gradients (backgrounds, buttons, text) | Solid tokens. The only permitted gradient is the 0→48 % black scrim at the bottom of a photo to keep overlaid controls legible |
| Shadows on resting content | Elevation only for things that float: sheets, dialogs, the sticky action bar while content scrolls under it, toasts |
| Badges for every attribute | Facts as a `·`-separated sentence; one status label per card, only if actionable |
| An icon before every label | Icons only in navigation, in icon-only buttons (with an accessible label), and where they replace a word users already know (back, close, share, save, search, map) |
| A hero banner with a headline on the home screen | Search first, then content. Headline copy belongs on the web landing page, not the app |
| Section titles at 24–32 px | Section titles are `title2` (18 px). `display` (28 px) is for the one name/headline that defines a screen |
| Every screen the same "header + cards list" | Use the screen template that fits the job ([SCREEN_DESIGN_STANDARDS.md §2](SCREEN_DESIGN_STANDARDS.md#2-screen-templates)) |
| Dashboards (KPI tiles, charts) on consumer screens | Consumer screens show plans and places. Charts belong to the partner portal |
| Emoji, exclamation marks and "Oops!" in copy | Plain, warm, specific copy: what happened, what to do |
| Placeholder stock photos | Neutral surface with the listing's initial (see [COMPONENT_GUIDELINES.md §4.1](COMPONENT_GUIDELINES.md#41-media)) |
| A spinner in the middle of an empty screen | Skeletons that match the final layout |
| Modal dialogs for non-destructive information | Inline messages, banners, or a bottom sheet |

## 6. Where Beavent is today (audit of the repository, 19 Nov 2026)

What exists, read from the code on branch `stage-1/catalogue`.

### 6.1 Inventory

| Surface | What exists | State |
|---|---|---|
| Mobile (`apps/public-mobile/`, Expo SDK 58, Expo Router, React 19, RN 0.88) | `src/app/_layout.tsx` (theme provider + splash + tabs), `src/app/index.tsx` (language switch, app name, tagline, Ethiopian date and time card, two text lines "Book a salon…" / "Find events…"), `src/app/explore.tsx` (Expo starter content), `src/components/app-tabs.tsx` (native tabs: *Home*, *Explore*), `app-tabs.web.tsx` (custom web tab bar branded "Expo Starter"), template components (`ThemedText`, `ThemedView`, `Collapsible`, `HintRow`, `WebBadge`, `ExternalLink`, `AnimatedIcon`) | Starter shell. **No marketplace screens, no sign-in screens, no Business mode.** |
| Web marketplace (`apps/web/`, Next.js 16, Tailwind 4) | `[lang]` layout (Noto Sans + Noto Sans Ethiopic, `data-script`), home page (app name, tagline, two list items, Ethiopian date/time), `LanguageSwitch`, `EthiopianNow`, 404 | Localised shell |
| Partner portal (`apps/business/`) | Same shell as web with "Create your business" / "Sign in" as non-interactive spans | Localised shell; Stage 1 catalogue screens are next |
| Admin console (`apps/admin-console/`) | Real screens: overview, verification queue and case review, organizations, people, audit, flags, markets. Own primitives in `src/components/ui.tsx` (`PageTitle`, `Badge`, `Table`, `Empty`, `SearchForm`) | Working internal tool, unstyled zinc palette |
| `packages/ui` | Tokens (colour light/dark, 4-pt spacing, radii, type scale), Ethiopic typography rules, `lineHeightPx`, `cssVariables()`, performance budget | Values provisional pending D9; **no components** |
| `packages/i18n` | `am` / `en` / `om` catalogues for the shells, including `nav.explore`, `nav.bookings`, `nav.tickets`, `nav.account`, `nav.signIn` (not yet used by any navigation) | Good base |
| `packages/calendar`, `packages/money` | Ethiopian calendar and local time, `formatInstant`; exact money with `format()` | Good base, two gaps (§6.3) |
| Backend contracts | Identity (OTP, Google), tenancy (organizations, locations with landmark and opening hours), files (w320/w960 variants), reference (categories with `icon`, admin areas), **catalog (this branch)**: service groups, services with variants/stages/resources, price overrides and resolution, add-ons, resources, bundles, galleries; **team** (staff profiles, pricing levels, working patterns, shifts, time off, schedule); **booking policies** (with an "effective policy" endpoint), location closures, service areas and public holidays (in progress on this branch while these docs were written). Schema for listings, appointments, events, ticket types, tickets, reviews, saved items, follows | Contracts define what screens can show |

### 6.2 Strengths to keep

1. **Localisation is foundational, not bolted on.** Language in the URL on web, device language on mobile, Amharic default, `scriptOf()` choosing line height, Ethiopian calendar and local time with tests. This is the hardest part of the brief and it is already right.
2. **Token names are stable and shared** between web (CSS variables) and React Native (numbers) through one package.
3. **Native tabs** (`expo-router/native-tabs`) — correct platform behaviour and good performance on low-end Android.
4. **A written performance budget** (4 MB bundle, 3 s cold start, 320 px list images, virtualise lists above 50 rows).
5. **The data model already expresses good UX:** landmark-first addresses, price types (`FIXED`, `FROM`, `FREE`, `ON_CONSULTATION`), processing-time stages, `audience`, `deliveryModes`, request-to-book, deposits, verified-only reviews, short ticket serials, `HIDDEN_WHEN_UNAVAILABLE` ticket visibility.

### 6.3 Problems found

| # | Problem | Evidence | Consequence | Fix (where documented) |
|---|---|---|---|---|
| A1 | **Two token systems in mobile.** The template's `Colors`/`Spacing`/`Fonts` in `apps/public-mobile/src/constants/theme.ts` drive `ThemedText`/`ThemedView`; `@beavent/ui` is used only for `lineHeightPx`. The scales collide: `Spacing.three` = 16 but `space[3]` = 12; `Spacing.four` = 24 but `space[4]` = 16. | `constants/theme.ts`, `components/themed-*.tsx`, `app/index.tsx` | Every screen built now will mix scales; brand colours never reach mobile. | Retire `constants/theme.ts` colours and spacing; map to `@beavent/ui` ([DESIGN_TOKENS.md §9](DESIGN_TOKENS.md#9-migration-from-the-current-code)) |
| A2 | **Dark-mode primary fails contrast.** The partner portal draws white text on `--bv-color-primary`; in dark mode that is `#9d8cff`, ≈ 2.8:1 (needs 4.5:1). | `apps/business/src/app/[lang]/page.tsx` uses `text-white`; `packages/ui` defines `onPrimary` dark as `#14102b` but it isn't used | Unreadable primary buttons in dark mode | Always pair `primary` with `onPrimary`; new values in [DESIGN_TOKENS.md §2](DESIGN_TOKENS.md#2-colour) |
| A3 | **Brand colour too close to Fresha.** Provisional primary `#5b3fd9` is a saturated violet, the core of Fresha's identity. | `packages/ui/src/index.ts` | Beavent reads as a Fresha derivative | Emerald primary (§4) |
| A4 | **Template branding still shipping.** Splash background `#208AEF`, Android adaptive icon background `#E6F4FE`, Expo icons, web tab bar text "Expo Starter", tab labels "Home"/"Explore" hard-coded in English, starter assets (`react-logo*.png`, `tutorial-web.png`, `logo-glow.png` 331 KB, `icon.png` 800 KB) | `apps/public-mobile/app.json`, `app-tabs*.tsx`, `assets/images/` | Wrong brand; untranslated navigation; bundle near the 4 MB budget (doc 07, step 0.11 notes 3.9 MB) | Remove before Stage 2 screens ([AI_AGENT_DESIGN_INSTRUCTIONS.md §7](AI_AGENT_DESIGN_INSTRUCTIONS.md#13-implementation-order)) |
| A5 | **Oversized type in the mobile primitives.** `ThemedText` `title` is 48 px and `subtitle` 32 px; `linkPrimary` hard-codes `#3c87f7`. | `components/themed-text.tsx` | Headline-heavy screens; colours outside the token set | Type roles in [DESIGN_TOKENS.md §3](DESIGN_TOKENS.md#3-typography) |
| A6 | **Script-aware sizing is half done.** `lineHeightPx()` raises line height for Ge'ez, but nothing raises the *font size* to the 14 px Ethiopic minimum, so a 12 px caption in Amharic is still 12 px. | `packages/ui/src/index.ts` | Illegible Amharic captions | `Text` component applies `max(size, minSize)` ([COMPONENT_GUIDELINES.md §2.1](COMPONENT_GUIDELINES.md#21-text)) |
| A7 | **Language is screen state.** Mobile keeps the chosen language in `useState` inside the Home screen; nothing else can read it and it is lost on restart. | `app/index.tsx` | Every screen would re-implement language | App-level preference provider ([MARKETPLACE_NAVIGATION.md §7](MARKETPLACE_NAVIGATION.md#7-account-and-preferences)) |
| A8 | **`ThemedView` ignores `lightColor`/`darkColor`.** The props are destructured and dropped. | `components/themed-view.tsx` | Silent bug if anyone relies on them | Replace with token-based `Box`/`Screen` primitives |
| A9 | **Web tokens copied by hand.** `apps/web/src/app/globals.css` hard-codes 4 of the 10 colour tokens instead of using `cssVariables()`; `surface`, `textMuted`, semantic colours are missing on web. | `web/…/globals.css`, `business/…/globals.css` | Drift between apps as soon as values change | Generate from `@beavent/ui` ([DESIGN_TOKENS.md §9](DESIGN_TOKENS.md#9-migration-from-the-current-code)) |
| A10 | **Duplicated components across web apps** (fixed 19 Nov 2026 by merging the web apps). `EthiopianNow`, `LanguageSwitch` and `lib/i18n.ts` are byte-identical copies in `apps/web/` and `apps/business/`. The `packages/ui` README says shared components move there "as they appear in two apps". | `diff -r apps/web/src apps/business/src` | Two places to fix every bug | Decision needed on React components in `packages/` ([§8 open questions](#8-open-questions)) |
| A11 | **Admin console ignores the design system** (zinc palette, Arial, references to Geist font variables that are never loaded, status badges show raw enum codes like `AWAITING_INFO`). | `apps/admin-console/src/app/globals.css`, `components/ui.tsx` | Acceptable for an internal tool today; inconsistent later | Low priority; adopt tokens when the console is next touched |
| A12 | **Formatting gaps for marketplace screens.** `money.format()` always prints minor digits (`ETB 1,500.00`) — fine for receipts, too heavy for cards. `calendar.formatInstant()` prints one time convention; doc 06 requires both on appointments and tickets. `formatStandardTime()` is 24-hour while doc 06's example uses `9:00 AM`. | `packages/money`, `packages/calendar` | Each screen would hand-roll formatting (forbidden by doc 06) | Add display helpers ([DESIGN_TOKENS.md §8](DESIGN_TOKENS.md#8-formatting-tokens-money-dates-times)) |
| A13 | **Image pipeline has only two widths** (`w320`, `w960`) and no placeholder colour/blurhash. A 240 dp rail card on a 3× screen needs ~720 px. | Step 0.9, `files` module | Either blurry rails or heavy downloads | Rules in [MOBILE_UI_GUIDELINES.md §6](MOBILE_UI_GUIDELINES.md#6-images); `w640` + dominant colour proposed (§8) |
| A14 | **No map dependency.** "Map and near me" is P1, but neither the app nor web has a map library. | `apps/public-mobile/package.json`, `apps/web/package.json` | Map screens can't be built without a dependency decision | §8 |

**Status:** A1–A9 and A12 were fixed in the global UI implementation (19 Nov 2026); A10, A11 partly; A13, A14 remain open decisions. Details per item: [GLOBAL_UI_IMPLEMENTATION_REPORT.md](GLOBAL_UI_IMPLEMENTATION_REPORT.md).

### 6.4 Missing journeys (all marketplace work is ahead)

Everything in the Services and Events specs is unbuilt. The order the plan needs them in:

| Plan step | Consumer screens needed | Spec |
|---|---|---|
| 0.7 (done in API) | Sign-in by phone/email code or Google; profile | [MARKETPLACE_NAVIGATION.md C-6](MARKETPLACE_NAVIGATION.md#56-c-6-sign-in) |
| 1.1–1.4 (this branch) | Partner portal catalogue, team, policies, locations | [SERVICES §8](SERVICES_MARKETPLACE_DESIGN.md#8-provider-facing-partner-portal-catalogue-stage-1) |
| 2.5 | Venue profile, booking flow (pay at venue), Bookings tab, appointment detail, cancel, reschedule | SERVICES S-04 … S-14 |
| 2.6 | Waitlist | S-15 |
| 3.4, 3.6 | Payment step, "approve on your phone", deposits | C-7, S-10 |
| 6.8 | Event page, ticket selection, checkout, ticket wallet | EVENTS E-05 … E-14 |
| 8.1–8.3 | Explore, search, results, map, filters, saved, profiles, reviews | C-1, C-2, S-01 … S-03, E-01 … E-04, S-16, E-15 |

**Important sequencing fact:** discovery (Stage 8) ships *after* booking (Stage 2) and ticketing (Stage 6). Until then, customers arrive through a **direct link** shared by the business or organizer (`appointments.source = DIRECT_LINK`). So the venue profile and the event page must work as landing pages on their own — with no assumption that the user came from Explore, and with an obvious path to install the app or continue on the web.

## 7. Design decisions

Numbered so other docs and pull requests can cite them.

| # | Decision | Rationale |
|---|---|---|
| DD1 | **Emerald primary + Adey gold accent on a warm neutral canvas** (§4). Token *names* unchanged; values replaced. | Distinct from both benchmarks; accessible in both themes; calm and trustworthy for money and time. |
| DD2 | **Four consumer tabs at pilot — Explore · Saved · Bookings · Account — with Inbox added as a fifth when messaging ships.** Bookings holds appointments *and* tickets on one timeline. | One product (D1, doc 02 §3.9); the existing `nav.*` keys anticipate it. Reasoning and alternatives in [MARKETPLACE_NAVIGATION.md §2](MARKETPLACE_NAVIGATION.md#2-bottom-navigation-recommendation). |
| DD3 | **Detail screens and flows cover the tab bar**; tab roots and result lists show it. | A detail screen's sticky action bar and a tab bar must never stack. |
| DD4 | **Booking flow = full-screen modal stack**: Services → Professional → Time → Review → (Pay) → Confirmation. Steps are skipped when they have only one answer. | Fresha-proven sequence; skipping keeps independents (one professional) to three steps. |
| DD5 | **Ticket flow = full-screen modal**: (Date) → Tickets → Details → (Pay) → Confirmation. | Eventbrite-proven; reservation timer lives in the flow, not the event page. |
| DD6 | **Prices shown at selection are all-in**; breakdown on demand and on the review step. | Trust (P1). Needs an API answer on whether `price_from_minor` includes buyer fees (§8). |
| DD7 | **Dual time on every appointment and ticket.** Order follows the user's preference; default Ethiopian-first for `am`/`om`, Gregorian/standard-first for `en`. | Doc 06 §2.1 rule; no-show prevention. |
| DD8 | **No uppercase, no letter-spacing as hierarchy.** | Ge'ez has no case (P4). |
| DD9 | **Cards only for image-led tappable objects; rows with dividers for everything else.** | Calm density (P3, P7). |
| DD10 | **Sign-in is inline at the moment it is needed** (save, confirm booking, checkout), as a sheet; browsing is never gated. | Phone OTP is light enough to feel like a guest step; keeps discovery open for direct-link arrivals. |
| DD11 | **System font for Latin on mobile; Noto Sans Ethiopic for Ge'ez everywhere; Noto Sans for Latin on web.** | Keeps app size and cold start down (budget), keeps Ge'ez consistent. |
| DD12 | **A rail needs at least 3 items or it becomes part of a list.** | Pilot inventory is small (10–20 businesses, 3–5 organizers, doc 07 §10.1); one-card rails look broken. |
| DD13 | **Business mode swaps the whole tab bar** (Today · Calendar · Check-in · Sales · More, filtered by capability); the switch lives in Account and in the Business header. | Clear mental model: you are either buying or working. |
| DD14 | **RN components live in `apps/public-mobile/src/components/`; web components in each Next.js app until a shared-components decision is made.** `packages/ui` stays tokens + pure helpers. | No new dependencies in `packages/ui` (it has no React); avoids a premature cross-platform abstraction. |

## 8. Open questions

Decisions this documentation cannot make alone. Each blocks the screens noted.

| # | Question | Blocks | Recommendation |
|---|---|---|---|
| Q1 | Approve the visual direction (DD1) and commission a logo/wordmark, app icon and splash | Store builds (Stage 9), all screens' final polish | Approve the palette now so screens can be built; brand mark can follow |
| Q2 | Amharic (and Afaan Oromo) label for the unified **Bookings** tab. The current `am` string `ቀጠሮዎች` means *appointments* only | Tab bar | Native-speaking editor to choose a word covering appointments and tickets |
| Q3 | Map library for mobile (`expo-maps` or `react-native-maps`) and a web map provider; quality of Ethiopian map data | Results map (S-03, E-03), venue location preview | Decide before Stage 8; until then use a static "Open in maps" link |
| Q4 | Bundle Noto Sans Ethiopic on iOS (consistent glyphs, adds app size) or rely on Kefa | Typography consistency on iOS | Bundle it; measure against the 25 MB download budget |
| Q5 | Add a `w640` image variant and a dominant colour (or blurhash) per public image | Rail and card image quality; placeholders | Add both to the files pipeline (backend change) |
| Q6 | Does `discovery.listings.price_from_minor` (and ticket prices returned to buyers) include buyer fees? | DD6 all-in pricing on cards and ticket rows | Return both `price` and `allInPrice` from marketplace endpoints |
| Q7 | Standard-time format for consumers: 12-hour (`9:00 AM`, doc 06 example) or 24-hour (`09:00`, current `formatStandardTime`) | Every time display | Locale-driven: `en` 12-hour, `am`/`om` show standard time as 24-hour in parentheses; partner tools 24-hour |
| Q8 | Screen brightness for QR (`expo-brightness`) and haptics (`expo-haptics`) are not installed | Ticket pass, slot selection feedback | Both are Expo modules; approve when E-11 is built |
| Q9 | ~~Where shared React (web) components live (A10, DD14)~~ **Resolved 19 Nov 2026:** one web platform, so the shared components live in `apps/web/src/components` | — | A `packages/web-ui` for Next.js apps when the third duplicate appears |
| Q10 | Data-saver mode (force `w320`, no rails images on cellular) — user setting or automatic | Image rules | User setting in Account, default off; automatic later |
| Q11 | How reviewers are named publicly | Reviews (S-16, E-15) | Given name + first letter of father's name (`Selam T.`), per doc 06 naming |
| Q12 | Dark mode in the partner portal and admin console | Portal styling | Support it through tokens from day one in the portal; admin later |

**Owner decisions, 19 Nov 2026** (they override the recommendations above):

| # | Decision |
|---|---|
| Q1 | **Placeholder logo** until a real one is designed: the plain emerald "b" mark (app icon, splash, adaptive icon, favicons). Replace the image files only; no other code depends on them |
| Q2 | The Amharic UI uses the **English word "Bookings"** for the tab (`nav.bookings` in `am.ts`) until a native term covering appointments and tickets is chosen |
| Q3, Q8 | **Dependencies approved:** map library, `expo-brightness`, `expo-haptics`, `expo-camera`. Chosen packages: `expo-maps` (~58.0.11) for mobile maps (Expo-first rule in `apps/public-mobile/AGENTS.md`), MapLibre GL JS for web maps (tile provider still to choose), `expo-brightness` (~58.0.3) for the ticket pass, `expo-camera` (~58.0.14) for check-in. `expo-haptics` is installed and used; the others are installed by the stage that builds their screen (keeps unused native code out of the download) |
| Q4 | **Noto Sans Ethiopic is bundled** on every platform (`@expo-google-fonts/noto-sans-ethiopic`, weights 400/500/600 only) |
| Q7 | **12-hour international time with AM/PM** for people in every language (`9:00 AM`, `ጠዋት 3:00 (9:00 AM)`); partner and staff tools keep the 24-hour clock (doc 06 §2.1) |
| Budget | `mobileBundleKb` means gzip-compressed Hermes bytecode; enforced in CI by `npm run bundle:check -w @beavent/public-mobile` |
| Q9 / apps | **Apps, 19 Nov 2026:** two mobile apps — `apps/public-mobile` (customers) and `apps/business-mobile` (business owners, staff and organizers) — and **one web platform** `apps/web` containing the public site, the business and organizer console (`/[lang]/business`) and the admin console behind a secret path (`ADMIN_PATH`). The separate `business/` and `admin-console/` web apps were merged into it |
