# Global UI implementation report

**Date:** 19 Nov 2026 · **Branch:** `stage-1/catalogue` (uncommitted working-tree changes; nothing staged or committed)
**Scope:** apply the Beavent design system ([`docs/design/beavent/`](.)) to every existing frontend, make it the
enforced default for future work, and record what is and isn't compliant. No marketplace features were built
(those follow the Services/Events specs in later tasks).

---

## 1. Summary

- **One source of truth for design values.** `packages/ui` now holds every token from DESIGN_TOKENS.md (emerald /
  Adey gold / warm neutrals, type roles with Ge'ez metrics, layout roles, sizes, elevation, motion). A test checks
  every allowed colour pair for WCAG contrast in both themes and that the generated web stylesheet is current.
- **All four frontends consume it.** The three Next.js apps import one generated `@beavent/ui/tokens.css`
  (variables, Tailwind theme, `bv-type-*` role classes, focus ring, `accent-color`); the mobile app's competing
  template theme is gone and its screens are built from new token-based primitives.
- **Shared formatting fixed at the source:** display money without forced `.00`, dual Ethiopian/international dates
  and times, 12-hour international clock (decided), durations.
- **Existing screens restyled and corrected** across mobile, partner portal, web marketplace and admin console;
  Expo starter branding and assets removed; preferences (language, calendar, time, theme) are app-wide and persisted.
- **Permanent instructions:** root `AGENTS.md` (mandatory UI standard) + `CLAUDE.md` import, per-app `AGENTS.md`
  pointers, README section, PR-template checkbox, rewritten AI agent instructions, and a reusable visual-QA suite.
- **Verified:** lint, typecheck, unit tests, production builds, the existing end-to-end tests, and visual QA of 66
  rendered screenshots (am/en × light/dark × phone/desktop) that I reviewed.

## 2. Applications and screens inspected

| App | Screens (route) | How inspected |
|---|---|---|
| Mobile (Expo; web build at 360 px) | Explore `/`, Bookings `/bookings`, Account `/account` (signed out and in), Business "My schedule" `/business` (signed out and with data), sign-in panel | Code + screenshots am/en × light/dark; e2e |
| Partner portal (`apps/business/`) | Landing `/[lang]`, sign-in, your businesses, onboarding (form not captured — see §13), menu, new service, service detail with staff prices, team, staff hours, rota, booking rules, 404 (code only) | Code + screenshots (en light; menu and rota also am dark; tablet width); e2e |
| Web marketplace (`apps/web/`) | Landing `/[lang]`, 404 (code only) | Code + screenshots am/en × light/dark × phone/desktop |
| Admin console | Sign-in, overview, verification queue, organizations, people, audit log, flags, markets (light and dark, with a local `READ_ONLY` staff test account); organization detail, case review, confirm panel (code only) | Code + screenshots |

## 3. Existing UI audit (state after this work)

Categories: **Compliant** · **Needs restyling** (works, visually off-system) · **Needs correction** (accessibility,
readability, theme or usability problem) · **Template/placeholder** · **Not yet implemented**.

| Screen | Before | After | Notes |
|---|---|---|---|
| Mobile Explore | Template/placeholder (localisation demo; "Explore" was Expo starter content) | **Compliant** (interim content) | Real marketplace Explore is Stage 8 (not implemented) |
| Mobile Bookings | Not implemented | **Compliant** (states only) | Signed-out, loading and empty states from C-4; data arrives with Stage 2.5 |
| Mobile Account | Not implemented | **Compliant** | Preferences inline as option groups; profile editing, addresses not yet built |
| Mobile sign-in (panel + modal) | Needs correction (hard-coded violet button, `#b42318` errors, "?" as error text) | **Compliant visually**; spec gaps | Single "phone or email" field instead of PhoneField + CodeField with resend timer, Google/Apple and the name step (C-6) |
| Mobile Business "My schedule" | Needs restyling (template theme; Ethiopian time *only* for Amharic; no error state) | **Compliant** | 24-hour partner clock + Ethiopian time underneath; skeleton, error with retry. Placement is interim (DD13, §12) |
| Mobile tab bar | Template (Home/Explore/Business, template icons) | **Compliant** | Explore · Bookings · Account, translated, token colours, SF/Material symbols |
| Mobile web tab bar | Template ("Expo Starter") | **Compliant** | |
| App icon / splash / adaptive icon | Template (Expo logo, `#208AEF`) | **Interim** | Plain emerald mark until the logo exists (Q1) |
| Portal landing | Needs correction (CTAs were non-clickable `<span>`s; white on dark-mode violet ≈ 2.8:1) | **Compliant** | |
| Portal sign-in, businesses, onboarding | Needs restyling | **Compliant** (sign-in, businesses reviewed; onboarding by code) | |
| Portal shell | Needs restyling (top link bar, no current-page state) | **Compliant** | AppShell sidebar (≥ 1024 px) / top bar, `aria-current` |
| Portal menu | Needs correction (`90′ · 1500 ETB`: untranslatable prime, ISO code, float maths) | **Compliant** | `30 min · Br 400` / `30 ደቂቃ · ብር 400` |
| Portal service editor, staff prices | Needs restyling | **Compliant visually**; spec gaps | Two name fields instead of LocalizedTextField tabs; no StepsEditor/PriceMatrix yet (P-02/P-03) |
| Portal staff hours | Needs correction (time inputs full-width and wrapping — `w-28` lost to `w-full`) | **Compliant** | Native date input is Gregorian only (§13) |
| Portal team, rota, booking rules | Needs restyling | **Compliant** | |
| Portal workspace errors | Needs correction (unhandled errors) | **Compliant** | New error boundary with retry |
| Web landing | Template/placeholder content; needs restyling | **Compliant** (placeholder content) | Marketplace pages not implemented |
| Web / portal 404 | Needs restyling | **Compliant** (code only) | |
| Admin console (all pages) | Needs restyling (zinc palette, Arial, raw enum badges like `AWAITING_INFO`) | **Compliant** | Noto Sans, tokens, readable status labels with the code as tooltip |
| Admin pages without permission | Needs correction (unhandled runtime error) | **Compliant** | New error boundary ("Your staff role does not allow this …") |
| All marketplace screens (S-, E-, C-1 full, C-2, C-3, C-7, C-8) and Business B-1…B-5 | Not implemented | Not implemented | Build to spec in later tasks |

## 4. Design tokens standardised

- **Values:** DESIGN_TOKENS.md §2 colours (incl. new `surfaceRaised`, `surfacePressed`, `textSubtle`, `textInverse`,
  `borderStrong`, `primaryPressed`, `primarySubtle`, `accent`, `onAccent`, subtle status fills, `onDanger`, `info`,
  `scrim`, `imageScrim`, `focus`); provisional violet replaced by emerald (fixes A2, A3).
- **Structure:** `textRoles` + `textStyle` + `fontSizePx` (Ge'ez minimum 14 px — fixes A6), `scriptOfText`,
  `layout`, `size`, `borderWidth`, `breakpoints`, `elevation`, `duration`, `easing`, `contrastPairs`,
  `contrastRatio`.
- **Web delivery:** `tokenStylesheet()` → `packages/ui/tokens.css` (generated; `npm run generate -w @beavent/ui`),
  imported by all three Next.js apps — replaces hand-copied variables (fixes A9). Tailwind `rounded-*` and `text-*`
  now follow the Beavent radii and type scale.
- **Mobile delivery:** `useApp().colors` + components; `constants/theme.ts`, `global.css`, `use-theme.ts`,
  `ThemedText`, `ThemedView` removed (fixes A1, A5, A8).
- **Contrast found by the new test:** `warning` text on `warningSubtle` is 4.22:1 — rule recorded: text on subtle
  fills is `text`, the status colour is for icons and fills (COMPONENT_GUIDELINES §4.5, §4.10).

## 5. Shared components improved

| Where | Components |
|---|---|
| `apps/public-mobile/src/components/ui` (new) | `Text`, `Icon`, `Button`, `TextField`, `Screen`, `ScreenTitle`, `Section`, `ListRow`, `OptionRow`, `Group`, `Divider`, `Chip`, `Banner`, `EmptyState`, `Skeleton`, `SkeletonRows`, `DualLine` |
| `apps/public-mobile/src/components` | `app-tabs` (native + web), `sign-in-panel` (rebuilt on `TextField`/`Button`, translated errors, offline message) |
| `apps/business/src/components` | `ui.tsx` (`inputBaseClass`, `inputClass`, `buttonClass`, `secondaryButtonClass`, `secondaryButtonLargeClass`, `listClass`, `rowLinkClass`, `Field`, `Section` without card border, `FormMessage`, `formatPrice`, exact `toBirr`), `WorkspaceNav` (new) |
| `apps/admin-console/src/components` | `ui.tsx` (`Badge` readable labels, token table/empty/search), `ConfirmPanel` |
| Error boundaries (new) | `apps/admin-console/src/app/(console)/error.tsx`, `apps/business/src/app/[lang]/o/[orgId]/error.tsx` |

Duplicated web components (`EthiopianNow`, `LanguageSwitch` in `apps/web/` and `apps/business/`) were restyled but not merged —
a shared web package is open question Q9 (A10 remains).

## 6. Formatting and localisation improvements

| Package | Added | Fixes |
|---|---|---|
| `@beavent/money` | `formatDisplay` (narrow symbol, `Br` for ETB in English, no `.00` unless needed, `{ exact }` for payment surfaces, engine fallback), `formatDisplayRange`, `toPlainAmount` | A12 money; portal float maths |
| `@beavent/calendar` | `defaultDisplayPreferences` (DD7), `formatClock` (12-hour in every language — decision Q7), `formatDualTime`, `formatDualDate`, `formatEthiopianDate({ year: false })` | A12 dual time/date |
| `@beavent/i18n` | `formatDuration`; strings for the mobile shell (en + am); template tab keys removed | Untranslated tab labels (A4) |
| Mobile | Preferences provider: language, calendar, time convention, appearance; persisted (keystore via the already-installed `expo-secure-store`, `localStorage` on web); applied app-wide | A7 |

New Amharic strings were written for this change and need native-speaker review (as all Amharic copy does, doc 07
step 0.11).

## 7. Existing issues fixed (audit items from DESIGN_PHILOSOPHY.md §6.3)

| # | Issue | Result |
|---|---|---|
| A1 | Two mobile token systems | Fixed — one system |
| A2 | Dark-mode white-on-violet buttons | Fixed — `onPrimary`/`text-on-primary` everywhere; contrast tested |
| A3 | Violet brand next to Fresha | Fixed — emerald |
| A4 | Expo branding, English tab labels, starter assets | Fixed — Expo template screens, components, assets and scripts removed; translated tabs; app icon/splash use an interim mark |
| A5 | 48 px headings, hard-coded link colour | Fixed — type roles; page titles `title1` 22 px |
| A6 | Small Amharic text below 14 px | Fixed — `Text` / `bv-type-*` raise Ge'ez to 14 px |
| A7 | Language not app-wide/persistent | Fixed — the other session made it app-wide; this change persists it with calendar, time and theme |
| A8 | `ThemedView` ignored props | Fixed — component removed |
| A9 | Hand-copied web tokens | Fixed — generated `tokens.css` |
| A10 | Duplicated web components | Open (Q9) |
| A11 | Admin console off-system | Fixed |
| A12 | Money and dual-time formatting | Fixed (helpers in packages) |
| A13 | Image sizes | Open (Q5; backend change) |
| A14 | Map dependency | Open (Q3) |

Also fixed while verifying: hydration failure of the mobile web build for non-English devices (device-dependent UI
now waits for `ready`); portal hours form layout; portal landing CTAs not clickable; doubled focus ring on portal
inputs; checkbox colour (`accent-color`); unhandled permission errors in admin and portal.

**Bundle:** Android Hermes bundle 4,060,065 → 4,043,268 bytes after the template removal; JavaScript stayed about
flat (removed template code was small; primitives and preferences were added). After the follow-up decisions:
**1,629 KB gzip** against the 4,000 KB budget (3,958 KB raw); bundled assets 2,050 KB, of which ≈ 1.1 MB is the
bundled Ge'ez font. The budget is now defined as gzip-compressed bytecode and checked in CI.

## 8. Permanent AI agent instructions

- **New `AGENTS.md`** (root): "MANDATORY BEAVENT UI/UX STANDARD" — read `docs/design/beavent/`, tokens only, DD1–DD14,
  typography/spacing, warm canvas · emerald ink · Adey gold, shared components, no generic AI UI, localisation and
  Ethiopian calendar, Ge'ez and RTL, accessibility and contrast, light/dark, visual QA, no unapproved dependencies,
  and safe behaviour alongside concurrent sessions.
- **New `CLAUDE.md`** (root): imports `AGENTS.md` so Claude Code sessions load it automatically.
- **Per-app `AGENTS.md`** (`apps/public-mobile/`, `apps/web/`, `apps/business/`, `apps/admin-console/`): a design-standard section appended
  after the auto-generated Next.js/Expo blocks (not overwritten).
- **`AI_AGENT_DESIGN_INSTRUCTIONS.md` v2:** mandatory pre-implementation checklist, real token/component/formatter
  APIs, typography, localisation, responsive and accessibility requirements, verification and visual-QA procedure,
  rules for adding tokens and components and documenting changes, pitfalls already hit, implementation status.

## 9. Developer documentation updated

- `README.md`: "Beavent UI/UX Design Standard" section pointing to `AGENTS.md`, `docs/design/beavent/` and the agent
  instructions, plus the token-generation and visual-QA commands.
- `.github/pull_request_template.md`: UI checkbox (tokens and shared components, light/dark × am/en, visual QA
  screenshots).
- `apps/public-mobile/README.md`: Expo template README replaced with a Beavent one.
- Design docs updated where implementation changed or sharpened decisions: DESIGN_TOKENS §2.2/§9 (implemented; text
  on subtle fills), COMPONENT_GUIDELINES §0 (implementation status), §4.5/§4.10 (text colour rule), §9 (portal
  density, no conflicting utilities), MARKETPLACE_NAVIGATION §3.1 (implemented routes; interim Business route),
  DESIGN_PHILOSOPHY §6.3 (audit status).

## 10. Tests and build results

| Check | Result |
|---|---|
| `npx prettier --check .` | Pass (generated `tokens.css` added to `.prettierignore`) |
| `npm run lint` | 4/4 tasks pass |
| `npm run typecheck` | 11/11 tasks pass (includes `tools/e2e` with the new visual spec) |
| `npm run test` | 6/6 tasks pass — `@beavent/ui` 8 tests (contrast in both themes, Ge'ez metrics, generated CSS), money 11, calendar 33, i18n 6 |
| `npm run build` | 14/14 tasks pass; built CSS of all three Next.js apps contains the token variables, dark block and role classes |
| Mobile `npx expo lint`, `npx tsc --noEmit` | Pass |
| Existing e2e (`tests/partner-portal.spec.ts`, `tests/mobile-schedule.spec.ts`) against API + apps | 2/2 pass after the restyle |
| Visual QA suite (`npm run visual -w @beavent/e2e`, new) | 6/6 pass, 50 screenshots; plus 16 admin screenshots with a local staff test account |

## 11. Visual QA results

Reviewed by opening the screenshots (not only by test status):

- **Confirmed good:** mobile Explore (am/en, light/dark) with Ethiopian-first dual date/time for Amharic; Account
  option groups in Ge'ez; Business schedule (en light, am dark) with 24-hour + Ethiopian time; preferences chosen in
  Account survive a reload; portal shell, menu (am dark), service editor, staff hours, rota (am dark), booking rules,
  team; landing pages at phone and desktop widths; admin sign-in, organizations; admin error state.
- **Found and fixed during QA:** mobile web hydration error (Amharic devices), portal hours-form layout, landing
  button size conflict, blue checkboxes, doubled input focus ring, Amharic landing heading wrapping to four lines on
  phones (now 28 px below 640 px), unhandled permission error in admin.
- **Environment note:** an unrelated project's Expo server holds port 8081 on this machine, which broke Expo's dev
  server-rendering here ("Requiring unknown module"); QA used `npx expo export` + `npx expo serve` (documented in
  the agent instructions §9).
- **Not visually verified:** native iOS/Android rendering (only the web build of the app was rendered — native tabs,
  SF/Material icons, splash and safe areas need a device or simulator check), 200 % font scale, 320 dp width, screen
  readers, portal onboarding form, 404 pages, admin organization detail / case review.

## 12. Decisions

Owner decisions of 19 Nov 2026 (recorded in DESIGN_PHILOSOPHY.md §8 and applied):

| # | Decision | Applied |
|---|---|---|
| Q1 | Placeholder logo | The plain emerald "b" mark stays as the app icon, splash, adaptive icon and favicons until a logo is designed |
| Q2 | English "Bookings" in the Amharic UI | `am.ts` `nav.bookings` = "Bookings" |
| Q3, Q8 | Map, brightness, haptics, camera approved | `expo-haptics` installed and used (selection feedback); `expo-maps` + MapLibre GL JS, `expo-brightness`, `expo-camera` chosen and pinned in the docs, installed by the stages that build maps, the ticket pass and check-in |
| Q4 | Bundle the Ge'ez font | Noto Sans Ethiopic 400/500/600 bundled on all platforms and used by `Text` and `TextField` |
| Q7 | 12-hour time | `formatClock` is 12-hour with AM/PM in every language; partner/staff tools stay 24-hour (doc 06) |
| Budget | Do the recommended things | Budget defined as gzip Hermes bytecode; `npm run bundle:check -w @beavent/public-mobile` added and run in CI |

Still open:

| # | Question | State |
|---|---|---|
| Q9 | Answered: two mobile apps and one web platform (see §15) | Done: web apps merged; `apps/business-mobile` scaffolded |
| DD13 | Business mode swaps the whole tab bar | Interim: "My schedule" is pushed from Account; becomes its own tab set with more Business screens |
| Q5, Q6, Q10–Q12, G6 | Image variants, all-in fee data, data saver, reviewer names, admin dark mode, add-to-calendar | Unchanged |

## 13. Remaining non-compliant or unverified screens

- **Spec gaps on compliant-looking screens:** mobile sign-in lacks PhoneField/CodeField/Google/Apple/name step (C-6);
  portal service editor lacks LocalizedTextField tabs, StepsEditor, PriceMatrix and preview (P-02, P-03); portal
  lists are rows, not yet DataTable with filters (P-01); portal date input is Gregorian only (doc 06 asks for both
  calendars in date pickers).
- **Placeholder content:** web marketplace landing; mobile Explore (interim until Stage 8).
- ~~Duplicated components~~ resolved by merging the web apps (§15).
- **Admin console:** English-only by design (internal); entity kinds still shown as codes (`BUSINESS`).
- **Unverified rendering:** see §11.
- **Everything in the Services and Events specs** — not implemented.

## 14. Recommended next steps

1. **Commit this work on its own** (separately from Stage 1 backend commits) and open a PR with the screenshots.
2. **Native check** on an Android low-end device and iOS simulator: tabs, icons, splash, safe areas, Ge'ez rendering
   (Kefa on iOS), 200 % font scale; record results here.
3. **Finish the shell primitives** the next features need: BottomSheet, PhoneField, CodeField (C-6 complete),
   StickyActionBar, StepProgress, Toast.
4. Q5 (image variants) before imagery-heavy screens.
5. **Partner portal to spec** (P-01 … P-11) — it is the active stage.
6. **Stage 2.5 consumer booking** (S-04 → S-10b, Bookings with data), then payments (C-7), events (E-), discovery.
7. Add each new screen to `tools/e2e/visual/screens.spec.ts`; consider running the visual suite in CI once a hosted
   stack exists.

## 15. Changes after v1

Record design-system changes here (date — what — why), per AI_AGENT_DESIGN_INSTRUCTIONS.md §10.

- 19 Nov 2026 — v1 of the implemented system (this report).
- 19 Nov 2026 — Restructure: apps moved under `apps/` and the backend under `services/`; `mobile` renamed `public-mobile`; `business-mobile` added; `web`, `business` and `admin-console` merged into **one web platform** `apps/web` (public `/[lang]`, business console `/[lang]/business`, admin console at the secret `ADMIN_PATH`). Code paths named in this report before this entry refer to the old layout: `apps/business/src/components` is now `apps/web/src/components/business`, `apps/admin-console/src/components` is `apps/web/src/components/ops`.
- 24 Nov 2026 — Web platform decisions: `cacheComponents` and `partialPrefetching` are **off** in the merged `apps/web` (only the old public `web` app had them, and nothing used `"use cache"`; the per-user consoles can't run under them without boundaries everywhere). Pages that read no cookies but need live data must call `await connection()` (e.g. the admin Markets page), or the build prerenders them and calls the API. Console pages show skeletons from `loading.tsx`. Backend invitation links now point at the business console (`PARTNER_PORTAL_URL=http://localhost:3000/business`); the invitation-accept page itself is not built yet. `mobile` was renamed `public-mobile`.
- 19 Nov 2026 — Owner decisions applied (§12): placeholder logo kept; English "Bookings" in Amharic; 12-hour
  international time in every language (`formatClock`); Noto Sans Ethiopic bundled and used by `Text`/`TextField`;
  `expo-haptics` added (`selectionFeedback` on option rows and chips); map/brightness/camera packages chosen;
  bundle budget defined as gzip and enforced by `npm run bundle:check -w @beavent/public-mobile` in CI.
