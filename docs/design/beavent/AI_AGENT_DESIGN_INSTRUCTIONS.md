# Instructions for AI agents implementing Beavent screens

**Status:** v1, 19 Nov 2026. **Audience:** AI coding agents (and humans) who build or change any Beavent UI in `mobile/`, `web/`, `business/` or `admin-console/`.
**Authority:** these instructions and the documents they point to are the design decisions. **Do not invent design decisions.** If something isn't specified, follow §8.

---

## 1. Read before you start

Read in this order, every time you start a UI task (they are short enough):

1. [`DESIGN_PHILOSOPHY.md`](DESIGN_PHILOSOPHY.md) — principles P1–P8, decisions DD1–DD14, the audit (§6) and open questions (§8).
2. [`DESIGN_TOKENS.md`](DESIGN_TOKENS.md) — the only colours, sizes, spacing, radii, elevation and formats you may use.
3. [`COMPONENT_GUIDELINES.md`](COMPONENT_GUIDELINES.md) — the components and their rules.
4. [`SCREEN_DESIGN_STANDARDS.md`](SCREEN_DESIGN_STANDARDS.md) — templates, composition, copy, states, QA checklist.
5. The journey spec for your screen: [`MARKETPLACE_NAVIGATION.md`](MARKETPLACE_NAVIGATION.md) (C-screens, routes), [`SERVICES_MARKETPLACE_DESIGN.md`](SERVICES_MARKETPLACE_DESIGN.md) (S- and P-screens), [`EVENTS_MARKETPLACE_DESIGN.md`](EVENTS_MARKETPLACE_DESIGN.md) (E-screens).
6. [`MOBILE_UI_GUIDELINES.md`](MOBILE_UI_GUIDELINES.md) for any mobile work.
7. Repository rules: [`mobile/AGENTS.md`](../../../mobile/AGENTS.md) (**Expo SDK 58 — fetch versioned Expo docs, don't trust memory; install Expo packages only with `npx expo install`**), the root [`README.md`](../../../README.md) conventions, and [doc 06](../../06-markets-and-localization.md) §2 and §5 for localisation.

Use [`DESIGN_REFERENCE_CATALOG.md`](DESIGN_REFERENCE_CATALOG.md) only to understand *why*; never to copy a reference product's layout, assets or wording.

## 2. Hard rules

### You MUST

1. Find the screen's spec ID (C-, S-, E-, B-, P-) and implement **its** layout, content, actions, navigation and **all** listed states.
2. Use **only tokens** from `@beavent/ui` for colour, spacing, radius, type, elevation and motion. If the token you need is in DESIGN_TOKENS.md but not yet in code, add it to `packages/ui` **exactly as documented** (name and value) in the same PR.
3. Use **documented components**. If one doesn't exist yet in code, build it to its spec in `mobile/src/components/ui/` or `mobile/src/components/marketplace/` (web: the app's `src/components/`).
4. Take every user-visible string from `@beavent/i18n` (add keys to `en.ts` and `am.ts`; `om.ts` may lag and falls back). Use ICU plurals for counts.
5. Format money with `@beavent/money`, dates and times with `@beavent/calendar`, in the **location's or occurrence's time zone**, with dual time where the spec says so.
6. Read data shapes from the OpenAPI contract (`backend/src/main/openapi`, generated client `@beavent/api-client`). Bind fields by their API names.
7. Implement loading (skeleton), empty, error, offline and signed-out states, plus the screen-specific states in its spec.
8. Make it accessible: roles, translated labels, states, grouping of composite rows, header roles, 48 dp targets, 200 % font scale.
9. Check light + dark, Amharic + English, 360 dp and 320 dp widths.
10. Keep the primary action in the sticky bottom bar (flows, details) and use one primary button per screen.
11. Run the checks in §9 and attach the screenshots listed there.

### You MUST NOT

