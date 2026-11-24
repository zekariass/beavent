# Instructions for AI agents implementing Beavent screens

**Status:** v2, 19 Nov 2026 (v1 described the target API; v2 describes what is implemented). **Audience:** AI coding
agents and humans building or changing any Beavent UI in `apps/public-mobile/`, `apps/business-mobile/`, `apps/web/` (public site, business console, admin console) or a future
frontend. **Authority:** these instructions and the documents they point to are the design decisions. The short,
always-loaded version is the root [`AGENTS.md`](../../../AGENTS.md); this is the detailed reference.

**Do not invent design decisions.** If something isn't specified, follow §12.

---

## 1. Read before you start

1. [`DESIGN_PHILOSOPHY.md`](DESIGN_PHILOSOPHY.md) — principles P1–P8, decisions **DD1–DD14**, audit, open questions.
2. [`DESIGN_TOKENS.md`](DESIGN_TOKENS.md) — the only colours, sizes, spacing, radii, elevation and formats.
3. [`COMPONENT_GUIDELINES.md`](COMPONENT_GUIDELINES.md) — components and their rules (§0 lists what is implemented).
4. [`SCREEN_DESIGN_STANDARDS.md`](SCREEN_DESIGN_STANDARDS.md) — templates, composition, copy, states, QA checklist.
5. The spec for your screen: [`MARKETPLACE_NAVIGATION.md`](MARKETPLACE_NAVIGATION.md) (C-screens, routes),
   [`SERVICES_MARKETPLACE_DESIGN.md`](SERVICES_MARKETPLACE_DESIGN.md) (S-, P-, B-screens),
   [`EVENTS_MARKETPLACE_DESIGN.md`](EVENTS_MARKETPLACE_DESIGN.md) (E-screens).
6. [`MOBILE_UI_GUIDELINES.md`](MOBILE_UI_GUIDELINES.md) for anything on a phone.
7. The app's own `AGENTS.md` (Expo SDK 58 / Next.js 16 rules — fetch versioned docs, don't trust memory) and
   [doc 06](../../06-markets-and-localization.md) §2 and §5 for localisation.
8. [`GLOBAL_UI_IMPLEMENTATION_REPORT.md`](GLOBAL_UI_IMPLEMENTATION_REPORT.md) — what is already compliant and what
   is not.

[`DESIGN_REFERENCE_CATALOG.md`](DESIGN_REFERENCE_CATALOG.md) explains *why*; never copy a reference product.

## 2. Mandatory pre-implementation checklist

Do these, in order, before writing UI code. Put the answers in your plan or PR description.

1. **Target:** which app and platform (mobile consumer, mobile Business mode, web marketplace, partner portal,
   admin console)? Which screen ID (C-, S-, E-, B-, P-) and template (SCREEN_DESIGN_STANDARDS §2)?
2. **Docs read:** the spec for that screen plus §1 above.
3. **Tokens:** list the colour, spacing, type and size tokens the screen needs. All must exist in `@beavent/ui`.
4. **Components:** list the existing components you will reuse (§4). Anything missing → §10 first.
5. **Similar screens:** open the closest implemented screen (e.g. `apps/public-mobile/src/app/(tabs)/account.tsx` for settings,
   `apps/web/src/app/(site)/[lang]/business/o/[orgId]/services/page.tsx` for a console list) and match its structure.
6. **Navigation:** route, push vs modal vs sheet, back behaviour — from MARKETPLACE_NAVIGATION.md §3–§4.
7. **Localisation:** strings to add to `en.ts` **and** `am.ts`; which dates/times/money appear and which formatter
   and time zone they use; Ge'ez and long-English text fit.
8. **Themes:** anything that must stay fixed across themes (QR panels, photos)?
9. **States:** loading, empty, error (with retry), offline, signed out, success — and the screen-specific ones.
10. **Identity check:** does the plan use warm neutrals, emerald for actions/selection, gold at most once, rows not
    cards, one primary button? If not, change the plan, not the system.

## 3. Hard rules

### You MUST

