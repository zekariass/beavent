# Beavent marketplace navigation and information architecture

**Status:** v1, 19 Nov 2026.
**Scope:** the consumer app (`public-mobile`), the business app (`business-mobile`, "Business mode" below) and the web platform's navigation. Screen IDs: `C-` shared screens (specified here), `S-` services ([SERVICES_MARKETPLACE_DESIGN.md](SERVICES_MARKETPLACE_DESIGN.md)), `E-` events ([EVENTS_MARKETPLACE_DESIGN.md](EVENTS_MARKETPLACE_DESIGN.md)), `B-` Business mode, `P-` partner portal.
**Current code:** `apps/public-mobile/src/app/_layout.tsx` renders `AppTabs` (native tabs *Home*, *Explore*) with no stacks; `index.tsx` is a localisation demo, `explore.tsx` is template content. Everything below is new.

---

## 1. Information architecture

```
Beavent (one account, one app)
├── Consumer mode
│   ├── Explore ─────────────── C-1  (scopes: All · Services · Events)
│   │   ├── Search ──────────── C-2  → Results: S-02 (places) / E-03 (events), Map S-03 / E-03
│   │   ├── Category landing ── S-02 / E-02
│   │   ├── Venue profile ───── S-04 → Service sheet S-05 → Booking flow S-06…S-10 → C-7 → Confirmation S-10b
│   │   ├── Event page ──────── E-05 → Ticket flow E-06…E-09 → C-7 → Confirmation E-10
│   │   └── Organizer profile ─ E-15
│   ├── Saved ───────────────── C-3  (places, events, professionals)
│   ├── Bookings ────────────── C-4  (appointments + tickets on one timeline: Upcoming · Past)
│   │   ├── Appointment ─────── S-11 → Reschedule S-12, Cancel S-13, Review S-16
│   │   └── Ticket order ────── E-12 → Ticket pass E-11, Transfer E-13, Refund E-14, Review E-18
│   ├── (Inbox) ─────────────── added when messaging ships (§2.4)
│   └── Account ─────────────── C-5  → Profile, Addresses, Preferences, Notifications C-8, Help, "Beavent for business" (opens the web console)
├── Sign-in (sheet, on demand) ─ C-6
(separate app, apps/business-mobile) Business: B-1 Today/Schedule · B-2 Calendar · B-3 Check-in · B-4 Sales · B-5 More
```

Three ideas carry the structure:
1. **Discovery is one tree with two branches.** Explore, Search and Saved hold both kinds of listing (`discovery.listings.kind` `LOCATION | EVENT`). Scope tabs and filters narrow them; nothing forks into separate apps.
2. **Everything you have planned is in one place.** Bookings interleaves appointments and tickets by date, because "what am I doing on Saturday?" is one question.
3. **Detail and checkout are above the tabs.** Profiles, event pages and flows are pushed over the tab bar (DD3), so their sticky action bars own the bottom of the screen.

## 2. Bottom navigation recommendation

### 2.1 Recommendation

**Pilot (Stage 10): four tabs — Explore · Saved · Bookings · Account.**
**When in-app messaging ships (Inbox, doc 02 §2.12): five tabs — Explore · Saved · Bookings · Inbox · Account.**

| Tab | i18n key | Icon (outline / filled when selected) | Root screen | Badge |
|---|---|---|---|---|
| Explore | `nav.explore` (exists) | magnifying glass | C-1 | — |
| Saved | `nav.saved` (new) | heart | C-3 | — |
| Bookings | `nav.bookings` (exists; the Amharic UI shows the English word "Bookings" — decision Q2) | calendar | C-4 | Dot when something needs action (payment pending, request answered, review requested) |
| Inbox (later) | `nav.inbox` (new) | message bubble | Conversations + notifications | Unread count |
| Account | `nav.account` (exists) | person circle | C-5 | Dot when profile/verification needs attention |

`nav.tickets` (exists) stays as the label of the Tickets filter inside Bookings; it is not a tab.

### 2.2 Why this structure

