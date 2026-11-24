# Beavent mobile app

One Expo app (SDK 58, Expo Router) for consumers and, for organization members, Business mode
([doc 03 §1.1](../../docs/03-system-architecture.md#11-mobile-app-one-app-two-modes)).

```bash
npx expo start          # dev server (press w for the web build, or scan with a development build)
npx expo lint           # lint
npx tsc --noEmit        # typecheck
```

- Routes: `src/app/(tabs)` — Explore · Bookings · Account; `src/app/business.tsx` — My schedule (Business mode);
  `src/app/sign-in.tsx` — sign-in modal. Navigation rules: [`MARKETPLACE_NAVIGATION.md`](../../docs/design/beavent/MARKETPLACE_NAVIGATION.md).
- UI: build from `src/components/ui` on `@beavent/ui` tokens — the design standard is mandatory
  ([`AGENTS.md`](AGENTS.md), [`docs/design/beavent/`](../../docs/design/beavent/)).
- App state (session, language, calendar/time/theme preferences): `src/lib/app-context.tsx`.
- App icon, splash and favicon use a **placeholder** emerald mark until a logo is designed (decision Q1): replace the
  image files in `assets/images/` only.
- `npm run bundle:check` — Android bundle size against the budget in `packages/ui` (also run in CI).