1. Hard-code hex colours, `fontSize`, `fontWeight`, `lineHeight`, or spacing numbers outside the token scale in screens or components.
2. Use `src/constants/theme.ts` `Colors`/`Spacing`/`Fonts` or `ThemedText`/`ThemedView` in new code — they are template leftovers scheduled for removal (A1, A5, A8).
3. Add dependencies (npm or Expo modules) without an approved decision. Open questions that need one: map (Q3), `expo-brightness`/`expo-haptics` (Q8), `expo-camera` (check-in), calendar integration (SERVICES §10 G6). Use the documented interim behaviour instead.
4. Use gradients, decorative shadows, cards around sections, card-in-card, badge rows, icon-per-fact rows, uppercase text, letter-spacing (except ticket serials), emoji in copy, stock photos, illustrations, or floating action buttons.
5. Concatenate translated strings, call `toLocaleString`/`Intl`/`Date` formatting in screens, or parse money as floats.
6. Show a success state before the server confirms (bookings, payments, transfers, refunds) or use optimistic UI for anything involving money or inventory.
7. Change navigation structure (tabs, route groups, modal vs push) — it is fixed in MARKETPLACE_NAVIGATION.md.
8. Invent copy for legal, policy or payment statements beyond the patterns given; use the documented sentences with real values.
9. Hide network-dependent actions offline — disable them with an explanation.
10. Copy Fresha, Eventbrite, Airbnb, Vinted or Revolut branding, assets, layouts pixel-for-pixel, or wording.

## 3. How to build a screen (procedure)

1. **Locate** the spec and template (SCREEN_DESIGN_STANDARDS §2). Note its route from MARKETPLACE_NAVIGATION §3.1.
2. **List data** the spec shows and find each field in the API. Missing field → it is probably in the spec's "Gaps" table; implement the documented fallback (e.g. hide the Popular group) and don't fake data.
3. **List components** used. Build missing ones first, each in its own file, matching COMPONENT_GUIDELINES (variants, states, a11y).
4. **Compose** the route file: data hooks + layout + components. No styling in route files beyond layout (`gap`, `padding` with tokens).
5. **Add strings** to `@beavent/i18n` with descriptive keys (`booking.time.title`, `booking.time.noSlots`, …).
6. **Implement states** (skeleton → content; empty; errors mapped from problem codes; offline; signed out).
7. **Verify** with §9.

## 4. Using tokens and components (target API)

The components below are specified but **not yet built**; this is the API shape to build toward. Keep names identical so other agents can rely on them.

```tsx
// mobile/src/app/place/[slug]/index.tsx — composition only (S-04)
import { Screen, Section, Text, StickyActionBar, Button } from "@/components/ui";
import { ProfileHeader, ServiceRow, ReviewSummary } from "@/components/marketplace";
import { useT } from "@/lib/preferences"; // app-wide language/calendar/time preferences (to be built)

export default function VenueProfile() {
  const t = useT();
  // data via @beavent/api-client React Query hooks …
  return (
    <Screen
      header={{ variant: "overImage" }}
      stickyFooter={
        <StickyActionBar
          summary={<Text role="label" tone="muted">{t("venue.nextAvailable", { time: nextLabel })}</Text>}
          action={<Button variant="primary" size="md" label={t("venue.bookNow")} onPress={startBooking} />}
        />
      }
    >
      <ProfileHeader kind="venue" listing={listing} location={location} />
      <Section title={t("venue.services")}>
        {services.map((s) => <ServiceRow key={s.id} service={s} selected={basket.has(s.id)} onToggle={toggle} />)}
      </Section>
      <Section title={t("venue.reviews")} seeAllHref={`/place/${slug}/reviews`}>
        <ReviewSummary summary={reviews} />
      </Section>
    </Screen>
  );
}
```

```tsx
// Inside a component: tokens only, no raw values.
import { color, space, radius } from "@beavent/ui";
const styles = (scheme: "light" | "dark") => ({
  chip: {
    height: 36,                         // documented size (tokens §4.4)
    paddingHorizontal: 14,              // documented in COMPONENT_GUIDELINES §3.10
    borderRadius: radius.full,
    backgroundColor: color[scheme].surface,
    gap: space[2],
  },
});
```

Documented one-off sizes (like chip height 36, padding 14) are allowed because they are written in the docs; undocumented ones are not.