1. Implement the spec's layout, content, actions, navigation and **all** listed states.
2. Use **only tokens** from `@beavent/ui` (via the components on mobile, via `tokens.css` utilities on web).
3. Use **existing components**; add new ones to the design system first (§10).
4. Take every visible string from `@beavent/i18n` (Amharic and English; `om` may lag and falls back). ICU plurals.
5. Format money with `@beavent/money`, dates and times with `@beavent/calendar`, in the location's/occurrence's zone.
6. Bind data by API field names from the OpenAPI contract (`@beavent/api-client`).
7. Make it accessible (§8) and check light + dark, Amharic + English, 360 dp + 320 dp (+ desktop for web).
8. Keep one primary action per screen, in the sticky bottom bar on mobile flows and detail screens.
9. Run the checks and the visual QA in §9 and attach the screenshots.

### You MUST NOT

1. Hard-code hex colours, `fontSize`, `fontWeight`, `lineHeight`, `color` or off-scale spacing in screens (the
   mobile `Text` component does not even accept them).
2. Add dependencies (npm or Expo modules) that aren't approved. Approved (DESIGN_PHILOSOPHY.md §8): `expo-haptics`
   and Noto Sans Ethiopic (installed), `expo-maps` + MapLibre GL JS, `expo-brightness`, `expo-camera` (install with
   `npx expo install` when their screen is built). Ask for anything else.
3. Use gradients, decorative shadows, cards around sections, card-in-card, badge rows, icon-per-fact rows,
   uppercase or letter-spaced labels, emoji in UI copy, stock photos, illustrations, floating action buttons.
4. Concatenate translated strings, call `toLocaleString`/`Intl`/`Date` formatting in screens, or use float money.
5. Show success before the server confirms, or use optimistic UI for money or inventory.
6. Change the navigation structure (tabs, route groups, modal vs push) outside MARKETPLACE_NAVIGATION.md.
7. Copy Fresha, Eventbrite, Airbnb, Vinted or Revolut branding, assets, layouts or wording.
8. Hide network-dependent actions offline — disable them with an explanation.

## 4. What exists: tokens, components, formatters

### 4.1 Tokens (`packages/ui/src/index.ts`)

| Export | What |
|---|---|
| `color.light` / `color.dark` | Semantic colours (DESIGN_TOKENS.md §2.2), `Palette` type |
| `contrastPairs`, `contrastRatio` | Allowed foreground/background pairs, checked by the package test |
| `space`, `layout`, `size`, `radius`, `borderWidth`, `breakpoints` | Spacing scale, layout roles (gutter 16, sectionGap 32, titleGap 12 …), component sizes, radii |
| `textRoles`, `textStyle(role, script)`, `fontSizePx`, `lineHeightPx`, `scriptOfText` | Type roles with Ge'ez metrics |
| `elevation`, `duration`, `easing` | Floating elements and motion |
| `tokenStylesheet()` → `tokens.css` | Web stylesheet (generated; `npm run generate -w @beavent/ui`) |

### 4.2 React Native components (`apps/public-mobile/src/components/ui`)

| Component | Use |
|---|---|
| `Text` (`role`, `tone`, `numeric`, `lines`) | All text. Applies Ge'ez size/line height automatically; refuses font/colour styles |
| `Button` (`variant`: primary · secondary · tertiary · destructive · destructiveTertiary; `size`: lg · md · sm; `block`, `loading`) | Actions |
| `TextField` (`label`, `helper`, `error`) | Inputs (label above, 52 dp, 2 dp focus/error outline) |
| `Screen` (`inTabs`, `edges`), `ScreenTitle`, `Section` (`title`, `action`) | Screen frame, title1 heading, title2 sections with 32 dp rhythm |
| `ListRow`, `OptionRow`, `Group`, `Divider` | Rows; radio options; inset-grouped settings lists |
| `Chip` | Choice chips |
| `Banner` (`tone`), `EmptyState`, `Skeleton`, `SkeletonRows` | Feedback and states |
| `DualLine` | A date/time in the preferred convention with the other one muted |
| `Icon` | Documented symbols only (info, success, warning, error, chevron, check) |

Ge'ez text uses the bundled Noto Sans Ethiopic automatically (`src/constants/fonts.ts`; the root layout loads it
before the first render). Choices give a haptic tick through `selectionFeedback()` (`src/lib/haptics.ts`).

