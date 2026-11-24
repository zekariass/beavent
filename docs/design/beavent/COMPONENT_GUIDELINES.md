# Beavent component guidelines

**Status:** v1, 19 Nov 2026. None of these components exist yet (the mobile app has only Expo template primitives; see [DESIGN_PHILOSOPHY.md §6](DESIGN_PHILOSOPHY.md#6-where-beavent-is-today-audit-of-the-repository-19-nov-2026)).
**Uses:** tokens from [DESIGN_TOKENS.md](DESIGN_TOKENS.md). Every size, colour and radius below is a token name or a value from that file.
**Rule:** a screen is built from these components. If a screen needs something not listed, add it to this document first (with anatomy, variants, states and rules), then build it.

---

## 0. Implementation status (19 Nov 2026)

| Implemented | Where |
|---|---|
| `Text`, `Icon`, `Button`, `TextField`, `Screen`, `ScreenTitle`, `Section`, `ListRow`, `OptionRow`, `Group`, `Divider`, `Chip`, `Banner`, `EmptyState`, `Skeleton`, `SkeletonRows`, `DualLine` | `packages/native/src/ui` (`@beavent/native`) |
| Native tab bar (token colours, translated labels, SF/Material symbols) and its web variant | `apps/public-mobile/src/components/app-tabs*.tsx` |
| Partner-portal primitives (`inputClass`, `buttonClass`, `secondaryButtonClass`, `secondaryButtonLargeClass`, `listClass`, `rowLinkClass`, `Field`, `Section`, `FormMessage`) and `WorkspaceNav` (AppShell sidebar) | `apps/web/src/components/business` |
| Admin primitives on tokens (`PageTitle`, `Badge` with readable labels, `Table`, `Empty`, `SearchForm`) | `apps/web/src/components/ops/ui.tsx` |

Everything else in this document is specified but **not built yet** (BottomSheet, Dialog, Toast, PhoneField,
CodeField, Stepper, SegmentedControl, StickyActionBar, StepProgress, AnchorTabs and all §6 marketplace components).
Build them to this spec when a screen first needs them (AI_AGENT_DESIGN_INSTRUCTIONS.md §10).

## 1. Where components live

| Layer | Location | Contains |
|---|---|---|
| Tokens and pure helpers | `packages/ui` (TypeScript only, no React — keep it that way, DD14) | Tokens, `lineHeightPx`, `fontSizePx`, `cssVariables`, contrast test |
| Formatting | `packages/money`, `packages/calendar`, `packages/i18n` | Price, date, time, duration, plural formatting |
| React Native components | `packages/native/src/ui/` (`@beavent/native`, shared by both mobile apps: foundations, actions, inputs, overlays, states) and `apps/public-mobile/src/components/marketplace/` (consumer-only cards, rows, pickers, summaries, ticket pass) | Everything in §2–§8 |
| Web components | `apps/web/src/components/` (shared), `components/business/` and `components/ops/` — one web app, so no shared web package is needed | Web equivalents with the same names, props and rules |
| Route files | `apps/public-mobile/src/app/**`, `apps/business-mobile/src/app/**` | Composition only: data fetching, layout of components. No styling beyond layout |

**Naming:** PascalCase component, file in kebab-case (`venue-card.tsx` exports `VenueCard`), matching the existing mobile convention. Props use domain words from the API (`priceType`, `durationMinutes`, `deliveryModes`), not new synonyms.

**Every interactive component** must: expose `accessibilityRole` and a translated `accessibilityLabel` when its visible text is not enough; meet the 48 dp target; show pressed, focused (keyboard/web) and disabled states; work in light and dark; work in `am` and `en` at 200 % font scale.

---

## 2. Foundations

### 2.1 Text

The only way to render text. Replaces `ThemedText`.

| Prop | Values |
|---|---|
| `role` | `display`, `title1`, `title2`, `body`, `bodyStrong`, `label`, `labelStrong`, `caption`, `buttonLarge` ([tokens §3.2](DESIGN_TOKENS.md#32-type-roles)) |
| `tone` | `default` (`text`), `muted` (`textMuted`), `subtle` (`textSubtle`, disabled only), `primary`, `success`, `warning`, `danger`, `inverse` |
| `numeric` | `true` → tabular figures |
| `lines` | Max lines before ellipsis (titles: 2) |

Behaviour: reads the UI language from the preferences provider; detects Ge'ez code points in the string and applies the `Ethi` size (with the 14 px minimum) and line height — this closes gap A6. Never accepts a raw `fontSize`, `color` or `fontWeight` style.

### 2.2 Box, Screen, Section

- **`Screen`** — background `background`, safe-area handling, optional scroll, optional `stickyFooter` slot (renders `StickyActionBar`), optional `header`. Applies `gutter` horizontally and `maxContentWidth` centring on tablets.
- **`Section`** — a `title2` title (optional "See all" tertiary button on the right), `titleGap`, content, and `sectionGap` after it. No background, no border.
- **`Box`** — layout primitive accepting only token values for padding, gap and background.

### 2.3 Pressable feedback

| Element | Pressed feedback |
|---|---|
| Rows, chips, secondary buttons | Background → `surfacePressed` (`duration.fast`) |
| Primary button | Background → `primaryPressed` |
| Cards with images | Whole card opacity 0.85; no scale animation |
| Icon buttons | Circle fills with `surfacePressed` (or `imageScrim` darkens to 0.55 on photos) |

Android ripple is allowed instead of the background change for rows (`android_ripple` with `surfacePressed`), never both.

### 2.4 Icon

Wraps `SymbolView` from `expo-symbols`. Props: `name` (one cross-platform name mapped to `{ ios, android, web }`), `size` (16/20/24), `tone` (as `Text`). Decorative icons are hidden from screen readers; meaningful icon-only controls get their label from the parent button.

### 2.5 Divider

Hairline, `border`, inset to the text start of the row it separates. Prop `inset: "none" | "text" | "thumbnail"`. Never between a section title and its content.

---

## 3. Actions and inputs

### 3.1 Button

| Variant | Fill | Label | Use |
|---|---|---|---|
| `primary` | `primary` | `onPrimary` | The single main action of a screen or sheet |
| `secondary` | `surface` | `text` | Alternatives next to or below a primary ("Add to calendar") |
| `tertiary` | none | `primary` | Low-emphasis actions, "See all", "Change", links outside running text |
| `destructive` | `danger` | `onDanger` | Final confirmation of cancel/refund/remove, only inside a confirmation sheet or dialog |
| `destructiveTertiary` | none | `danger` | "Cancel booking" entry point on a detail screen |

| Size | Height | Label role | Horizontal padding |
|---|---|---|---|
| `lg` | 52 | `buttonLarge` | 20 |
| `md` | 44 | `buttonLarge` | 16 |
| `sm` | 36 (hit area 48) | `labelStrong` | 12 |

**States:** default, pressed, focused (2 px `focus` ring, 2 px offset), disabled (fill `surface`, label `textSubtle`, not focusable by screen readers as actionable — announce "dimmed"), loading (label replaced by a 20 dp activity indicator in the label colour; width does not change; the button is disabled while loading).
**Rules:** radius `lg`; full width in `StickyActionBar` and sheets; one `primary` per screen; label is a verb phrase ("Confirm booking", "Get tickets"), never "OK"/"Submit"; optional leading icon only for `secondary` with a well-known icon (calendar, directions, share). Never two primary buttons side by side — pair `primary` with `secondary` (stacked on phones, side by side ≥ 600 dp with the primary on the trailing side).

### 3.2 IconButton

Circle, 40 dp visual (48 hit area). Variants: `plain` (no fill; icon `text`), `filled` (`surface` fill), `onImage` (`imageScrim` fill, icon `#FFFFFF`). Required `accessibilityLabel`. Toggle variant for save: heart outline → filled heart (`text` on page backgrounds, `#FFFFFF` on images), with `accessibilityState.selected` and a toast "Saved" / "Removed from saved — Undo". Use: back, close, share, save, more, map/list toggle.

### 3.3 TextField

Anatomy: label (`label`, above the field, always visible — no floating labels, they fail at 200 % scale and in Ge'ez), field (height 52, radius `lg`, 1 px `borderStrong`, fill `background`), optional leading/trailing element, helper text (`caption` `textMuted`), error text (`caption` `danger`, with an error icon, replaces helper).
States: default, focused (2 px `primary`), filled, disabled (fill `surface`, text `textSubtle`), error (2 px `danger` + message), read-only (no border, `surface` fill).
Rules: errors appear on blur or submit, never while typing the first time; the message says how to fix ("Enter a phone number starting with 9 or 7"); keyboard type and `autoComplete`/`textContentType` always set; `returnKeyType` moves to the next field.

### 3.4 PhoneField

TextField with a non-editable country prefix (`+251` with flag-free text, the market's calling code from `ref.countries`), national-format grouping as you type (`91 123 4567`), validation through libphonenumber metadata on the API side (doc 06 §5.6), `textContentType="telephoneNumber"`. One field, never split into parts.

### 3.5 CodeField (one-time code)

Six boxes (48 × 56, radius `lg`, 1 px `borderStrong`, active box 2 px `primary`), digits `title1` `numeric`. Single hidden input underneath so paste and SMS autofill (`textContentType="oneTimeCode"`, Android SMS Retriever/autofill hints) work. Below: "Didn't get it? Resend in 0:28" (tertiary button enabled after 30 s — matches the API's 30 s resend window) and "Send by Telegram instead" when available (doc 06 §2.3 fallback order). Error state: boxes `danger`, message, input cleared and focused. Auto-submits on the sixth digit.

### 3.6 SearchField

Two forms:
- **Entry (button)** on Explore: 52 dp, radius `lg`, fill `surface`, search icon + placeholder `textMuted` ("Search services, places or events"). Tapping opens Search (C-2) with the keyboard up. It is a button for screen readers.
- **Active** in Search: same look plus a clear button (×) when non-empty and a "Cancel" tertiary button to the right. Debounce queries 250 ms; minimum 2 characters (1 for Ge'ez, since one syllable carries more information).

### 3.7 Stepper (quantity)

`−` IconButton (`filled`), count (`bodyStrong` `numeric`, min width 32, centred), `+` IconButton (`filled`). Disabled `−` at 0 or min; disabled `+` at max with a `caption` explaining why ("Max 4 per order", "Only 2 left"). Accessibility: `adjustable` role with increment/decrement actions; announces "2 tickets". Used for ticket quantities and guests.

### 3.8 Checkbox, Radio, Switch

- **Checkbox** 24 dp, radius `sm`, 1.5 px `borderStrong`; checked fill `primary` with `onPrimary` check. Label to the right, whole row tappable.
- **Radio** in `OptionRow` (full-width row: label + description on the left, 24 dp radio on the right); selected row gets a `primary` 1.5 px outline and `primarySubtle` fill when options are presented as a group of cards (payment methods), otherwise just the radio.
- **Switch** platform-native (`Switch`) with `primary` track when on. Only for settings that apply immediately; never in forms that have a submit button.

### 3.9 SegmentedControl

Track `surface`, radius `full`, height 36; selected segment `background` (light) / `surfaceRaised` (dark) with `labelStrong`; unselected `label` `textMuted`. 2–3 segments, equal width, labels ≤ 14 characters in English and checked in Amharic. Use: Bookings "Upcoming | Past", map/list on wide screens. Not for primary navigation.

### 3.10 Chip

| Variant | Use | Default | Selected |
|---|---|---|---|
| `filter` | Toggling a filter (Open now, Women only, Free) or opening a filter sheet (Price ▾) | Fill `surface`, label `text` | Fill `primarySubtle`, 1.5 px `primary`, label `labelStrong` `primary`; sheet-opening chips show the chosen value ("Under Br 1,000") |
| `choice` | Single choice in a row (category chips on Explore, date chips "Today / This weekend") | Fill `surface` | Same as filter selected |
| `input` | Showing an applied value that can be removed (in search results summary) | Fill `surface` + × | — |

Height 36, radius `full`, padding 14 horizontal, `label` role. Chip rows scroll horizontally with `gutter` insets at both ends; never wrap to more than 2 lines (use a sheet for longer lists). A chip that opens a sheet ends with a chevron-down icon (16).

### 3.11 LocalizedTextField (partner portal and organizer tools)

For `LocalizedText` API fields (`Name` requires at least one language). Language tabs above the field (`am · en · om`, the market's enabled languages) with a dot on tabs that have content; the organization's default language first. Character counter per language. Validation message when no language has text. Machine translation (events, `is_machine_translated`) is a tertiary "Translate from Amharic" action that fills an empty tab and marks it "Machine translated — review".

---

## 4. Content

### 4.1 Media

Wraps `expo-image` (already a dependency). Props: `file` (API file with variants), `aspect` (`3:2`, `2:1`, `1:1`, `4:5`), `variant` (`list` | `detail` — chooses `w320`/`w960` per [MOBILE_UI_GUIDELINES.md §6](MOBILE_UI_GUIDELINES.md#6-images)), `radius`, `placeholder` (`initial` with a letter, or `label`).
States: loading (placeholder `surface` + optional initial; fade-in `duration.base` when loaded), error (placeholder stays; no broken-image icon), no file (placeholder). `contentFit="cover"`, `cachePolicy` memory-disk. Accessibility: decorative unless it is the subject (gallery viewer images get alt text from the gallery caption or "Photo 3 of 12, {venue}").

**Gallery** variant (venue hero, event cover): full-bleed, swipeable (`FlatList` pager), photo counter `caption` on `imageScrim` pill bottom-right, tap opens the full-screen viewer (pinch to zoom, swipe down to close). Only the visible and next image load.

### 4.2 Avatar

Circle; sizes 24/32/40/56/80. Image or initials (given name + first letter of father's name, doc 06 §2.2; `primarySubtle` fill, `primary` text). Optional `selected` ring (2 px `primary` with 2 px `background` gap) used in the professional picker. Organization logos use `radius.lg` squares, not circles.

### 4.3 ListRow

The workhorse for everything that is not an image-led card.

Anatomy: optional leading (avatar 40, thumbnail 64, or icon 24), content (title `bodyStrong`, up to 2 metadata lines `label` `textMuted`), optional trailing (value text, price, chevron, switch, radio, button `sm`). Min height 56, `rowPaddingY` 12, `gutter` sides. Divider `inset="text"` between rows.
Variants: `navigation` (chevron; whole row navigates), `value` (label left, value right — used in summaries), `action` (trailing button; the row itself is not tappable), `option` (radio/checkbox).
Rules: title wraps to 2 lines; no more than one trailing element; destructive rows use `danger` title and no chevron.

### 4.4 SectionHeader

`title2` + optional tertiary "See all" (navigates to a full list). Optional one-line `label` `textMuted` subtitle ("Based on 212 verified visits"). Never an icon before the title.

### 4.5 StatusLabel

Small text label, radius `sm`, padding 2/6, `label` 500. Tones: `neutral` (`surface` fill), `success` (`successSubtle` fill), `warning` (`warningSubtle`), `danger` (`dangerSubtle`), `info` (`infoSubtle`) — **label text is always `text`** (the status colour on its subtle fill fails contrast for small text, e.g. warning 4.2:1; enforced by `contrastPairs`), plus `onImage` (`imageScrim` + `#FFFFFF`). Text always present (no dot-only status).
**Allowed statuses (consumer):** Confirmed, Requested (awaiting approval), Pending payment, Cancelled, Completed, No-show, Sold out, Few left, Sales ended, Postponed, Cancelled (event), Free, Online, Promoted. One per card/row maximum (P7). "Free" and "Online" are facts and should usually be part of the metadata sentence instead.

### 4.6 Rating

`★ 4.8` (`accent` star 16 + `labelStrong` `numeric`) + ` (212)` `label` `textMuted`. Variant `large` for review summaries: `display` number, 5-star row (20 dp, partial stars by clipping), count `label`. Hidden (replaced by "New on Beavent" text) when `rating_count < 3`. Accessibility label: "Rated 4.8 out of 5 from 212 reviews".

### 4.7 PriceText

Props: `money` (`{ amountMinor, currency }`), `priceType` (`FIXED | FROM | FREE | ON_CONSULTATION`), `range` (`{ min, max }`), `original` (pre-discount), `size` (`default` = `bodyStrong`, `small` = `labelStrong`, `large` = `title1` 600). Formatting per [DESIGN_TOKENS.md §8.1](DESIGN_TOKENS.md#81-money). Always `numeric`, never truncated, never coloured except `success` for "Free" in ticket lists. Discounts: new price first, original struck through in `textMuted`.

### 4.8 DateTimeText

Props: `instant` (ISO string), `timeZone` (location or occurrence zone — never the device zone), `format` (`long | short | relative | time | slot`), `dual` (`auto | always | never`). Uses the user's calendar and time-convention preferences ([DESIGN_TOKENS.md §8.2–8.3](DESIGN_TOKENS.md#82-dates)). Renders primary text in the given role and the secondary convention in `caption`/`label` `textMuted`. Accessibility label spells out both ("Saturday, Tahsas 6, 8 PM, which is 2 in the evening Ethiopian time").

### 4.9 MetaLine

A sentence of facts joined by ` · ` in `label` `textMuted`, e.g. `Hair salon · Bole · 1.2 km`. Items are omitted (not shown as blanks) when absent. Wraps naturally; never more than 2 lines (the least important items are dropped first; each use site defines the order).

### 4.10 Banner

Inline, full-width inside the gutter, radius `lg`, padding 12/16, `*Subtle` background + status-coloured icon (20) + `label`/`body` text in `text` (never in the status colour — see StatusLabel) + optional tertiary action. Tones: `info`, `success`, `warning`, `danger`. Not dismissible unless the content is purely informational. Use: "This event was postponed to …", "You're offline — showing saved tickets", "Payment is taking longer than usual". Never more than one banner per screen region.

---

## 5. Navigation

### 5.1 Tab bar

Native tabs (`expo-router/native-tabs`, already used). Labels from `@beavent/i18n` (`nav.*`), never hard-coded (today "Home"/"Explore" are hard-coded — A4). Icons from `expo-symbols`/template images, outline unselected and filled selected. Colours from tokens ([DESIGN_TOKENS.md §2.3](DESIGN_TOKENS.md#23-theme-behaviour)). Hidden on detail screens and flows (DD3). Tabs and their order: [MARKETPLACE_NAVIGATION.md §2](MARKETPLACE_NAVIGATION.md#2-bottom-navigation-recommendation).

### 5.2 Header

| Variant | Anatomy | Use |
|---|---|---|
| `tabRoot` | Large title `title1` left-aligned, optional trailing IconButton(s) (max 2); collapses to a compact centred `bodyStrong` title with a hairline on scroll | Saved, Bookings, Account |
| `explore` | Location selector (`labelStrong` + chevron) left; SearchField entry below; ScopeTabs below that. The location row scrolls away; search + scope tabs stick | Explore |
| `stack` | Back IconButton, centred `bodyStrong` title (1 line), up to 2 trailing IconButtons | Lists, settings, sub-pages |
| `overImage` | Transparent; `onImage` IconButtons (back left; share + save right) over the hero; after the hero scrolls out, becomes `stack` with the venue/event name and a hairline | Venue profile, event detail |
| `flow` | Close (×) or back left, step title centred, optional trailing "Skip"/tertiary; `StepProgress` under it | Booking and ticket flows |

### 5.3 ScopeTabs

Text tabs under the Explore search: **All · Services · Events**. `labelStrong` selected with a 2 px `text` underline (not `primary`: selection here is navigation, not a choice inside a form), unselected `label` `textMuted`. Swiping the content horizontally does **not** switch scope (conflicts with rails). Remembers the last scope per device.

### 5.3a AnchorTabs

In-page section tabs on long detail screens (venue: Services · Team · Reviews · About; event: About · Tickets · Location · Organizer). Same look as ScopeTabs. Appear (sticky, under the collapsed header) only after the hero scrolls away; tapping scrolls to the section; the selected tab follows the scroll position (scroll-spy). Sections that are empty are omitted from the tabs. Not used on screens shorter than ~2 viewport heights.

### 5.4 StepProgress

A thin segmented bar (3 px, gaps of 4) under the `flow` header: completed and current segments `primary`, upcoming `border`. Number of segments = steps actually shown (skipped steps are not drawn). Accessibility: "Step 2 of 4, Choose a time". No step numbers in text, no step names row.

### 5.5 StickyActionBar

Bottom-anchored bar on detail screens and flow steps. `surfaceRaised`, elevation `sticky` (only while content scrolls beneath), `actionBarPadding`.
Layouts:
- **Summary + action:** left column summary (`bodyStrong` price/total + `label` `textMuted` line such as "2 services · 2 h 15 min" or "3 tickets"), right a `primary` `md` button. Used on venue profile with a basket, ticket selection, review steps.
- **Action only:** full-width `primary` `lg` button.
- **Two actions:** `primary` full width + `tertiary` below (e.g. "Pay Br 360" / "Pay at venue instead").
Hides when the keyboard is open on forms (the form's own submit becomes reachable via the keyboard return key and stays above the keyboard).

### 5.6 CategoryChips

A horizontal `choice` chip rail of categories from `GET /v1/ref/categories` (kind `SERVICE`/`BUSINESS` in Services scope, `EVENT` in Events scope). Text only — the category `icon` field is not shown in chips (P7). Max 12 chips, then "More" opens the full category sheet.

---

## 6. Marketplace components

Data fields reference the API/schema so implementers bind the right values.

### 6.1 VenueCard (rails)

Width: rail card width ([tokens §4.4](DESIGN_TOKENS.md#44-sizes)). Image 3:2, radius `lg`, `list` variant. Below the image (8 dp): name `bodyStrong` (2 lines), `Rating` + MetaLine (`category · area`), optional third line: next availability ("Next: Today ጠዋት 4:00 (10:00)") from `next_available_at` when present. Save IconButton `onImage` top-right of the image. No card background or border — image + text on the page background.

### 6.2 VenueResultRow (search results, category lists)

Leading thumbnail 104 × 104 radius `lg`; content: name `bodyStrong` (2 lines), `Rating`, MetaLine (`area · distance`), then up to **3 matching services** as compact rows (`label` name left, `labelStrong` price right, `caption` duration under the name) separated by 4 dp; "See all services" tertiary when more match. Tapping the venue part opens the profile; tapping a service opens the profile with that service preselected in the booking flow. Divider between results. (This is the Fresha "services in results" idea with a data-light thumbnail instead of a carousel.)

### 6.3 ServiceRow

On venue profile and booking step 1. Left: name `bodyStrong` (2 lines), duration (`label` `textMuted`, range across variants: "45 min – 1 h 30 min"), optional one-line description `label` `textMuted` (1 line, ellipsis). Right: `PriceText` (respecting `priceType`) and a 36 dp add control (`+` in a `surface` circle; selected → check in a `primary` circle with `onPrimary`). Tapping the row opens **ServiceSheet**; tapping the control toggles selection (if the service has more than one variant, the control opens the sheet instead). Audience and delivery mode facts appear in the metadata line only when they differ from the venue default ("At home available", "Women only").

### 6.4 ServiceSheet

Bottom sheet: name `title1`, duration + price line, description (expandable), work photos row (service gallery, 1:1 thumbnails 88 dp), **variants** as `OptionRow` radios (variant name, duration, price; default variant preselected), **add-ons** as checkboxes (name, "+ 15 min", "+ Br 200"), **requirements** as `info` Banner ("Needs a patch test at least 48 h before" when `requiresPatchTest`), aftercare under a disclosure. Sticky footer: `primary` "Add · Br 1,800" (updates with variant and add-ons) or "Update" if already selected, plus "Remove" tertiary when selected.

### 6.5 ProfessionalOption

`OptionRow` with avatar 56, name `bodyStrong`, job title `label` `textMuted`, `Rating` (staff rating when ≥ 3 reviews), and price delta if staff pricing differs ("Br 2,000", from the resolved price). First option is always **"Any professional"** (generic avatar with a people icon, subtitle "Most availability"). Grid variant (2 columns of 80 avatars + name) when there are > 6 professionals and none has a price difference.

### 6.6 DateStrip

Above it: month label (`labelStrong`, both calendars when they differ: "Tahsas · Dec–Jan"), with previous/next week IconButtons. The strip: 7 date pills visible on a 360 dp phone (pill 44 × 64, radius `md`): weekday abbreviation (`caption`) over day number (`bodyStrong` `numeric`). States: available (default), selected (`primary` fill, `onPrimary` text), today (dot under the number), no availability or closed/holiday (number in `textSubtle`; still tappable, showing the "no times" message with the next available date; accessibility hint "no times available" or "closed"). Horizontally scrollable to `max_advance_days`. Ethiopian calendar users see Ethiopian day numbers; month boundary marked by a 1 px `border` divider and the new month's abbreviation.

### 6.7 TimeSlotGrid

Slots grouped under `label` headers by day period — Morning · Afternoon · Evening (`ጠዋት · ከሰዓት · ማታ`, the periods from `@beavent/calendar`). Grid of `slot` chips: 3 columns on 360 dp (4 on ≥ 412 dp), height 48 (56 when two-line). Default: fill `background`, 1 px `borderStrong`; selected: `primarySubtle` + 1.5 px `primary`, `labelStrong`. Two-line chip for Ethiopian-time users: `ጠዋት 3:00` / `9:00` caption. Empty day: no grid; a message + "Next available: Thu, Tahsas 9" tertiary + "Join waitlist" secondary. Slots refresh when the screen regains focus and after a `SLOT_TAKEN` error.

### 6.8 EventCard (rails)

Width: rail card width. Image 2:1, radius `lg`. Below: date line (`labelStrong` `primary`, short format + time, e.g. "Sat, Tahsas 6 · ማታ 2:00"), title `bodyStrong` (2 lines), MetaLine (`venue · area` or "Online"), price-from (`label`; "Free" when free). At most one StatusLabel on the image's top-left (`onImage`): Sold out, Few left, Postponed, Promoted. Save IconButton top-right.

### 6.9 EventResultRow

Leading 2:1 thumbnail 128 × 64 radius `md`; content: date line (`label` `primary`), title `bodyStrong` (2 lines), MetaLine (venue · price-from). Optional status label replaces the price-from text ("Sold out"). Divider between rows.

### 6.10 OccurrenceList

For recurring, multi-day, class-series and timed-entry events: a vertical list of `OptionRow`s grouped by month (`labelStrong` header): date (short) + time range, availability text on the right (`label`: "Available", `warning` "Few left", `danger` "Sold out" — disabled row). For `TIMED_ENTRY`, after choosing a date, a `TimeSlotGrid` of entry slots (`slot_label`). For > 30 occurrences, a month calendar grid replaces the list (days with occurrences are tappable, others `textSubtle`).

### 6.11 TicketTypeRow

Inside the ticket selection step. Left: name `bodyStrong`, price (`PriceText`, all-in, with "incl. Br 25 fee" `caption` when `fee_handling = PASS_TO_BUYER`), description (2 lines + "More"), sales info `caption` (`warning` "Early bird ends in 2 days", "Sales end Fri, Tahsas 5", "On sale Mon, Tahsas 1 ጠዋት 3:00"), admissions note for group tickets ("Admits 8"). Right: `Stepper` (min/max per order from `min_per_order`/`max_per_order` and remaining capacity). Variants: `donation`/`pay what you want` (amount TextField with the minimum shown, then a stepper), `locked` (access-code tickets are hidden until a code is applied — no locked teaser rows), `unavailable` (sold out / not yet on sale: stepper replaced by the StatusLabel, row text `textMuted`). Rows separated by dividers; no card per ticket type.

### 6.12 ProfileHeader (venue and organizer)

- **Venue** (`overImage` header + Gallery 3:2): name `display` (2 lines), `Rating` + review count link, MetaLine (`category · area`), landmark address line (`label` `textMuted`, 2 lines) with "Directions" tertiary, opening status (`label`: `success` "Open" · "until ማታ 2:00 (20:00)" or `textMuted` "Closed · opens …"), then at most two facts in a MetaLine ("Women only · At-home visits"). Actions row: none (save/share live in the header; booking lives in the sticky bar).
- **Organizer**: no hero gallery; logo 80 (`radius.lg`) left, name `title1`, MetaLine (`followers · events hosted`), short bio (3 lines + "More"), **Follow** `secondary` `md` button (→ "Following" with check). Verified organizations (KYB `VERIFIED`) show a single "Verified" text with a check icon after the name — the only icon-plus-text fact allowed in the header.

### 6.13 ReviewSummary and ReviewItem

- **ReviewSummary:** `Rating` `large`, sub-ratings (from `sub_ratings`, e.g. Cleanliness, Value, Punctuality) as `label` rows with a 4 dp bar (`text` on `surface`) and the value; "Based on 212 verified visits" (or "verified tickets").
- **ReviewItem:** reviewer name (Q11 format) `labelStrong` + date `caption` `textMuted`; stars 14; service or event name `caption`; body `body` (6 lines + "More"); staff name if rated ("with Hana"); business response indented 16 with a `surface` background block, radius `lg`, "Response from Abeba Beauty" `labelStrong`. Report action in a `more` menu.

### 6.14 BookingSummary / OrderSummary

`value` ListRows without dividers inside a section: item lines (service + professional + duration / ticket type × quantity), add-ons, travel fee (`AT_CUSTOMER`), discounts (`success` "− Br 150"), buyer fees ("Service fee" with an info IconButton opening an explanation sheet), then a divider and **Total** `bodyStrong` + `PriceText` `large`. For deposits: "Pay now (deposit 20 %)" and "Pay at venue" lines under the total. Tax is not added on top (prices are tax-inclusive); receipts break it out.

### 6.15 PaymentMethodList

`OptionRow` cards (1 px `border`, radius `lg`, selected `primary` outline + `primarySubtle`): method name `bodyStrong` (telebirr, CBE Birr, M-PESA, Card — the methods Chapa returns as available for the market and channel), one-line description (`label` `textMuted`: "Approve on your phone", "Pay with a Visa or Mastercard"), method logo 32 (`radius.md`) on the left — logos are the one place brand marks of third parties appear. "Pay at venue" appears as its own option when `allow_pay_at_venue` and no deposit is required. Last used method preselected. Phone-prompt methods show the phone number that will receive the prompt with "Change".

### 6.16 PaymentWaiting

Full-screen state (C-7): illustration-free. Title `title1` "Approve the payment on your phone", the phone number, the amount `PriceText` `large`, three numbered steps (`body`): "1. Open the prompt from telebirr · 2. Enter your PIN · 3. Come back here". Below: a slim indeterminate progress bar (`primary`), "We'll confirm automatically" `label` `textMuted`, time remaining on the hold (`HoldTimer`), `secondary` "I've approved it" (forces an immediate status check), `tertiary` "Use another method". States: waiting, confirming (after the user taps "I've approved it"), succeeded (→ confirmation), failed (`danger` Banner + "Try again" primary + other method), unknown/slow (`warning` Banner "This is taking longer than usual. Your time is held until 3:42 PM. We'll notify you when it's confirmed."). Survives backgrounding: on return the screen re-checks immediately.

### 6.17 TicketPass

The ticket as shown at the door (E-11). Panel `#FFFFFF` in both themes, radius `xl`, padding 24: event title `title2` (dark ink — fixed colours inside the panel), date/time (dual, always), QR 240 dp centred, serial below in `title2` `numeric` grouped (`7KQ-29F`), holder name `label`, ticket type and admission ("General admission · 1 of 3"). Outside the panel: venue + "Directions", "Add to calendar", Transfer, Order details. Pager dots when the order has several tickets (swipe). Offline indicator `caption` "Available offline" with a check when cached. Void/refunded tickets: QR replaced by a `danger` StatusLabel "Not valid — refunded" and no code.

### 6.18 PlanRow (Bookings timeline)

One row type for both verticals in the Bookings tab. Leading: date block (weekday `caption` over day number `title2` `numeric`, in the preferred calendar), content: title `bodyStrong` (service names joined, or event title), MetaLine (venue/organizer · time), optional StatusLabel (Requested, Pending payment, Cancelled, Postponed). Trailing: chevron. The **Next up** variant (first upcoming item within 48 h) is larger: image (venue 3:2 / event 2:1) above, the same text, and one `secondary` action ("Show ticket" / "Directions"), with a gold `accent` 4 dp leading bar — the screen's one gold highlight.

### 6.19 MapPreview

A static map image (no interactive map until Q3) 2:1 with a pin, radius `lg`, the landmark text below, and "Open in maps" `secondary` (deep link to the platform maps app with coordinates and the venue name). Without coordinates: hide the image; show the address and landmark only.

### 6.20 HoldTimer

Text, not a ring: "Held for you until 3:42 PM" (`label` `textMuted`). Switches to a tabular countdown "Held for 01:59" in `warning` during the last 2 minutes, announced to screen readers at 2:00, 1:00 and 0:30 only. On expiry: a blocking sheet "Your time slot was released" / "Your tickets were released" with "Find another time" / "Choose tickets again".

### 6.21 AddressPicker (at-home services)

Saved addresses first (`OptionRow`: label "Home", landmark line). "Add an address": sub-city (admin area picker from `GET /v1/ref/admin-areas`), street/area line, **landmark (required)** with an example placeholder ("e.g. behind Edna Mall, 2nd gate"), house/apartment, instructions for the professional, map pin (confirm on a static map until Q3; "Use my current location" when permission is granted). Shows the travel fee and whether the address is inside the service area before confirming.

---

## 7. Overlays

### 7.1 BottomSheet

Built on React Native `Modal` + Reanimated + Gesture Handler (all already installed; no sheet library). `surfaceRaised`, top radius `xl`, drag handle (36 × 4, `border`), `scrim` behind. Heights: `auto` (content up to 90 % of the window, then scrolls) or `full` (for long pickers). Dismiss: swipe down, tap the scrim, close IconButton in the sheet header when the sheet has a title, Android back. Sheets with a sticky footer keep it above the keyboard. Focus moves into the sheet on open and returns to the trigger on close.
Use for: service detail, filters, sort, cancellation confirmation, sign-in, fee explanations, share. Never stack two sheets (replace content instead).

### 7.2 Dialog

Centred, `surfaceRaised`, radius `xl`, max width 320, padding 24: title `title2`, body `body`, actions stacked (primary/destructive on top, secondary below) on phones. Only for: destructive confirmations that cannot be a sheet (rare), permission explanations before an OS prompt, and blocking errors (session expired). Never for information.

### 7.3 Toast

Bottom, above the tab bar/action bar, `text`-coloured fill with `textInverse` text (light), `surfaceRaised` (dark); radius `lg`; 4 s; optional single tertiary-style action ("Undo"). Use for confirmations of reversible actions ("Saved", "Removed from saved — Undo", "Link copied"). Never for errors that need action (use Banner) and never for booking/payment success (use the confirmation screen).

### 7.4 ActionMenu

The `more` (…) menu: platform action sheet on iOS, bottom sheet list on Android/web; destructive items last in `danger`.

---

## 8. States

### 8.1 Skeleton

Blocks in `surface` matching the final layout's geometry (image boxes at the right aspect, text bars at the role's line height, 60–80 % widths). Opacity pulse 1.2 s (disabled under Reduce Motion). Shown immediately for content screens; for actions shorter than 300 ms show nothing. Never a full-screen spinner. Each screen spec lists its skeleton.

### 8.2 EmptyState

Inside the content area, left-aligned on phones (centred only on tablets/web): title `title2`, one or two lines `body` `textMuted` explaining why and what to do, one `secondary` (or `primary` if it is the screen's only action) button. No illustration, no icon larger than 24. Copy is specific: "No bookings yet" + "When you book a service or get tickets, they'll appear here." + "Explore".

### 8.3 ErrorState

Same layout as EmptyState: title says what failed ("Couldn't load this salon"), body says whether it's the network or us, `primary` "Try again". For partial failures (one section failed), use an inline Banner in that section instead of replacing the screen. Problem codes from the API (`SLOT_TAKEN`, `SOLD_OUT`, `PAYMENT_PENDING`, `RATE_LIMITED`, `STEP_UP_REQUIRED`) map to specific copy and recovery actions, defined per screen in the journey docs.

### 8.4 OfflineBanner

`info` Banner pinned under the header while offline: "You're offline. Showing saved information." Disappears automatically on reconnect (with a brief "Back online" toast only if the user tried an action while offline). Actions that need the network are disabled with an explanation, not hidden.

### 8.5 SuccessState (confirmation)

Used by booking and ticket confirmations. Top: 48 dp check in a `successSubtle` circle (the only "illustration"), `display` title ("You're booked" / "Request sent" / "You're going"), one `body` line with the key fact ("Sat, Tahsas 6 · ጠዋት 3:00 (9:00) at Abeba Beauty"). Then the summary rows and next-step actions. No confetti, no animation beyond a 200 ms fade/scale of the check.

### 8.6 Inline validation

Errors next to the field (TextField §3.3). On submit with errors: scroll to and focus the first invalid field; a `danger` Banner at the top only when the error is not tied to a field (e.g. `422` from the API with a general message).

---

## 9. Business console components (web, `apps/web`, `/[lang]/business`)

The partner portal is desktop-first and denser, but it uses the same tokens, type roles and button variants. Desktop density: buttons and inputs are 44 px (`md`), secondary row actions 36 px; the 52 px input height is for touch screens. Shared class strings must never be extended with a utility that sets the same property (Tailwind v4 resolves conflicts by stylesheet order, not attribute order): use `inputBaseClass` + a width, or a named variant.

| Component | Spec |
|---|---|
| **AppShell** | Left sidebar 240 px (`surface` background, items `label` with 20 px icons, selected item `primarySubtle` + `primary` text), top bar with organization switcher, location switcher (location-scoped members see only theirs), language switch, account menu. Content max width 1200, `gutterWide` padding |
| **PageHeader** | `title1` + optional `label` `textMuted` description; primary action on the right ("Add service") |
| **DataTable** | Header row `label` `textMuted` on `background` with a bottom hairline; rows 56 px, `body`; row hover `surface`; numeric columns right-aligned `numeric`; row click opens the detail drawer; empty state inside the table; pagination by cursor ("Load more") — matches the API's `PageInfo` |
| **SortableList** | For ordered things (service groups, services within a group, ticket types): drag handle on the left, keyboard reordering (Space to lift, arrows to move), saves `sortOrder` |
| **EditorDrawer** | Right drawer 560 px for editing an item without leaving the list; sections with `title2`; sticky footer with `primary` Save + `secondary` Cancel. Full page instead of drawer for complex editors (service editor, event wizard) |
| **SaveBar** | For full-page forms: sticky bottom bar appearing when the form is dirty: "Unsaved changes" + Discard + Save |
| **ConflictBanner** | On `412 Precondition Failed` (ETag/`If-Match`): `warning` Banner "This was changed by someone else while you were editing." + "Review their changes" (shows a diff of fields) + "Overwrite" (re-submits with the new ETag). Never silently overwrite |
| **LocalizedTextField** | §3.11 |
| **MoneyField** | Currency prefix (`Br`), accepts major units and converts exactly with `@beavent/money` `parse()`; shows `ETB` decimals only if typed |
| **DurationField** | Hours + minutes selects (5-minute steps), shows the total ("1 h 30 min") |
| **StepsEditor** | For variant stages: horizontal timeline bar proportional to minutes (service = `primary`, processing = `primarySubtle` with "staff free" label, finishing = `primary`), rows below with kind, minutes, staff busy toggle; shows "Total must equal 2 h 30 min" validation |
| **PriceMatrix** | Variants as rows × (Default, per location, per staff, per pricing level) columns; editable cells; empty cell = inherits; a "Resolved price" preview panel using `GET /v1/orgs/{orgId}/services/{serviceId}/variants/{variantId}/price` explains "most specific wins" with the source (`STAFF_AND_LOCATION`, …) |
| **StatusLabel / Banner / Toast / Skeleton / EmptyState** | Same as mobile |