**Wrong:**
```tsx
<Text style={{ fontSize: 24, fontWeight: "700", color: "#5b3fd9" }}>Services</Text>   // raw values, wrong role
<View style={{ shadowOpacity: 0.2, borderWidth: 1, borderRadius: 20 }}>…</View>         // card with shadow + border
<Text>{"Book " + service.name}</Text>                                                   // concatenation
<Text>{new Date(slot).toLocaleTimeString()}</Text>                                      // device zone, one convention
```
**Right:**
```tsx
<Text role="title2">{t("venue.services")}</Text>
<DateTimeText instant={slot.startsAt} timeZone={location.timeZone} format="slot" />
<PriceText money={variant.price} priceType={variant.priceType} />
```

## 5. Data, formatting and localisation rules

- **Money:** `{ amountMinor, currency }` from the API → `PriceText`/`@beavent/money`. Lists hide `.00`; payment surfaces show it (tokens §8.1). Never compute fees or taxes in the UI; display what the API returns.
- **Dates/times:** ISO instants + the location/occurrence IANA zone → `DateTimeText`/`@beavent/calendar`, using the user's calendar and time preferences (DD7).
- **Localized content** (`LocalizedText` / `*_i18n`): resolve with the fallback chain user language → org default → market default → `en` (use `localized()` from `@beavent/i18n`); show "Translated · Show original" when not the source language.
- **Names:** given name first; never label a field "Surname".
- **Addresses:** landmark first.
- **IDs and slugs:** routes use listing slugs for public pages and ids for private ones (MARKETPLACE_NAVIGATION §3).
- **Problem codes → copy:** map `SLOT_TAKEN`, `SOLD_OUT`, `PAYMENT_PENDING`, `RATE_LIMITED`, `STEP_UP_REQUIRED`, `FORBIDDEN`, `NOT_FOUND`, validation `422` to the copy in the screen spec; unknown errors use `error.generic`.
- **Idempotency:** creation and payment calls send an `Idempotency-Key` (the client helper exists) and reuse it on retry.
- **ETags:** partner portal edits send `If-Match`; on `412` show ConflictBanner — never auto-overwrite.

## 6. Quality requirements

- **States** per SCREEN_DESIGN_STANDARDS §6.
- **Accessibility** per MOBILE_UI_GUIDELINES §8.
- **Performance** per MOBILE_UI_GUIDELINES §5–§6: virtualise lists > 50 rows; `w320` in lists; no shadows/animations in list items; no new heavy libraries; check the Android bundle stays ≤ 4 MB.
- **Offline:** tickets and upcoming appointments readable offline (doc 03 §1.1).

## 7. Implementation order

Build in this order; each step is a separate pull request. Steps 1–3 come before any marketplace screen.

| # | Work | Why first | Specs |
|---|---|---|---|
| 1 | **Design foundation:** update `packages/ui` tokens (new values + new tokens + `textRoles` + `fontSizePx` + contrast test); generate web CSS variables from `cssVariables()` in `web/` and `business/`; fix partner-portal `onPrimary`; remove Expo template screens, components and assets; native tab labels from i18n; splash/icon colours | Every later screen depends on it; reclaims bundle budget | DESIGN_TOKENS §9, PHILOSOPHY §6.3 A1–A9 |
| 2 | **Formatting helpers:** display money (narrow symbol, no `.00` in lists), dual time, short/relative dates, 12-hour English standard time (after Q7), duration formatting with plural keys | Prevents per-screen formatting | DESIGN_TOKENS §8, PHILOSOPHY A12 |
| 3 | **App shell and primitives:** preferences provider (language, calendar, time, theme, data saver), React Navigation theme from tokens, route groups and tabs per MARKETPLACE_NAVIGATION §3.1, `Text`, `Screen`, `Section`, `Button`, `IconButton`, `TextField`, `PhoneField`, `CodeField`, `Chip`, `ListRow`, `BottomSheet`, `Skeleton`, `EmptyState`, `ErrorState`, `Banner`, `Toast`; **C-6 Sign-in** and **C-5 Account** (preferences) | Needed by every journey; sign-in API already exists | COMPONENT §2–§3, §7–§8; NAV C-5, C-6 |
| 4 | **Partner portal catalogue (Stage 1, this branch):** AppShell, DataTable, LocalizedTextField, MoneyField, DurationField, StepsEditor, PriceMatrix; screens P-01 … P-08 | Businesses must build menus before consumers can book | SERVICES §8 |
| 5 | **Stage 2.5 consumer booking (pay at venue):** Media, PriceText, DateTimeText, MetaLine, Rating, ProfileHeader, ServiceRow/Sheet, ProfessionalOption, DateStrip, TimeSlotGrid, StickyActionBar, StepProgress, BookingSummary; screens S-04 → S-10b, C-4 Bookings, S-11, S-12, S-13; then S-15 waitlist (2.6) | First end-to-end consumer value; reached by direct links | SERVICES §4–§6 |
| 6 | **Stage 3 payments:** PaymentMethodList, PaymentWaiting, HoldTimer; C-7; deposit variants of S-10/S-11 | Money flows | NAV C-7 |
| 7 | **Stage 6.8 events consumer UI:** E-05 → E-12, E-13, E-14, E-16, E-19, TicketPass with offline store | Ticketing | EVENTS §4–§6 |
| 8 | **Stage 8 discovery:** C-1 Explore, C-2 Search, S-01 … S-03, E-01 … E-04, C-3 Saved, E-15, S-16/E-18 reviews, C-8 notifications | Discovery ships last in the plan | NAV §5, SERVICES §3, §7, EVENTS §3, §7 |
| 9 | **Business mode** B-1 … B-5 as their stages arrive (2.3, 4, 7) | Staff tools | SERVICES §9, EVENTS §9.2 |

