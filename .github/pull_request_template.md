## What and why

<!-- Which plan step (docs/07-implementation-plan.md) does this implement? What changed? -->

Plan step:

## Definition of done

- [ ] Code reviewed; unit and integration tests added or updated
- [ ] OpenAPI spec updated and `packages/api-client` regenerated (if the API changed)
- [ ] `npm run db:validate` green (if `db/` changed); new tables follow docs/05 §7
- [ ] `npm run lint`, `npm run typecheck`, `npm run build`, `npm run api:build` green
- [ ] Amharic and English strings; Ethiopian date/time display where dates are shown
- [ ] Audit events for sensitive actions; metrics/alerts for new background jobs
- [ ] Docs updated where behaviour differs from docs 03–06