App state: `useApp()` from `apps/public-mobile/src/lib/app-context.tsx` → `t`, `language`, `display` (calendar/time
preferences for formatters), `colors`, `scheme`, `signedIn`, `api`, setters for preferences, `ready`.

### 4.3 Web utilities (`@beavent/ui/tokens.css`)

Tailwind colours: `background`, `surface`, `surface-raised`, `surface-pressed`, `fg`, `fg-muted`, `fg-subtle`,
`fg-inverse`, `border`, `border-strong`, `primary`, `primary-pressed`, `primary-subtle`, `on-primary`, `accent`,
`on-accent`, `success(-subtle)`, `warning(-subtle)`, `danger(-subtle)`, `on-danger`, `info(-subtle)`, `focus` — use as
`bg-*`, `text-*`, `border-*`. Radii: `rounded-sm` 4, `rounded-md` 8, `rounded-lg` 12, `rounded-xl` 16. Type roles:
`bv-type-display`, `bv-type-title1`, `bv-type-title2`, `bv-type-body`, `bv-type-body-strong`, `bv-type-label`,
`bv-type-label-strong`, `bv-type-caption`, `bv-type-button-large`; `bv-numeric` for tabular figures. Spacing: only
Tailwind steps that exist in the Beavent scale (`1 2 3 4 5 6 8 10 12 16`).

Business-console primitives (`apps/web/src/components/business/ui.tsx`): `inputClass` / `inputBaseClass`, `buttonClass`,
`secondaryButtonClass`, `secondaryButtonLargeClass`, `listClass`, `rowLinkClass`, `Field`, `Section`,
`FormMessage`, `formatPrice`, `toBirr`; `WorkspaceNav` for the sidebar. Admin console: `apps/web/src/components/ops/ui.tsx`
(`PageTitle`, `Badge`, `Table`, `Empty`, `SearchForm`).

### 4.4 Formatters

| Need | Use |
|---|---|
| Price on screens | `formatDisplay(money, locale)` → `Br 1,800`; `{ exact: true }` on payment surfaces; `formatDisplayRange` |
| Amount in an input | `toPlainAmount(money)` → `1500` |
| Date (both calendars) | `formatDualDate(instantOrDate, display, { weekday, year })` → `{ primary, secondary }` |
| Time (both conventions) | `formatDualTime(instant, display)` (international part is 12-hour `9:00 AM`, `formatClock`); partner and staff tools: `formatStandardTime` (24-hour) |
| Defaults per language | `defaultDisplayPreferences(language)` (DD7) |
| Duration | `formatDuration(minutes, t)` → `1 h 30 min` / `1 ሰዓት 30 ደቂቃ` |
| Localized API text | `localized(text, language, orgDefault)` |

## 5. Typography requirements

- Roles only (DESIGN_TOKENS.md §3.2). The apps top out at `display` 28 px, once per screen; screen titles `title1`
  22, section titles `title2` 18. `text-3xl` (34) only for web marketing headings.
- Weights 400/500/600. No uppercase, letter-spacing (except ticket serials) or italics as hierarchy.
- Ge'ez: line height 1.7 body / 1.5 headings, minimum 14 px — automatic via `Text` and `bv-type-*` (and for user
  text in Ge'ez inside an English UI, via `scriptOfText`).
- Tabular figures (`numeric` / `bv-numeric`) for prices, times, counts.

## 6. Localisation requirements

- Strings: `en.ts` and `am.ts` (the i18n test fails if Amharic misses a key); mark new Amharic for native review.
- Dates and times: dual display per DD7; the zone is the location's or occurrence's, never the device's.
- Money: `formatDisplay` (no hard-coded symbols, no forced `.00`).
- Addresses landmark-first; names given-name-first; "father's name", never "surname".
- Layout must survive longer English labels and taller Ge'ez lines: no fixed heights on text, buttons wrap.
- RTL readiness: `start`/`end` (`marginStart`, `paddingEnd`; Tailwind `ms-*`, `pe-*`), never `left`/`right`.
- Mixed scripts: the components pick Ge'ez metrics for Ge'ez content even in an English UI.

## 7. Responsive requirements

- Mobile: 360 × 640 dp baseline, must not break at 320; safe areas; content max 800 dp on tablets; sticky action
  bar or tab bar at the bottom, never both.
