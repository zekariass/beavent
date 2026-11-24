# Beavent mobile app

One Expo app (SDK 58, Expo Router) for customers; business owners, staff and organizers use the separate
[Beavent Business app](../business-mobile/README.md)
([doc 03 §1.1](../../docs/03-system-architecture.md#11-mobile-apps-two-apps-shared-code)).

```bash
npx expo start          # dev server (press w for the web build, or scan with a development build)
npx expo lint           # lint
npx tsc --noEmit        # typecheck
```

- Routes: `src/app/(tabs)` — Explore · Bookings · Account;
  `src/app/sign-in.tsx` — sign-in modal. Navigation rules: [`MARKETPLACE_NAVIGATION.md`](../../docs/design/beavent/MARKETPLACE_NAVIGATION.md).
- UI: build from `@beavent/native` (`packages/native`) on `@beavent/ui` tokens — the design standard is mandatory
  ([`AGENTS.md`](AGENTS.md), [`docs/design/beavent/`](../../docs/design/beavent/)).
- App state (session, language, calendar/time/theme preferences): `packages/native/src/app-context.tsx`.
- App icon, splash and favicon use a **placeholder** emerald mark until a logo is designed (decision Q1): replace the
  image files in `assets/images/` only.
- `npm run bundle:check` — Android bundle size against the budget in `packages/ui` (also run in CI).
