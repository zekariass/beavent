# Beavent events marketplace design

**Status:** v1, 19 Nov 2026. **Benchmark:** Eventbrite (see [DESIGN_REFERENCE_CATALOG.md §2](DESIGN_REFERENCE_CATALOG.md#2-eventbrite--events-marketplace)).
**Builds on:** [MARKETPLACE_NAVIGATION.md](MARKETPLACE_NAVIGATION.md) (C-1…C-8), [COMPONENT_GUIDELINES.md](COMPONENT_GUIDELINES.md), [DESIGN_TOKENS.md](DESIGN_TOKENS.md).
**Data sources:** `db/migration/V1_07__events.sql` (events, occurrences, ticket types, price phases, inventory buckets, online access, sessions), `V1_10__ticketing.sql` (tickets, transfers, scans, admissions), `V1_19__discovery_and_trust.sql` (listings, follows, reviews, collections), doc 03 §3.2–3.3 (checkout and check-in workflows). No events API exists yet (Stage 6).
**Current implementation:** none. Plan steps: authoring and checkout 6.1–6.8, check-in 7.x, discovery 8.1–8.2, reviews 8.3.

---

## 1. Journey

```
Arrive ─┬─ Explore (E-01) ─ Category (E-02) / Search & results (E-03) ─┐
        ├─ Organizer profile (E-15) ───────────────────────────────────┤
        └─ Shared link / Telegram post / poster QR (the main way in until Stage 8) ─┤
                                                                       ▼
                                                            Event page (E-05)
 Ticket flow (modal): Dates (E-06)* → Tickets (E-07) → Details (E-08) → Extras (E-08b)* → Review and pay (E-09)
                      * only for multi-date events / events with add-ons       │
                                                  free order ───────┤──── paid ────┐
                                                                    ▼              ▼
                                                     Confirmation (E-10) ◀── Payment waiting (C-7)
                                                                    │
            Bookings tab (C-4) → Order (E-12) → Ticket pass (E-11) · Transfer (E-13) · Refund (E-14)
                                                                    → after the event: Review (E-18)
                                 sold out → Waitlist (E-17)
```

**What "approaching Eventbrite" means here:** from a link in a Telegram channel to a ticket in the wallet in under a minute, with the all-in price visible before the first tap on "Get tickets", a fair reservation timer, a ticket that works at the door without signal, and a clear path when an event is postponed or cancelled.

## 2. Events-specific display rules

| Topic | Rule | Source |
|---|---|---|
| Date and time | In the **occurrence's** time zone, dual per [tokens §8.2–8.3](DESIGN_TOKENS.md#82-dates). Cards: short date + start time. Detail: long date, start–end range. Doors-open time (from `content`) as a separate line when given | `events.occurrences` |
| Multi-day | "Sat, Tahsas 6 – Mon, Tahsas 8" | `kind = MULTI_DAY` |
| Recurring / class series | Cards show the next occurrence + "+ 11 more dates" `label` `textMuted`; detail shows "Every Saturday" when an RRULE summary is available | `kind = RECURRING / CLASS_SERIES`, `recurrence_rules` |
| Timed entry | "Entry slots from ጠዋት 3:00 (9:00)" | `kind = TIMED_ENTRY`, `occurrences.slot_label` |
| Online / hybrid | MetaLine shows "Online"; venue omitted for online-only; hybrid shows the venue + "· Also online" | `format` |
| Price | All-in (DD6): ticket price + buyer fee when `fee_handling = PASS_TO_BUYER`, tax-inclusive. Cards and the sticky bar: "from Br 525"; all free: "Free"; donation-only: "Donation" | `ticket_types`, fees (needs Q6 confirmation) |
| Status | One StatusLabel per card/row, priority: Cancelled > Postponed > Sold out > Sales ended > Few left > Promoted | `events.status`, `occurrences.status`, buckets |
| "Few left" | Shown when remaining capacity across visible ticket types is ≤ 10 % and ≤ 50 tickets (threshold configurable); never show exact remaining counts unless the organizer opts in | Inventory buckets (API flag, §10) |
| Age | "18+" in the MetaLine on detail; on cards only for 18+ events | `min_age` |
| Refund policy | `NO_REFUNDS` → "No refunds" · `UNTIL_1_DAY` → "Refunds up to 1 day before" · `UNTIL_7_DAYS` / `UNTIL_30_DAYS` likewise · `CUSTOM` → organizer's text | `refund_policy`, `refund_policy_text` |
| Organizer | Name + "Verified" when KYB `VERIFIED`; follower count only when ≥ 10 | `tenant.organizations`, `discovery.follows` |
| Translation | Event text in the UI language when an `event_translations` row exists; machine translations are labelled "Translated automatically · Show original" | `events.event_translations.is_machine_translated` |

---

## 3. Discovery

### E-01 Explore — Events scope

**Purpose.** Answer "what's on?" for a time and a mood.
**Layout.** C-1 shell; under the scope tabs:
1. **Date chips** (choice): Today · Tomorrow · This weekend (Friday 18:00 → Sunday end, local) · Choose dates (sheet with a range calendar in the preferred calendar).
2. **Category chips** (`EVENT` categories: Music, Nightlife, Business & professional, Technology, Arts & theatre, Food & drink, Religious & spiritual, Community & culture, Sports & fitness, Classes & workshops, Wellness & retreats, Fashion & beauty, Charity & causes, Tours & experiences).
3. Modules: Next up (tickets) → From organizers you follow → This weekend → Popular in {city} → Free events → Online events → Classes & workshops. With a date chip selected, modules collapse into one date-filtered list (E-03 layout) — chips are a shortcut to results, not a second filter UI.
**Featured and recommended rules (E-04, below)** decide what fills "Popular" and where promoted items may appear.
**States.** As C-1. Pilot inventory is small (3–5 organizers): expect most modules to be hidden by DD12; the fallback list "All upcoming events in Addis Ababa" sorted by date is the normal pilot view.

### E-02 Category landing

E-03 layout titled with the category, date chips above the filter chips, subcategory chips when the category has children. Web SEO landing (`/{lang}/{city}/{category}`) adds an `h1` and a localized intro.

### E-03 Search results and map

**Layout.** Stack header (query or category; summary "This weekend · Addis Ababa"), filter chips: **Date ▾ · Price ▾ (Free / Under Br 500 / …) · Category ▾ · Online · Distance ▾ · Sort ▾ (Soonest · Recommended · Nearest)**; count; EventResultRows grouped by date headers when sorted by Soonest ("Saturday, Tahsas 6"). Floating "Map" pill when Q3 is resolved (pins show the date "Sat 6", selected pin opens a compact EventCard pager).
**States.** Loading: rows skeleton. Empty: "No events match" + removable filter chips + "Clear filters"; for a date with nothing: "Nothing listed for Tuesday. Try this weekend" (tertiary jumps). Error/offline: as S-02.
**Responsive.** ≥ 600: 2-column EventCard grid; web ≥ 1024: 3–4-up grid with filters as a top bar; map as a toggle.

### E-04 Featured and recommended (rules, not a screen)

| Surface | Ranking (P1) | Later |
|---|---|---|
| Popular in {city} | `quality_score` (organizer verified, cover image present, description in the UI language, sales velocity, not cancelled) for events starting in the next 30 days | Personalised by follows, past purchases, categories (P2) |
| From organizers you follow | Upcoming events from `discovery.follows`, soonest first | — |
| This weekend | Soonest first within the window, then quality | — |
| Collections (P2) | Platform-curated `discovery.collections` (e.g. "Meskel and Enkutatash", "Timket in Gondar"), shown as a module titled with the collection name; holiday-aware via `ref.public_holidays` | Organizer and user collections |
| Promoted (P2, `marketing.ad_campaigns`) | At most 1 promoted item in any 5 consecutive items; never the first item of a module; always labelled "Promoted"; never in Next up, Following, or the user's own Bookings/Saved | — |

Never rank by "few left" or create urgency that isn't true.

---

## 4. Event page

### E-05 Event page

**Purpose.** Decide to go, then get tickets. Also the **landing page for shared links** — often opened from Telegram on a slow connection: the date, title, place and price must render before the cover image.

**Layout:**
```
┌──────────────────────────────────────┐
│ (‹)                         (⇪) (♡)  │  overImage header
│          2:1 cover (w960)            │
├──────────────────────────────────────┤
│ Sat, Tahsas 6 · ማታ 2:00 (20:00)       │  date line: labelStrong primary
│ Addis Jazz Night                     │  display (2–3 lines)
│ Fendika Cultural Center · Kazanchis  │  MetaLine
│ (logo) Fendika Arts ✓ Verified  Follow│  organizer row (tertiary Follow)
│ ┌ Postponed to Sat, Tahsas 13 ─────┐ │  Banner only when relevant
├──────────────────────────────────────┤
│ About   Tickets   Location  Organizer│  AnchorTabs (after hero)
├──────────────────────────────────────┤
│ When                                 │  title2
│ Saturday, Tahsas 6, 2019             │  body
│ (15 December 2026)                   │  caption
│ ማታ 2:00 – 5:00 (20:00 – 23:00)         │
│ Doors open ማታ 1:30 (19:30)             │
│ Add to calendar · 12 more dates      │  tertiary buttons
│ Where                                │
│ Fendika Cultural Center              │
│ Behind Kazanchis Total, 3rd gate     │
│ [ static map 2:1 ]   Directions      │
│ About                                │
│ Description… Read more               │
│ Line-up / Speakers  (avatar rail)    │  from content (only if present)
│ Agenda  (time · title rows)          │
│ Good to know                         │
│ 18+ · No refunds · Transfers allowed │  MetaLine-style facts, one per line ok
│ FAQ (disclosure rows)                │
│ Organizer                            │  compact ProfileHeader + Follow
│ More from Fendika Arts  (rail)       │
│ Similar events (rail)                │
│ Report this event                    │  tertiary sm
├──────────────────────────────────────┤
│ from Br 525            [ Get tickets ]│  StickyActionBar
└──────────────────────────────────────┘
```

**Sticky bar states.**

| Situation | Left | Button |
|---|---|---|
| On sale, paid | "from Br 525" (all-in) | `primary` **Get tickets** |
| On sale, all free | "Free" | `primary` **Reserve a spot** |
| Multiple dates | "from Br 525 · 12 dates" | `primary` **Choose a date** |
| Sold out, waitlist on | "Sold out" | `secondary` **Join waitlist** (E-17) |
| Sold out, no waitlist | "Sold out" | none; `caption` "Follow Fendika Arts for new dates" + Follow |
| Not yet on sale | "On sale Mon, Tahsas 1 · ጠዋት 3:00" | `secondary` **Remind me** (notification opt-in) |
| Sales ended / past | "Sales ended" / "This event has ended" | none; past events link to reviews |
| Postponed, date TBA | "Postponed" | none (existing ticket holders see their order) |
| Cancelled | "Cancelled" | none |
| User already has tickets | "You have 2 tickets" | `secondary` **View tickets** (→ E-12) + `tertiary` "Get more" |
| Private / password | — | `primary` **Enter password** (sheet) |

**Content and data.** `events.events` (title via translations, `category_id`, `format`, `min_age`, `refund_policy`, `content` jsonb: agenda, speakers/performers, sponsors, FAQs, highlights, links), occurrences (next or selected), venue (`tenant.places` / organization location) with landmark and `geo`, organizer (`tenant.organizations`, verification, follower count), ticket price range, availability status.
**Content blocks** render only when present, in this fixed order: When → Where → About → Line-up/Speakers → Agenda → Good to know → FAQ → Organizer → More from organizer → Similar. Sponsors appear as a single text line "With support from …" (no logo walls).

**States.**
- Loading: date line, title and meta render from the link preview/listing cache first if available; otherwise skeleton (2:1 box + 3 bars + sticky bar skeleton).
- Event not found / unpublished / private without access: full-screen EmptyState "This event isn't available" + "Explore events".
- Error: ErrorState + Try again.
- Offline: cached page if viewed before (sticky button disabled, "Tickets need a connection"); if the user holds tickets, the button becomes "View tickets" and works offline.
**Responsive.** ≥ 1024 web: cover (2:1, max 960 wide) on top, two columns — content (640) and a sticky ticket box (360) containing date selector, price and "Get tickets".
**Interaction.** "Add to calendar" uses the `.ics` approach (SERVICES §10 G6). Share sends the web URL with a rich preview (Open Graph from the listing). Follow from the organizer row toggles in place with a toast.

---

## 5. Ticket flow

Full-screen modal stack (DD5), `flow` header with StepProgress. After inventory is reserved (end of E-07), a **HoldTimer** runs in the header area of every following step.

### E-06 Choose a date (multi-date events only)

**Layout.** Header "Choose a date". OccurrenceList (COMPONENT 6.10): grouped by month (both calendars in the month header), rows with short date, time range, availability (`label`: Available · `warning` Few left · `danger` Sold out (disabled) · Sales ended (disabled)). > 30 occurrences: month grid view with a list of the selected day's times below. Timed entry: after the date, a TimeSlotGrid of entry slots (`slot_label`) with availability per slot.
**Action.** Selecting a row continues to E-07 immediately (no separate Continue).
**States.** Loading skeleton rows; all sold out → EmptyState "All dates are sold out" + Join waitlist (if enabled).

### E-07 Tickets

**Layout:**
```
┌──────────────────────────────────────┐
│ ×        Choose tickets              │  flow header
│ Sat, Tahsas 6 · ማታ 2:00 (20:00)  Change│  occurrence summary (Change → E-06)
│                     Have a code?     │  tertiary → code sheet
│ Early bird                (−) 2 (+)  │  TicketTypeRow
│ Br 525 incl. Br 25 fee               │
│ Ends in 2 days                       │  caption warning
│ ──────────────────────────────────── │
│ General                   (−) 0 (+)  │
│ Br 840 incl. Br 40 fee               │
│ ──────────────────────────────────── │
│ VIP table · Admits 8                 │
│ Br 12,600           Sold out         │  unavailable variant
│ ──────────────────────────────────── │
│ Max 10 tickets per order             │  caption
├──────────────────────────────────────┤
│ 2 tickets · Br 1,050  [ Continue ]   │
└──────────────────────────────────────┘
```

**Data.** Visible ticket types for the occurrence (`visibility` `VISIBLE`, `HIDDEN_WHEN_UNAVAILABLE` only while available; `ACCESS_CODE_ONLY` only after a code; never `BOX_OFFICE_ONLY` / `INVITE_ONLY` / `HIDDEN`), current price phase, sales windows, `min_per_order` / `max_per_order`, event `max_tickets_per_order`, remaining capacity flag, `admissions_per_unit`, `pricing` (`PAID` / `FREE` / `DONATION` / `PAY_WHAT_YOU_WANT` with `min_price_minor`), `release_after_ticket_type_id` (sequential release: the next tier shows "On sale when Early bird sells out").
**Code sheet.** TextField "Promo or access code" + Apply. Results: discount → rows show the new price with the old struck through + toast "Code applied: 10 % off"; access code → unlocked types appear at the top with a "Unlocked with code" `caption` + toast; invalid → inline error "That code isn't valid for this event".
**Actions.** Steppers; **Continue** reserves inventory (doc 03 §3.2 RESERVE movements) and starts the hold.
**Errors.** `SOLD_OUT` for one type on Continue: rows refresh, the affected row shows Sold out, `warning` Banner "Early bird just sold out. Your other tickets are still selected." — no modal. Quantity above remaining: stepper max adjusts with a `caption` "Only 3 left".
**High-demand on-sales (P2):** before E-07, a waiting room screen "You're in line" with position, estimated wait, and "Don't close the app — we'll bring you to tickets" (bot check if required). Not part of the pilot.

### E-08 Details

**Purpose.** Who is buying, who is going, and the organizer's questions.
**Sections.**
1. **Contact:** signed in → a ListRow "Selam T. · 091 123 4567" with "Change". Not signed in, in the app → inline phone + code (C-6 steps embedded in the page, not a separate sheet). On web/widgets → guest checkout by phone (name + phone verified by code; plan 6.4); email optional for an emailed copy.
2. **Attendees** (when any selected type has `requires_attendee_details`): one compact group per ticket ("Ticket 1 · Early bird") with name (prefilled from the buyer for ticket 1 via a "This ticket is for me" checkbox), optional phone (used to deliver the ticket by SMS to that person).
3. **Questions** (`crm.forms` ORDER_QUESTIONS / ATTENDEE_QUESTIONS): rendered with standard inputs; required questions marked "Required" in `caption` (no asterisks).
4. **Marketing consent** (when `ask_marketing_consent`): unchecked Checkbox "Send me news from Fendika Arts" (`crm.customer_consents`).
**Sticky bar.** Total + `primary` **Continue** (validation on tap: scroll to the first problem).

### E-08b Extras (only when the event sells add-ons)

Rows: product image 1:1 64, name, price, Stepper; `caption` "Pick up at the event — show your ticket". `primary` **Continue** (also when nothing is added; the label stays Continue). Kept as its own step (Eventbrite's 2026 pattern) so the ticket choice stays uncluttered.

### E-09 Review and pay

**Layout.** Event line (thumbnail 2:1 64 × 32, title, date/time dual); OrderSummary (ticket types × quantity with prices, extras, discount, **Service fee** with info sheet "This fee helps run Beavent. It's included in the prices you saw." when fees are passed to the buyer — fees were already in the shown prices, so the summary splits them out without adding anything); refund policy line; PaymentMethodList (Chapa methods); **Total** `PriceText` `large`.
**Free orders:** no payment section; button **Reserve** and no HoldTimer pressure beyond the reservation window.
**Sticky bar.** HoldTimer + `primary` **Pay Br 1,050** → C-7 / **Reserve** (free) → E-10.
**Errors.** Reservation expired: blocking sheet "Your tickets were released" + "Choose tickets again" (back to E-07 with the same quantities if still available). Payment failure: C-7 states.

### E-10 Confirmation

SuccessState: title **You're going** (paid or free); key fact "Sat, Tahsas 6 · ማታ 2:00 (20:00) at Fendika Cultural Center"; rows: tickets ("2 × Early bird"), delivery note `caption` "Your tickets are in Bookings and work without internet. We also sent them by SMS with code 7KQ-29F." (and Telegram when linked); actions: `primary` **View tickets** (→ E-11), `secondary` "Add to calendar", `secondary` "Share with friends" (shares the event, not the ticket). The organizer is auto-followed after purchase (`follows.source = AFTER_PURCHASE`) with a visible line "You're now following Fendika Arts · Undo".

---

## 6. Tickets and after the event

### E-11 Ticket pass (QR)

**Purpose.** Get in at the door, fast, offline.
**Layout.** Full-screen (`ticket/[id]`), background `background`; TicketPass component (COMPONENT 6.17): white panel in both themes, event title, date/time dual, QR 240 dp, serial `title2` grouped, holder name, ticket type, "1 of 2" with swipe between tickets in the order (pager dots). Below the panel: venue + Directions, Add to calendar, Transfer (if allowed), Order details (→ E-12).
**Behaviour.** Renders entirely from the encrypted offline store (doc 06 §2.5); "Available offline" `caption` with a check once cached. Screen brightness boost needs `expo-brightness` (Q8) — until then show a `caption` "Turn up your screen brightness at the door". The screen stays awake while visible (`expo-keep-awake` ships with Expo — confirm it is available in the SDK before relying on it; otherwise no special handling).
**States.** Not yet cached and offline: "This ticket isn't saved on this phone yet. Connect once to save it." + the serial if known (SMS fallback). Transferred away: panel replaced by "Transferred to Hana" (no QR). Void/refunded/cancelled: "Not valid — refunded" `danger` label, no QR. Online-only event: no QR; "Join event" button (E-19).

### E-12 Order (ticket management)

**Layout.** Header stack "Your tickets". Event block (2:1 thumbnail, title → E-05, date/time dual, venue + Directions). Status banner when relevant (§E-16). **Tickets** list: each ListRow — ticket type, holder name, serial `caption`, status (Valid / Transfer pending / Transferred / Refunded / Checked in), trailing "Show" (`secondary` `sm`) → E-11 at that ticket. **Order**: summary (amounts with minor digits, method "telebirr · 091…", date), receipt row. **Actions**: `primary` sticky **Show tickets**; rows: Add to calendar, Transfer a ticket (E-13; only when `transfers_enabled` and the type `is_transferable`), Request a refund (E-14; only when the policy allows now — otherwise the row reads "No refunds for this event" in `textMuted`, not tappable), Get help.
**States.** Loading skeleton; offline: everything from cache except transfer/refund (disabled with explanation).

### E-13 Transfer a ticket

**Flow (sheet → full screen on phones):** pick ticket(s) (checkboxes; checked-in or already-pending tickets disabled) → recipient: name + PhoneField → confirm: "Hana will get a message to accept. Your QR stops working as soon as she accepts." `primary` **Send ticket**.
**After.** Ticket shows "Transfer pending" with `tertiary` "Cancel transfer" until accepted. On acceptance the old QR is invalid (credential rotation, `credential_version`); the sender sees "Transferred to Hana".
**Recipient side.** Deep link `order/[id]?transfer=…` → sign in with the invited phone (C-6) → "Selam sent you 1 ticket to Addis Jazz Night" + **Accept ticket** / Decline → ticket appears in their Bookings. Expired/declined/cancelled transfers show a plain message and return the ticket to the sender.

### E-14 Request a refund

**Sheet:** the policy in one sentence ("Refunds are available until Fri, Tahsas 5"), tickets to refund (checkboxes), the amount the buyer will get back and to where ("Br 1,000 back to telebirr · the Br 50 service fee isn't refundable" — per market fee policy, doc 04 §8 / plan 5.4), optional reason chips, `destructive` **Request refund**.
**After.** Order shows a refund status row: Requested → Approved → Processing → Refunded (or Declined with the organizer's reason). Tickets in the request are void immediately when approved, not on request.
**No refunds allowed:** no sheet; the row explains the policy and suggests "Transfer it to a friend instead" when transfers are allowed.

### E-16 Event changes (postponed, rescheduled, cancelled)

Driven by `events.status` / `occurrences.status` / `postponed_from` and the outbox (`occurrence.rescheduled`). Every change triggers a notification (push + SMS/Telegram) linking to E-12.

| Change | Banner on E-05 and E-12 | Ticket holder options |
|---|---|---|
| Rescheduled (new date known) | `info` "Moved to Sat, Tahsas 13 · ማታ 2:00 (20:00). Your tickets are valid for the new date." | Keep tickets (default, no action); request a refund if the policy or market rules give a refund right on date changes |
| Postponed (date TBA) | `info` "Postponed. Fendika Arts will announce a new date. Your tickets stay valid." | Request a refund if allowed |
| Location changed | `info` "New location: … " + Directions | — |
| Cancelled | `danger` "Cancelled by the organizer. A refund of Br 1,050 to telebirr is on its way." + refund status | None needed; refund is automatic (bulk refunds, plan 6.6) |
| Sales paused | Sticky bar shows "Sales paused" | — |

### E-17 Waitlist (sold out)

**Join (sheet):** quantity Stepper, ticket type preference (Any / specific), contact (signed in or phone), `primary` **Join waitlist**. Bookings → Upcoming shows it under "Waitlists".
**Offer:** notification → E-07 with the offered tickets preselected and held (HoldTimer = offer expiry) and a `success` Banner "Tickets are available for you until 9:30 PM". Expired: "This offer went to the next person" + "Stay on the waitlist".

### E-18 Review an event

Same pattern as S-16: overall stars → optional sub-ratings (Organisation, Venue, Sound, Value — configurable per category) → optional comment → **Post review**. Eligible after the occurrence ends for orders with at least one admitted ticket (verified reviews require an `order_id`). Reviews appear on the organizer profile (E-15) and on future events by the same organizer ("Fendika Arts events are rated 4.7").

### E-19 Online event access

For `ONLINE`/`HYBRID` events (`events.online_access`): E-12 and E-11 show a **Join event** `primary` button that is disabled with the time it opens ("Link available Sat, Tahsas 6 · ማታ 1:45 (19:45)", from `reveal_at`), then opens the provider link (Zoom, Google Meet, YouTube Live, Facebook Live, Telegram, custom) in the browser/app. The link is never shown before `reveal_at` and never on the public event page.

---

## 7. Organizer profile

### E-15 Organizer profile

**Layout.** `stack` header; ProfileHeader (organizer variant, COMPONENT 6.12): logo 80, name `title1` (+ Verified), MetaLine (followers · events hosted · rating across events when ≥ 3 reviews), bio (3 lines + More), **Follow** `secondary` (→ Following ✓; a second tap opens "Unfollow / Turn off notifications" options — `follows.notify`). Sections: **Upcoming** (EventResultRows; all of them, paginated), **Past events** (collapsed: "32 past events" → list), **Reviews** (summary + 3 items), **About** (links as text rows: website, Telegram channel, Instagram — opening externally), Report.
**Web.** `/{lang}/o/{orgSlug}`; organizations with both service locations and events show a combined brand page: locations first if the org's primary category is a business, events first if it is an organizer.
**States.** No upcoming events: "No upcoming events. Follow to hear about new ones." with Follow as the action.

---

## 8. Ticket-specific interaction rules

1. **Reservation timer length** is set by the backend (holds must outlast mobile-money approval — doc 06 §2.5); the UI only displays it (HoldTimer) and never invents a shorter one.
2. **The price a buyer sees on E-05 equals the per-ticket amount on E-07 and the line amount on E-09.** If the price phase changes during checkout, the reserved price stands (it was reserved) — the UI never re-prices silently.
3. **Quantities default to 0**, except single-ticket-type free events where 1 is preselected.
4. **Max-per-order messages** cite the limit, not an error code.
5. **Group/table tickets** state admissions ("Admits 8") everywhere the type appears, and produce N passes in E-11.
6. **Tickets always work offline** once delivered; the app downloads them on confirmation and on every Bookings refresh.
7. **SMS serial** is visible under every QR; door staff can look it up by serial, name or phone (B-3).

---

## 9. Organizer-facing requirements

Specs to be expanded at Stage 6; these are the binding UX requirements.

### 9.1 Partner portal (web)

| ID | Screen | Requirements |
|---|---|---|
| **P-20** | Events list | Tabs Upcoming · Drafts · Past; rows with cover thumbnail 2:1, title, next date, tickets sold / capacity (a 4 px progress bar in `primary` on `surface` — the only bar chart outside reports), gross sales, status label. `primary` "Create event" |
| **P-21** | Event wizard (full page, left step list: Details · Schedule · Location · Tickets · Settings · Checkout questions · Review) | **Details:** LocalizedTextField title/summary/description (machine-translate assistance clearly labelled), category, cover upload with a 2:1 crop tool (recommended 2160 × 1080; shows how the card and page will crop), content blocks (agenda, line-up/speakers, FAQs, highlights) as structured repeaters (`events.content`). **Schedule:** single / multi-day / recurring (RRULE builder in plain language: "Every Saturday from … until …" with a list of generated dates that can be excluded) / timed entry (slot length, capacity per slot). **Location:** search shared public venues (`tenant.places.is_public_venue`), own location, or online (provider + link + reveal time). **Tickets:** type editor (name, pricing kind, price, fee handling with a live "Buyer pays Br 525 · You receive Br 487" line from the fee engine, visibility, min/max per order, sales window, sequential release, price phases with time or quantity limits, admissions per unit, transferable, attendee details) and **capacity** (event capacity + shared pools drawn as nested bars: "Early bird + General share 300"), organizer holds (sponsors/press). **Settings:** refund policy, age limit, max per order, waitlist, transfers, marketing consent. **Checkout questions:** forms builder. **Review:** checklist + phone-frame preview of E-05 in each language + "Publish" (or "Submit for review" for new organizers — `PENDING_REVIEW`, with the expected review time) |
| **P-22** | Event dashboard | Sales by ticket type and by day (simple bars), orders, attendee list (search by name/phone/serial, export), check-ins live count, payout/settlement info as reported by Chapa (Stage 5.5). Actions: Edit, Pause sales, Postpone, Change date (moves orders; customers notified), Cancel event (consequence sheet: number of orders, total to refund, "requires pre-funding" when above the float — plan §3; step-up code) |
| **P-23** | Promo and access codes | Code list with usage; editor: discount or unlock or both (`commerce.promo_codes.kind`), ticket types, limits, dates |

### 9.2 B-3 Check-in (mobile Business mode, Stage 7)

| Requirement | Design |
|---|---|
| Choose what to scan | Event → occurrence (today's first) → gate name (free text, stored with scans). Shows manifest status: "Ready offline · 1,240 tickets · updated 2 min ago" |
| Scan | Full-screen camera with a square guide; **camera access needs `expo-camera` (not installed) — dependency decision before Stage 7** |
| Result (≤ 300 ms, readable at arm's length) | Full-width result panel over the lower half: **Admitted** (`success` fill, check icon, holder name `title1`, ticket type, "1 of 3 in this order"), **Re-admitted** (re-entry), **Already inside / already scanned** (`warning`, time of first scan and gate), **Not valid** (`danger`: Not found · Voided/refunded · Wrong date · Not yet open · Device revoked), **Conflict** (offline duplicate — `warning`, flagged for the supervisor). Colour + icon + words, never colour alone. Auto-dismiss after 1.5 s for Admitted; others wait for a tap |
| Offline | Persistent header chip "Offline · 23 scans to sync"; scans keep working from the signed manifest; sync is automatic and idempotent (`client_scan_id`) |
| Search | Manual lookup by name, phone or serial with the same result panel; for SMS-ticket holders |
| Counters | Checked in / total, per ticket type, per gate (WebSocket when online) |
| Re-entry | Toggle per occurrence (`allow_reentry`) mirrored in the scanner as "Scanning: In / Out" segmented control |
| Box office (7.5) | Sales tab (B-4) with order channel BOX_OFFICE, cash drawer tied to the occurrence, tickets issued as SMS or printed serial |

## 10. Gaps and API needs

| # | Needed by | Need |
|---|---|---|
| G1 | All | Public events API (event page payload with occurrences, ticket types with current price phase and all-in price, availability flags) — Stage 6 |
| G2 | Cards, E-07 | `fewLeft` / `soldOut` flags per occurrence and type without exposing exact counts |
| G3 | DD6 | Confirmation that buyer-fee amounts are returned per ticket type for all-in display (Q6) |
| G4 | E-11 | Offline ticket store format (encrypted, QR secret + serial + event details) and refresh endpoint |
| G5 | E-11, B-3 | `expo-brightness` and `expo-camera` — **approved** (19 Nov 2026); install with `npx expo install` when E-11 / B-3 are built |
| G6 | E-04 | Ranking signals in `discovery.listings.quality_score` documented; promoted-slot rules enforced server-side |
| G7 | E-16 | Market rule: do date changes grant a refund right? (consumer-protection review with counsel, D3) |
| G8 | E-08 | Forms-engine renderer contract (field types, validation, conditional logic) shared with S-09 |
