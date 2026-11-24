# Beavent Business (mobile)

The mobile app for **business owners, staff and event organizers**: calendar and schedule, check-in, point of sale and
customers (doc 03 §1.1 "Business mode"). The consumer app is [`apps/public-mobile/`](../public-mobile/README.md); the web platform
(`apps/web/`, `apps/business/`, `apps/admin-console/`) is separate. Expo SDK 58, Expo Router, same React/RN versions as `apps/public-mobile/`.

```bash
npx expo start          # dev server (w for web)
npx expo lint
npm run typecheck
```

**Status:** scaffolded with `create-expo-app` (default template, SDK 58), cleaned of starter content, joined to the
npm workspaces, and wired to `@beavent/ui` tokens and `@beavent/i18n`. It shows a placeholder screen only.

Next steps (not done yet):

1. Share the app shell between both apps (session and preferences provider, token-based components from
   `apps/public-mobile/src/components/ui`) instead of copying — e.g. a `packages/native` workspace.
2. Move Business mode ("My schedule") out of `apps/public-mobile/` into this app and give this app its own tab set
   (Today · Calendar · Check-in · Sales · More, filtered by capability — DD13).
3. Register separate bundle identifiers and store listings (not chosen yet).

The Beavent UI/UX design standard is mandatory here too: [`AGENTS.md`](AGENTS.md), root [`AGENTS.md`](../../AGENTS.md),
[`docs/design/beavent/`](../../docs/design/beavent/).
