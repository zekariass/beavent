# 08 — Availability engine (plan step 2.1)

**Status:** spec v1, 24 Nov 2026. Slice 1 (the algorithm as a pure function, with property tests) is in
`services/backend/.../scheduling/internal/AvailabilityEngine.java` (28 hand-built tests and 3 property tests, 1,500
random calendars each; checked by mutation: deliberately wrong engines fail them). Slice 2 (loading the facts from the
database, the public `Availability` interface and the endpoint) is in `Availabilities.java` and
`AvailabilityController.java`, with API tests against PostGIS; see §9 for what it decided. Caching is not built: see §9.
**Read with:** [03 §3.1](03-system-architecture.md#31-appointment-booking-marketplace-deposit-by-mobile-money),
[05 data model](05-data-model.md), [07 step 2.1](07-implementation-plan.md).

## 1. What it answers

Given a location, one person's ordered list of services (each a variant, possibly with add-ons), who may perform
them (a specific team member or "anyone"), and a date range: **which start times can be booked, and with whom?**
The answer is *advisory*. The database stays the authority: booking inserts reservations, and the GiST exclusion
constraints on `scheduling.schedule_reservations` reject a lost race with `SLOT_TAKEN`. The engine's job is to make
that rare and to never offer a time that is impossible for reasons the database cannot see (closed, off shift, too
soon, too far ahead).

## 2. Inputs

| Input | Source | Notes |
|---|---|---|
| Items | `catalog.service_variants`, `service_variant_stages`, add-ons | Per item: customer-facing duration = sum of stages (a plain variant is one SERVICE stage of `duration_minutes`) plus add-on minutes; each stage says whether the staff member is busy (`staff_busy`); buffers before/after; resource requirements (a specific resource or any of a kind, units, whole item or one stage) |
| Candidate staff per item | `Service.staffIds` ∩ bookable online ∩ works at the location; narrowed to one person when the customer chose | Order matters: it is the tie-break (first feasible wins) |
| Working time | `team.Rota` | Intervals per person (regular pattern, replaced by one-off shifts, minus approved time off) |
| Location open time | `tenant.location_hours` per weekday, minus `location_closures`, minus public holidays when the location is "closed on public holidays" | Applies to on-premises bookings only; at-home bookings need only the person's working time |
| Existing reservations | `scheduling.schedule_reservations` with status `HELD` (not expired), `CONFIRMED`, `BLOCKED`, including `allow_overlap` rows | Staff and resource unit intervals. `RELEASED` and expired holds are ignored. Forced overlaps *do* block online offers: the desk double-booked on purpose, the marketplace must not add to it |
| Rules | `BookingPolicies.effective(location, service)` | `min_notice_minutes`, `max_advance_days`, `slot_interval_minutes`; request-to-book does not change availability (the booking is still held) |
| Travel | `tenant.service_areas.travel_minutes` | At-home only: staff busy for the travel time before the first item and after the last, not part of the customer-facing time |
| Clock and zone | `now`, the location's IANA time zone | The grid and weekday are in the location's zone; everything else is UTC instants |

## 3. Output

A list of **slots**, ordered by start time. A slot has a start (customer-facing), an end (customer-facing, buffers and
travel excluded) and, per item, the assigned person and the resource units chosen. If several people can do an item,
the first feasible one in the input order is assigned (deterministic; fairness rules such as "least busy first" are a
later policy on top). The booking step re-checks and may choose differently if the assignment was taken.

## 4. Rules

1. **Grid.** Start times are multiples of `slot_interval_minutes` from local midnight.
2. **Notice and horizon.** `start >= now + min_notice` and `start < now + max_advance_days × 24 h`.
3. **Timeline of an item** starting at `t`: stages run back to back; the customer-facing span is `[t, t + Σ stages)`.
   The person is *busy* during `SERVICE`, `FINISHING` and `CONSULTATION` stages and any stage with `staff_busy`; a
   `PROCESSING` stage with `staff_busy = false` leaves them free (they can serve someone else meanwhile). Buffers add
   busy time: `buffer_before` before the item's first busy time and `buffer_after` after its last. They apply to the
   person and to every resource the item uses, and they need not lie inside working hours, but they must be free.
4. **Items in sequence.** Item `k + 1` starts where item `k` ends (no gap). Each item may have a different person.
   An item's feasibility depends only on its own window and the calendar's existing reservations; a person's own
   consecutive items never conflict with each other (their buffers merge).
5. **Working time.** Every *busy stage* (and travel) of an item lies fully inside one working interval of its person.
6. **Location hours** (on-premises only). The whole customer-facing span lies inside one open interval after
   closures and holidays are removed. A booking may not run past closing time.
7. **No overlaps.** An item's busy windows (with buffers and travel) do not overlap any existing reservation of its
   person. Intervals are half-open `[start, end)`: one ending when another starts is fine.
8. **Resources.** For each requirement, `units` distinct free units *of one resource* are needed over the stage (or
   the whole item): a specific resource, or any resource of the kind at this location. A unit is free when no
   reservation of that unit overlaps the window (buffers included) and no other requirement of the same slot holds it
   at the same time. A resource's units are `1..units`. Which resource and units are chosen is found by an **exact
   search** across all requirements of all items (resources by id, units ascending, first solution wins): a greedy
   choice can strand a requirement that another choice would satisfy. The search is bounded (64 options per
   requirement, 20,000 steps per candidate slot); if the bound is hit the slot is **omitted**, never offered, so the
   bound can only reduce what is offered, never offer something impossible. Real menus need 0–2 requirements and never
   come near it. The assignment lists the units in requirement order.
9. **Nothing is offered when nothing fits.** An item longer than every free window yields no slots; a day with no
   open or working time yields none; unknown or inactive staff are never candidates.

## 5. Algorithm

For each local date in the range, for each grid start `s` in that day: compute the item windows from `s`; for each
item pick the first candidate for whom rules 5–7 hold and for whom rule 8 can be satisfied; if every item succeeds
and rules 2 and 6 hold, emit the slot. Busy intervals are kept sorted per person and per resource unit; overlap is a
binary search, so a location-day with 10 people is a few thousand cheap checks (target < 300 ms including loading).

Caching (slice 2): per location and day, keyed by a calendar version that the calendar-changing writes bump
(reservation, shift, time off, closure, policy, service edits) through the outbox, so a cached answer is never older
than the last change. Because the answer is advisory, a short TTL (a few seconds) is an acceptable safety net.

## 6. Edge cases (each has a test)

- Start exactly at opening; end exactly at closing (allowed); one minute past closing (not allowed).
- Back-to-back with another reservation on either side (allowed); one minute of overlap (not allowed).
- Split shifts and a lunch gap: an item cannot span the gap if its busy stages need the person throughout; a
  processing stage can span it if the person is free during that stage (rule 5 only checks busy stages).
- Processing stage: the person is offered to other customers during it.
- Buffers: a buffer that collides with the next reservation blocks the slot even though the service itself would fit.
- Holds: an unexpired hold blocks; an expired hold does not; a released reservation does not.
- Forced overlap rows block.
- Notice boundary (`now + notice` exactly is allowed) and horizon boundary (the instant `now + horizon` is not).
- Two items with different people; the same person twice in a row; a person who can do item 1 but not item 2 at the
  chosen time.
- A resource with 2 units: one reservation leaves one unit; two leave none; a specific resource vs any-of-kind.
- At-home: travel before and after counts against working time and reservations; location hours are ignored.
- Daylight saving: the engine uses the location's zone rules (no assumption of a fixed offset).

## 7. Tests

`AvailabilityEngineTest` has hand-built cases for every edge case above, and **property tests** over many random small
calendars (seeded, reproducible):

1. **Safety:** no offered slot overlaps a reservation of its assigned people or resource units, lies outside working
   time or location hours, breaks notice/horizon, or is off the grid. Checked by an independent verifier that does
   not share code with the engine.
2. **Completeness (oracle):** on small instances the engine returns exactly the set of start times that a brute-force
   oracle finds by trying every grid time and every person assignment.
3. **Monotonicity:** adding a reservation never adds a slot; removing one never removes a slot.

Performance test: one location-day, 10 people, 60 existing reservations, a 3-item booking; asserts the engine alone
stays well under the 300 ms target (the loading budget is measured in slice 2).

## 8. Out of scope here (later steps)

Group bookings with parallel items and bundles with `PARALLEL` order; recurring series; the waitlist offer (uses the
same engine to find a slot for a held offer); fairness/"least busy" assignment; per-staff service duration overrides
(`ResolvedPrice.durationMinutes` feeds the item's duration when slice 2 builds the input); online (virtual) services;
the marketplace endpoint, caching and invalidation (slice 2).

## 9. The database loader and the endpoint (slice 2)

**Interface.** `scheduling.Availability.find(Query, now)`, called inside a transaction in the business's context.
`Query` = location, items (variant, add-ons, chosen person or "anyone"), local date range (at most 14 days), and an
optional service area for an at-home visit. The result lists offers: start, end, and the person per item.

**Endpoint.** `POST /v1/public/locations/{locationId}/availability` (OpenAPI `searchAvailability`, no sign-in; a POST
because the question has a body, not because it changes anything). The location resolves to its business through
`tenancy.PublicLocations` (location active, takes online bookings, business `ACTIVE` or still `ONBOARDING`; everything
else looks like a location that does not exist), and the read runs in that business's context, so row-level security
confines it. 120 requests per minute per address.

**What is read** (all in one transaction, so one request sees one calendar):

| Fact | From | Notes |
|---|---|---|
| Service, stages, buffers, requirements | `catalog.service_variants`, `service_variant_stages`, `service_resource_requirements` | Service active, variant active, online booking on, offered at the location, and the delivery mode fits (venue vs at home against the location's `service_mode`). No stage rows means one `SERVICE` stage of the variant's duration |
| Add-ons | `catalog.addons` linked to the service | Their minutes are one more busy `SERVICE` stage at the end. An add-on the service does not offer is refused |
| Candidate people | `team.staff` ∩ `catalog.staff_services` ∩ `team.staff_locations` | Active, bookable, bookable online, employed in the range; ordered by `sort_order`, name, id (the order is the tie-break) |
| Working time | `team.Rota` | Pattern, one-off shifts, minus time off, for the location, a day either side |
| Reservations | `scheduling.schedule_reservations` | People and the location's resources; `CONFIRMED`, `BLOCKED`, `HELD` with `hold_expires_at` in the future. Released rows and expired holds are ignored; forced overlaps are included |
| Location open time | `tenant.location_hours` per local day, minus public holidays (when the location closes on them; holidays of the country, or of the place's area) and `location_closures` | On-premises only |
| Notice, horizon, grid | `BookingPolicies.effective` per item | Several services: the longest notice, the shortest horizon and the coarsest slot interval win |
| Travel | `tenant.service_areas.travel_minutes` | At-home only |

**Decisions.**

- **Per-person durations.** A price override can carry a different duration for a person. The engine plans one
  duration per service, so "anyone" means the people whose duration equals the location's; a chosen person is planned
  with their own duration (as one `SERVICE` stage). A location or person duration that differs from the sum of the
  stages replaces the stages with one stage of that length. Revisit if a pilot business mixes durations.
- **Expired holds.** The engine ignores them, but the database exclusion constraint still counts a `HELD` row until the
  expiry job releases it (`scheduling.expire_holds`). The booking step (2.3) must release expired holds that overlap
  before inserting, or it would answer `SLOT_TAKEN` for a time the engine offered.
- **No cache yet.** The plan called for a per-day cache invalidated through the outbox. Measured uncached, one
  location-day with 10 people, 60 reservations and a 3-service booking answers in about 55–65 ms (median, in-process,
  warm; the budget is 300 ms), so a cache would add invalidation risk for no benefit now. Add one when a measurement
  needs it (search by time window in Stage 8 is the likely trigger).
- **Public by location id.** Location ids are random; the venue page (2.2) adds the readable slug.
