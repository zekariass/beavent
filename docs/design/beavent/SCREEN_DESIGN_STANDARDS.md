# Beavent screen design standards

**Status:** v1, 19 Nov 2026.
**Purpose:** the rules that make every Beavent screen look and behave like the same product, and the checklist a screen must pass before it merges. Values come from [DESIGN_TOKENS.md](DESIGN_TOKENS.md); parts from [COMPONENT_GUIDELINES.md](COMPONENT_GUIDELINES.md).

---

## 1. The hierarchy model

Every screen is read in three levels. A viewport (one screen-height) shows **at most three levels of emphasis**:

| Level | Carried by | Example (venue profile) |
|---|---|---|
| 1 — What this is | One `display` or `title1` text, or a hero image + `display` | "Abeba Beauty Studio" |
| 2 — What you can do / the key facts | `title2` section titles, `bodyStrong` row titles, prices, the primary button | "Services", "Box braids — Br 1,800", "Book now" |
| 3 — Supporting detail | `label`/`caption` in `textMuted`, MetaLines, helper text | "3 h 30 min", "Bole · 1.2 km" |

If a fourth level seems necessary (a sub-sub-heading, a coloured callout inside a section), restructure: split into sections, move detail into a sheet, or drop it.

## 2. Screen templates

Choose a template before designing a screen. Each journey spec names its template.

### 2.1 Feed (Explore)
**Anatomy:** location row → search entry → scope tabs (sticky) → optional Next-up row → chip row → sections of rails (max 6) → end.
**Rules:** a rail needs ≥ 3 items (DD12); no section has more than one "See all"; no banners or promos above the first rail except a single system Banner (offline, service notice); the first viewport on a 360 × 640 phone shows the search field, the scope tabs, the chip row and the start of the first rail.

### 2.2 Results list
**Anatomy:** stack header with title + summary line → filter chips (sticky) → count → rows → end note; floating Map pill (when available).
**Rules:** rows, not cards (except ≥ 600 dp grids); every row has the same structure; sorting and filters are visible as chips, not hidden behind a single icon; empty and no-match states keep the filters visible.

### 2.3 Profile / detail
**Anatomy:** `overImage` header + hero (venue gallery 3:2 / event cover 2:1) or plain header (organizer, professional) → identity block (level 1 + facts) → AnchorTabs (sticky after hero) → sections in a fixed order → report link → sticky action bar.
**Rules:** the identity block fits in the first viewport *with* the hero on a 360 × 640 phone (hero ≤ 45 % of the viewport height); sections render only when they have content; the sticky bar's left side states the most decision-relevant fact (next availability, price-from, "You have 2 tickets").

### 2.4 Flow step
**Anatomy:** `flow` header (close/back + step title) + StepProgress → (optional context line, e.g. the chosen date) → one decision → sticky action bar (summary + Continue).
**Rules:** **one decision per step** (choose services; choose a professional; choose a time; review). Title is a verb phrase ("Choose a time"). The summary on the sticky bar always shows what has been chosen so far and the running total. Skippable steps are skipped, not shown pre-answered.

### 2.5 Sheet
**Anatomy:** handle → optional title row (title `title2` + close) → content → optional sticky footer with one primary.
**Rules:** a sheet does one thing (filter, detail of one item, confirm one action). Never a sheet on top of a sheet. Content longer than 90 % of the screen scrolls inside the sheet; if it is regularly that long, it should be a screen.

### 2.6 Confirmation
**Anatomy:** SuccessState (check, `display` title, key fact) → summary rows → next-step secondary actions → sticky primary "Done" / "View tickets".
**Rules:** shows only after server confirmation; states what happens next (reminders, where tickets are, when the request will be answered); no marketing, no ratings prompt, no upsell.

### 2.7 Tab root (management list)
**Anatomy:** `tabRoot` large title → SegmentedControl or chips → grouped rows (date headers in Bookings) → end.
**Rules:** one emphasised item at most (Next up); dates as headers, not repeated in every row's text; actions inline only when they are the obvious next step (Pay now, Leave a review, Book again).

### 2.8 Item detail (appointment, order)
**Anatomy:** stack header (+ more menu) → status → level-1 date/time → place block (map, directions) → what (summary) → money → policy → actions (secondary stacked) → destructive tertiary at the bottom.
**Rules:** status-specific layouts follow the status tables in the journey docs; destructive actions are never the first action on the screen.

