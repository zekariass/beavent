# Beavent services marketplace design

**Status:** v1, 19 Nov 2026. **Benchmark:** Fresha (see [DESIGN_REFERENCE_CATALOG.md §1](DESIGN_REFERENCE_CATALOG.md#1-fresha--services-marketplace)).
**Builds on:** [MARKETPLACE_NAVIGATION.md](MARKETPLACE_NAVIGATION.md) (shared screens C-1…C-8), [COMPONENT_GUIDELINES.md](COMPONENT_GUIDELINES.md), [DESIGN_TOKENS.md](DESIGN_TOKENS.md).
**Data sources:** catalog API on this branch (`backend/src/main/openapi/catalog`), tenancy (`Location`, `OpeningHours`), `db/migration/V1_09__scheduling.sql` (appointments, booking policies, holds), `V1_19__discovery_and_trust.sql` (listings, reviews, saved items), doc 03 §3.1 (booking workflow).
**Current implementation:** none of these screens exist. Plan steps: catalogue and team 1.1–1.4 (this branch, partner portal), consumer booking 2.5, waitlist 2.6, payments 3.4–3.6, discovery and reviews 8.1–8.3.

---

## 1. Journey

```
Arrive ─┬─ Explore (S-01) ─ Search / Category (S-02) ─ Map (S-03) ─┐
        └─ Direct link from the business (Stages 2–7: the main way in) ─┤
                                                                       ▼
                                                       Venue profile (S-04)
                                       ┌──────────── Service sheet (S-05), Professional sheet (S-17)
                                       ▼
 Booking flow (modal):  Services (S-06) → Professional (S-07)* → Time (S-08) → Details (S-09)* → Review (S-10)
                                                          * skipped when there is nothing to choose
                                                                       │
                                     ┌──── pay at venue / request ─────┤──── deposit or prepay ───┐
                                     ▼                                                            ▼
                             Confirmation (S-10b)  ◀──────────────────────────────  Payment waiting (C-7)
                                     │
                       Bookings tab (C-4) → Appointment (S-11) → Reschedule (S-12) · Cancel (S-13)
                                     │                         → after the visit: Review (S-16) · Book again (S-14)
                                     └── no slots → Waitlist (S-15) → Offer → Review (S-10)
```

**What "approaching Fresha" means here:** a person who has never used Beavent can go from a shared link to a confirmed appointment in under a minute with no account in advance (sign-in is one inline phone code), seeing exact prices, durations, the professional and the policy before confirming.

## 2. Services-specific display rules

These apply to every screen below.

| Topic | Rule | Source |
|---|---|---|
| Price | Use `PriceText` with the service's `priceType`. Ranges across variants or staff: "Br 1,200 – 2,400"; on rows show the lowest with "from" when there's a range. Prices are tax-inclusive (no tax line) | `ServiceVariant.priceType/priceMinor`, `ResolvedPrice`, `ref.markets.prices_include_tax` |
| Duration | Total customer-facing duration of the variant (`durationMinutes`), not buffers. Processing stages don't change what the customer sees; they only free the professional | `ServiceVariant.durationMinutes`, `stages` |
| Times | Location's time zone (`Africa/Addis_Ababa` today), dual display per [tokens §8.3](DESIGN_TOKENS.md#83-times). If the device's zone differs: `caption` "Times are in Addis Ababa time" on the Time step and appointment detail | `tenant.locations` time zone |
| Address | Landmark-first: `landmark` line first, then area (`admin_area`), then street. "Directions" opens the platform maps app with `geo` | `Location` |
| Audience | Only show "Women only" / "Men only" / "Children" when the service (or most of the menu) differs from Everyone | `Service.audience` |
| Delivery | "At your place" only for `AT_CUSTOMER` services; venue-level "At-home visits" fact when the location's `deliveryMode` is `MOBILE_ONLY` or `BOTH` | `Service.deliveryModes`, `Location.deliveryMode` |
| Policy | Always state, before confirmation: the free-cancellation deadline (as a date/time, not "24 h"), late-cancel and no-show fees as amounts, deposit amount, request-to-book | `scheduling.booking_policies` (most specific wins) |
| Rating | Location rating from verified visits; staff ratings only with ≥ 3 reviews | `discovery.reviews` |
| Hidden services | `status = HIDDEN` or `onlineBooking = false` services never appear to consumers | `Service.status`, `onlineBooking` |

---

## 3. Discovery

### S-01 Explore — Services scope

**Purpose.** Help someone who knows the *kind* of service (braids, massage) but not the place.
**Layout.** C-1 shell (location, search, scope tabs) with the Services modules from [MARKETPLACE_NAVIGATION.md §5.1](MARKETPLACE_NAVIGATION.md#51-c-1-explore): Next up (appointments) → service category chips (`SERVICE` categories: Hair, Braids & locs, Nails, Makeup, Brows & lashes, Facials & skin care, Hair removal, Bridal, Massage, Body treatments, Personal training) → Book again → Top-rated near you → Available today → At-home professionals → New on Beavent.
**Content.**
- *Book again* (signed in, ≥ 1 completed appointment): VenueCards for past venues with a third line "Last: Box braids · with Hana"; tapping the card opens S-04; a `secondary` `sm` "Book again" under the card opens S-14 directly.
- *Available today*: VenueCards whose `next_available_at` is today, third line "Next: ከሰዓት 4:00 (16:00)".
- *At-home professionals*: VenueCards for `MOBILE_ONLY`/`BOTH` locations serving the selected area, third line "Travel fee from Br 150".
**Actions.** Category chip → S-02 for that category; "See all" → S-02 with the module's filter (e.g. Open now, At home).
**States, responsive.** As C-1.

### S-02 Results and category landing (places)

**Purpose.** Compare places for a need and pick one.

**Layout:**
```
┌──────────────────────────────────────┐
│ ‹  Braids & locs                     │  stack header (query or category name)
│    Any time · Bole                   │  label textMuted (When · Where); tap → edit sheet
│ (Sort ▾)(When ▾)(Price ▾)(4+ ★)(Open… │  filter chips (scroll)
│ 48 places                            │  label textMuted
│ ┌────┐ Abeba Beauty Studio           │  VenueResultRow
│ │104 │ ★ 4.8 (212)                   │
│ │ ×  │ Bole · 1.2 km                 │
│ │104 │                               │
│ └────┘ Box braids — medium   Br 1,800│  matching services (≤ 3)
│        3 h 30 min                    │
│        Knotless braids    from Br 2,2│
│        4 h – 5 h                     │
│        See all 14 services           │
│ ──────────────────────────────────── │
│ …                                    │
│              ( ◎ Map )               │  floating pill (secondary), hidden until Q3
├──────────────────────────────────────┤
│  Explore   Saved   Bookings  Account │
└──────────────────────────────────────┘
```

**Category landing differences.** Title = category name; when the category has children (`Category.parentId`), a choice-chip row of subcategories sits above the filters; SEO landing on web uses the same layout with an `h1` and a short localized intro paragraph above the results.

**Filters (sheet; each chip also opens its own section directly):**

| Filter | Options | Backed by |
|---|---|---|
| Sort | Recommended (default) · Nearest · Top rated · Lowest price | `quality_score`, `geo`, `rating_avg`, `price_from_minor` |
| When | Any time · Today · Tomorrow · Choose a date (+ Morning / Afternoon / Evening) | `next_available_at` for Today; **a date/time filter needs an availability search endpoint (gap, §10)** |
| Price | Any · Under Br 500 · Br 500–1,000 · Br 1,000–2,500 · Over Br 2,500 (bands set per market) | `price_from_minor` |
| Rating | Any · 4+ · 4.5+ | `rating_avg` |
| For | Everyone · Women · Men · Children | `attributes.audience` / `Service.audience` |
| Where | At the venue · At my place | `attributes.delivery_modes` |
| Languages spoken | Amharic · English · Afaan Oromo · Tigrinya · … | `team.staff.languages` → `attributes` |
| Payment | Pay at venue · Pay online | `attributes` (from booking policies and "Get paid" status) |
| Open now | Toggle chip | Opening hours |

Sheet footer: `tertiary` "Clear all" + `primary` "Show 48 places" (count updates as filters change; "No places" disables the button and says so). Applied filters appear as selected chips; the summary line under the title reflects When and Where.

**States.**
- Loading: 5 skeleton rows (thumbnail + 3 bars + 2 service bars).
- No results: EmptyState "No places match" + active filters as removable `input` chips + `secondary` "Clear filters"; if a nearby area has results: "6 places in nearby Bole" `tertiary` link.
- Error: ErrorState "Couldn't load places" + Try again (filters kept).
- Offline: OfflineBanner + last results for the same query if cached; otherwise EmptyState "Search needs a connection".
- Pagination: infinite scroll by cursor, skeleton row at the end; "You've seen all 48 places" end note.

**Responsive.** ≥ 600 dp: two columns of results (list) or list + map side by side when the map exists. Web ≥ 1024: filters as a horizontal bar of dropdowns; list (60 %) + sticky map (40 %).
**Interaction.** Filter changes apply on sheet confirm (not live) on mobile, live on web. Scroll position is kept when returning from a profile. Tapping a service line opens S-04 and then immediately S-06 with that service preselected.

### S-03 Map

**Blocked by Q3** (no map dependency). Specified so it can be built when decided.
**Layout.** Full-screen map under a compact header (back, title, filter chip row). Pins are pills showing the venue's price-from ("Br 800"); pins of saved venues show a small heart. Selected pin: `primary` fill, `onPrimary` text, raised. A bottom horizontal pager of compact VenueCards (image 1:1 88 dp + name + rating + price-from) syncs with the selected pin. "Search this area" `secondary` `sm` appears at the top after the user pans. "List" pill returns to S-02 keeping filters.
**States.** Clustered pins show a count circle. No results in view: toast-like inline message "No places in this area" + "Zoom out". Location permission denied: map centres on the chosen area; no error.
**Interim (before Q3):** no map pill; S-04 uses MapPreview (static image + "Open in maps").

---

## 4. Venue

### S-04 Venue profile

**Purpose.** Decide whether this place is right and start booking. Also the **landing page for direct links** (DESIGN_PHILOSOPHY §6.4): it must make sense to someone who has never opened Beavent.

**Layout:**
```
┌──────────────────────────────────────┐
│ (‹)                         (⇪) (♡)  │  overImage header (IconButtons onImage)
│         3:2 gallery, w960            │
│                              1/12    │  photo counter
├──────────────────────────────────────┤
│ Abeba Beauty Studio                  │  display (2 lines)
│ ★ 4.8 (212) · Hair salon · Bole      │  Rating + MetaLine; rating → reviews
│ Behind Edna Mall, 2nd gate           │  landmark (label textMuted)
│ Open · until ማታ 2:00 (20:00)  Directions│ status + tertiary
│ Women only · At-home visits          │  ≤ 2 facts (MetaLine)
├──────────────────────────────────────┤
│ Services   Team   Reviews   About    │  AnchorTabs (sticky after hero scrolls away)
├──────────────────────────────────────┤
│ Services                             │  title2
│ (At the salon | At your place)       │  SegmentedControl, only if both modes exist
│ (Popular)(Hair)(Braids)(Nails)(Make… │  group chips = scroll-spy
│ Popular                              │  labelStrong group header
│ Box braids — medium        Br 1,800  │  ServiceRow
│ 3 h 30 min                      (+)  │
│ ──────────────────────────────────── │
│ Wash & blow-dry            Br 450    │
│ 45 min                          (+)  │
│ …                                    │
│ Team                                 │
│ (◯)(◯)(◯)(◯)  Hana · Senior stylist  │  avatar rail 56 → S-17
│ Reviews                              │  ReviewSummary + 3 ReviewItems + See all
│ About                                │  description, hours, map, policies
│ Other locations (if any)             │
│ Report this listing                  │  tertiary sm
├──────────────────────────────────────┤
│ Next available today ጠዋት 4:00 (10:00)│  StickyActionBar (summary + action)
│                        [ Book now ]  │
└──────────────────────────────────────┘
```

**Content and data.**
- Header: listing title, gallery (`catalog.gallery_items` for the location/org), `rating_avg`/`rating_count`, business category, admin area name, `Location` landmark/address/`geo`, opening status from `OpeningHours` + closures + public holidays, distance when location permission exists.
- Services: active, online-bookable services offered at this location grouped by `ServiceGroup` in `sortOrder`; a synthetic **Popular** group first (top 5 by bookings — needs an API field, §10; omit until available). Bundles appear in their group with the metadata "3 services".
- Team: bookable-online staff at this location with avatar, `display_name`, `job_title`, rating.
- About: localized description; opening hours table (7 rows, today in `bodyStrong`, split shifts as two time ranges, closures listed under "Upcoming closures"); MapPreview; amenities and languages spoken (from `attributes`); **Policies** block in plain sentences: "Free cancellation up to 24 hours before" (on the profile the window is generic, so a duration is right; S-10 turns it into an exact date and time), "Deposit of 20 % for new customers", "Pay at the venue: cash or mobile money", "Bookings are confirmed by the salon" (request-to-book).
- Other locations of the same organization (multi-location brand) as VenueResultRows without service lines.

**Actions.**
- Primary (sticky): **Book now** → S-06 (no preselection). Left of it: "Next available today ጠዋት 4:00 (10:00)" from `next_available_at`, or the price-from when unknown.
- With a basket (user tapped `+` on rows): the bar becomes "2 services · 2 h 15 min" / "Br 2,250" + **Continue** → S-06 with the selection (user can still adjust).
- Request-to-book venues: button label **Request booking**.
- Secondary: save, share (system share sheet with the web URL), Directions, rating → S-16 list, team avatar → S-17, service row → S-05.

**Navigation.** Push over tabs. Back returns to the origin (results keep scroll). From a direct link with no history, back → Explore.

**States.**
- Loading: hero `surface` box (3:2) + title bars + chips + 4 service row skeletons. Header buttons are live.
- Not bookable online (every service `onlineBooking = false`, or location not accepting online bookings): sticky bar `secondary` "Call to book" (tel: link) + `caption` "This place takes bookings by phone"; services still listed with prices.
- No services listed yet: Services section shows "This place hasn't added its services yet" + "Call"; the sticky bar shows only "Call to book".
- Delisted / organization suspended / not found: full-screen EmptyState "This place isn't on Beavent right now" + "Explore similar places" (→ S-02 for its category).
- Error: ErrorState + Try again; the header's back still works.
- Offline: if previously viewed, cached profile with OfflineBanner and "Book now" disabled ("Booking needs a connection"); else EmptyState.

**Responsive.** ≥ 600 dp: gallery becomes a 3-image mosaic (one large 3:2 + two small), info and services in a reading column (640) with the booking box as a right column on ≥ 1024 (web): sticky box with next availability, basket, "Book now".
**Interaction.** Group chips scroll-spy as the user scrolls services and scroll to a group when tapped. The AnchorTabs appear only after the hero scrolls out. Description "Read more" expands inline. Hero swipes through photos (only the next image is prefetched).

### S-05 Service sheet

The ServiceSheet component (COMPONENT 6.4) opened from any ServiceRow.
**Content.** Name, duration (range across variants), price (range), description (localized; "Translated" `caption` if the content language differs from the UI language), work photos for this service, variants as radio options (each: variant name or duration, duration, price; for staff-dependent prices "Br 1,800 – 2,200 depending on the professional"), add-ons (checkboxes with "+ 20 min · + Br 200"), requirements banner (patch test: "Needs a patch test at least {n} hours before. Book the patch test first." with `tertiary` "Book patch test" when the venue lists one), aftercare (disclosure), audience/delivery facts.
**Actions.** `primary` "Add · Br 1,800" (live total of variant + add-ons) / "Update" / `tertiary` "Remove". From S-04 adding a service puts it in the basket and closes the sheet with the sticky bar updating; from S-06 the same.
**States.** Loading variants (rare; usually included in the profile payload): skeleton radios. Patch test required and no valid test on file: the Add button reads "Book patch test first" and opens the patch-test service instead.

### S-17 Professional sheet

**Content.** Avatar 80, name `title1`, job title, `Rating` (staff, when ≥ 3), languages spoken (MetaLine), bio (localized), work photos (staff gallery, 1:1 grid of 3 columns), services they perform (names only, max 6 + "and 8 more"), latest 2 reviews mentioning them.
**Actions.** `primary` "Book with Hana" → S-06 with the professional preselected (S-07 then shows them selected); save professional (heart) → Saved.

### S-18 Brand page (multi-location organization)

Organization header (logo, name, rating across locations, short bio) + list of its locations (VenueResultRows with distance) + shared gallery. Used at `/o/{orgSlug}` and from "Other locations". Single-location organizations redirect to S-04.

---

## 5. Booking flow

Full-screen modal stack (DD4). Header `flow` with StepProgress. The basket persists across steps; leaving asks for confirmation ([nav §4](MARKETPLACE_NAVIGATION.md#4-navigation-behaviour)).

### S-06 Services

**Purpose.** Choose what to book (one or more services, variants, add-ons).
**Layout.** Header "Choose services" + `caption` venue name. Delivery-mode SegmentedControl (if both). Group chips (scroll-spy). ServiceRows with selection state. At the bottom: `tertiary` "Add another person" (group booking, `scheduling.appointment_groups` — P1, build after the single-person flow is live; adds person tabs "You · Guest 1" above the list, each with its own basket).
**Sticky bar.** "2 services · 2 h 15 min" + total, `primary` "Continue" (disabled with 0 services; the label stays "Continue" and a `caption` "Choose at least one service" appears on tap).
**Rules.** Multi-variant services open S-05 instead of toggling. Selecting an `AT_CUSTOMER`-only service switches the segmented control to "At your place" with a toast explaining why. Services that can't be combined with the current selection by any single professional are still selectable (S-07 handles per-service professionals).
**States.** Loading: as S-04 services. Error: inline Banner + retry; selection kept.

### S-07 Professional

**Purpose.** Choose who does it — or not care.
**Skipped when:** only one online-bookable professional can perform the selection, or the organization is an `INDEPENDENT_PROFESSIONAL`.
**Layout.** Header "Choose a professional". ProfessionalOption list: **Any professional** (preselected, subtitle "Most availability"), then each professional who can perform *all* selected services (from `Service.staffIds`), with price where it differs (resolved price for that staff; `ResolvedPrice.source` `STAFF*`).
If no single professional can do everything: notice `info` Banner "No one person does all of these. Choose for each service." and per-service sections each with "Any professional" + options.
**Sticky bar.** Total (updates with staff price) + `primary` "Continue".
**Interaction.** Tapping an avatar opens S-17 without changing selection; tapping the row selects. A professional preselected from S-17/Saved is selected and scrolled into view.

### S-08 Time

**Purpose.** Pick a time that is really available.

**Layout:**
```
┌──────────────────────────────────────┐
│ ‹       Choose a time                │  flow header
│ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬            │  StepProgress
│ (Any professional ▾)                 │  choice chip → sheet with S-07 options
│ Tahsas 2019 · Dec 2026      ‹   ›    │  month label (both calendars) + week arrows
│ ┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐         │  DateStrip (selected = primary)
│ │Sat││Sun││Mon││Tue││Wed││Thu││Fri│   │
│ │ 6 ││ 7 ││ 8 ││ 9 ││10 ││11 ││12 │  │
│ └──┘└──┘└──┘└──┘└──┘└──┘└──┘         │
│ Morning · ጠዋት                         │  label header
│ ┌────────┐┌────────┐┌────────┐       │  TimeSlotGrid (2-line chips for
│ │ጠዋት 3:00││ጠዋት 3:30││ጠዋት 4:00│       │  Ethiopian-time users)
│ │  9:00  ││  9:30  ││ 10:00  │       │
│ └────────┘└────────┘└────────┘       │
│ Afternoon · ከሰዓት                     │
│ …                                    │
│ Can't find a time? Join the waitlist │  tertiary
├──────────────────────────────────────┤
│ Sat, Tahsas 6 · ጠዋት 3:00 (9:00)        │  sticky summary (after selection)
│ 3 h 30 min · Br 1,800   [ Continue ] │
└──────────────────────────────────────┘
```

**Data.** `GET /v1/marketplace/locations/{id}/availability` (doc 03 §3.1) for the selected services, professional (or any), date range; slot interval from the booking policy (`slot_interval_minutes`); horizon `max_advance_days`; minimum notice `min_notice_minutes` (slots inside the notice window aren't returned). Day availability for the strip (dates with ≥ 1 slot) for the visible week.
**Actions.** Select date → slots; select slot → sticky summary appears; **Continue** → signs in if needed (C-6 sheet; the hold needs an identity) → creates the hold (`POST /v1/checkout/appointments`) → S-09 or S-10.
**States.**
- Loading slots: grid skeleton (6 chip boxes); the strip stays interactive.
- No slots on a day: "No times on Tue, Tahsas 9." + `tertiary` "Next available: Thu, Tahsas 11" (jumps) + `secondary` "Join the waitlist" (S-15).
- No slots in the horizon: EmptyState "No online availability in the next 90 days" + "Call {venue}" + "Join the waitlist".
- `SLOT_TAKEN` on Continue: `warning` Banner above the grid "That time was just booked by someone else. Here are the latest times." — slots refresh, selection cleared, no modal.
- Error loading: inline ErrorState in the grid area + Try again.
- Offline: grid replaced by "Times need a connection" + retry on reconnect.
**Interaction.** "Any professional" assigns the professional at hold time; the review step then shows "with Hana". Changing professional from the chip keeps the date and reloads slots. Slots are re-fetched when the screen regains focus after > 60 s.

### S-09 Details (conditional)

**Shown only when** the booking is at the customer's place, or the service has required forms (consultation/intake/consent forms assigned via `crm.form_assignments`, plan 2.4), or a patch test confirmation is needed.
**Sections.**
- **Where:** AddressPicker (COMPONENT 6.21). After choosing, a line "Travel fee Br 150 · about 25 min travel" (from `tenant.service_areas`). Outside the service area: `danger` inline message "This address is outside Abeba Beauty's area" + "Choose another address".
- **Forms:** each form as a ListRow "Consultation form · 2 min" → full-screen form (forms engine renders fields; e-signature where required) → row shows a check when complete.
**Sticky bar.** `primary` "Continue" (disabled until required items are complete, with the reason shown).

### S-10 Review and confirm

**Purpose.** Show everything that matters and confirm — with no surprises after this screen.

**Layout (top to bottom):**
1. Venue line: thumbnail 64 + name `bodyStrong` + landmark `label` `textMuted`.
2. **When:** DateTimeText `long` dual (`title2`), "with Hana" (or per service), `tertiary` "Change" (→ S-08).
3. **What:** BookingSummary lines: each service (variant name, professional, duration) with price; add-ons; travel fee; promotion line (when a promo code is applied, Stage 3.2); `tertiary` "Add a promo code" → sheet with TextField.
4. **For:** ListRow "Me (Selam T.)" → sheet with "Me" / "Someone else" (name, optional phone; `appointment_items.guest_name`).
5. **Note to the venue:** ListRow "Add a note" → sheet (300 characters).
6. **Policy:** plain sentences, computed for this booking: "Free cancellation until Fri, Tahsas 5 · ጠዋት 3:00 (9:00)." · "After that, cancelling costs Br 450 (25 %). Not showing up costs Br 900 (50 %)." · "Abeba Beauty confirms bookings; you'll hear back by message." (request-to-book). `tertiary` "Full policy" → sheet with `policy_text_i18n`.
7. **Payment** (only when the policy requires a deposit/prepayment or online payment is offered):
   - Split line: "Pay now (deposit 20 %) Br 360" / "Pay at the venue Br 1,440".
   - PaymentMethodList (telebirr, CBE Birr, M-PESA, Card — as Chapa offers; "Pay at the venue" as an option only when no deposit is required and `allow_pay_at_venue`).
   - Venue not yet enabled for online payments (KYB/subaccount gate, plan 3.5): no payment section; "Pay at the venue" statement only.
8. **Total** `PriceText` `large`.

**Sticky bar.** Summary "Total Br 1,800" (+ "Br 360 now" when a deposit applies), HoldTimer `caption` under it, `primary`:
- Pay at venue: **Confirm booking**
- Deposit/prepay: **Pay Br 360** → C-7
- Request-to-book: **Send request** (with payment when a deposit is required: **Pay Br 360 and send request**)

**States.**
- Hold expired while on this screen: blocking sheet "We released your time" + "Choose another time" (→ S-08 with the same selection).
- Submit error `SLOT_TAKEN` (rare after a hold): sheet "That time is no longer available" + "Choose another time".
- `422` validation (e.g. guest name missing): inline at the field.
- Network error on submit: `danger` Banner "Couldn't confirm. Nothing was charged." + Try again (idempotency key reused, so retries are safe).
**Interaction.** "Change" links jump back to the step and return here on Continue. The total never changes on this screen without a visible line explaining why.

### S-10b Booking confirmation

SuccessState (COMPONENT 8.5).
- Title: **You're booked** · **Request sent** (request-to-book: "Abeba Beauty will confirm. We'll message you.") · **Deposit paid — you're booked**.
- Key fact: "Sat, Tahsas 6 · ጠዋት 3:00 (9:00) at Abeba Beauty Studio".
- Rows: services and professional; address with landmark + **Directions**; payment status ("Deposit paid ETB 360.00 · Br 1,440 at the venue" or "Pay at the venue: Br 1,800"); reminder note `caption` "We'll remind you a day before and 2 hours before." (Stage 8.4, channels per preferences).
- Actions: `secondary` "Add to calendar" (ICS/`expo-calendar` not installed → share an `.ics` via the system share sheet, or web link — Q8-style dependency question; see §10), `secondary` "Directions"; sticky `primary` **Done** → Bookings tab with the item highlighted.
- Signed-in first booking: one `info` Banner "Turn on notifications so you don't miss reminders" with "Turn on" (only if push permission not yet decided).

---

## 6. After booking

### S-11 Appointment detail

**Layout.**
```
┌──────────────────────────────────────┐
│ ‹        Appointment          (⋯)    │  stack header; ⋯ = Add to calendar, Share, Help
│ Confirmed                            │  StatusLabel
│ Saturday, Tahsas 6                   │  title1
│ ጠዋት 3:00 (9:00) · 3 h 30 min           │  body + caption
│ ┌────┐ Abeba Beauty Studio         › │  venue row → S-04
│ │ 64 │ Behind Edna Mall, 2nd gate    │
│ └────┘                               │
│ [ static map 2:1 ]                   │  MapPreview
│ [ Directions ]  [ Call ]             │  secondary buttons
│ Box braids — medium      Br 1,800    │  BookingSummary
│ with Hana · 3 h 30 min               │
│ Deposit paid             Br 360      │
│ To pay at the venue      Br 1,440    │
│ Free cancellation until Fri, Tahsas 5│  policy (label)
│ · ጠዋት 3:00 (9:00)                      │
│ [ Reschedule ]                       │  secondary (full width)
│ Cancel booking                       │  destructiveTertiary
└──────────────────────────────────────┘
```

**By status** (`scheduling.appointments.status`):

| Status | Top of screen | Actions |
|---|---|---|
| `REQUESTED` | `info` Banner "Waiting for Abeba Beauty to confirm. We'll message you." | Cancel request |
| `PENDING_PAYMENT` | `warning` Banner "Pay the deposit to keep this time" + HoldTimer | `primary` Pay Br 360 (sticky), Cancel |
| `CONFIRMED` | Status label | Reschedule, Cancel, Directions, Call, Add to calendar |
| `ARRIVED` / `IN_PROGRESS` | "You're checked in" | Directions hidden; Call |
| `COMPLETED` | "Completed" | `primary` Book again (sticky), `secondary` Leave a review (if eligible), Receipt row |
| `CANCELLED` | "Cancelled by you / by Abeba Beauty on …" + refund status row (Requested → Processing → Refunded, amount, method) | Book again |
| `NO_SHOW` | "Marked as no-show" + fee taken from deposit | Book again, Help |
| `DECLINED` | "Abeba Beauty couldn't accept this request" + their message if any | `primary` Find another time (→ S-08) |
| `EXPIRED` | "This booking wasn't completed" (hold/payment expired) | `primary` Book again |

**States.** Loading: skeleton matching the layout. Offline: cached detail (today's and upcoming appointments are cached — doc 03 §1.1), actions needing the network disabled with explanation; Directions works (opens maps). Error: ErrorState with cached data if available.

### S-12 Reschedule

Opens S-08 as a modal titled **Reschedule** with "Currently: Sat, Tahsas 6 · ጠዋት 3:00 (9:00)" above the strip; same services and professional (changeable via the chip). After choosing: confirmation sheet "Move to Mon, Tahsas 8 · ከሰዓት 2:00 (14:00)?" with any policy consequence and `primary` "Confirm new time". Deposit carries over (stated). Inside the window when the policy forbids online changes: the Reschedule button is replaced by text "Changes within 24 hours need a call" + `secondary` "Call".

### S-13 Cancel

Bottom sheet:
- Title "Cancel this booking?"
- Policy evaluation for *now* (needs a preview endpoint, §10): "You won't be charged." **or** "Cancelling now costs Br 450 (late-cancellation fee). Br 360 of your deposit covers part of it; nothing more will be charged." **or** "Your deposit of Br 360 will be refunded to telebirr. Refunds usually arrive within 3 days."
- Optional reason chips (Plans changed · Found another time · Price · Other).
- `destructive` **Cancel booking**; `secondary` **Keep booking**.
After: the detail screen updates to `CANCELLED` with the refund row; a toast is not used.

### S-14 Book again

From a past appointment, Book again opens the booking flow at **S-08 Time** with the same services (current versions; removed services are dropped with an `info` Banner "Box braids — long is no longer offered") and the same professional preselected if still bookable (else Any professional with a Banner). The user can step back to change services.

### S-15 Waitlist

**Join** (sheet from S-08): "Get notified when a time opens up". Days: a 2-week DateStrip in multi-select mode (up to 7 days); Times: choice chips Morning · Afternoon · Evening (multi); Professional: Any / the selected one. `primary` "Join waitlist". Confirmation toast "We'll message you if a time opens up"; the entry appears at the bottom of Bookings → Upcoming under "Waitlists" (row: venue, services, chosen windows, "Leave waitlist").
**Offer** (from a push/SMS deep link, `commerce.waitlist_offers` with a held slot): full-screen "A time opened up" + slot (DateTimeText long dual) + services + professional + HoldTimer (offer expiry) + `primary` "Book this time" (→ S-10) + `tertiary` "Not this time". Expired: "This time was offered to the next person" + "Stay on the waitlist" / "Leave".

---

## 7. Reviews

### S-16 Reviews (list and write)

**List** (from the rating on S-04 / "See all"): ReviewSummary (overall, sub-ratings, "Based on 212 verified visits") → filter chips (All · 5 · 4 · 3 · 2 · 1 · With comments · by professional ▾) → ReviewItems (newest first; "Most relevant" sort later). Empty: "No reviews yet. Reviews come only from people who visited." Loading: summary skeleton + 3 item skeletons.

**Write** (from the review-request notification, Bookings → Past row, or S-11 for `COMPLETED` within the eligibility window):
1. Header: venue, services, date (`label` `textMuted`).
2. "How was your visit?" — 5 stars, 48 dp each, labelled ("Terrible … Excellent").
3. After a star is chosen, sub-ratings appear (Cleanliness, Value, Punctuality — the set configured for the category) as compact 5-star rows, optional.
4. "How was Hana?" (if a professional is attached) — optional stars.
5. Comment (optional, up to 1,000 characters) with placeholder "What should others know?".
6. `primary` **Post review**. Note `caption`: "Your first name and the first letter of your father's name are shown" (Q11).
After posting: SuccessState-lite "Thanks for your review" (+ "It will appear after a quick check" when `PENDING_MODERATION`). One review per appointment (`reviews_one_per_appointment`); editing is not supported at launch.

---

## 8. Provider-facing: partner portal catalogue (Stage 1)

The partner portal (`business/`, Next.js) is where businesses build what consumers see. Stage 1 (this branch) adds the catalogue API; these screens consume it. Desktop-first (≥ 1024), usable on tablets; same tokens and type roles; components from [COMPONENT_GUIDELINES.md §9](COMPONENT_GUIDELINES.md#9-partner-portal-components-web-business).

**Shell.** AppShell sidebar: Home · Calendar · Sales · Clients · **Catalogue** (Services, Add-ons, Bundles, Resources, Gallery) · Team · Events · Marketing · Reports · Settings (Locations, Booking policies, Get paid, Business details). Items for capabilities the organization lacks are hidden (e.g. no Events without the `EVENTS` capability).

| ID | Screen | API | Layout and rules |
|---|---|---|---|
| **P-01** | Service menu | `GET /service-groups`, `GET /services` | Page header "Services" + `primary` "Add" menu (Service · Group · Bundle). Filters: location, category, status (Active/Hidden/Archived), search. Groups as sortable sections (`sortOrder`), each with rows: name, duration range, price range (`PriceText`), number of team members, status label only when Hidden or online booking is off. Row → P-02. Drag to reorder services within and between groups. Empty: "Add your first service" + `primary` "Add service" (later: suggested starter menus per business category). |
| **P-02** | Service editor (full page) | `POST/PUT /services/{id}` (`If-Match`), `DELETE` = archive | Sections: **Basics** — LocalizedTextField name (required, at least one language), category (SERVICE categories), group, description, aftercare. **Pricing and duration** — variants table (name optional for a single variant, DurationField, price type, MoneyField, default radio, "Add variant", max 20); per-variant *Advanced*: buffers before/after, StepsEditor (stages must sum to the duration, with "staff free during processing" explained), resource requirements (specific resource or any of a kind, units, stage). **Where and who** — locations (default all), team members (read-only list from the team API, link to Team), audience, delivery modes. **Online booking** — switch + status (Active / Hidden: "Your team can book it; customers can't see it"). **Requirements** — patch test + validity days. **Add-ons** — pick from the library. Right column (≥ 1280): live **Customer preview** (ServiceRow + ServiceSheet in a chosen language). SaveBar when dirty; ConflictBanner on `412`. Archive: confirm dialog "Archived services stay in past bookings and reports." |
| **P-03** | Prices by professional, level and location | `GET/PUT /services/{id}/prices`, `GET …/variants/{variantId}/price` | PriceMatrix with variant rows and override columns; empty cell inherits. Explanation panel: "Most specific wins: professional at a location → professional → level at a location → level → location → standard price." A "Check a price" tool picks location + professional and shows the resolved price, duration and source. Effective-now note: "Changes apply to new bookings; existing bookings keep their price." |
| **P-04** | Add-ons | `/addons` | DataTable (name, duration, price, status) + EditorDrawer. Used-by count per add-on. |
| **P-05** | Resources | `/resources` | Grouped by location; kind, units ("Sauna for 4 = 4 units"), active switch. Explain in the empty state what resources do ("Stop two massages being booked into one room"). |
| **P-06** | Bundles | `/bundles` | Editor: bundle name, items (services/variants, sequential or parallel), bundle price vs sum ("Customers save Br 300"), online booking. |
| **P-07** | Gallery | `/gallery`, files API | Tabs: Business · each location · services · team. Upload by drag-and-drop or picker → pre-signed PUT → thumbnail shows "Processing…" until `READY`; `REJECTED` shows the reason and a remove button. Drag to reorder; first image is the cover (labelled). Guidance `caption`: "Use bright, real photos of your work. 3:2 landscape works best." Minimum recommended: 5 photos. |
| **P-08** | Listing preview and quality | Listing status (`Organization` listing status HIDDEN / PENDING_REVIEW / LISTED / DELISTED) | Phone-frame preview of S-04 with a language switch. Checklist (not a score): cover photo · 5+ photos · description in Amharic and English · opening hours · 3+ services with prices · team photos · policies reviewed · online payments set up. Each item links to where to fix it. |
| **P-09** | Team and schedule (1.2) | `/staff`, `/staff/{staffId}/working-pattern`, `/shifts`, `/time-off` (+ `/decision`), `/pricing-levels`, `/schedule` | Team list (avatar, name, job title, locations, bookable online); member editor (profile, languages spoken, services they do, staff-specific prices link to P-03); rota editor: week grid per location with working patterns (N-week), shifts, time off; "My schedule" also in mobile Business mode. |
| **P-10** | Booking policies (1.3) | `/booking-policies`, `/booking-policies/{policyId}`, `/booking-policies/effective` (use it for the live preview) | One page with organization defaults and per-location / per-service overrides ("most specific wins" shown as an inheritance tree). Fields in plain language: "Customers can book at least [1 hour] ahead and up to [90 days] ahead", slot interval, "Confirm each booking myself" (request-to-book), deposit (none / % / fixed / full, and who it applies to: everyone, new customers, marketplace customers, flagged), cancellation window, late-cancel and no-show fees, pay at venue, policy text (localized). A live sentence preview shows exactly what customers will read on S-10. |
| **P-11** | Locations, hours, service areas (1.4) | `/locations`, `/locations/{locationId}/closures`, `/locations/{locationId}/service-areas`, `GET /v1/ref/public-holidays` | Location editor (landmark-first address following the country format, map pin), opening hours with split shifts, closures and public holidays ("Closed for Meskel?" prompts from `ref.public_holidays`, never automatic), at-home service areas (radius or sub-cities, travel fee, travel time). |

**Partner portal states.** Every list has a skeleton table, an explanatory empty state with one action, and inline errors per field; saves show a toast "Saved"; failures keep the form and show a Banner. Sensitive actions (none in the catalogue; later "Get paid") use step-up (C-6 step-up sheet pattern on web).

## 9. Provider-facing: mobile Business mode

Requirements (full specs when Stage 2.3 starts). Navigation: [MARKETPLACE_NAVIGATION.md §6](MARKETPLACE_NAVIGATION.md#6-business-mode-navigation).

| Screen | Must do | Design requirements |
|---|---|---|
| **B-1 Today** | Today's appointments across the team (or my own), arrivals, balances due, quick status changes | Vertical timeline with a "now" line (`danger` 1 px with a dot); rows: time (24-hour, Ethiopian time on long-press/hover as doc 06 says for partner calendars), customer name (given name first), services, professional, StatusLabel, balance due. Swipe/row actions: Arrived · Start · Complete · No-show · Take payment. "Walk-in" as a `primary` `sm` button in the header (no floating action button). Works offline for viewing (cached), status changes queue with a visible "Waiting to sync" label |
| **B-2 Calendar** | Day view by staff/resource, week view for one person; create, move, block | Staff columns (2 visible on a phone, horizontal scroll; header avatars); 15-minute grid lines in `border`; appointment blocks `primarySubtle` with a 3 dp `primary` leading bar; requested `warningSubtle`; processing stages shown as a lighter section inside the block ("staff free"); blocks/time off `surface` with a label. Tap empty slot → new appointment sheet; long-press block → move with drag, then a confirmation sheet with "Notify customer" switch; overlapping only through an explicit "Book anyway" (staff-only forced overlap, `allow_overlap`). Real-time updates (WebSocket) with a subtle highlight on changed blocks |
| **B-4 Sales** (Stage 4) | POS: services, products, gift cards, tips, discounts, split tender | Cart on top, catalogue search below; tender sheet with Cash (amount given → change, cash rounding as an explicit line), Chapa push to customer's phone, payment link/QR, gift card, balance; receipt by SMS/Telegram |
| **Customers** | Profile, notes/alerts/allergies, history, forms | Alerts (allergies) shown as a `warning` Banner at the top of the customer and on their appointments |

## 10. Gaps and API needs for these screens

| # | Needed by | Need | Owner |
|---|---|---|---|
| G1 | S-02, S-04 | Public marketplace endpoints (listing search, location profile with services and resolved price ranges per service, staff for the location) — not yet in the OpenAPI spec | Backend (Stages 2.5, 8.1) |
| G2 | S-02 "When" filter | Availability-aware search (date/time window → venues with slots) beyond `next_available_at` | Backend (8.1) |
| G3 | S-04 Popular group | Booking counts per service (or a `popular` flag) on the profile payload | Backend |
| G4 | S-13 | Cancellation preview endpoint returning fee, deposit kept, refund amount and method for *now* | Backend (2.2/5.1) |
| G5 | S-09 | Travel fee/time estimate for an address against `tenant.service_areas` | Backend (1.4/2.1) |
| G6 | S-10b | Add-to-calendar: either `expo-calendar` (new Expo module) or an `.ics` endpoint shared via the share sheet | Decision (recommend `.ics` endpoint: no dependency, works on web) |
| G7 | All prices | Display money helper and dual-time helper in `@beavent/money` / `@beavent/calendar` (A12) | Frontend foundation |
| G8 | S-03 | Map dependency (Q3) | Decision |
| G9 | S-16 | Category-specific sub-rating keys and labels (i18n) | Product |