- Web marketplace: `compact` < 600, `medium` 600–1023, `expanded` ≥ 1024; reading columns ≤ 640 px; grids ≤ 1200.
- Partner portal: desktop-first sidebar (≥ 1024) collapsing to a top bar with scrolling section links; forms one
  column ≤ 640 px with a preview column only ≥ 1280; tables over cards.
- Admin console: information density first; same tokens.

## 8. Accessibility requirements

Contrast pairs from `contrastPairs` only (banner and status-label text is `text`/`fg`, the status colour is for the
icon or fill); roles, translated labels and states; composite rows read as one sentence; headings marked; 48 dp
targets; visible focus (`:focus-visible` ring is global on web); colour never the only signal; 200 % font scale;
Reduce Motion respected. Details: MOBILE_UI_GUIDELINES.md §8.

## 9. Verification and visual QA (required before "done")

```bash
npm run lint && npm run typecheck && npm run test && npm run build     # repo root
(cd mobile && npx expo lint && npx tsc --noEmit)                        # mobile, per apps/public-mobile/AGENTS.md
npm run bundle:check -w @beavent/public-mobile                                 # mobile bundle budget (also in CI)
```

**Visual QA** — render and look:

1. Start the stack: `docker compose up -d`; API `cd backend && ./gradlew bootRun > /tmp/api.log`; apps
   `npm run dev -w @beavent/web` (one web platform on :3000; the admin console is at `/ops`, from `ADMIN_PATH`); mobile web: `cd apps/public-mobile && npx expo start --web --port 19006`.
   If another project's Metro already holds port 8081, Expo's dev server-rendering can load the wrong bundle
   ("Requiring unknown module …"); use `npx expo export --platform web && npx expo serve --port 19006` instead.
2. `API_LOG=/tmp/api.log npm run visual -w @beavent/e2e` — screenshots of every app in am/en × light/dark at phone
   and desktop widths, plus signed-in partner-portal and Business-mode screens, in
   `tools/e2e/test-results/visual/`. Add your new screen to `tools/e2e/visual/screens.spec.ts`.
3. **Open the screenshots** and check: spacing rhythm, alignment to the gutter, type roles, contrast in dark mode,
   Ge'ez clipping, long English labels, nothing truncated that must not be, one primary action, no error overlays
   (identical file sizes across different screens usually mean an error page).
4. Fix and repeat. Then run the SCREEN_DESIGN_STANDARDS.md §7 checklist and paste it into the PR with screenshots.

## 10. Changing the design system

**Adding or changing a token**

1. There must be a reusable need (two or more screens, or a documented component).
2. Edit `packages/ui/src/index.ts` (names stable; light and dark values together; new foreground/background pairs
   added to `contrastPairs`).
3. `npm run generate -w @beavent/ui` and `npm test -w @beavent/ui` (contrast + generated-CSS checks must pass).
4. Update DESIGN_TOKENS.md (table, usage rule) in the same PR.

**Adding a component or variant**