### 2.9 Pass (ticket)
**Anatomy:** white panel (fixed colours) with event, time, QR, serial, holder → pager → actions below.
**Rules:** nothing interactive inside the panel; works offline; no ads, no recommendations.

### 2.10 Settings / account
**Anatomy:** inset grouped sections on `surface` (the only template where grouped surfaces are used) → rows with values/switches.
**Rules:** switches apply immediately; destructive items last; sign out centred at the end.

### 2.11 Partner portal list and editor
**List:** PageHeader (title + primary) → filters → DataTable → load more. **Editor:** full page with section headings (`title2`), right preview column ≥ 1280, SaveBar when dirty.
**Rules:** one primary per page header; tables over cards; forms in a single column of max 640 px (preview to the side).

## 3. When to use a card

A **card** here means an element with its own visual container (image + text block, or a bordered tile).

| Use a card | Use a row with dividers | Use plain section content |
|---|---|---|
| An image-led, tappable object in a rail or grid (VenueCard, EventCard) | Lists of homogeneous items (results, services, ticket types, bookings, reviews, settings) | Descriptions, policies, opening hours, "Good to know" |
| A selectable option that must look selectable as a whole (payment methods, delivery address options) | Summaries (BookingSummary/OrderSummary are rows) | Facts about the thing on screen |
| The ticket pass | Navigation lists | |

Never: card inside a card; a card around a whole section; a card containing a single line of text; cards with both border and shadow; coloured card backgrounds to separate content types.

## 4. Composition rules

1. **Left-aligned, single text edge.** Text starts at the gutter (or after a fixed-width leading element). Centre alignment only in SuccessState, EmptyState on tablets/web and the ticket panel.
2. **Numbers right, words left.** Prices, quantities and totals align to the trailing edge in rows and summaries; tabular figures keep columns aligned.
3. **One primary button per screen** (sticky bar or sheet footer). Secondary actions are `secondary`/`tertiary` and visually lighter.
4. **Facts as sentences.** MetaLine (`a · b · c`) instead of badges or icon rows. Maximum 4 facts per MetaLine, 2 lines.
5. **One status label per card/row**, one Banner per screen region, one gold accent per screen.
6. **Images earn their place.** Images appear where they help choose (venues, events, work photos, professionals). No decorative imagery, no icons as illustrations.
7. **Repetition is fine, monotony is not.** Feed rails, result rows and detail sections are different templates on purpose; don't build every screen as "title + list of cards".
8. **Above the fold (360 × 640):**

| Screen | Must be visible without scrolling |
|---|---|
| Explore | Search field, scope tabs, chip row, first rail start |
| Results | Filter chips, first 2 results with their price lines |
| Venue profile | Hero, name, rating, area/landmark, open status, sticky "Book now" |
| Event page | Date line, title, venue, organizer row, sticky price + "Get tickets" (the cover may be partially visible) |
| Time step | Date strip and the first two rows of slots |
| Ticket selection | The occurrence, the first two ticket types with steppers, the sticky total |
| Review step | When, what, total and the primary button (policy and payment may require scrolling; the button stays sticky) |
| Bookings | Next up item, or the first two rows |

9. **Density limits:** result rows ≤ 3 service lines; ticket descriptions ≤ 2 lines before "More"; reviews ≤ 6 lines before "More"; chips in a row ≤ 12 before "More".
10. **Spacing never compensates for missing hierarchy.** If something needs to stand out, change its level (type role) or move it — don't add extra space or borders.

## 5. Content and copy standards

| Topic | Standard |
|---|---|
| Voice | Warm, direct, respectful. Amharic uses the polite second person (as the existing catalogue does: "ያስይዙ", "ይግቡ"); English uses "you" and plain verbs |
| Case | Sentence case in English for everything (titles, buttons, tabs). No title case, no all caps |
| Buttons | Verb + object: "Book now", "Get tickets", "Pay Br 360", "Join waitlist", "Show ticket". Never "OK", "Submit", "Click here" |
| Numbers in copy | Digits, not words ("3 tickets"); money and times from the formatters only |
| Errors | What happened + what to do, without blame: "That time was just booked. Here are the latest times." Never codes, never "Oops", never exclamation marks |
| Empty states | Why it's empty + what to do: "No upcoming plans. When you book or get tickets, they'll appear here." |
| Policies | Concrete dates and amounts, not abstractions: "Free cancellation until Fri, Tahsas 5 · ጠዋት 3:00 (9:00)" |
| Trust copy | State what Beavent does with money: "Pay the deposit now. The rest you pay at the salon." |
| Emoji | None in UI copy. (Organizer-written event descriptions may contain them.) |
| Abbreviations | Avoid, except units (min, h, km) and E.C. in English dates |
| Gender | Amharic messages addressed to a person use gender-neutral polite forms or an ICU `select` (doc 06 §2.2) |