## 8. When the docs don't answer

1. **Search the docs** (this folder, then `docs/0*.md`). Many answers are in a table you haven't read.
2. If still unanswered, **pick the closest documented pattern** (same template, same component) and implement it — then **record the gap**: add a row to the relevant spec's "Gaps" table or to DESIGN_PHILOSOPHY §8 "Open questions", and mention it in the PR description under "Design decisions needed".
3. **Never** introduce a new visual pattern, colour, component variant, navigation behaviour or dependency to fill a gap. If no documented pattern fits, stop and ask the user.
4. If the code contradicts these docs (e.g. an API field doesn't exist), the **code and API contract are the source of truth for data**; the docs are the source of truth for design. Note the mismatch in the PR.

## 9. Definition of done (UI work)

- [ ] Spec implemented, including every state; deviations listed in the PR with reasons.
- [ ] SCREEN_DESIGN_STANDARDS §7 checklist pasted into the PR and ticked.
- [ ] Screenshots: light/dark × Amharic/English for each new screen; 200 % font scale and 320 dp for flows; offline state for Bookings/tickets.
- [ ] `npm run lint` and `npm run typecheck` pass at the root; for mobile also `cd mobile && npx expo lint && npx tsc --noEmit` (per `mobile/AGENTS.md`).
- [ ] `npm run test` passes (including the `packages/ui` contrast test once added).
- [ ] i18n: new keys in `en.ts` and `am.ts`; Amharic marked for native-speaker review (doc 07 step 0.11).
- [ ] No new dependencies, or the approving decision is linked.
- [ ] Docs updated if behaviour intentionally differs (definition of done in doc 07 §2).

## 10. Quick reference: replace this with that

| Instead of | Use |
|---|---|
| `ThemedText type="title"` (48 px) | `Text role="display"` (28) — once per screen |
| `Colors.light.textSecondary` | `color[scheme].textMuted` |
| `Spacing.three` (16) | `space[4]` (16) — note the template's names differ from the scale |
| A card with a shadow around a list | `Section` + `ListRow`s with dividers |
| Badges "Women only" "At home" "Popular" | `MetaLine`: "Women only · At-home visits" |
| A spinner in the middle of the screen | `Skeleton` matching the layout |
| `Alert.alert` for errors | Inline field error, `Banner`, or `ErrorState` |
| Modal dialog for info | `BottomSheet` or `Banner` |
| "OK" / "Submit" | "Confirm booking" / "Pay Br 360" |
| Date picker for bookings | `DateStrip` + `TimeSlotGrid` |
| `toLocaleString()` | `DateTimeText` / `PriceText` |
| Text on an image | Text below the image; only icon buttons on `imageScrim` over it |
| Top-right "Save" on a mobile form | Sticky bottom action bar |
