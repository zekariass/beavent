# Beavent design tokens

**Status:** v1 proposal, 19 Nov 2026. Replaces the provisional values in [`packages/ui/src/index.ts`](../../../packages/ui/src/index.ts) once approved (open question Q1 in [DESIGN_PHILOSOPHY.md](DESIGN_PHILOSOPHY.md#8-open-questions)).
**Source of truth in code:** `@beavent/ui` (`packages/ui`). Web consumes it as CSS custom properties (`cssVariables()` → `--bv-*`), React Native as plain numbers and strings. **No app defines its own colours, spacing or type sizes.**
**Rule for names:** existing token names (`background`, `surface`, `text`, `textMuted`, `border`, `primary`, `onPrimary`, `success`, `warning`, `danger`; `space[0…16]`; `radius.sm…full`; `fontSize.xs…3xl`) are stable and keep their meaning. This document changes some values and adds tokens; it renames nothing.

---

## 1. Principles for tokens

1. **Semantic first.** Screens use semantic tokens (`text`, `surface`, `primary`), never palette steps (`emerald600`) and never hex.
2. **Every foreground has a declared background.** A colour pair is only allowed if it is in the contrast table (§2.4).
3. **Light and dark are equal citizens.** Every semantic token has both values; nothing is "light only".
4. **Tokens are few.** If a screen seems to need a new token, it probably needs an existing component. New tokens are added here first, then in code.

## 2. Colour

### 2.1 Palette (reference only — do not use directly in screens)

| Ramp | Steps (hex) |
|---|---|
| **Neutral (warm)** | `0 #FFFFFF` · `50 #F5F4F1` · `100 #EBE9E4` · `200 #E4E1DA` · `300 #C9C5BC` · `400 #A8A5A0` · `500 #8F8B83` · `600 #6B6862` · `700 #5E5B55` · `800 #33312E` · `850 #2E2D2A` · `900 #252422` · `925 #1D1C1B` · `950 #1A1917` · `1000 #121211` |
| **Emerald (brand)** | `50 #E2F0EA` · `300 #7AD6B5` · `400 #5CC8A1` · `600 #0B6B53` · `700 #085542` · `900 #133A2F` · `950 #06261D` |
| **Adey gold (accent)** | `300 #F5C542` · `500 #F0B323` |
| **Green (success)** | `50 #E5F3EB` · `400 #5CC794` · `700 #1F7A4D` · `950 #15301F` |
| **Amber (warning)** | `50 #FCF0DC` · `400 #F0A64A` · `700 #A86200` · `950 #33240F` |
| **Red (danger)** | `50 #FCE9E7` · `400 #F97066` · `600 #B42318` · `950 #3A1714` |
| **Blue (info)** | `50 #E6EEFB` · `300 #7EA6F2` · `600 #1F5BC4` · `950 #172440` |

### 2.2 Semantic colour tokens

"Current" is today's value in `packages/ui`; **bold** names are new tokens.

| Token | Light | Dark | Current light / dark | Use |
|---|---|---|---|---|
| `background` | `#FFFFFF` | `#121211` | same / same | Screen background |
| `surface` | `#F5F4F1` | `#1D1C1B` | `#f6f6f4` / same | Grouped sections, filled inputs, chips, skeletons, image placeholders |
| **`surfaceRaised`** | `#FFFFFF` | `#252422` | — | Sheets, dialogs, menus, the sticky action bar, toasts (dark mode uses colour instead of shadow) |
| **`surfacePressed`** | `#EBE9E4` | `#2E2D2A` | — | Pressed state of rows, chips and secondary buttons |
| `text` | `#1A1917` | `#F2F1EE` | `#1c1b1a` / same | Primary text, icons |
| `textMuted` | `#5E5B55` | `#A8A5A0` | `#5f5d58` / same | Secondary text: metadata, Gregorian secondary dates, helper text, placeholders |
| **`textSubtle`** | `#8F8B83` | `#74716B` | — | Disabled text and decorative separators (`·`) only. **Never** for information |
| **`textInverse`** | `#FFFFFF` | `#121211` | — | Text on `text`-coloured fills (toasts, the "Next up" pill) and on photo scrims (always `#FFFFFF` there) |
| `border` | `#E4E1DA` | `#33312E` | `#e2e0db` / same | Hairline dividers, card outlines on images-less tiles |
| **`borderStrong`** | `#8F8B83` | `#6B6862` | — | Input and checkbox outlines, unselected radio/slot outlines (≥ 3:1 for UI components) |
| `primary` | `#0B6B53` | `#5CC8A1` | `#5b3fd9` / `#9d8cff` | Primary buttons, selected states, links, active tab, progress |
| **`primaryPressed`** | `#085542` | `#7AD6B5` | — | Pressed primary button |
| **`primarySubtle`** | `#E2F0EA` | `#133A2F` | — | Selected chip/slot/date fill, selected row tint |
| `onPrimary` | `#FFFFFF` | `#06261D` | `#ffffff` / `#14102b` | Text and icons on `primary` |
| **`accent`** | `#F0B323` | `#F5C542` | — | Rating stars, brand mark, one highlight per screen. Never text on light backgrounds |
| **`onAccent`** | `#1A1917` | `#1A1917` | — | Text on `accent` |
| `success` | `#1F7A4D` | `#5CC794` | same / same | Confirmed, paid, open now |
| **`successSubtle`** | `#E5F3EB` | `#15301F` | — | Success banner background |
| `warning` | `#A86200` | `#F0A64A` | same / same | Few left, payment pending, closing soon |
| **`warningSubtle`** | `#FCF0DC` | `#33240F` | — | Warning banner background |
| `danger` | `#B42318` | `#F97066` | same / same | Errors, destructive actions, cancelled, sold out. On subtle fills (banners, status labels) text is `text`; the status colour is for the icon or fill only |
| **`onDanger`** | `#FFFFFF` | `#1A1917` | — | Text on a `danger` fill (destructive buttons). White on dark-mode `#F97066` would be 2.8:1 |
| **`dangerSubtle`** | `#FCE9E7` | `#3A1714` | — | Error banner background |
| **`info`** | `#1F5BC4` | `#7EA6F2` | — | Neutral system information (offline, postponed) |
| **`infoSubtle`** | `#E6EEFB` | `#172440` | — | Info banner background |
| **`scrim`** | `rgba(17,16,15,0.48)` | `rgba(0,0,0,0.60)` | — | Behind sheets and dialogs |
| **`imageScrim`** | `rgba(0,0,0,0.40)` | `rgba(0,0,0,0.40)` | — | Circle behind icon buttons placed on photos; bottom fade on hero images |
| **`focus`** | `#0B6B53` | `#5CC8A1` | — | 2 px focus ring (web, keyboard, TV/remote) |

**Fixed (theme-independent) values:** QR codes are always black modules on `#FFFFFF` with a 16 px quiet zone, in both themes (scanners need it). Photos are never tinted in dark mode.

### 2.3 Theme behaviour

- **Default:** follow the system (`userInterfaceStyle: "automatic"` in `apps/public-mobile/app.json`, `prefers-color-scheme` on web). An in-app override (System / Light / Dark) lives in Account → Display ([MARKETPLACE_NAVIGATION.md §7](MARKETPLACE_NAVIGATION.md#7-account-and-preferences)).
- **Dark mode is not an inversion.** Elevation is expressed by lighter surfaces (`background` → `surface` → `surfaceRaised`), not shadows. Brand and semantic colours shift lighter so they keep contrast.
- **React Navigation theme** (`_layout.tsx` today passes the library's `DarkTheme`/`DefaultTheme`) must be built from these tokens: `background` → `colors.background`, `surfaceRaised` → `colors.card`, `text` → `colors.text`, `border` → `colors.border`, `primary` → `colors.primary`.
- **Native tabs:** background `background`, selected label/icon `primary`, unselected `textMuted`. No indicator pill colour other than `primarySubtle` on Android.

### 2.4 Contrast pairs (WCAG 2.2; computed from the hex values above)

Allowed foreground/background pairs. Body text needs ≥ 4.5:1, large text (≥ 18 px regular or ≥ 14 px bold-ish 600) and UI component boundaries ≥ 3:1.

| Foreground on background | Light | Dark | Allowed for |
|---|---|---|---|
| `text` on `background` | 17.4 | 16.6 | Everything |
| `text` on `surface` | 15.6 | 15.0 | Everything |
| `textMuted` on `background` | 6.8 | 7.6 | Secondary text |
| `textMuted` on `surface` | 6.2 | 6.9 | Secondary text |
| `primary` on `background` | 6.5 | 9.1 | Links, tertiary buttons, selected labels |
| `primary` on `primarySubtle` | 5.5 | 6.0 | Selected chip/slot labels |
| `onPrimary` on `primary` | 6.5 | 7.9 | Primary buttons |
| `onAccent` on `accent` | 9.3 | 10.8 | Text on gold |
| `onDanger` on `danger` | 6.6 | 6.2 | Destructive buttons |
| `success` / `warning` / `danger` / `info` on `background` | 5.3 / 4.8 / 6.6 / 6.3 | ≥ 6 | Status text |
| `borderStrong` on `background` | 3.4 | 3.4 | Input/checkbox/slot outlines |
| `accent` on `background` | 1.9 | — | **Decorative only** (stars are always next to the numeric rating) |
| `textSubtle` on `background` | 3.4 | 3.6 | Disabled text only (exempt from contrast rules) |

Add these pairs as a unit test in `packages/ui/test` when the values land, so a future value change cannot silently break contrast.

### 2.5 Colour usage rules

1. **One brand colour per screen region.** `primary` marks actions and selection. If more than ~10 % of a screen's pixels are `primary`, something is wrong.
2. **Gold appears at most once per screen** (rating stars excluded; they are part of the rating component).
3. **Status colour is never the only signal.** `Sold out` is red *and* says "Sold out"; a selected slot is tinted *and* has a 1.5 px border.
4. **Never put text on a photo without `imageScrim`.** Overlaid text on photos is limited to the photo counter (`1/12`) and nothing else; titles go below images.
5. **Semantic subtle backgrounds are for banners only**, not for cards, rows or sections.
6. **Links are `primary` and underlined only inside running text.** Standalone links are tertiary buttons.

## 3. Typography

### 3.1 Families

| Platform | Latin (`Latn`) | Ge'ez (`Ethi`) |
|---|---|---|
| Mobile iOS | System (SF Pro) — no `fontFamily` set | Noto Sans Ethiopic, bundled (`@expo-google-fonts/noto-sans-ethiopic`, 400/500/600; decision Q4), loaded before the first render |
| Mobile Android | System (Roboto) | Noto Sans Ethiopic, the same bundled files as iOS (consistent metrics with the type roles) |
| Web, partner portal | Noto Sans (`next/font`, already loaded as `--font-latin`) | Noto Sans Ethiopic (`--font-ethiopic`) |
| Admin console | Same as web (today it uses Arial — A11) | Same as web |

Choosing the script: `scriptOf(language)` from `@beavent/i18n`. For user-generated text in another script than the UI (an Amharic venue name in the English UI), the `Text` component detects Ge'ez code points (U+1200–U+139F, U+2D80–U+2DDF, U+AB00–U+AB2F) and applies the `Ethi` metrics to that string.

### 3.2 Type roles

Sizes reuse the existing `fontSize` scale. Line heights in px (React Native needs absolute values).

| Role | Size token | Weight | Latn size / line height | Ethi size / line height | Use |
|---|---|---|---|---|---|
| `display` | `2xl` | 600 | 28 / 34 | 28 / 42 | The one defining name on a screen: venue or event title, confirmation headline. Max once per screen |
| `title1` | `xl` | 600 | 22 / 28 | 22 / 34 | Screen titles on tab roots and flow steps ("Choose a time") |
| `title2` | `lg` | 600 | 18 / 24 | 18 / 28 | Section titles ("Services", "This weekend") |
| `body` | `md` | 400 | 16 / 23 | 16 / 27 | Running text, descriptions, list primary text where not emphasised |
| `bodyStrong` | `md` | 600 | 16 / 23 | 16 / 27 | Row titles, service names, prices in lists |
| `label` | `sm` | 500 | 14 / 20 | 14 / 24 | Metadata lines, buttons sm, chips, form labels, tab labels |
| `labelStrong` | `sm` | 600 | 14 / 20 | 14 / 24 | Section links ("See all"), selected chip labels |
| `caption` | `xs` | 400 | 12 / 17 | **14** / 24 | Legal text, Gregorian secondary dates, photo counters. Raised to 14 for Ge'ez (minimum size rule) |
| `buttonLarge` | `md` | 600 | 16 / 20 | 16 / 24 | `lg` and `md` buttons (single line, so a tighter line height is allowed) |

`3xl` (34) is reserved for web marketing headings (the web landing page hero). It is not used in the app.

Heading line height for Ge'ez is 1.5 (not 1.7) for `display`, `title1`, `title2` — verify on a low-end Android device; if any glyph clips, revert to 1.7 for that role. Body roles keep 1.7.

### 3.3 Typography rules

1. **Weights:** 400, 500, 600 only. No 300 (thin Ge'ez strokes break up on low-density screens), no 700+ except numerals in `PriceText` `large`.
2. **No uppercase, no letter-spacing, no italics as hierarchy.** Ge'ez has none of them (DD8). Emphasis is weight or colour (`textMuted` vs `text`).
3. **Numerals:** prices, times, counts, countdowns and ticket serials use tabular figures (`fontVariant: ["tabular-nums"]` in RN; `font-variant-numeric: tabular-nums` on web). Ticket serials also use `letterSpacing` 1 and are grouped (`7KQ-29F`) — the one exception to the letter-spacing rule, because serials are read aloud at the door.
4. **Line length:** running text ≤ 70 Latin characters per line on wide screens (`maxWidth` 640 dp on tablets/web).
5. **Truncation:** titles in cards wrap to 2 lines then truncate with an ellipsis; names of people 1 line; descriptions use "Read more" expansion, never silent truncation. Never truncate a price, date, time or status.
6. **Font scaling:** support system font scale up to 200 %. Never set `allowFontScaling={false}` except inside the QR panel's serial (which has its own large size).
7. **Minimum sizes:** 12 px Latin, 14 px Ge'ez (enforced by the `Text` component — A6).

## 4. Spacing and layout

### 4.1 Scale (unchanged values)

`space`: `0 → 0` · `1 → 4` · `2 → 8` · `3 → 12` · `4 → 16` · `5 → 20` · `6 → 24` · `8 → 32` · `10 → 40` · `12 → 48` · `16 → 64`

### 4.2 Layout roles (new names, documentation-level; add to `packages/ui` as `layout`)

| Role | Value | Use |
|---|---|---|
| `gutter` | 16 (`space[4]`) | Horizontal page padding on phones |
| `gutterWide` | 24 (`space[6]`) | Horizontal page padding ≥ 600 dp |
| `sectionGap` | 32 (`space[8]`) | Between sections on a scrolling screen |
| `titleGap` | 12 (`space[3]`) | Section title → its content |
| `stackGap` | 8 (`space[2]`) | Between lines inside one item (title → metadata) |
| `inlineGap` | 8 (`space[2]`) | Between inline items (icon → label, chip → chip) |
| `railGap` | 12 (`space[3]`) | Between cards in a horizontal rail |
| `rowPaddingY` | 12 (`space[3]`) | Vertical padding of list rows (min row height 56) |
| `actionBarPadding` | 12 top / 16 sides / `max(12, safe-area bottom)` | Sticky bottom action bar |
| `maxContentWidth` | 800 | Existing `MaxContentWidth` (mobile web, tablets) |
| `readingWidth` | 640 | Text-heavy detail columns on tablets and web |
| `webContainer` | 1200 | Web marketplace grids |

### 4.3 Breakpoints (web and tablets)

| Name | Width | Layout |
|---|---|---|
| `compact` | < 600 | Phone layouts; one column |
| `medium` | 600 – 1023 | Tablets, small laptops: two-column results (list + map or 2-up cards), detail with side panel for the action box |
| `expanded` | ≥ 1024 | Web marketplace: 3–4-up card grids, sticky booking/ticket box in a right column (360 px) |

### 4.4 Sizes

| Item | Size (dp/px) |
|---|---|
| Minimum touch target | 48 × 48 (use `hitSlop` to reach it for smaller visuals) |
| Button heights | `lg` 52 · `md` 44 · `sm` 36 |
| Input height | 52 (single line) |
| Chip height | 36 |
| Time-slot chip | min 48 (two-line variant min 56) |
| Icon sizes | 16 (with caption), 20 (in rows and buttons), 24 (navigation, standalone) |
| Avatar sizes | 24 · 32 · 40 · 56 · 80 |
| List thumbnail | 104 × 104 (venues), 128 × 64 (events, 2:1) |
| Rail card width | `min(320, round((windowWidth − 16) × 0.78))` → 268 on a 360 dp phone (shows a peek of the next card) |

## 5. Shape

### 5.1 Radii (unchanged values, new usage)

| Token | Value | Use |
|---|---|---|
| `radius.sm` | 4 | Status labels, small inline tags |
| `radius.md` | 8 | Thumbnails ≤ 64 dp, date pills in the date strip |
| `radius.lg` | 12 | Buttons, inputs, cards, list thumbnails, slot chips, banners |
| `radius.xl` | 16 | Top corners of bottom sheets, dialogs, QR panel |
| `radius.full` | 9999 | Chips, avatars, icon buttons on photos, segmented control track |

Full-bleed hero images have no radius. Nested radii: inner radius = outer radius − padding (never an inner radius larger than the outer).

### 5.2 Borders and dividers

| Kind | Spec |
|---|---|
| Divider between rows | Hairline (`StyleSheet.hairlineWidth`), `border`, inset to the text start (not under thumbnails) |
| Section separator | None — use `sectionGap`. A full-width 8 dp `surface` band is allowed only between independent blocks on detail screens (e.g. between "Services" and "Reviews" on a venue profile on phones) |
| Input outline | 1 px `borderStrong`; focused 2 px `primary`; error 2 px `danger` |
| Selected chip/slot/date | 1.5 px `primary` + `primarySubtle` fill |
| Card outline | None on cards with images. Image-less tiles (e.g. a ticket type row in a sheet) use 1 px `border` |
| Never | A border **and** a shadow on the same element; dashed borders (except the "add photo" target in the partner portal) |

## 6. Elevation

| Level | Light | Dark | Use |
|---|---|---|---|
| `none` | — | — | All resting content |
| `sticky` | iOS shadow `0 −1 8` `rgba(0,0,0,0.06)`; Android `elevation: 4`; plus top hairline `border` | `surfaceRaised` + top hairline `border`, no shadow | Sticky bottom action bar *only while content scrolls beneath it*; collapsed sticky header shows only the hairline |
| `overlay` | iOS `0 8 24` `rgba(0,0,0,0.16)`; Android `elevation: 12` | `surfaceRaised`, no shadow | Bottom sheets, dialogs, menus |
| `toast` | iOS `0 4 16` `rgba(0,0,0,0.20)`; Android `elevation: 8` | `surfaceRaised` + 1 px `border` | Toasts/snackbars |

Layers (z-order): content 0 → sticky 10 → (native tab bar) → sheet 100 → dialog 200 → toast 300.

## 7. Motion, iconography and imagery

### 7.1 Motion

| Token | Value | Use |
|---|---|---|
| `duration.fast` | 120 ms | Press feedback, chip selection, checkbox |
| `duration.base` | 200 ms | Sheet resize, banner appear, cross-fades, skeleton → content |
| `duration.slow` | 300 ms | Sheet open/close, full-screen modal |
| `easing.standard` | cubic-bezier(0.2, 0, 0, 1) | Most transitions |
| `easing.exit` | cubic-bezier(0.3, 0, 1, 1) | Elements leaving |

Rules: motion explains a change (a sheet comes from the bottom because it is dismissible downwards); no decorative loops; no parallax on low-end Android; **Reduce Motion** (OS setting) replaces movement with cross-fades of `duration.base`. Skeleton shimmer is a slow opacity pulse (1.2 s), disabled under Reduce Motion. Reanimated (already installed) is the only animation library.

### 7.2 Iconography

- **One set:** platform symbols through `expo-symbols` (`SymbolView`, already used): SF Symbols on iOS, Material Symbols on Android and web. Do not add an icon font or SVG icon pack.
- **Style:** outline by default, filled for the selected state of tab icons and the saved (heart) state. Weight "regular"/400.
- **Colour:** `text` or `textMuted`; `primary` only when the icon is itself the action label or selected.
- **Allowed icons (consumer app):** tab icons; back, close, share, save (heart), search, filter (`slider.horizontal.3` / `tune`), map, list, location pin, chevron (disclosure in rows), calendar (add to calendar), phone, directions, more (`ellipsis`), check (selected/confirmed), info, warning, error, star (rating only), minus/plus (stepper), QR. Anything else needs a reason in the spec.
- **No icon-per-fact rows** (P7). Metadata is text.

### 7.3 Imagery

| Image | Aspect | List variant | Detail variant | Placeholder |
|---|---|---|---|---|
| Venue cover / gallery | 3:2 | `w320` | `w960` | `surface` fill + the venue's initial in `title1` `textMuted` |
| Event cover | 2:1 | `w320` | `w960` | `surface` fill + category name in `label` `textMuted` |
| Service gallery (work photos) | 1:1 thumbnails, 4:5 viewer | `w320` | `w960` | `surface` |
| Staff avatar | 1:1 circle | `w320` | `w320` | Initials (given name + father's name initial) on `primarySubtle`, `primary` text |
| Organization logo | 1:1, `radius.lg` | `w320` | `w320` | Initial on `surface` |

Rules: images never carry UI text; organizer covers that contain baked-in text are allowed (it is their artwork) but Beavent never overlays text on them. No stock photos, no illustrations of people. See [MOBILE_UI_GUIDELINES.md §6](MOBILE_UI_GUIDELINES.md#6-images) for variant selection and data budgets.

## 8. Formatting tokens (money, dates, times)

Formatting is part of the visual language. All of it comes from `@beavent/money`, `@beavent/calendar` and `@beavent/i18n`; screens never call `toLocaleString`, `Intl` or `Date` formatting directly ([doc 06 §2.1](../../06-markets-and-localization.md#21-calendar-and-time)).

### 8.1 Money

| Context | Format | Example (`en` / `am`) |
|---|---|---|
| Lists, cards, rows, ticket types | Narrow currency symbol, minor digits only when non-zero | `Br 1,800` / `ብር 1,800` ; `Br 49.50` |
| Totals in review/checkout | Same as lists | `Br 2,160` |
| Receipts, payment confirmation, refunds | Always minor digits (existing `format()`) | `ETB 2,160.00` / `ብር 2,160.00` |
| Price types (`catalog` `PriceType`) | `FIXED` → amount · `FROM` → "from {amount}" · `FREE` → "Free" · `ON_CONSULTATION` → "Price on consultation" | `from Br 1,200` |
| Ranges (variants, staff prices) | "{min} – {max}" sharing one symbol | `Br 1,200 – 2,400` |
| Strikethrough (promotions) | Old price `textMuted` with strikethrough after the new price | `Br 1,350  ~~Br 1,500~~` |
| Free events / tickets | "Free" (never "Br 0") | `Free` / `ነፃ` |

**Gap:** `@beavent/money` `format()` always prints minor digits with the ISO code (`ETB 1,500.00`). Add a display helper (e.g. `formatDisplay(value, locale)`) using `currencyDisplay: "narrowSymbol"` and dropping `.00` — in the package, not in screens.

### 8.2 Dates

| Context | Ethiopian-first (`am`, `om`) | Gregorian-first (`en`) |
|---|---|---|
| Long (detail screens) | `ቅዳሜ፣ ታኅሣሥ 6 ቀን 2019 ዓ.ም.` + caption `(15 Dec 2026)` | `Tuesday, 15 December 2026` + caption `Tahsas 6, 2019 E.C.` |
| Short (cards, rows, slot headers) | `ቅዳሜ፣ ታኅሣሥ 6` | `Sat, 15 Dec` |
| Relative (≤ 6 days ahead) | `ዛሬ` (Today), `ነገ` (Tomorrow), then weekday | `Today`, `Tomorrow`, then weekday |
| Date-strip pill | Weekday abbreviation + day number in the preferred calendar; month label above the strip in both calendars when they differ | `Sat` / `15` |

The year is omitted when it is the current year in the preferred calendar. Calendar preference: `iam.users.display_calendar`, defaulting from the market (`ETHIOPIAN` in ET) for `am`/`om` and to `GREGORIAN` for `en` (DD7).

### 8.3 Times

| Context | User prefers Ethiopian time | User prefers standard time |
|---|---|---|
| Appointment and ticket detail, confirmation, reminders | `ማታ 2:00` + muted `(20:00)` | `8:00 PM` + muted `(2:00 in the evening, Ethiopian time)` |
| Slot chips, cards, rows | Two lines: `ጠዋት 3:00` / caption `9:00` | One line: `9:00 AM` |
| Durations | `1 ሰዓት 30 ደቂቃ` (ICU plural keys) | `1 h 30 min`; `45 min`; `3 h` |
| Countdowns (holds, reservations) | `09:42` tabular | `09:42` tabular |

**Implemented:** `formatDualTime()` returns `{ primary, secondary }`; international time for people is 12-hour with AM/PM in every language (`formatClock`, decision Q7), so the Amharic secondary reads `(9:00 AM)` as in doc 06. Partner and staff tools use the 24-hour `formatStandardTime`.

### 8.4 Other values

| Value | Format |
|---|---|
| Rating | One decimal, star icon in `accent`, count in `textMuted`: `★ 4.8 (212)`. Shown only when `rating_count ≥ 3`; otherwise the text "New on Beavent" |
| Distance | `800 m`, `1.2 km`, `12 km` (no decimals ≥ 10 km) |
| Counts | ICU plurals from `@beavent/i18n` (`{count, plural, one {# ticket} other {# tickets}}`) |
| Phone numbers | Displayed in national format with spaces (`091 123 4567`), stored E.164 |
| Ticket serials | Groups of 3 with a hyphen, tabular, `letterSpacing` 1 |

## 9. Migration from the current code

**Status: done (19 Nov 2026)** — see GLOBAL_UI_IMPLEMENTATION_REPORT.md. How it was implemented, for reference:

- `packages/ui/src/index.ts` holds every token in this document plus `textRoles`/`textStyle`, `fontSizePx`, `scriptOfText`, `contrastPairs`/`contrastRatio`, `layout`, `size`, `elevation`, `duration`/`easing`; `npm test -w @beavent/ui` checks every allowed pair in both themes and that `tokens.css` is current.
- Web apps `@import "@beavent/ui/tokens.css"` (generated by `npm run generate -w @beavent/ui`): CSS variables for both schemes (`prefers-color-scheme`, overridable with `<html data-theme>`), Tailwind colour names (`bg-surface`, `text-fg`, `text-fg-muted`, `border-border-strong`, `bg-primary`, `text-on-primary` …), Beavent radii and type scale for `rounded-*`/`text-*`, `bv-type-*` role classes with Ge'ez metrics, `bv-numeric`, a global focus ring and `accent-color`.
- Mobile reads tokens through `useApp().colors` and the components in `apps/public-mobile/src/components/ui`; the template theme files are gone.

The original plan:

1. **`packages/ui/src/index.ts`:** replace colour values (§2.2), add the new colour tokens, add `layout` (§4.2), `elevation` (§6), `duration`/`easing` (§7.1), and `textRoles` (§3.2) with per-script sizes and line heights; add `fontSizePx(size, script)` that applies the Ge'ez minimum (A6). Extend `cssVariables()` to emit every token. Add the contrast test (§2.4).
2. **Mobile:** delete the colour and spacing parts of `src/constants/theme.ts` (`Colors`, `Spacing`, `Fonts`); keep `BottomTabInset` only if native tabs still need it and `MaxContentWidth` (→ `layout.maxContentWidth`). Replace `ThemedText`/`ThemedView` with `Text`/`Box`/`Screen` primitives ([COMPONENT_GUIDELINES.md §2](COMPONENT_GUIDELINES.md#2-foundations)). Build the React Navigation theme from tokens (§2.3). Native tab colours from tokens.
3. **`apps/public-mobile/app.json`:** splash `backgroundColor` → `background` light (`#FFFFFF`) with the brand mark; Android adaptive icon background → `primary` (`#0B6B53`) once the mark exists (Q1).
4. **Web and partner portal `globals.css`:** stop hand-copying values; emit `cssVariables("light")` / `cssVariables("dark")` from the layout (or a generated CSS file) and map them in Tailwind's `@theme inline` (`--color-surface: var(--bv-color-surface)` etc.) so utilities read `bg-surface text-text-muted border-border`.
5. **Partner portal:** buttons on `primary` use `onPrimary` (fixes A2).
6. **Admin console (later):** load the web fonts and map the zinc utilities to tokens; human labels for status codes.
