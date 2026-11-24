# Beavent mobile UI guidelines

**Status:** v1, 19 Nov 2026. **Applies to:** `apps/public-mobile/` (Expo SDK 58, React Native 0.88, Expo Router, React Compiler on, typed routes on). Most rules also apply to the mobile web layouts of `apps/web/`.
**Read with:** [DESIGN_TOKENS.md](DESIGN_TOKENS.md), [COMPONENT_GUIDELINES.md](COMPONENT_GUIDELINES.md), [SCREEN_DESIGN_STANDARDS.md](SCREEN_DESIGN_STANDARDS.md). Performance numbers come from `performanceBudget` in `packages/ui` and doc 06 §2.5.

---

## 1. Target devices and test matrix

| Profile | Device class | Why |
|---|---|---|
| **Baseline (must be perfect)** | Android 9+, 2–3 GB RAM, 360 × 640 dp, ~2× density, slow 3G, **Amharic UI** | The launch-market majority (doc 06 §2.5) |
| Small | 320 dp wide Android | Must not break (no horizontal overflow, buttons still full labels) |
| Large phone | 412–430 dp (Pixel, iPhone Pro Max) | Grids switch to 4 slot columns; rails show more peek |
| iPhone | Current iOS, notch/Dynamic Island, home indicator | Safe areas, native tabs (iOS 26 look), VoiceOver |
| Tablet / web in Expo | ≥ 600 dp | `maxContentWidth` 800 centring, 2-up grids |