## 6. State standards

Every screen spec must define these states; a screen without them is not done.

| State | Required treatment |
|---|---|
| **Loading (first load)** | Skeleton matching the layout; interactive chrome (back, search) usable |
| **Refreshing** | Pull-to-refresh indicator or none; never replace content with a skeleton on refresh |
| **Empty** | EmptyState with reason + one action |
| **No results (filtered)** | Show active filters as removable chips + "Clear filters" |
| **Error (whole screen)** | ErrorState with "Try again"; keep any cached content visible with a Banner instead when possible |
| **Error (part of the screen)** | Inline Banner in that section; rest of the screen works |
| **Offline** | OfflineBanner; cached content; network actions disabled with "Needs a connection" |
| **Signed out** | Public content works; personal content shows a sign-in prompt explaining the benefit |
| **Permission denied** (location, notifications, camera) | Works without it; offers a settings link only where the feature truly needs it |
| **Success** | Confirmation screen (bookings, tickets) or toast (reversible actions) |
| **Unavailable / gone** | Delisted venue, unpublished event, expired link: EmptyState with a way forward (Explore, similar) |
| **Long-running** | Payment waiting: explicit waiting screen with time held and what happens if the user leaves |

## 7. Visual QA checklist

Run before requesting review; paste the checklist into the pull request with screenshots (light/dark × am/en for every new screen, plus 200 % font scale for flows).

**Tokens and components**
- [ ] No hex colours, raw font sizes or off-scale spacing in the diff (only tokens).
- [ ] Every element is a documented component (or the component was added to COMPONENT_GUIDELINES first).
- [ ] One primary button; one display text; ≤ 3 hierarchy levels per viewport.
- [ ] No cards where rows belong; no border + shadow; no gradients; no badge rows; no decorative icons.

**Content**
- [ ] All strings come from `@beavent/i18n` (am and en present; om falls back).
- [ ] Prices via `@beavent/money`, dates/times via `@beavent/calendar`; dual time where required; correct time zone (location/occurrence).
- [ ] Copy follows §5 (sentence case, verb buttons, specific errors).

**Layout**
- [ ] Baseline 360 × 640 in Amharic: nothing clipped, above-the-fold table (§4.8) met.
- [ ] 320 dp: no horizontal overflow.
- [ ] 200 % font scale: wraps, nothing truncated that shouldn't be.
- [ ] Safe areas correct; sticky bar never overlaps the tab bar or content.

**States**
- [ ] Loading skeleton, empty, error, offline, signed-out (and the screen-specific states from its spec) implemented and screenshotted.

**Accessibility**
- [ ] Roles, labels (translated), states; composite rows read as one sentence; headings marked.
- [ ] Contrast pairs from the allowed table only; colour never the only signal.
- [ ] Touch targets ≥ 48 dp; focus order correct; sheets trap and return focus.

**Performance**
- [ ] Lists virtualised; correct image variants; no shadows/animations in list items.
- [ ] Release build checked on a low-end Android profile; no new dependency without an approved decision.

## 8. Review rubric

Reviewers score each new screen; anything below "Pass" on a gate blocks merge.

| Gate | Pass means |
|---|---|
| G1 Fidelity | Matches its spec in the journey doc (layout, content, actions, states) or documents an agreed deviation in the PR |
| G2 System | Only tokens and documented components |
| G3 Trust | Prices all-in and consistent across steps; times dual where required; policies concrete |
| G4 Language | Correct in Amharic and English; Ge'ez metrics applied |
| G5 Resilience | All required states present; offline behaviour as specified |
| G6 Access | Screen reader pass on one platform; 200 % scale pass |
| G7 Budget | No regression of the bundle or cold-start budget; images per §6 of the mobile guidelines |