1. Write its spec in COMPONENT_GUIDELINES.md first (purpose, anatomy, variants, states, a11y, do/don't).
2. Implement it in `apps/public-mobile/src/components/ui/` (or `marketplace/`), export it from `index.ts`; web equivalents in the
   app's `src/components/` with the same name and rules.
3. Use it in at least one screen; add that screen to the visual QA spec.

**Documenting a design-system change**

- Update the affected doc(s) in this folder in the same PR, and add a line to GLOBAL_UI_IMPLEMENTATION_REPORT.md §15
  ("Changes after v1") with the date, what changed and why.
- A change to DD1–DD14 needs the owner's agreement; record it in DESIGN_PHILOSOPHY.md §7 rather than silently
  diverging.

## 11. Pitfalls already hit (don't repeat them)

| Pitfall | Rule |
|---|---|
| Appending a conflicting utility to a shared class string (`${inputClass} w-28` with `w-full` inside; `text-base` on a `text-sm` class) — in Tailwind v4 the stylesheet order decides, not the attribute order | Never override a property a shared class already sets; use a base class without it (`inputBaseClass`) or a named variant (`secondaryButtonLargeClass`) |
| React Native's own `role` prop collides with `Text`'s `role` | `Text` omits RN's ARIA `role`; use `accessibilityRole` |
| Expo web is server-rendered; language/theme/clock differ between server and browser → hydration error | Nothing device-dependent renders before `useApp().ready` (the root layout waits) |
| `setState` directly in an effect (React Compiler lint) | Reset loading state in the event that triggers the reload |
| Status colour as small text on its subtle fill (warning on warningSubtle = 4.2:1) | Text on subtle fills is `text`/`fg`; status colour only for icons and fills |
| Native checkboxes in browser blue | `accent-color` is set globally in `tokens.css` |

## 12. When the docs don't answer

1. Search this folder and `docs/0*.md`.
2. Use the closest documented pattern (same template, same component) and **record the gap** — a row in the
   spec's "Gaps" table or DESIGN_PHILOSOPHY.md §8, and "Design decisions needed" in the PR.
3. Never introduce a new visual pattern, colour, variant, navigation behaviour or dependency to fill a gap. If nothing
   fits, stop and ask.
4. Code and the API contract are the truth for **data**; these docs are the truth for **design**. Note mismatches.

## 13. Implementation order

| # | Work | Status (19 Nov 2026) |
|---|---|---|
| 1 | Design foundation: tokens, generated web CSS, contrast test, template removal, tab labels, splash/icon colours | **Done** (interim brand mark; logo is Q1) |
| 2 | Formatting helpers: display money, dual date/time, 12-hour clock (decided Q7), durations | **Done** |
| 3 | App shell and primitives: preferences (persisted), token theme, tabs, core components, C-6 sign-in, C-5 account | **Done** for the components listed in §4.2; BottomSheet, PhoneField, CodeField, StickyActionBar, StepProgress still to build |
| 4 | Partner portal catalogue P-01 … P-11 to spec (sidebar, editors, price matrix …) | Partly: existing screens restyled; spec features pending |
| 5 | Stage 2.5 consumer booking: S-04 → S-10b, C-4 with data, S-11 … S-15 | Not started |
| 6 | Stage 3 payments: PaymentMethodList, PaymentWaiting, HoldTimer, C-7 | Not started |
| 7 | Stage 6.8 events consumer UI: E-05 → E-19, TicketPass offline | Not started |
| 8 | Stage 8 discovery: C-1 full, C-2, S-01 … S-03, E-01 … E-04, C-3 Saved, reviews, C-8 | Not started |
| 9 | Business mode B-1 … B-5 with the swapped tab bar (DD13) | My schedule only (as a pushed screen, interim) |

## 14. Definition of done (UI work)

- [ ] §2 checklist answered; spec implemented with every state; deviations listed in the PR.
- [ ] SCREEN_DESIGN_STANDARDS.md §7 checklist pasted and ticked.
- [ ] Screenshots: light/dark × Amharic/English for each new screen (+ 320 dp and 200 % font scale for flows).
- [ ] Checks in §9 green; visual QA reviewed.
- [ ] i18n keys in `en.ts` and `am.ts`; Amharic marked for native review.
- [ ] No new dependencies, or the approving decision linked.
- [ ] Design docs updated if anything in the system changed (§10).

## 15. Quick reference

| Instead of | Use |
|---|---|
| `<Text style={{ fontSize: 24, fontWeight: "700", color: "#5b3fd9" }}>` | `<Text role="title2">` |
| `ThemedText`, `ThemedView`, `constants/theme.ts`, `useTheme` | Removed — `Text`, `Screen`, `useApp().colors` |
| `Spacing.three` (old template, 16) | `space[4]` / `layout.gutter` (16) |
| Card with border + shadow around a list | `Section` + `ListRow`s with dividers (web: `listClass`) |
| Badges "Women only" "At home" | A `·`-separated sentence |
| Spinner in the middle of a screen | `Skeleton` / `SkeletonRows` |
| `Alert.alert` for errors | Field error, `Banner`, or `EmptyState` with retry |
| "OK" / "Submit" | "Confirm booking" / "Send code" |
| `new Date().toLocaleString()` | `formatDualDate` / `formatDualTime` + `DualLine` |
| `` `${minor / 100} ETB` `` | `formatDisplay(money(minor, currency), language)` |
| `text-2xl font-semibold` page title | `bv-type-title1` |
| `opacity-70` for secondary text | `text-fg-muted` |
| `text-white` on a primary fill | `text-on-primary` |
