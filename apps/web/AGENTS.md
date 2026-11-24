<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## Beavent UI/UX design standard (mandatory)

Every screen in this app (public site, business console and admin console) follows the Beavent design system: read the root [`AGENTS.md`](../../AGENTS.md) and [`docs/design/beavent/AI_AGENT_DESIGN_INSTRUCTIONS.md`](../../docs/design/beavent/AI_AGENT_DESIGN_INSTRUCTIONS.md) first. Use the token utilities and `bv-type-*` classes from `@beavent/ui/tokens.css` (imported in `src/app/globals.css`); no hex colours, ad-hoc font sizes or new visual styles.
