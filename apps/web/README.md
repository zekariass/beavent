# Beavent web platform

One Next.js 16 app for everything on the web. Routes:

| URL                          | What                                                                                 |
| ---------------------------- | ------------------------------------------------------------------------------------ |
| `/[lang]` (`am`, `en`, `om`) | Public site (marketplace shell today; Services and Events marketplace to come)       |
| `/[lang]/business/...`       | Business and organizer console: sign-in, onboarding, menu, team, rota, booking rules |
| `/<ADMIN_PATH>/...`          | Admin console for platform staff (secret path; default `/ops` in development)        |

```bash
npm run dev -w @beavent/web     # http://localhost:3000
npm run lint -w @beavent/web
npm run typecheck -w @beavent/web
```

**Admin console path.** Set `ADMIN_PATH` (letters, digits, `-`, `_`; see `.env.example`). It is required in production:
without it the console is off. `src/proxy.ts` rewrites `/<ADMIN_PATH>/...` to the internal route
`src/app/(ops)/ops-console`, answers 404 for direct requests to `/ops-console`, keeps staff sessions alive and marks
every admin response `noindex`. The secret path hides the console; staff roles are still enforced by the API on every
call. Don't put the path in `robots.txt`, docs or client code.

**Layout of `src/`**

- `app/(site)/[lang]/` — public pages; `business/` — the business console (own cookies `bv_portal_*`, own sign-in).
- `app/(ops)/ops-console/` — admin console (own cookies `bv_admin_*`, own sign-in, own root layout).
- `components/` — shared (`EthiopianNow`, `LanguageSwitch`); `components/business/`, `components/ops/` — each console's
  primitives. `lib/business/session.ts`, `lib/ops/session.ts` — server-side sessions; `lib/admin-path.ts` — the secret
  prefix.
- Every console page is per-user and rendered at request time and shows a skeleton from `loading.tsx` while it loads.
  `cacheComponents` / `partialPrefetching` are off on purpose (see `next.config.ts`).

The Beavent UI/UX design standard is mandatory: [`AGENTS.md`](AGENTS.md), root [`AGENTS.md`](../../AGENTS.md).
