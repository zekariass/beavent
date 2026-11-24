# Beavent Business (mobile)

The mobile app for **business owners, staff and event organizers**: schedule, calendar, check-in, point of sale and
customers (doc 03 §1.1; decision D20). The consumer app is [`apps/public-mobile/`](../public-mobile/README.md); the
web platform is [`apps/web/`](../web/README.md). Expo SDK 58, Expo Router, same React/RN versions as `public-mobile`.

```bash
npx expo start          # dev server (press w for web; the API's local CORS allows http://localhost:19007)
npx expo lint
npm run typecheck
npm run bundle:check    # Android bundle against the budget in packages/ui
```

**Tabs today:** Schedule ("My schedule" for the signed-in team member, this week and next) and More (display
preferences, sign out). People who aren't in a business yet are offered "Create a business on the web". Calendar,
Check-in and Sales join as their stages are built, filtered by the member's capabilities and permissions (DD13).

**Code:** routes in `src/app`; everything shared with the consumer app (token-based components, session and
preferences provider, Ge'ez font, sign-in panel, tabs, `useBeaventShell()`) comes from
[`@beavent/native`](../../packages/native/src/index.ts).

**Open:** the API records sign-ins by `ClientApp`; this app signs in as `MOBILE_APP` like the consumer app. A separate
`BUSINESS_APP` value would need a backend contract change (token audience) — not decided. Bundle identifiers and
store listings are not chosen yet.

The Beavent UI/UX design standard is mandatory here too: [`AGENTS.md`](AGENTS.md), root [`AGENTS.md`](../../AGENTS.md),
[`docs/design/beavent/`](../../docs/design/beavent/).