1. **It matches the product's central claim** — one app for a nail appointment on Friday and a concert on Saturday (doc 02 §3.9). A single Bookings tab is the clearest expression of that; separate "Appointments" and "Tickets" tabs would make Beavent feel like two apps sharing a tab bar.
2. **It matches what the codebase already anticipated.** The i18n catalogue already contains `nav.explore`, `nav.bookings`, `nav.tickets`, `nav.account`. The recommendation reuses three of them and repurposes `nav.tickets` as a filter.
3. **Saved earns a tab** because both benchmarks show saving is a core loop: events are saved days or weeks before buying (Eventbrite's "Likes"), and people re-book favourite professionals (Fresha favourites; `discovery.saved_items` supports professionals as well as listings).
4. **Search is not a tab.** The search field is the first thing on Explore (one tap away, same as a tab), and a separate Search tab would duplicate Explore. This follows the Airbnb/Fresha pattern rather than Eventbrite's separate Search tab.
5. **Tickets at the door stay one tap away** without a Tickets tab: the Bookings tab opens on Upcoming with the **Next up** item first, and its primary action is "Show ticket". Tickets are cached for offline use, so this works with no signal ([EVENTS E-11](EVENTS_MARKETPLACE_DESIGN.md#e-11-ticket-pass-qr)).
6. **Four tabs fit Amharic labels** at 360 dp with the 14 px Ge'ez minimum; five still fit (≈ 72 dp per tab). Six would not.
7. **No empty tabs at launch.** Inbox waits for real two-way messaging; until then notifications live in Account (C-8) and are pushed to the device.

### 2.3 Alternatives considered

| Option | Tabs | Why not |
|---|---|---|
| Vertical tabs | Explore · Bookings (appointments) · Tickets · Account | Splits "my plans" by vertical; two half-empty tabs for most users; contradicts the one-product decision. Kept as the fallback if user research shows people can't find tickets in Bookings |
| Eventbrite-style | Explore · Search · Saved · Tickets · Account | Separate Search duplicates Explore; no home for appointments |
| Mode switch at the top | Services ⇄ Events toggle with separate tab sets | Two apps in one binary; doubles navigation code; hides cross-vertical value (spa retreats, salon masterclasses) |
| Five tabs at pilot with an empty Inbox | — | An empty tab is a broken promise; adds nothing until messaging exists |

### 2.4 Phasing

| Stage | Tabs | Notes |
|---|---|---|
| 2–6 (internal builds) | Explore (minimal: recently viewed, deep-link entry), Bookings, Account | Discovery API arrives in Stage 8; customers arrive by direct links |
| 8 → pilot | Explore · Saved · Bookings · Account | Full discovery |
| After messaging (Stage 11) | + Inbox | Notifications move from Account to Inbox |

## 3. Routes

### 3.1 Mobile route map (Expo Router, proposed)

Typed routes are on (`experiments.typedRoutes`). Groups keep URLs clean; `(consumer)` contains the tabs, everything else is a sibling of the tab group in the root stack so it covers the tab bar.

```
apps/public-mobile/src/app/
├── _layout.tsx                         Root Stack: preferences, API/query client, theme from tokens, splash
├── (consumer)/
│   ├── _layout.tsx                     NativeTabs: explore · saved · bookings · account
│   ├── explore/_layout.tsx             Stack inside the tab (tab bar stays visible)
│   ├── explore/index.tsx               C-1 Explore
│   ├── explore/results.tsx             S-02 / E-03 results (query, scope, filters in params)
│   ├── explore/category/[code].tsx     S-02 / E-02 category landing
│   ├── saved/index.tsx                 C-3
│   ├── bookings/index.tsx              C-4
│   └── account/{index,profile,addresses,preferences,notifications,help}.tsx   C-5, C-8
├── search.tsx                          C-2 (full-screen modal, fade)
├── place/[slug]/index.tsx              S-04 venue profile        (slug = listing slug)
├── place/[slug]/reviews.tsx            S-16 reviews list
├── place/[slug]/gallery.tsx            Full-screen gallery viewer
├── event/[slug]/index.tsx              E-05 event page
├── organizer/[slug].tsx                E-15 organizer profile
├── book/[locationId]/_layout.tsx       Booking flow modal stack (S-06 … S-10b)
│   └── {services,professional,time,review,done}.tsx
├── tickets/[eventId]/_layout.tsx       Ticket flow modal stack (E-06 … E-10)
│   └── {dates,select,details,review,done}.tsx
├── pay/[orderId].tsx                   C-7 payment waiting / redirect return
├── appointment/[id].tsx                S-11
├── order/[id].tsx                      E-12 ticket order
├── ticket/[id].tsx                     E-11 ticket pass (full-screen)
├── review/[kind]/[id].tsx              S-16 / E-18 write a review (kind = appointment | order)
├── sign-in.tsx                         C-6 (modal sheet presentation)
```

The business app is a separate project (`apps/business-mobile`): `src/app/(tabs)/{index (Schedule), more}.tsx` today,
with `calendar`, `check-in` and `sales` added by their stages (§6).

**Implemented so far:** `(tabs)/index.tsx` (Explore, interim), `(tabs)/bookings.tsx`, `(tabs)/account.tsx`, `sign-in.tsx` (modal) in `public-mobile`; Business mode (Schedule, More) moved to `apps/business-mobile` on 24 Nov 2026. The tab group is named `(tabs)` rather than `(consumer)`.

Template files removed in that change (kept here for the record): `src/app/explore.tsx`, `src/components/app-tabs.web.tsx` "Expo Starter" bar, `hint-row.tsx`, `web-badge.tsx`, starter images (A4).

### 3.2 Web marketplace URLs (`apps/web/`)

Language prefix stays first (existing `[lang]` segment). Listing URLs reuse `discovery.listings.slug_path`.

| Path | Screen |
|---|---|
| `/{lang}` | C-1 Explore (web home) |
| `/{lang}/search?q=&scope=&date=&…` | Results (list + map side by side ≥ 1024) |
| `/{lang}/{city}/{category}` e.g. `/am/addis-ababa/hair-salons` | SEO category landing (S-02 / E-02) |
| `/{lang}{slug_path}` e.g. `/en/addis-ababa/salon/abeba-beauty-bole` | S-04 venue profile |
| `/{lang}/events/{slug}` | E-05 event page |
| `/{lang}/o/{orgSlug}` | Organizer / brand page (E-15, multi-location brand page) |
| `/{lang}/book/{locationId}/…` and `/{lang}/tickets/{eventId}/…` | Flows (web can use a right-hand panel instead, §8) |
| `/{lang}/account/bookings`, `/{lang}/account/…` | C-4, C-5 |
| `/embed/{book|tickets}/…` | Widgets (Stage 8.6), no site chrome |

### 3.3 Deep links and universal links

- Scheme `beavent://` (already set in `app.json`) plus universal/app links for the web domain (configure when the domain exists). **The same path opens the same screen** on web and in the app: `/{lang}/events/{slug}` → `event/[slug]`; `{slug_path}` → `place/[slug]`.
- Notification and SMS links: appointment reminders → `appointment/[id]`; ticket delivery → `order/[id]`; review requests → `review/[kind]/[id]`; transfer acceptance → `order/[id]?transfer=…`; payment return → `pay/[orderId]`.
- Arriving by link with no history: the back button on the first screen goes to the tab root that owns it (place/event → Explore; appointment/order → Bookings), never closes the app.
- Link to a listing that is unpublished/delisted: a full-screen EmptyState "This page isn't available" + "Explore" (no 404 jargon).

## 4. Navigation behaviour

| Situation | Behaviour |
|---|---|
| Opening a profile, event page, appointment, order | **Push** (slide from the trailing edge), over the tab bar |
| Booking and ticket flows | **Full-screen modal stack** (slide up). Close (×) on the first step; back chevron on later steps |
| Closing a flow with selections made | Confirmation sheet: "Leave booking? Your selections won't be saved." (Leave / Keep booking). After a hold exists: "Leave? The time we're holding for you will be released." |
| Search | Full-screen modal with a fade; Cancel returns to where it was opened |
| Filters, sort, service detail, cancel confirmation, sign-in | **Bottom sheet** |
| After confirmation (booking/tickets) | "Done" dismisses the flow and **switches to the Bookings tab** showing the new item highlighted for 2 s; the profile/event page is removed from the stack |
| Back (iOS swipe, Android back, header back) | Pops one level. Android back on a tab root (not Explore) → Explore; on Explore → exits. In flows: back = previous step; on step 1 = close (with confirmation if needed) |
| Re-tapping the active tab | Scrolls to top; a second re-tap on Explore focuses search |
| Tab state | Each tab keeps its own stack and scroll position when switching tabs |
| Predictive back (Android) | Disabled today (`predictiveBackGestureEnabled: false` in `app.json`); keep disabled until flows are tested with it |
| Sign-in gating (DD10) | Browsing, search, profiles, event pages: open. Save, confirm booking, checkout, follow, review: open the sign-in sheet (C-6) and **continue the original action** after success |
| Web | Browser back works through every step (each step has a URL); flows on desktop open as a right-hand panel on the detail page |

## 5. Shared screens

Each spec: purpose · layout · content and data · actions · navigation · states · responsive · interaction details.

### 5.1 C-1 Explore

**Purpose.** Start any plan: search, browse by category or date, return to favourites, and see what's next.

**Layout (360 dp, All scope):**
```
┌──────────────────────────────────────┐
│ Bole, Addis Ababa ▾                  │  location selector (scrolls away)
│ ┌──────────────────────────────────┐ │
│ │ ⌕  Search services, places, events│ │  SearchField entry  ┐ sticky
│ └──────────────────────────────────┘ │                     │
│  All    Services    Events           │  ScopeTabs          ┘
│ ─────                                │
│ ▌Tonight · ማታ 2:00 (20:00)            │  Next-up row (only if a plan is within 48 h)
│ ▌Addis Jazz Night      Show ticket › │
│                                      │
│ (Hair) (Braids) (Nails) (Music) (Ma… │  CategoryChips (mixed, most used first)
│                                      │
│ This weekend                 See all │  Section (title2)
│ ┌────────────┐ ┌──────────          │  EventCard rail
│ │  2:1 image │ │                     │
│ └────────────┘ └──────────          │
│ Sat, Tahsas 6 · ማታ 2:00               │
│ Addis Jazz Night                     │
│ Fendika · from Br 500                │
│                                      │
│ Top-rated near you           See all │  VenueCard rail
│ …                                    │
├──────────────────────────────────────┤
│  Explore   Saved   Bookings  Account │  native tabs
└──────────────────────────────────────┘
```

**Modules by scope** (order fixed; a module is hidden when it has fewer than 3 items, DD12; maximum 6 modules):

| All | Services ([S-01](SERVICES_MARKETPLACE_DESIGN.md#s-01-explore--services-scope)) | Events ([E-01](EVENTS_MARKETPLACE_DESIGN.md#e-01-explore--events-scope)) |
|---|---|---|
| Next up (conditional) | Next up (appointments only) | Next up (tickets only) |
| Category chips (mixed) | Category chips (`SERVICE`) | Date chips + category chips (`EVENT`) |
| Book again (signed in, past visits) | Book again | From organizers you follow |
| This weekend (events) | Top-rated near you | This weekend |
| Top-rated near you (venues) | Available today | Popular in {city} |
| Classes & workshops (events `CLASS_SERIES` + `EDUCATION`) | At-home professionals (if `AT_HOME_SERVICES` enabled) | Free events |
| New on Beavent (venues) | New on Beavent | Online events |

When the market has little inventory (pilot), modules that fall below 3 items disappear and a single "All places and events in Addis Ababa" vertical list (rows S-02/E-03 style) fills the screen instead — so the screen never shows one-card rails or a near-empty page.

**Data.** `discovery.listings` via the Stage 8 search API (title, image, rating, `price_from_minor`, `next_available_at` / `next_starts_at`, `geo`, `admin_area_path`, `attributes`); the user's upcoming plans; saved items; categories `GET /v1/ref/categories`.

**Actions.** Primary: search. Secondary: chips, "See all" (→ results with the module's filter), cards (→ S-04 / E-05), save on cards, location selector (sheet: "Use my location" + sub-city list from admin areas, recent areas).

**States.**
- Loading: skeleton of search field + chips + two rails (image boxes + 3 text bars). Search field and scope tabs are interactive immediately.
- Empty market / no results for the area: EmptyState "Nothing listed in Kirkos yet" + "Show all of Addis Ababa".
- Error: ErrorState in the content area; search still works.
- Offline: OfflineBanner; show the last cached feed (up to 24 h old) with "Updated 2 h ago" `caption`; Next up still works from the offline cache.
- Signed out: no Book again / Following modules; nothing else changes.

**Responsive.** ≥ 600 dp: rails become 2-up grids per module (no horizontal scroll), max width 800. Web ≥ 1024: search becomes a large inline field with "What / Where / When" segments (Airbnb-style), modules as 4-up grids.

**Interaction.** Location defaults to the last chosen area, else the device location (asked only when the user taps "Use my location" — never on first launch), else the market's main city. Pull to refresh. Rails lazy-load images only when within one screen of the viewport.

### 5.2 C-2 Search

**Purpose.** Find a specific thing fast, in any script.

**Layout.** Active SearchField at top with Cancel; scope chips under it (All · Services · Events, preselected from Explore's scope); below, content depends on input:
- **Empty input:** Recent searches (rows with a clock icon; "Clear" tertiary), Popular categories (chips), "Near me now" row (services open now nearby).
- **Typing (≥ 2 Latin chars or 1 Ge'ez syllable):** suggestions grouped under `label` headers — Categories, Places, Events, Professionals — max 3 each, with the matched text in `bodyStrong`. Transliteration is handled server-side (doc 06 §5.5): "bole" and "ቦሌ" give the same results.
- **Submit:** goes to Results (S-02 / E-03 depending on scope; All shows a mixed list with "Places" and "Events" sections, each with "See all").

**States.** No suggestions → "Search for '…'" row still submits. Network error while typing → keep showing recent searches, no error flash; error only on submit. Offline → "Search needs a connection" inline.

**Interaction.** Keyboard opens on entry; scrolling suggestions dismisses the keyboard; the voice/microphone button is not included (no reliable Amharic speech recognition — revisit later).

### 5.3 C-3 Saved

**Purpose.** Come back to places, events and professionals the user cares about.

**Layout.** `tabRoot` header "Saved"; filter chips **All · Places · Events · Professionals** (only types the user has); content:
- Events first when any are upcoming (sorted by date) as EventResultRows; then Places as VenueResultRows without the services sub-list; then Professionals as ListRows (avatar 56, name, venue name, "Book" `secondary` `sm` → booking flow with that professional preselected).
- Past events move to a collapsed "Past events" section at the bottom.

**States.** Signed out: EmptyState "Save places and events you like" + "Sign in" (`primary`). Empty: "Nothing saved yet" + "Tap the heart on any place or event to save it here." + "Explore". Offline: cached list, remove/save disabled with explanation. Sold-out/cancelled saved events show their StatusLabel.

**Interaction.** Unsave by tapping the heart → row stays with "Removed" + Undo toast; it disappears on leaving the screen. Saving from anywhere shows a toast "Saved" with "View" action. Following an organizer (E-15) is separate from saving and is listed in Account → Following (not here), to keep Saved about things you might book.

### 5.4 C-4 Bookings

**Purpose.** Everything you have planned or done, appointments and tickets together.

**Layout:**
```
┌──────────────────────────────────────┐
│ Bookings                             │  title1 (tabRoot)
│ ( Upcoming │  Past )                 │  SegmentedControl
│ (All) (Appointments) (Tickets)       │  chips, only if both kinds exist
│                                      │
│ ▌┌──────────────────────────────┐    │  Next up (PlanRow large, gold bar)
│ ▌│ 2:1 event image               │    │
│ ▌└──────────────────────────────┘    │
│ ▌Tonight · ማታ 2:00 (20:00)            │
│ ▌Addis Jazz Night · Fendika          │
│ ▌[ Show ticket ]                     │
│                                      │
│ Saturday, Tahsas 6                   │  day header (label, both calendars)
│ ┌──┐ Box braids, Wash & blow-dry   › │  PlanRow
│ │SAT│ Abeba Beauty · ጠዋት 3:00 (9:00)   │
│ │ 6│ Pending payment                 │  StatusLabel (warning)
│ └──┘                                 │
│ ─────────────────────────────────    │
│ …                                    │
└──────────────────────────────────────┘
```

**Content.** Upcoming: appointments with status `REQUESTED`, `PENDING_PAYMENT`, `CONFIRMED`, `ARRIVED`, `IN_PROGRESS`; ticket orders with valid tickets for future occurrences; postponed events. Grouped by local date of the venue/occurrence. Past: completed, cancelled, no-show appointments and past/cancelled/refunded ticket orders, newest first, with inline actions on the row ("Book again" for appointments, "Leave a review" when eligible and not yet reviewed).

**Actions.** Row → S-11 appointment or E-12 order. Next-up action: "Show ticket" (→ E-11 directly) or "Directions" (appointment). Pending payment rows show "Pay now" `secondary` `sm` until the hold/order expires.

**States.**
- Signed out: EmptyState "Your bookings and tickets live here" + "Sign in" + `tertiary` "Bought tickets without an account? Sign in with the same phone number to see them." (guest orders are claimed after OTP to the same number — plan step 6.4).
- Empty upcoming: "No upcoming plans" + "Explore". Empty past: "Nothing here yet".
- Loading: skeleton rows (date block + 2 bars).
- Offline: OfflineBanner; full Upcoming list from cache (tickets with QR, today's appointments — doc 03 §1.1); actions that need the network (cancel, reschedule, transfer) disabled with "Needs a connection".
- Error: ErrorState with cached data shown if available.

**Interaction.** Pull to refresh; the badge dot on the tab clears when the user opens the item that needed attention. Ticket rows for today move to Next up 3 hours before start.

### 5.5 C-5 Account

**Layout.** Header: avatar 56 + name (`title2`) + phone/email (`label` `textMuted`) → Profile. Then grouped lists (`surface` grouped sections with rows, the only place inset-grouped styling is used):
1. **Your account:** Profile (given name, father's name — optional, labelled "Father's name", never "Surname"; photo; email), Addresses (AddressPicker list), Following (organizers), Payment methods (only when saved instruments exist, P2).
2. **Preferences (§7):** Language, Calendar, Time, Appearance, Data saver, Notifications (C-8).
3. **For business:** if a member → "Switch to {Org name}" (→ Business mode; org picker if several); else "List your business on Beavent" (opens the partner portal sign-up on the web via `expo-web-browser`).
4. **Help and legal:** Help & support, Terms, Privacy, Delete account (with step-up OTP).
5. Sign out (`destructiveTertiary`, centred).

**States.** Signed out: header becomes "Sign in or create an account" with `primary` button; Preferences and Help remain available (language and calendar must work before sign-in).

### 5.6 C-6 Sign-in

**Purpose.** Identify the person with the least friction, at the moment it is needed (DD10). Matches the API: one-time code to phone or email (one step for new and existing people), Google, Apple on iOS (doc 07 §1, step 0.7).

**Flow (bottom sheet, `full` height on phones):**
1. **Start:** title `title1` contextual ("Sign in to confirm your booking" / "Sign in to save" / "Sign in"); PhoneField (market calling code); `primary` "Continue"; divider "or"; `secondary` "Continue with Google"; `secondary` "Continue with Apple" (iOS only); `tertiary` "Use email instead". Legal line `caption`: "By continuing you agree to the Terms and Privacy Policy" with links (current published versions; consent recorded via `/v1/me/consents`).
2. **Code:** "Enter the code sent to 091 123 4567" + "Change" tertiary; CodeField (auto-submit, SMS autofill, resend after 30 s, Telegram fallback when available).
3. **New person only — name:** "What should we call you?" Given name (required), father's name (optional, helper "Helps businesses recognise you"). `primary` "Continue".
4. Sheet closes and **the original action continues** (booking confirmation, save, checkout).

**States/errors.** Wrong code: CodeField error "That code isn't right. 4 tries left." (5 attempts per code). Expired: "This code expired" + "Send a new code". `429 RATE_LIMITED`: Banner "Too many attempts. Try again in 10 minutes." with the remaining time. SMS not arriving after 30 s: show the Telegram/voice options. Google/Apple failure: inline error, phone remains available.
**Step-up** (sensitive actions, `403 STEP_UP_REQUIRED`): the same CodeField in a smaller sheet "Confirm it's you" with the masked destination; never asks for anything else.

### 5.7 C-7 Payment and payment waiting

Payment *method choice* is part of each flow's review step ([S-10](SERVICES_MARKETPLACE_DESIGN.md#s-10-review-and-confirm), [E-09](EVENTS_MARKETPLACE_DESIGN.md#e-09-review-and-pay)) using PaymentMethodList. C-7 is what happens after "Pay":

| Method interaction (doc 07 step 3.3) | Mobile app | Web |
|---|---|---|
| Push prompt (telebirr, CBE Birr, M-PESA via Chapa direct charge) | PaymentWaiting screen (COMPONENT 6.16): "Approve the payment on your phone" | Chapa hosted checkout |
| Redirect / hosted checkout (cards, bank apps) | Opens Chapa checkout in an in-app browser (`expo-web-browser`, already a dependency); returns via `beavent://pay/{orderId}`; then the same checking state | Redirect and return to `/{lang}/pay/{orderId}` "Checking payment…" page |

**States.**
- **Waiting** (polling intent status; WebSocket when available): title, amount, steps, HoldTimer, "I've approved it", "Use another method".
- **Succeeded:** replace with the flow's confirmation screen (S-10b / E-10). Only after server verification (P1: no success UI before the backend says so).
- **Failed / cancelled by customer:** `danger` Banner "Payment didn't go through" + reason when known ("Not enough balance", "You cancelled on your phone") + `primary` "Try again" + `secondary` "Choose another method". The hold stays until it expires.
- **Unknown / slow** (status `UNKNOWN`, poll still running): `warning` Banner "This is taking longer than usual" + "Your time is held until 3:42 PM. We'll send you a message when it's confirmed." + `secondary` "Back to Bookings" (the item shows "Pending payment").
- **Hold expired before success:** sheet "Your time slot was released" / "Your tickets were released"; if payment later succeeds, the backend refunds or re-confirms (doc 03 §3.1 step 6) and the user is notified — the UI never promises the slot after expiry.
- **App backgrounded / killed:** on return, `pay/[orderId]` re-checks immediately; a push notification links back to it.

**Interaction.** No countdown ring; no "Do not close this screen" scare text (it is safe to leave). The amount is shown exactly with minor digits (`ETB 360.00`) since this is a payment surface.

### 5.8 C-8 Notifications

List of notifications (booking confirmed, reminder, ticket delivered, event changed, request answered, review request, refund status) grouped Today / Earlier; each row: one-line title `bodyStrong`, one-line body `label` `textMuted`, time `caption`; unread rows have an 8 dp `primary` dot. Tapping deep-links (§3.3). Settings link at the top right ("Settings" tertiary → notification preferences: channels SMS/Telegram/email/push per category, quiet hours — `comms.preferences`). Empty: "No notifications yet".

## 6. Business app navigation (`apps/business-mobile`)

A separate app (decision D20): owners, staff and organizers install it and sign in with the same account. People who aren't in a business yet see "Create a business on the web". Implemented tabs today: **Schedule** (B-1, as "My schedule") and **More** (B-5: preferences, sign out); the rest are built with their stages.

| Tab | Shown when | Root |
|---|---|---|
| Today (B-1) | Always | Day timeline across staff (or "My schedule" for staff without calendar permissions), arrivals, unpaid balances |
| Calendar (B-2) | `APPOINTMENTS` capability + calendar permission | Day/week by staff or resource |
| Check-in (B-3) | `EVENTS` capability + event staff assignment | Event/occurrence picker → scanner |
| Sales (B-4) | POS permission (Stage 4) | Checkout/POS |
| More (B-5) | Always | Customers, messages, sales snapshot, own schedule, settings, "Switch to personal" |

Rules: the app has its own tab bar (DD13); a persistent header shows the organization name and location (switchable when the member has several); every Business mode screen shows a thin `primary` 2 px line at the very top of the header so screenshots and staff can tell the mode at a glance; switching back is always in More and in the header's org menu. Door staff with only an event assignment see only Check-in and More. Requirements per screen: [SERVICES §9](SERVICES_MARKETPLACE_DESIGN.md#9-provider-facing-mobile-business-mode) and [EVENTS §9](EVENTS_MARKETPLACE_DESIGN.md#9-organizer-facing-requirements).

## 7. Account and preferences

Preferences are app-wide state (fixes A7), stored on the device and synced to the profile when signed in (`/v1/me` PATCH; `iam.users.display_calendar`, `preferred_language`).

| Preference | Options | Default | Effect |
|---|---|---|---|
| Language | አማርኛ · English · Afaan Oromoo (`LANGUAGES`) | Device language negotiated, else Amharic | All UI text; content language fallback chain (doc 06 §5.1) |
| Calendar | Ethiopian · Gregorian | Ethiopian for `am`/`om`, Gregorian for `en` (DD7) | Primary calendar in DateTimeText, DateStrip |
| Time | Ethiopian time · International time | Ethiopian for `am`, international for `en`/`om` | Primary clock in DateTimeText, TimeSlotGrid |
| Appearance | System · Light · Dark | System | Theme |
| Data saver | Off · On | Off (Q10) | Forces `w320` images, disables rail images on cellular (text-only rails), no gallery prefetch |
| Notifications | Per category × channel | Transactional on, marketing off until consent | `comms.preferences`, `crm.customer_consents` |

The language row shows each language in its own name; changing language re-renders immediately without restart.

## 8. Web marketplace navigation

- **Header (desktop):** wordmark left; search ("What · Where · When") centre on Explore, compact search button elsewhere; right: language switch (existing `LanguageSwitch`, restyled as a menu), "For business" link (→ partner portal), Saved, Bookings, avatar menu (or "Sign in"). **Mobile web:** wordmark + search icon + menu; no imitation of the app's tab bar.
- **Flows on desktop:** the venue/event page keeps a sticky right column (360 px) with the booking/ticket box; steps open in that column (services basket, then professional/time) and the review step opens as a centred panel. Each step has its own URL.
- **Footer:** about, help, for business, legal, language, social. Plain text links.
- **Install prompts:** one dismissible smart banner on mobile web ("Open in the Beavent app") — never an interstitial; never on checkout pages.

## 9. Entry points summary

| Need | Entry points |
|---|---|
| Search | Explore search field (C-1); re-tap Explore tab; web header search; "See all" links into results |
| Book a service | Venue profile "Book now"; a service row's add control; VenueResultRow service lines; Saved professional "Book"; Bookings "Book again" |
| Buy tickets | Event page "Get tickets"; EventCard → page; organizer profile event rows; notification links |
| Manage an appointment | Bookings → S-11; reminder notifications/SMS; confirmation "Done" |
| Show a ticket | Bookings Next up "Show ticket"; Bookings → order → pass; ticket delivery link; works offline |
| Profile & account | Account tab; avatar in web header |
| Switch to business | Account → For business; Business header org menu; More tab |