Every screen is checked in: light + dark · `am` + `en` · system font scale 100 % and 200 % · online + offline · slow network (throttled) before it is considered done ([SCREEN_DESIGN_STANDARDS.md §7](SCREEN_DESIGN_STANDARDS.md#7-visual-qa-checklist)).

## 2. Layout

1. **Orientation:** portrait only on phones (`app.json` `orientation: "portrait"` — keep). Tablets may later allow landscape for Business mode calendar.
2. **Safe areas:** `react-native-safe-area-context` (installed). Content respects top/bottom insets; the sticky action bar adds the bottom inset to its padding (min 12). Full-bleed hero images extend under the status bar with the `overImage` header; status bar style switches to light content while the hero is visible and back to the theme default when it scrolls away.
3. **Gutters:** 16 dp on phones, 24 dp ≥ 600 dp. Rails and chip rows scroll edge to edge but start and end aligned to the gutter (content inset, not clipped padding).
4. **Vertical rhythm:** sections separated by 32 dp; title → content 12 dp; lines inside an item 4–8 dp. Do not add spacing inside components to compensate for missing section spacing.
5. **Max widths:** on wide screens centre content at `maxContentWidth` 800; text-heavy columns at 640.
6. **One scroll container per screen.** Nested vertical scrolls are forbidden; horizontal rails inside a vertical scroll are fine.
7. **Sticky regions:** at most one sticky region at the top (collapsed header + tabs/chips) and one at the bottom (action bar *or* tab bar, never both — DD3).
8. **Thumb zone:** the primary action is at the bottom (sticky action bar). Top-corner actions are secondary only (share, save, more, close).

## 3. Touch and gestures

| Rule | Detail |
|---|---|
| Targets | ≥ 48 × 48 dp; use `hitSlop` for visually smaller controls (header icons, steppers) |
| Spacing between targets | ≥ 8 dp between adjacent tappable elements |
| Feedback | Every tappable element shows pressed feedback within one frame ([COMPONENT_GUIDELINES.md §2.3](COMPONENT_GUIDELINES.md#23-pressable-feedback)) |
| Gestures | Only platform-standard gestures: scroll, swipe back (iOS), pull to refresh, swipe down to dismiss sheets, horizontal swipe in galleries/rails/ticket pager, pinch in the photo viewer. **Every gesture has a visible alternative** (buttons, menus) |
| No swipe-to-delete in consumer lists | Use explicit actions; swipe actions are allowed in Business mode Today rows with visible alternatives |
| Long press | Business mode only (calendar move/block), always with a visible alternative |
| Double-submit | Buttons that create things (book, pay, post review) disable while loading and send an `Idempotency-Key` (the API client supports it), so retries are safe |

## 4. Forms and keyboard

1. **Labels above fields, always visible.** No floating labels, no placeholder-only fields.
2. **Correct keyboards:** phone → `phone-pad`; code → `number-pad` + `oneTimeCode`; email → `email-address`, `autoCapitalize="none"`; names → `words` capitalisation for Latin script (no effect on Ge'ez); amounts → `decimal-pad`, parsed with `@beavent/money` `parse()`.
3. **Keyboard avoidance:** the focused field and the form's submit action stay visible above the keyboard (`KeyboardAvoidingView` / `automaticallyAdjustKeyboardInsets` on iOS scroll views). The sticky action bar hides while the keyboard is open in forms; `returnKeyType` "next"/"done" drives progression.
4. **Validation timing:** on blur and on submit, not on every keystroke; clear an error as soon as the input becomes valid.
5. **Required vs optional:** mark the *optional* fields ("optional" in `caption`) when most are required, and the required ones when most are optional. Never asterisks.
6. **Prefill** from the profile (name, phone) and the last used choice (payment method, address); never prefill consent checkboxes.
7. **Ge'ez input:** users type with phonetic Ge'ez keyboards; never restrict name fields to Latin; search accepts mixed scripts.
8. **Long forms** (consultation forms, attendee questions) are split into sections with headings; each section fits about one screen.
9. **Date input:** never the platform date picker for bookings (it can't show Ethiopian dates or availability); use DateStrip / the range calendar component. Birthdays (if ever asked) may use a plain three-field input in the preferred calendar.

## 5. Lists and rendering performance

Budgets (`performanceBudget`): JS bundle ≤ 4,000 KB gzip-compressed Hermes bytecode (1,629 KB on 19 Nov 2026; checked in CI by `npm run bundle:check -w @beavent/public-mobile`, which also prints raw size and assets), cold start ≤ 3 s on the baseline device, list images 320 px, virtualise lists over 50 rows.

1. **Virtualise** every list that can exceed 50 rows (`FlatList`/`SectionList` — no new list library). Provide stable `keyExtractor` from ids; use `getItemLayout` for fixed-height rows (slot grids, date strips, ticket rows).
2. **Rails** are horizontal `FlatList`s with `initialNumToRender` ≈ 3 and `windowSize` small.
3. **Avoid expensive styles** on list items: no shadows on rows/cards (also a design rule), no blur views in lists, no per-item animations on mount.
4. **Images in lists** decode at display size (`expo-image` does this) and use the `w320` variant (§6).
5. **React Compiler** is enabled — don't hand-roll `useMemo`/`useCallback` unless profiling shows a need; keep components pure.
6. **Bundle hygiene:** delete template code and assets (A4); Business mode is a separate route group loaded only for members (doc 03 §1.1); no moment/lodash-style libraries (dates come from `@beavent/calendar`).
7. **Measure** with a release build on a low-end Android device (or emulator with 2 GB RAM, CPU throttled). A screen's first meaningful paint ≤ 1 s after navigation on cached data.

## 6. Images

Variants available today: `w320`, `w960` (files pipeline, step 0.9). Proposed `w640` (Q5).

| Where | Variant (today) | With `w640` (Q5) | Data saver on |
|---|---|---|---|
| List thumbnails (104 dp, 128 × 64 dp), avatars | `w320` | `w320` | `w320` |
| Rail cards (≈ 268 dp wide) | `w320` on ≤ 2× density; `w960` on 3× | `w640` | `w320`, or text-only rails on cellular |
| Detail hero, gallery viewer | `w960` | `w960` | `w320` until tapped ("Tap to load full image") |
| Map pager cards | `w320` | `w320` | `w320` |

Rules:
1. `expo-image` with memory+disk cache; `transition` = `duration.base`; placeholder `surface` (+ initial/label) — no broken-image icon.
2. **Prefetch** only the next gallery image and the hero of a card the user has started pressing. Never prefetch whole rails.
3. **No autoplay video** anywhere in the consumer app (doc 06 §2.5); event videos (if ever) are tap-to-play with a size shown ("Video · 8 MB").
4. **Aspect ratios are fixed** per image type (3:2 venues, 2:1 events, 1:1 avatars/logos/work thumbnails); `contentFit="cover"`; layout never jumps when an image loads.
5. **No text over photos** except the photo counter and controls on `imageScrim` circles.
6. Uploads (partner portal, avatar) show processing state until the file is `READY` (scan + re-encode).

## 7. Network, loading and offline

1. **Data layer:** the generated client with React Query hooks (`@beavent/api-client`, `openapi-react-query`). Show cached data immediately and refresh in the background (stale-while-revalidate); a `caption` "Updated 2 h ago" appears only when data is older than 1 h and the refresh failed.
2. **Loading states:** skeletons for content (≥ 300 ms); nothing for < 300 ms; button spinners for actions. No full-screen blocking spinners except the payment confirmation check.
3. **Errors:** map API problem codes to specific copy (`SLOT_TAKEN`, `SOLD_OUT`, `PAYMENT_PENDING`, `RATE_LIMITED`, `STEP_UP_REQUIRED`, `FORBIDDEN`); network failures say "Check your connection" and keep the user's input.
4. **Retries:** automatic retry with backoff for reads (max 3); writes retry only with the same idempotency key and only on user action.
5. **Offline-first data** (doc 03 §1.1, doc 06 §2.5): issued tickets (encrypted, with QR secret and serial), today's and upcoming appointments, the Bookings list, the user's profile and preferences, recently viewed profiles (read-only). Everything else shows the OfflineBanner and disables actions with "Needs a connection" — never hidden.
6. **Optimistic UI** only for reversible, non-financial actions (save/unsave, follow, notification toggles). Never for bookings, payments, cancellations, transfers or refunds.
7. **Long operations** (payment approval) survive backgrounding and app restarts: state lives on the server, the screen re-checks on focus ([C-7](MARKETPLACE_NAVIGATION.md#57-c-7-payment-and-payment-waiting)).
8. **Low bandwidth:** list endpoints return only what rows need; detail payloads load the above-the-fold part first (header + services/tickets) when the API supports it.

## 8. Accessibility

Target: WCAG 2.2 AA equivalents on mobile (contrast pairs in [DESIGN_TOKENS.md §2.4](DESIGN_TOKENS.md#24-contrast-pairs-wcag-22-computed-from-the-hex-values-above)), platform screen readers, OWASP MASVS-compatible privacy.

1. **Roles and labels:** every interactive element has `accessibilityRole`; icon-only buttons have a translated `accessibilityLabel`; state via `accessibilityState` (selected, disabled, checked, expanded). Labels are in the UI language from `@beavent/i18n`.
2. **Meaningful labels for composite rows:** a VenueResultRow reads "Abeba Beauty Studio, rated 4.8 from 212 reviews, Bole, 1.2 kilometres" — one stop, not ten. Use `accessible` grouping on cards and rows.
3. **Dates and times** read in full with both conventions (DateTimeText provides the label).
4. **Headings:** section titles use `accessibilityRole="header"` so screen-reader users can jump between sections.
5. **Focus order** follows visual order; sheets and dialogs trap focus and return it to the trigger on close; after navigation, focus starts at the screen title.
6. **Announcements:** errors after submit, slot taken, sold out, payment result, hold timer at 2:00 / 1:00 / 0:30 — via `AccessibilityInfo.announceForAccessibility`. No other live announcements.
7. **Font scaling** to 200 %: text wraps; rows grow; slot chips grow to two lines; nothing truncates prices, times or statuses. Test the booking flow end-to-end at 200 %.
8. **Colour is never the only signal** (status labels have text; selected chips have borders; scanner results have icon + word).
9. **Reduce Motion** respected ([tokens §7.1](DESIGN_TOKENS.md#71-motion)); **Reduce Transparency** removes blur if any is ever used.
10. **Amharic speech:** whether VoiceOver/TalkBack can speak Amharic depends on the device's text-to-speech engines; labels must exist in Amharic regardless, and the team should verify on the target devices which speech output users actually get.
11. **Touch target and spacing** rules (§3) are accessibility rules too.

## 9. Localisation

1. **All strings from `@beavent/i18n`** with ICU placeholders and plurals; no concatenation ("Book " + name), no hard-coded English (today's tab labels violate this — A4).
2. **Language is app state** (preferences provider, [nav §7](MARKETPLACE_NAVIGATION.md#7-account-and-preferences)); switching re-renders without restart.
3. **Ge'ez metrics** applied by `Text` automatically (line height 1.7 body / 1.5 headings, minimum 14 px). Never set fixed heights on text containers.
4. **String length:** design for English being up to 30 % longer than the Amharic in labels, and Amharic lines being taller; test both. Buttons never truncate: they wrap (to 2 lines max) or the copy gets shorter.
5. **No case-based styling** (no `textTransform: "uppercase"`), no italics for emphasis (DD8).
6. **Numbers:** Western Arabic digits everywhere; money via `@beavent/money`; dates/times via `@beavent/calendar` — never `toLocaleString`.
7. **Names:** given name first; "father's name" not "surname"; sort people by given name; don't require a second name (doc 06 §2.2).
8. **Content language fallback:** user's language → organization's default → market default → English; show a "Translated" `caption` with "Show original" when the displayed text isn't the source.
9. **RTL readiness** (Arabic markets later, doc 06 §5.2): use `start`/`end` style properties (`paddingStart`, `marginEnd`), never `left`/`right` for layout; direction-dependent icons (back chevron, disclosure chevron, steppers' order) mirror automatically through `I18nManager`.

## 10. Platform conventions

| Area | iOS | Android |
|---|---|---|
| Tabs | Native tabs (system appearance) | Native tabs (Material) |
| Back | Swipe from edge + header chevron | System back + header arrow; predictive back disabled for now (`app.json`) |
| Action menus | Native action sheet | Bottom sheet list |
| Switches | `Switch` (iOS style) | `Switch` (Material) |
| Share | System share sheet | System share sheet |
| Haptics | `expo-haptics` (approved, installed): `selectionFeedback()` from `src/lib/haptics.ts` — a light tick when a choice changes (option rows, chips; later slot selection); success on booking confirmed when that screen exists. Never on scroll or every press | Same |
| Permissions | Ask in context with a one-line explanation sheet first: **push** after the first booking/ticket ("Get reminders and ticket updates"); **location** only when the user taps "Use my location"; **camera** only in Business mode check-in | Same; on Android 13+ push is a runtime permission — same timing |
| Sign-in buttons | Apple + Google + phone (Apple required when Google is offered) | Google + phone |

## 11. Notifications as part of the UI

Push, SMS and Telegram messages are screens the user reads first. Rules: title states the fact ("Booking confirmed", "Your tickets for Addis Jazz Night"); body includes the dual time for anything time-related ("Sat, Tahsas 6 · ጠዋት 3:00 (9:00 AM)"); every message deep-links to the relevant screen ([nav §3.3](MARKETPLACE_NAVIGATION.md#33-deep-links-and-universal-links)); no marketing push without consent; quiet hours respected (`comms.preferences`).

## 12. Dark mode

Follow the system by default, with an in-app override. Check every screen in dark: photos untinted; QR panels stay white; status colours use their dark values; no pure black (`#000`) backgrounds — `background` is `#121211`; elevation by surface colour, not shadows.

## 13. Privacy in the UI

- Mask phone numbers where the full number isn't needed ("091 ••• 4567" in confirmations shared with others).
- Never show another customer's details in consumer mode (waitlists, reviews show only Q11-format names).
- Sensitive actions (delete account, change phone, later: payout details in Business mode) use the step-up code sheet.
- Tickets in the offline store are encrypted; the QR screen doesn't block screenshots (people forward tickets), but transfers are the supported way to share — the pass shows "To give this ticket to someone, use Transfer" `caption` under the QR.
