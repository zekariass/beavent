# Beavent — instructions for coding agents

Beavent is one platform for booking services (Fresha-like) and selling events (Eventbrite-like), Ethiopia first.
Start with [`README.md`](README.md) (layout, commands, conventions) and [`docs/README.md`](docs/README.md) (design
and plan). Each app may add its own `AGENTS.md` (e.g. [`apps/public-mobile/AGENTS.md`](apps/public-mobile/AGENTS.md) for Expo SDK 58 rules);
read it before changing that app.

## MANDATORY BEAVENT UI/UX STANDARD

This applies to **every** Beavent UI — `apps/public-mobile/` (consumer app), `apps/business-mobile/` (business app), `apps/web/` (the one web platform:
public site, business and organizer console, admin console), and any future frontend — whenever you create, modify, refactor or review it. It is not
optional styling advice. **Do not invent a visual style.**

1. **Read and follow [`docs/design/beavent/`](docs/design/beavent/)** before writing UI code. The detailed agent
   rules, checklists and component APIs are in
   [`docs/design/beavent/AI_AGENT_DESIGN_INSTRUCTIONS.md`](docs/design/beavent/AI_AGENT_DESIGN_INSTRUCTIONS.md).
2. **Brand direction: warm canvas, emerald ink, Adey gold.** Warm neutral backgrounds, emerald `primary` (`#0B6B53`
   light / `#5CC8A1` dark) for actions and selection, gold `accent` at most once per screen. No violet or orange
   primaries, no new brand colours.
3. **Use the design tokens only** — `@beavent/ui` (`packages/ui/src/index.ts`) is the single source of truth.
   - React Native: build screens from `apps/public-mobile/src/components/ui` (`Text`, `Button`, `TextField`, `Screen`,
     `Section`, `ListRow`, `OptionRow`, `Group`, `Chip`, `Banner`, `EmptyState`, `Skeleton`, `DualLine`, `Icon`);
     colours come from `useApp().colors`.
   - Web: `@import "@beavent/ui/tokens.css"` gives Tailwind utilities (`bg-background`, `bg-surface`, `text-fg`,
     `text-fg-muted`, `border-border`, `border-border-strong`, `bg-primary`, `text-on-primary`, `bg-primary-subtle`,
     `text-danger` …) and type-role classes (`bv-type-display`, `bv-type-title1`, `bv-type-title2`, `bv-type-body`,
     `bv-type-label`, `bv-type-caption` …). Never hard-code hex colours, pixel font sizes or off-scale spacing.
   - New tokens or component variants need a reusable reason and go into the design system first (§10 of the agent
     instructions), never into one screen.
4. **Follow the 14 design decisions DD1–DD14** in
   [`DESIGN_PHILOSOPHY.md` §7](docs/design/beavent/DESIGN_PHILOSOPHY.md#7-design-decisions). Highlights: emerald +
   gold on warm neutrals (DD1); tabs Explore · Saved · Bookings · Account (DD2); details and flows cover the tab bar
   (DD3); all-in prices (DD6); dual Ethiopian/international time (DD7); no uppercase (DD8); cards only for image-led
   tappable objects, rows with dividers otherwise (DD9); sign-in inline when needed (DD10); rails need ≥ 3 items
   (DD12); Business mode swaps the tab bar (DD13).
5. **Typography:** type roles only; nothing above 28 px in the apps (34 px only for web marketing headings); weights
   400/500/600; Ge'ez text gets line height 1.7 (headings 1.5) and is never below 14 px — the `Text` component and
   the `bv-type-*` classes do this for you. No uppercase, letter-spacing or italics as hierarchy.
6. **Layout:** 16 px gutters on phones, 32 px between sections, 12 px title-to-content; one primary action per
   screen; safe areas respected.
7. **Avoid generic AI-generated UI:** no cards around everything, card-in-card, gradients, decorative shadows,
   border + shadow together, badge rows, icon-per-fact rows, emoji in UI copy, stock imagery, dashboards on consumer
   screens, oversized headings.
8. **Localisation is built in, not added later:** every string from `@beavent/i18n` (Amharic and English required);
   money via `@beavent/money` (`formatDisplay`, never `.00` in lists, never hard-coded currency symbols); dates and
   times via `@beavent/calendar` (`formatDualDate`, `formatDualTime`, in the location's time zone); landmark-first
   addresses; given name first, "father's name" not "surname"; RTL-safe styles (`start`/`end`, not `left`/`right`).
9. **Accessibility:** contrast pairs from `contrastPairs` only (tested in `packages/ui`); translated labels on
   icon-only controls; 48 dp touch targets; visible focus; colour never the only signal; 200 % font scale works.
10. **Every state is designed:** loading (skeleton), empty, error with retry, offline, signed out, success.
11. **Test light and dark, Amharic and English, phone and desktop widths, and do visual QA on rendered screens**:
    run the stack and `npm run visual -w @beavent/e2e` (screenshots in `tools/e2e/test-results/visual/`), look at
    them, fix what's wrong. A UI is not done because it compiles.
12. **Dependencies:** approved and chosen — `expo-haptics` (installed), Noto Sans Ethiopic via
    `@expo-google-fonts/noto-sans-ethiopic` (installed), `expo-maps` (mobile) and MapLibre GL JS (web) for maps,
    `expo-brightness` (ticket pass), `expo-camera` (check-in); install the last ones with `npx expo install` in the
    task that builds their screen. Anything else needs approval first. Owner decisions (placeholder logo, English
    "Bookings" label in Amharic, 12-hour time): `DESIGN_PHILOSOPHY.md` §8.
13. **Bundle budget:** `npm run bundle:check -w @beavent/public-mobile` must pass (CI runs it).

If the docs don't answer a question, use the closest documented pattern and record the gap (agent instructions §12);
never fill it with a new style.

## Other conventions

- Money is integer minor units + currency; time is UTC + IANA zone; tenant data carries `org_id` (README).
- Pull requests follow [`.github/pull_request_template.md`](.github/pull_request_template.md) and doc 07 §2.
- Several sessions may work in this repository at once: never reset, clean, stash, unstage or overwrite changes you
  did not make; commit only when asked.
