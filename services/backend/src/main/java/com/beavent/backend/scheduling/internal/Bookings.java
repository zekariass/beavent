package com.beavent.backend.scheduling.internal;

import static com.beavent.db.catalog.Tables.ADDONS;
import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.crm.Tables.PATCH_TESTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_GROUPS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEM_ADDONS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEM_RESOURCES;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEM_STAGES;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_SERIES;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_STATUS_HISTORY;
import static com.beavent.db.scheduling.Tables.CALENDAR_BLOCKS;
import static com.beavent.db.scheduling.Tables.HOLDS;
import static com.beavent.db.scheduling.Tables.SCHEDULE_RESERVATIONS;
import static com.beavent.db.team.Tables.STAFF;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.catalog.ServicePricing;
import com.beavent.backend.identity.People;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.scheduling.Availability;
import com.beavent.backend.scheduling.BookingPolicies;
import com.beavent.db.scheduling.tables.records.HoldsRecord;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Booking an appointment (plan step 2.3, docs/03 §3.1): a short <em>hold</em> on the exact time the engine offered,
 * then <em>confirm</em> by a signed-in person, which turns the hold into an appointment in one transaction.
 *
 * <p>Both steps re-check the slot with the availability engine and write the reservation ledger; its exclusion
 * constraints decide races ({@code SLOT_TAKEN}). The hold stores what it holds (the plan), so confirming writes exactly
 * what was checked. A person's own back-to-back services are merged into one reservation (their buffers would
 * otherwise collide with each other).
 */
@Component
class Bookings {

    static final Duration HOLD_TIME = Duration.ofMinutes(10);
    private static final String REFERENCE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};

    // --- what a hold stores ---------------------------------------------------------------------------------------

    record PlanStage(int seq, String kind, boolean staffBusy, Instant start, Instant end) {}

    record PlanUnit(UUID resourceId, int unit, Instant start, Instant end) {}

    record PlanAddon(UUID id, String name, int durationMinutes, long priceMinor) {}

    record PlanItem(
            UUID variantId,
            UUID serviceId,
            UUID staffId,
            String staffName,
            boolean staffRequested,
            String serviceName,
            String variantName,
            Instant start,
            Instant end,
            long priceMinor,
            String currency,
            List<PlanAddon> addons,
            List<PlanStage> stages,
            List<PlanUnit> units) {}

    /**
     * One reservation row: a person's (merged) blocked time or a resource unit's window. {@code item} and
     * {@code stageSeq} / {@code unitIndex} name the appointment row it is recorded against.
     */
    record PlanBlock(UUID staffId, UUID resourceId, Integer unit, Instant start, Instant end, int item, int ref) {}

    record Policy(
            boolean requiresApproval,
            boolean allowPayAtVenue,
            String depositMode,
            BigDecimal depositPercent,
            Long depositFixedMinor,
            int cancellationWindowMinutes,
            Instant freeCancellationUntil,
            BigDecimal lateCancelFeePercent,
            BigDecimal noShowFeePercent) {}

    record Plan(
            UUID locationId,
            String timezone,
            String deliveryMode,
            Instant start,
            Instant end,
            List<PlanItem> items,
            List<PlanBlock> blocks,
            Policy policy,
            long totalMinor,
            String currency) {}

    record Held(UUID holdId, String holderKey, Instant expiresAt, Plan plan) {}

    record Booked(UUID appointmentId, String reference, String status, Plan plan, boolean replayed) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Availabilities availabilities;
    private final ServicePricing pricing;
    private final BookingPolicies policies;
    private final People people;
    private final Outbox outbox;
    private final JsonMapper json;

    Bookings(
            DSLContext dsl,
            TransactionTemplate transactions,
            Availabilities availabilities,
            ServicePricing pricing,
            BookingPolicies policies,
            People people,
            Outbox outbox,
            JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.availabilities = availabilities;
        this.pricing = pricing;
        this.policies = policies;
        this.people = people;
        this.outbox = outbox;
        this.json = json;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Field<Object> range(Instant start, Instant end) {
        return DSL.field("tstzrange({0}, {1}, '[)')", Object.class, DSL.val(utc(start)), DSL.val(utc(end)));
    }

    // --- hold --------------------------------------------------------------------------------------------------------

    /** Holds the exact time for {@link #HOLD_TIME}. Call with the business's context bound (no person needed). */
    Held hold(
            UUID orgId,
            UUID locationId,
            List<Availability.Item> items,
            UUID serviceAreaId,
            Instant start,
            Instant now) {
        return hold(orgId, locationId, items, serviceAreaId, start, now, HOLD_TIME);
    }

    /** Holds the exact time for {@code holdTime} (a waitlist offer gives the person longer to answer). */
    Held hold(
            UUID orgId,
            UUID locationId,
            List<Availability.Item> items,
            UUID serviceAreaId,
            Instant start,
            Instant now,
            java.time.Duration holdTime) {
        return transactions.execute(status -> {
            releaseExpired(now);
            Availabilities.Chosen chosen = availabilities
                    .at(locationId, items, serviceAreaId, start, now)
                    .orElseThrow(() -> new ApiException(ProblemCode.SLOT_TAKEN));
            Plan plan = plan(orgId, locationId, chosen, now);

            UUID holdId = Ids.newId();
            String holderKey = UUID.randomUUID().toString();
            Instant expires = now.plus(holdTime);
            dsl.insertInto(HOLDS)
                    .set(HOLDS.ID, holdId)
                    .set(HOLDS.ORG_ID, orgId)
                    .set(HOLDS.LOCATION_ID, locationId)
                    .set(HOLDS.HOLDER_KEY, holderKey)
                    .set(HOLDS.EXPIRES_AT, utc(expires))
                    .set(HOLDS.PLAN, JSONB.valueOf(json.writeValueAsString(plan)))
                    .execute();
            for (PlanBlock block : plan.blocks()) {
                var insert = dsl.insertInto(SCHEDULE_RESERVATIONS)
                        .set(SCHEDULE_RESERVATIONS.ORG_ID, orgId)
                        .set(SCHEDULE_RESERVATIONS.LOCATION_ID, locationId)
                        .set(SCHEDULE_RESERVATIONS.KIND, block.staffId() != null ? "STAFF" : "RESOURCE")
                        .set(SCHEDULE_RESERVATIONS.STAFF_ID, block.staffId())
                        .set(SCHEDULE_RESERVATIONS.RESOURCE_ID, block.resourceId())
                        .set(
                                SCHEDULE_RESERVATIONS.RESOURCE_UNIT,
                                block.unit() == null ? null : block.unit().shortValue())
                        .set(Map.of(SCHEDULE_RESERVATIONS.DURING, range(block.start(), block.end())))
                        .set(SCHEDULE_RESERVATIONS.STATUS, "HELD")
                        .set(SCHEDULE_RESERVATIONS.HOLD_ID, holdId)
                        .set(SCHEDULE_RESERVATIONS.HOLD_EXPIRES_AT, utc(expires));
                insert.execute(); // a lost race fails here: 23P01 → SLOT_TAKEN
            }
            return new Held(holdId, holderKey, expires, plan);
        });
    }

    /** Lets go of a hold early (the person changed their mind). Unknown, converted or expired holds are ignored. */
    void release(UUID holdId, String holderKey) {
        transactions.executeWithoutResult(status -> {
            HoldsRecord hold = dsl.selectFrom(HOLDS)
                    .where(HOLDS.ID.eq(holdId), HOLDS.HOLDER_KEY.eq(holderKey), HOLDS.STATUS.eq("ACTIVE"))
                    .forUpdate()
                    .fetchOne();
            if (hold == null) {
                return;
            }
            dsl.update(SCHEDULE_RESERVATIONS)
                    .set(SCHEDULE_RESERVATIONS.STATUS, "RELEASED")
                    .where(SCHEDULE_RESERVATIONS.HOLD_ID.eq(holdId))
                    .execute();
            hold.setStatus("RELEASED");
            hold.update();
        });
    }

    /** Holds that ran out stop blocking at once, so the database agrees with what availability offers. */
    private void releaseExpired(Instant now) {
        dsl.update(SCHEDULE_RESERVATIONS)
                .set(SCHEDULE_RESERVATIONS.STATUS, "RELEASED")
                .where(SCHEDULE_RESERVATIONS.STATUS.eq("HELD"), SCHEDULE_RESERVATIONS.HOLD_EXPIRES_AT.le(utc(now)))
                .execute();
        dsl.update(HOLDS)
                .set(HOLDS.STATUS, "EXPIRED")
                .where(HOLDS.STATUS.eq("ACTIVE"), HOLDS.EXPIRES_AT.le(utc(now)))
                .execute();
    }

    // --- the plan: names, prices, policy, reservations --------------------------------------------------------------

    private Plan plan(UUID orgId, UUID locationId, Availabilities.Chosen chosen, Instant now) {
        String language = dsl.select(ORGANIZATIONS.DEFAULT_LANGUAGE)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.DEFAULT_LANGUAGE);
        List<Availabilities.Planned> planned = chosen.question().planned();
        List<PlanItem> items = new ArrayList<>();
        long total = 0;
        String currency = null;
        List<BookingPolicies.Policy> itemPolicies = new ArrayList<>();
        for (int k = 0; k < planned.size(); k++) {
            Availabilities.Planned p = planned.get(k);
            AvailabilityEngine.ItemLayout layout = chosen.layout().get(k);
            UUID staffId = chosen.slot().assignments().get(k).staffId();
            ServicePricing.Price price = pricing.resolve(p.variantId(), locationId, staffId, now);
            var names = dsl.select(SERVICES.NAME_I18N, SERVICE_VARIANTS.NAME_I18N)
                    .from(SERVICE_VARIANTS)
                    .join(SERVICES)
                    .on(SERVICES.ID.eq(SERVICE_VARIANTS.SERVICE_ID))
                    .where(SERVICE_VARIANTS.ID.eq(p.variantId()))
                    .fetchSingle();
            List<PlanAddon> addons = p.addonIds().isEmpty()
                    ? List.of()
                    : dsl.selectFrom(ADDONS)
                            .where(ADDONS.ID.in(p.addonIds()))
                            .orderBy(ADDONS.ID)
                            .fetch(a -> new PlanAddon(
                                    a.getId(),
                                    pick(a.getNameI18n(), language),
                                    a.getDurationMinutes(),
                                    a.getPriceMinor()));
            long itemTotal = price.priceMinor()
                    + addons.stream().mapToLong(PlanAddon::priceMinor).sum();
            total += itemTotal;
            currency = price.currency();
            String staffName = dsl.select(STAFF.DISPLAY_NAME)
                    .from(STAFF)
                    .where(STAFF.ID.eq(staffId))
                    .fetchSingle(STAFF.DISPLAY_NAME);
            List<PlanStage> stages = layout.stages().stream()
                    .map(s -> new PlanStage(s.seq(), s.kind().name(), s.staffBusy(), s.start(), s.end()))
                    .toList();
            List<PlanUnit> units = layout.units().stream()
                    .map(u -> new PlanUnit(u.unit().resourceId(), u.unit().unit(), u.start(), u.end()))
                    .toList();
            String variantName = pick(names.value2(), language);
            items.add(new PlanItem(
                    p.variantId(),
                    p.serviceId(),
                    staffId,
                    staffName,
                    p.staffRequested(),
                    pick(names.value1(), language),
                    variantName.isEmpty() ? null : variantName,
                    layout.start(),
                    layout.end(),
                    price.priceMinor(),
                    price.currency(),
                    addons,
                    stages,
                    units));
            itemPolicies.add(policies.effective(locationId, p.serviceId()));
        }
        Instant start = chosen.slot().start();
        Instant end = chosen.slot().end();
        return new Plan(
                locationId,
                chosen.question().place().zone().getId(),
                chosen.question().request().onPremises() ? "ON_PREMISES" : "AT_CUSTOMER",
                start,
                end,
                items,
                blocks(chosen.slot(), chosen.layout()),
                policy(itemPolicies, start),
                total,
                currency);
    }

    /**
     * The terms the customer agrees to, frozen in the appointment (never re-derived later). Several services: the
     * strictest rule of any of them applies.
     */
    private static Policy policy(List<BookingPolicies.Policy> all, Instant start) {
        BookingPolicies.Policy first = all.getFirst();
        int window = all.stream()
                .mapToInt(BookingPolicies.Policy::cancellationWindowMinutes)
                .max()
                .orElse(0);
        BigDecimal late = all.stream()
                .map(BookingPolicies.Policy::lateCancelFeePercent)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        BigDecimal noShow = all.stream()
                .map(BookingPolicies.Policy::noShowFeePercent)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        return new Policy(
                all.stream().anyMatch(BookingPolicies.Policy::requiresApproval),
                all.stream().allMatch(BookingPolicies.Policy::allowPayAtVenue),
                first.depositMode(),
                first.depositPercent(),
                first.depositFixedMinor(),
                window,
                start.minus(Duration.ofMinutes(window)),
                late,
                noShow);
    }

    /** A window to reserve, tagged with the appointment row (item, stage seq or unit index) it is recorded against. */
    private record Window(Instant start, Instant end, int item, int ref) {}

    /**
     * Reservation rows for a slot: each person's blocked windows merged across their items (a person's own consecutive
     * services must not collide with each other), and each resource unit's windows merged the same way.
     */
    static List<PlanBlock> blocks(AvailabilityEngine.Slot slot, List<AvailabilityEngine.ItemLayout> layout) {
        Map<UUID, List<Window>> byStaff = new LinkedHashMap<>();
        Map<AvailabilityEngine.ResourceUnit, List<Window>> byUnit = new LinkedHashMap<>();
        for (int k = 0; k < layout.size(); k++) {
            UUID staffId = slot.assignments().get(k).staffId();
            for (AvailabilityEngine.StaffBlock b : layout.get(k).blocks()) {
                byStaff.computeIfAbsent(staffId, x -> new ArrayList<>())
                        .add(new Window(b.start(), b.end(), k, b.stageSeq()));
            }
            List<AvailabilityEngine.UnitWindow> units = layout.get(k).units();
            for (int u = 0; u < units.size(); u++) {
                AvailabilityEngine.UnitWindow w = units.get(u);
                byUnit.computeIfAbsent(w.unit(), x -> new ArrayList<>()).add(new Window(w.start(), w.end(), k, u));
            }
        }
        List<PlanBlock> out = new ArrayList<>();
        byStaff.forEach((staffId, windows) -> {
            for (Window w : merge(windows)) {
                out.add(new PlanBlock(staffId, null, null, w.start(), w.end(), w.item(), w.ref()));
            }
        });
        byUnit.forEach((unit, windows) -> {
            for (Window w : merge(windows)) {
                out.add(new PlanBlock(null, unit.resourceId(), unit.unit(), w.start(), w.end(), w.item(), w.ref()));
            }
        });
        return out;
    }

    /** Overlapping windows become one, recorded against the earliest window's row. Touching windows stay apart. */
    private static List<Window> merge(List<Window> windows) {
        List<Window> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparing(Window::start));
        List<Window> out = new ArrayList<>();
        for (Window w : sorted) {
            if (!out.isEmpty() && w.start().isBefore(out.getLast().end())) {
                Window last = out.removeLast();
                Instant end = w.end().isAfter(last.end()) ? w.end() : last.end();
                out.add(new Window(last.start(), end, last.item(), last.ref()));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    // --- confirm -----------------------------------------------------------------------------------------------------

    /**
     * Turns the hold into an appointment for the signed-in person. Repeating the call for a hold that is already an
     * appointment returns that appointment. Call with the business's context bound for {@code userId}.
     */
    Booked confirm(UUID orgId, UUID userId, UUID holdId, String holderKey, String note, Instant now) {
        return transactions.execute(status -> {
            HoldsRecord hold = dsl.selectFrom(HOLDS)
                    .where(HOLDS.ID.eq(holdId), HOLDS.HOLDER_KEY.eq(holderKey))
                    .forUpdate()
                    .fetchOne();
            if (hold == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such hold.");
            }
            Plan plan = json.readValue(hold.getPlan().data(), Plan.class);
            if ("CONVERTED".equals(hold.getStatus()) && hold.getAppointmentId() != null) {
                var existing = dsl.select(APPOINTMENTS.REFERENCE, APPOINTMENTS.STATUS, APPOINTMENTS.BOOKED_BY_USER_ID)
                        .from(APPOINTMENTS)
                        .where(APPOINTMENTS.ID.eq(hold.getAppointmentId()))
                        .fetchSingle();
                if (!userId.equals(existing.value3())) {
                    throw new ApiException(ProblemCode.NOT_FOUND, "There is no such hold.");
                }
                return new Booked(hold.getAppointmentId(), existing.value1(), existing.value2(), plan, true);
            }
            if (!"ACTIVE".equals(hold.getStatus())
                    || !hold.getExpiresAt().toInstant().isAfter(now)) {
                throw new ApiException(ProblemCode.HOLD_EXPIRED);
            }
            People.Person person = people.find(userId).orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED));
            UUID customerId = customer(orgId, person);
            requirePatchTests(
                    customerId, plan.items().stream().map(PlanItem::variantId).toList(), plan.start());

            String appointmentStatus = plan.policy().requiresApproval() ? "REQUESTED" : "CONFIRMED";
            UUID appointmentId = Ids.newId();
            String reference = reference();
            dsl.insertInto(APPOINTMENTS)
                    .set(APPOINTMENTS.ID, appointmentId)
                    .set(APPOINTMENTS.ORG_ID, orgId)
                    .set(APPOINTMENTS.LOCATION_ID, plan.locationId())
                    .set(APPOINTMENTS.CUSTOMER_ID, customerId)
                    .set(APPOINTMENTS.BOOKED_BY_USER_ID, userId)
                    .set(APPOINTMENTS.REFERENCE, reference)
                    .set(APPOINTMENTS.SOURCE, "DIRECT_LINK")
                    .set(APPOINTMENTS.STATUS, appointmentStatus)
                    .set(APPOINTMENTS.DELIVERY_MODE, plan.deliveryMode())
                    .set(
                            APPOINTMENTS.SERVICE_ADDRESS,
                            "AT_CUSTOMER".equals(plan.deliveryMode()) ? JSONB.valueOf("{}") : null)
                    .set(APPOINTMENTS.STARTS_AT, utc(plan.start()))
                    .set(APPOINTMENTS.ENDS_AT, utc(plan.end()))
                    .set(APPOINTMENTS.TIMEZONE, plan.timezone())
                    .set(APPOINTMENTS.POLICY_SNAPSHOT, JSONB.valueOf(json.writeValueAsString(plan.policy())))
                    .set(APPOINTMENTS.CUSTOMER_NOTE, note == null || note.isBlank() ? null : note.strip())
                    .execute();

            // Swap the hold's reservations for the appointment's in the same transaction: no moment without them.
            dsl.update(SCHEDULE_RESERVATIONS)
                    .set(SCHEDULE_RESERVATIONS.STATUS, "RELEASED")
                    .where(SCHEDULE_RESERVATIONS.HOLD_ID.eq(holdId))
                    .execute();
            writeItems(orgId, appointmentId, plan);
            hold.setStatus("CONVERTED");
            hold.setAppointmentId(appointmentId);
            hold.update();

            dsl.insertInto(APPOINTMENT_STATUS_HISTORY)
                    .set(APPOINTMENT_STATUS_HISTORY.ORG_ID, orgId)
                    .set(APPOINTMENT_STATUS_HISTORY.APPOINTMENT_ID, appointmentId)
                    .set(APPOINTMENT_STATUS_HISTORY.TO_STATUS, appointmentStatus)
                    .set(APPOINTMENT_STATUS_HISTORY.ACTOR_USER_ID, userId)
                    .execute();
            outbox.publish(new DomainEvent(
                    "scheduling.appointment",
                    appointmentId,
                    appointmentStatus.equals("REQUESTED") ? "appointment.requested" : "appointment.booked",
                    1,
                    orgId,
                    Map.of(
                            "appointmentId", appointmentId,
                            "reference", reference,
                            "locationId", plan.locationId(),
                            "customerId", customerId,
                            "startsAt", plan.start().toString())));
            return new Booked(appointmentId, reference, appointmentStatus, plan, false);
        });
    }

    /**
     * Writes an appointment's items, add-ons, stages and resource units from a plan, and its reservations (merged
     * blocks recorded against the stage or resource row that starts them).
     */
    private void writeItems(UUID orgId, UUID appointmentId, Plan plan) {
        writeItems(orgId, appointmentId, plan, false);
    }

    /** @param allowOverlap the front desk chose "book anyway": the reservations are deliberate overlaps */
    private void writeItems(UUID orgId, UUID appointmentId, Plan plan, boolean allowOverlap) {
        // Rows the reservations are recorded against: stage per (item, seq), resource row per (item, unit index).
        Map<String, UUID> stageIds = new HashMap<>();
        Map<String, UUID> resourceIds = new HashMap<>();
        for (int k = 0; k < plan.items().size(); k++) {
            PlanItem item = plan.items().get(k);
            UUID itemId = Ids.newId();
            dsl.insertInto(APPOINTMENT_ITEMS)
                    .set(APPOINTMENT_ITEMS.ID, itemId)
                    .set(APPOINTMENT_ITEMS.ORG_ID, orgId)
                    .set(APPOINTMENT_ITEMS.APPOINTMENT_ID, appointmentId)
                    .set(APPOINTMENT_ITEMS.SEQ, (short) (k + 1))
                    .set(APPOINTMENT_ITEMS.SERVICE_VARIANT_ID, item.variantId())
                    .set(APPOINTMENT_ITEMS.STAFF_ID, item.staffId())
                    .set(APPOINTMENT_ITEMS.STAFF_NAME, item.staffName())
                    .set(APPOINTMENT_ITEMS.STAFF_REQUESTED, item.staffRequested())
                    .set(APPOINTMENT_ITEMS.SERVICE_NAME, item.serviceName())
                    .set(APPOINTMENT_ITEMS.VARIANT_NAME, item.variantName())
                    .set(APPOINTMENT_ITEMS.STARTS_AT, utc(item.start()))
                    .set(APPOINTMENT_ITEMS.ENDS_AT, utc(item.end()))
                    .set(APPOINTMENT_ITEMS.QUOTED_PRICE_MINOR, item.priceMinor())
                    .set(APPOINTMENT_ITEMS.CURRENCY_CODE, item.currency())
                    .execute();
            for (PlanAddon addon : item.addons()) {
                dsl.insertInto(APPOINTMENT_ITEM_ADDONS)
                        .set(APPOINTMENT_ITEM_ADDONS.ORG_ID, orgId)
                        .set(APPOINTMENT_ITEM_ADDONS.APPOINTMENT_ITEM_ID, itemId)
                        .set(APPOINTMENT_ITEM_ADDONS.ADDON_ID, addon.id())
                        .set(APPOINTMENT_ITEM_ADDONS.NAME, addon.name())
                        .set(APPOINTMENT_ITEM_ADDONS.DURATION_MINUTES, (short) addon.durationMinutes())
                        .set(APPOINTMENT_ITEM_ADDONS.PRICE_MINOR, addon.priceMinor())
                        .execute();
            }
            for (PlanStage stage : item.stages()) {
                UUID stageId = Ids.newId();
                stageIds.put(k + ":" + stage.seq(), stageId);
                dsl.insertInto(APPOINTMENT_ITEM_STAGES)
                        .set(APPOINTMENT_ITEM_STAGES.ID, stageId)
                        .set(APPOINTMENT_ITEM_STAGES.ORG_ID, orgId)
                        .set(APPOINTMENT_ITEM_STAGES.APPOINTMENT_ITEM_ID, itemId)
                        .set(APPOINTMENT_ITEM_STAGES.SEQ, (short) stage.seq())
                        .set(APPOINTMENT_ITEM_STAGES.KIND, stage.kind())
                        .set(APPOINTMENT_ITEM_STAGES.STAFF_BUSY, stage.staffBusy())
                        .set(Map.of(APPOINTMENT_ITEM_STAGES.DURING, range(stage.start(), stage.end())))
                        .execute();
            }
            for (int u = 0; u < item.units().size(); u++) {
                PlanUnit unit = item.units().get(u);
                UUID rowId = Ids.newId();
                resourceIds.put(k + ":" + u, rowId);
                dsl.insertInto(APPOINTMENT_ITEM_RESOURCES)
                        .set(APPOINTMENT_ITEM_RESOURCES.ID, rowId)
                        .set(APPOINTMENT_ITEM_RESOURCES.ORG_ID, orgId)
                        .set(APPOINTMENT_ITEM_RESOURCES.APPOINTMENT_ITEM_ID, itemId)
                        .set(APPOINTMENT_ITEM_RESOURCES.RESOURCE_ID, unit.resourceId())
                        .set(APPOINTMENT_ITEM_RESOURCES.RESOURCE_UNIT, (short) unit.unit())
                        .set(Map.of(APPOINTMENT_ITEM_RESOURCES.DURING, range(unit.start(), unit.end())))
                        .execute();
            }
        }

        for (PlanBlock block : plan.blocks()) {
            boolean staff = block.staffId() != null;
            dsl.insertInto(SCHEDULE_RESERVATIONS)
                    .set(SCHEDULE_RESERVATIONS.ORG_ID, orgId)
                    .set(SCHEDULE_RESERVATIONS.LOCATION_ID, plan.locationId())
                    .set(SCHEDULE_RESERVATIONS.KIND, staff ? "STAFF" : "RESOURCE")
                    .set(SCHEDULE_RESERVATIONS.STAFF_ID, block.staffId())
                    .set(SCHEDULE_RESERVATIONS.RESOURCE_ID, block.resourceId())
                    .set(
                            SCHEDULE_RESERVATIONS.RESOURCE_UNIT,
                            block.unit() == null ? null : block.unit().shortValue())
                    .set(Map.of(SCHEDULE_RESERVATIONS.DURING, range(block.start(), block.end())))
                    .set(SCHEDULE_RESERVATIONS.STATUS, "CONFIRMED")
                    .set(SCHEDULE_RESERVATIONS.ALLOW_OVERLAP, allowOverlap)
                    .set(
                            SCHEDULE_RESERVATIONS.APPOINTMENT_STAGE_ID,
                            staff ? stageIds.get(block.item() + ":" + block.ref()) : null)
                    .set(
                            SCHEDULE_RESERVATIONS.APPOINTMENT_RESOURCE_ID,
                            staff ? null : resourceIds.get(block.item() + ":" + block.ref()))
                    .execute();
        }
    }

    // --- the person changes their mind: cancel, reschedule -------------------------------------------------------

    /**
     * What cancelling now would mean (gap G4): free until the frozen free-cancellation time (or while the business has
     * not accepted a request), otherwise the late-cancellation fee on the all-in total. No money moves before Stage 3:
     * the fee is recorded with the cancellation.
     */
    record Cancellation(boolean allowed, boolean free, Instant freeUntil, long feeMinor, String currency) {}

    record Detail(
            UUID id,
            UUID locationId,
            String reference,
            String status,
            Instant start,
            Instant end,
            String timezone,
            String note,
            Plan plan,
            Cancellation cancellation,
            boolean canReschedule) {}

    /** The business behind one of the person's appointments (row-level security hides everyone else's). */
    java.util.Optional<UUID> orgOf(UUID appointmentId) {
        return transactions.execute(status -> dsl.select(APPOINTMENTS.ORG_ID)
                .from(APPOINTMENTS)
                .where(APPOINTMENTS.ID.eq(appointmentId))
                .fetchOptional(APPOINTMENTS.ORG_ID));
    }

    /** Call with the business's context bound for {@code userId}; only the person's own appointment is found. */
    Detail detail(UUID userId, UUID appointmentId, Instant now) {
        return transactions.execute(status -> detailIn(userId, appointmentId, now, false));
    }

    private Detail detailIn(UUID userId, UUID appointmentId, Instant now, boolean lock) {
        var select = dsl.selectFrom(APPOINTMENTS)
                .where(
                        APPOINTMENTS.ID.eq(appointmentId),
                        APPOINTMENTS
                                .BOOKED_BY_USER_ID
                                .eq(userId)
                                .or(APPOINTMENTS.CUSTOMER_ID.in(
                                        DSL.select(CUSTOMERS.ID).from(CUSTOMERS).where(CUSTOMERS.USER_ID.eq(userId)))));
        var a = lock ? select.forUpdate().fetchOne() : select.fetchOne();
        if (a == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
        }
        Policy policy = json.readValue(a.getPolicySnapshot().data(), Policy.class);
        List<PlanItem> items = new ArrayList<>();
        long total = 0;
        String currency = null;
        var rows = dsl.selectFrom(APPOINTMENT_ITEMS)
                .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                .orderBy(APPOINTMENT_ITEMS.SEQ)
                .fetch();
        for (var i : rows) {
            List<PlanAddon> addons = dsl.selectFrom(APPOINTMENT_ITEM_ADDONS)
                    .where(APPOINTMENT_ITEM_ADDONS.APPOINTMENT_ITEM_ID.eq(i.getId()))
                    .orderBy(APPOINTMENT_ITEM_ADDONS.ADDON_ID)
                    .fetch(x -> new PlanAddon(x.getAddonId(), x.getName(), x.getDurationMinutes(), x.getPriceMinor()));
            total += i.getQuotedPriceMinor()
                    + addons.stream().mapToLong(PlanAddon::priceMinor).sum();
            currency = i.getCurrencyCode().trim();
            items.add(new PlanItem(
                    i.getServiceVariantId(),
                    null,
                    i.getStaffId(),
                    i.getStaffName(),
                    i.getStaffRequested(),
                    i.getServiceName(),
                    i.getVariantName(),
                    i.getStartsAt().toInstant(),
                    i.getEndsAt().toInstant(),
                    i.getQuotedPriceMinor(),
                    i.getCurrencyCode().trim(),
                    addons,
                    List.of(),
                    List.of()));
        }
        Instant start = a.getStartsAt().toInstant();
        boolean upcoming = start.isAfter(now);
        boolean open = a.getStatus().equals("CONFIRMED") || a.getStatus().equals("REQUESTED");
        boolean free = a.getStatus().equals("REQUESTED") || now.isBefore(policy.freeCancellationUntil());
        long fee = free
                ? 0
                : new BigDecimal(total)
                        .multiply(
                                policy.lateCancelFeePercent() == null ? BigDecimal.ZERO : policy.lateCancelFeePercent())
                        .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                        .longValueExact();
        Plan plan = new Plan(
                a.getLocationId(),
                a.getTimezone(),
                a.getDeliveryMode(),
                start,
                a.getEndsAt().toInstant(),
                items,
                List.of(),
                policy,
                total,
                currency);
        return new Detail(
                a.getId(),
                a.getLocationId(),
                a.getReference(),
                a.getStatus(),
                start,
                a.getEndsAt().toInstant(),
                a.getTimezone(),
                a.getCustomerNote(),
                plan,
                new Cancellation(open && upcoming, free, policy.freeCancellationUntil(), fee, currency),
                open && upcoming && free && "ON_PREMISES".equals(a.getDeliveryMode()));
    }

    /** Releases every reservation of the appointment (its stages' and resource units'). */
    private void releaseReservations(UUID appointmentId) {
        releaseReservations(dsl, appointmentId);
    }

    static void releaseReservations(DSLContext dsl, UUID appointmentId) {
        dsl.update(SCHEDULE_RESERVATIONS)
                .set(SCHEDULE_RESERVATIONS.STATUS, "RELEASED")
                .where(
                        SCHEDULE_RESERVATIONS.STATUS.ne("RELEASED"),
                        SCHEDULE_RESERVATIONS
                                .APPOINTMENT_STAGE_ID
                                .in(DSL.select(APPOINTMENT_ITEM_STAGES.ID)
                                        .from(APPOINTMENT_ITEM_STAGES)
                                        .join(APPOINTMENT_ITEMS)
                                        .on(APPOINTMENT_ITEMS.ID.eq(APPOINTMENT_ITEM_STAGES.APPOINTMENT_ITEM_ID))
                                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId)))
                                .or(SCHEDULE_RESERVATIONS.APPOINTMENT_RESOURCE_ID.in(DSL.select(
                                                APPOINTMENT_ITEM_RESOURCES.ID)
                                        .from(APPOINTMENT_ITEM_RESOURCES)
                                        .join(APPOINTMENT_ITEMS)
                                        .on(APPOINTMENT_ITEMS.ID.eq(APPOINTMENT_ITEM_RESOURCES.APPOINTMENT_ITEM_ID))
                                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId)))))
                .execute();
    }

    private void history(UUID orgId, UUID appointmentId, String from, String to, UUID actor, String reason) {
        history(dsl, orgId, appointmentId, from, to, actor, reason);
    }

    static void history(
            DSLContext dsl, UUID orgId, UUID appointmentId, String from, String to, UUID actor, String reason) {
        dsl.insertInto(APPOINTMENT_STATUS_HISTORY)
                .set(APPOINTMENT_STATUS_HISTORY.ORG_ID, orgId)
                .set(APPOINTMENT_STATUS_HISTORY.APPOINTMENT_ID, appointmentId)
                .set(APPOINTMENT_STATUS_HISTORY.FROM_STATUS, from)
                .set(APPOINTMENT_STATUS_HISTORY.TO_STATUS, to)
                .set(APPOINTMENT_STATUS_HISTORY.ACTOR_USER_ID, actor)
                .set(APPOINTMENT_STATUS_HISTORY.REASON, reason)
                .execute();
    }

    /** The person cancels: the time is free at once, a late fee is recorded when the free period has passed. */
    Detail cancel(UUID orgId, UUID userId, UUID appointmentId, String reason, Instant now) {
        return transactions.execute(status -> {
            Detail before = detailIn(userId, appointmentId, now, true);
            if (!before.cancellation().allowed()) {
                throw new ApiException(ProblemCode.IMMUTABLE, "This appointment can no longer be cancelled.");
            }
            releaseReservations(appointmentId);
            dsl.update(APPOINTMENTS)
                    .set(APPOINTMENTS.STATUS, "CANCELLED")
                    .set(APPOINTMENTS.CANCELLED_AT, utc(now))
                    .set(APPOINTMENTS.CANCELLED_BY, "CUSTOMER")
                    .set(APPOINTMENTS.CANCEL_REASON, reason == null || reason.isBlank() ? null : reason.strip())
                    .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .execute();
            dsl.update(APPOINTMENT_ITEMS)
                    .set(APPOINTMENT_ITEMS.STATUS, "CANCELLED")
                    .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                    .execute();
            Cancellation c = before.cancellation();
            history(
                    orgId,
                    appointmentId,
                    before.status(),
                    "CANCELLED",
                    userId,
                    c.free()
                            ? "Cancelled by the customer, free"
                            : "Cancelled by the customer, late fee " + c.feeMinor());
            outbox.publish(new DomainEvent(
                    "scheduling.appointment",
                    appointmentId,
                    "appointment.cancelled",
                    1,
                    orgId,
                    Map.of(
                            "appointmentId", appointmentId,
                            "by", "CUSTOMER",
                            "free", c.free(),
                            "feeMinor", c.feeMinor(),
                            "currency", Objects.requireNonNullElse(c.currency(), ""))));
            return detailIn(userId, appointmentId, now, false);
        });
    }

    /**
     * Moves the appointment to {@code start} with the same services (and the same person where the customer chose one).
     * One transaction frees the old time first, so a small move with the same person does not collide with itself; if
     * the new time cannot be booked nothing changes ({@code SLOT_TAKEN}).
     */
    Detail reschedule(UUID orgId, UUID userId, UUID appointmentId, Instant start, Instant now) {
        return transactions.execute(status -> {
            Detail before = detailIn(userId, appointmentId, now, true);
            if (!before.canReschedule()) {
                throw new ApiException(
                        ProblemCode.IMMUTABLE,
                        "This appointment can no longer be moved online. Please call the business.");
            }
            requirePatchTests(
                    dsl.select(APPOINTMENTS.CUSTOMER_ID)
                            .from(APPOINTMENTS)
                            .where(APPOINTMENTS.ID.eq(appointmentId))
                            .fetchSingle(APPOINTMENTS.CUSTOMER_ID),
                    before.plan().items().stream().map(PlanItem::variantId).toList(),
                    start);
            List<Availability.Item> items = new ArrayList<>();
            for (PlanItem i : before.plan().items()) {
                items.add(new Availability.Item(
                        i.variantId(),
                        i.addons().stream().map(PlanAddon::id).toList(),
                        i.staffRequested() ? i.staffId() : null));
            }
            releaseReservations(appointmentId);
            Availabilities.Chosen chosen = availabilities
                    .at(before.locationId(), items, null, start, now)
                    .orElseThrow(() -> new ApiException(ProblemCode.SLOT_TAKEN));
            Plan fresh = plan(orgId, before.locationId(), chosen, now);
            // Existing bookings keep their price (P-03): only the times and, where "anyone" was asked, the person move.
            List<PlanItem> kept = new ArrayList<>();
            for (int k = 0; k < fresh.items().size(); k++) {
                PlanItem n = fresh.items().get(k);
                PlanItem o = before.plan().items().get(k);
                kept.add(new PlanItem(
                        n.variantId(),
                        n.serviceId(),
                        n.staffId(),
                        n.staffName(),
                        n.staffRequested(),
                        n.serviceName(),
                        n.variantName(),
                        n.start(),
                        n.end(),
                        o.priceMinor(),
                        o.currency(),
                        o.addons(),
                        n.stages(),
                        n.units()));
            }
            Plan plan = new Plan(
                    fresh.locationId(),
                    fresh.timezone(),
                    fresh.deliveryMode(),
                    fresh.start(),
                    fresh.end(),
                    kept,
                    fresh.blocks(),
                    fresh.policy(),
                    before.plan().totalMinor(),
                    before.plan().currency());
            dsl.deleteFrom(APPOINTMENT_ITEMS)
                    .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                    .execute();
            writeItems(orgId, appointmentId, plan);
            dsl.update(APPOINTMENTS)
                    .set(APPOINTMENTS.STARTS_AT, utc(plan.start()))
                    .set(APPOINTMENTS.ENDS_AT, utc(plan.end()))
                    .set(APPOINTMENTS.POLICY_SNAPSHOT, JSONB.valueOf(json.writeValueAsString(plan.policy())))
                    .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .execute();
            history(
                    orgId,
                    appointmentId,
                    before.status(),
                    before.status(),
                    userId,
                    "Rescheduled by the customer from " + before.start());
            outbox.publish(new DomainEvent(
                    "scheduling.appointment",
                    appointmentId,
                    "appointment.rescheduled",
                    1,
                    orgId,
                    Map.of(
                            "appointmentId", appointmentId,
                            "from", before.start().toString(),
                            "to", plan.start().toString())));
            return detailIn(userId, appointmentId, now, false);
        });
    }

    // --- the front desk: walk-ins, phone bookings, moves, blocks ------------------------------------------------

    /** Who the booking is for: an existing customer of the business, or a new one (name, phone optional). */
    record Who(UUID customerId, String name, String phone) {}

    record DeskBooked(UUID appointmentId, String reference, Plan plan) {}

    /**
     * The front desk books (walk-in or phone). The time must be free in the calendar's terms, unless {@code
     * allowOverlap} ("book anyway"): then the reservations are deliberate overlaps the database lets through. Call with
     * the business's context bound; the caller checked the permission.
     */
    DeskBooked deskBook(
            UUID orgId,
            UUID actorUserId,
            UUID locationId,
            List<Availability.Item> items,
            Instant start,
            Who who,
            String note,
            String source,
            boolean allowOverlap,
            boolean notify,
            Instant now) {
        return deskBookMany(
                        orgId,
                        actorUserId,
                        locationId,
                        List.of(new Party(who, items)),
                        List.of(start),
                        null,
                        note,
                        source,
                        allowOverlap,
                        false,
                        notify,
                        now)
                .booked()
                .getFirst();
    }

    /** One person in a desk booking: who it is for, and their services with whom. */
    record Party(Who who, List<Availability.Item> items) {}

    /** What a desk booking made: the appointments, and the dates of a series that were skipped as taken. */
    record DeskMany(UUID groupId, UUID seriesId, List<DeskBooked> booked, List<Instant> skipped) {}

    /**
     * The front desk books one person, several people at the same time (a group: one appointment each, all or none),
     * or one person on several dates (a series, every {@code everyWeeks} weeks). Everything happens in one
     * transaction, so the people and dates see each other's reservations. A time that is not free fails the whole
     * booking, unless {@code allowOverlap} (book anyway) or, for a series, {@code skipTaken} (leave that date out). In a
     * series only the first visit sends the customer a confirmation; reminders follow each visit.
     */
    DeskMany deskBookMany(
            UUID orgId,
            UUID actorUserId,
            UUID locationId,
            List<Party> parties,
            List<Instant> starts,
            Integer everyWeeks,
            String note,
            String source,
            boolean allowOverlap,
            boolean skipTaken,
            boolean notify,
            Instant now) {
        return transactions.execute(status -> {
            releaseExpired(now);
            List<UUID> customers = new ArrayList<>();
            for (Party party : parties) {
                customers.add(deskCustomer(orgId, party.who(), source));
            }
            UUID groupId = null;
            if (parties.size() > 1) {
                groupId = Ids.newId();
                dsl.insertInto(APPOINTMENT_GROUPS)
                        .set(APPOINTMENT_GROUPS.ID, groupId)
                        .set(APPOINTMENT_GROUPS.ORG_ID, orgId)
                        .set(APPOINTMENT_GROUPS.LEAD_CUSTOMER_ID, customers.getFirst())
                        .execute();
            }
            UUID seriesId = null;
            String timezone = dsl.select(LOCATIONS.TIMEZONE)
                    .from(LOCATIONS)
                    .where(LOCATIONS.ID.eq(locationId))
                    .fetchSingle(LOCATIONS.TIMEZONE);
            if (starts.size() > 1) {
                seriesId = Ids.newId();
                dsl.insertInto(APPOINTMENT_SERIES)
                        .set(APPOINTMENT_SERIES.ID, seriesId)
                        .set(APPOINTMENT_SERIES.ORG_ID, orgId)
                        .set(APPOINTMENT_SERIES.CUSTOMER_ID, customers.getFirst())
                        .set(
                                APPOINTMENT_SERIES.RRULE,
                                "FREQ=WEEKLY;INTERVAL=" + (everyWeeks == null ? 1 : everyWeeks) + ";COUNT="
                                        + starts.size())
                        .set(APPOINTMENT_SERIES.TIMEZONE, timezone)
                        .execute();
            }
            List<DeskBooked> booked = new ArrayList<>();
            List<Instant> skipped = new ArrayList<>();
            for (Instant start : starts) {
                for (int k = 0; k < parties.size(); k++) {
                    Party party = parties.get(k);
                    Availabilities.Chosen chosen = availabilities
                            .at(locationId, party.items(), null, start, now, true)
                            .orElse(null);
                    boolean forced = false;
                    if (chosen == null) {
                        if (allowOverlap) {
                            chosen = availabilities.forced(locationId, party.items(), start, now);
                            forced = true;
                        } else if (skipTaken && seriesId != null) {
                            skipped.add(start);
                            continue;
                        } else {
                            String when = start.atZone(ZoneId.of(timezone))
                                    .toLocalDate()
                                    .toString();
                            throw new ApiException(
                                    ProblemCode.SLOT_TAKEN,
                                    seriesId != null
                                            ? "The time on " + when + " is not free. Skip it or book anyway."
                                            : parties.size() > 1
                                                    ? "The time is not free for person " + (k + 1)
                                                            + ". Book anyway to overlap."
                                                    : "That time is not free for this person. Book anyway to overlap.");
                        }
                    }
                    Plan plan = plan(orgId, locationId, chosen, now);
                    boolean tell = notify && (seriesId == null || booked.isEmpty());
                    booked.add(insertDesk(
                            orgId,
                            actorUserId,
                            locationId,
                            plan,
                            customers.get(k),
                            note,
                            source,
                            forced,
                            groupId,
                            seriesId,
                            tell));
                }
            }
            if (booked.isEmpty()) {
                throw new ApiException(ProblemCode.SLOT_TAKEN, "None of these times are free.");
            }
            return new DeskMany(groupId, seriesId, booked, skipped);
        });
    }

    private DeskBooked insertDesk(
            UUID orgId,
            UUID actorUserId,
            UUID locationId,
            Plan plan,
            UUID customerId,
            String note,
            String source,
            boolean forced,
            UUID groupId,
            UUID seriesId,
            boolean notify) {
        UUID appointmentId = Ids.newId();
        String reference = reference();
        dsl.insertInto(APPOINTMENTS)
                .set(APPOINTMENTS.ID, appointmentId)
                .set(APPOINTMENTS.ORG_ID, orgId)
                .set(APPOINTMENTS.LOCATION_ID, locationId)
                .set(APPOINTMENTS.CUSTOMER_ID, customerId)
                .set(APPOINTMENTS.BOOKED_BY_USER_ID, actorUserId)
                .set(APPOINTMENTS.GROUP_ID, groupId)
                .set(APPOINTMENTS.SERIES_ID, seriesId)
                .set(APPOINTMENTS.REFERENCE, reference)
                .set(APPOINTMENTS.SOURCE, source)
                .set(APPOINTMENTS.STATUS, "CONFIRMED")
                .set(APPOINTMENTS.DELIVERY_MODE, plan.deliveryMode())
                .set(APPOINTMENTS.STARTS_AT, utc(plan.start()))
                .set(APPOINTMENTS.ENDS_AT, utc(plan.end()))
                .set(APPOINTMENTS.TIMEZONE, plan.timezone())
                .set(APPOINTMENTS.POLICY_SNAPSHOT, JSONB.valueOf(json.writeValueAsString(plan.policy())))
                .set(APPOINTMENTS.INTERNAL_NOTE, note == null || note.isBlank() ? null : note.strip())
                .execute();
        writeItems(orgId, appointmentId, plan, forced);
        history(orgId, appointmentId, null, "CONFIRMED", actorUserId, forced ? "Booked anyway (overlap)" : null);
        outbox.publish(new DomainEvent(
                "scheduling.appointment",
                appointmentId,
                "appointment.booked",
                1,
                orgId,
                Map.of("appointmentId", appointmentId, "notify", notify, "source", source)));
        return new DeskBooked(appointmentId, reference, plan);
    }

    /** What the front desk cancels: this visit, this and the following ones of its series, or its whole group. */
    enum CancelScope {
        ONE,
        FOLLOWING,
        GROUP
    }

    /** The appointments a desk cancellation would touch (still active ones), for the permission check. */
    List<UUID> deskCancelTargets(UUID appointmentId, CancelScope scope) {
        return transactions.execute(status -> targets(appointmentId, scope));
    }

    private List<UUID> targets(UUID appointmentId, CancelScope scope) {
        var a = dsl.selectFrom(APPOINTMENTS)
                .where(APPOINTMENTS.ID.eq(appointmentId))
                .fetchOne();
        if (a == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
        }
        var active = APPOINTMENTS.STATUS.in("CONFIRMED", "REQUESTED");
        return switch (scope) {
            case ONE -> List.of(appointmentId);
            case FOLLOWING ->
                a.getSeriesId() == null
                        ? List.of(appointmentId)
                        : dsl.select(APPOINTMENTS.ID)
                                .from(APPOINTMENTS)
                                .where(
                                        APPOINTMENTS.SERIES_ID.eq(a.getSeriesId()),
                                        APPOINTMENTS.STARTS_AT.ge(a.getStartsAt()),
                                        active)
                                .orderBy(APPOINTMENTS.STARTS_AT)
                                .fetch(APPOINTMENTS.ID);
            case GROUP ->
                a.getGroupId() == null
                        ? List.of(appointmentId)
                        : dsl.select(APPOINTMENTS.ID)
                                .from(APPOINTMENTS)
                                .where(APPOINTMENTS.GROUP_ID.eq(a.getGroupId()), active)
                                .orderBy(APPOINTMENTS.STARTS_AT, APPOINTMENTS.ID)
                                .fetch(APPOINTMENTS.ID);
        };
    }

    /**
     * The front desk cancels (no fee: the business cancels). The times are freed at once. Customers are told unless
     * the desk says not to; when the rest of a series is cancelled, the customer gets one message.
     */
    List<UUID> deskCancel(
            UUID orgId, UUID actorUserId, UUID appointmentId, CancelScope scope, String reason, boolean notify) {
        return transactions.execute(status -> {
            var first = dsl.selectFrom(APPOINTMENTS)
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .forUpdate()
                    .fetchOne();
            if (first == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
            }
            if (!List.of("CONFIRMED", "REQUESTED").contains(first.getStatus())) {
                throw new ApiException(
                        ProblemCode.IMMUTABLE, "Only a booked visit that has not started can be cancelled.");
            }
            List<UUID> ids = targets(appointmentId, scope);
            OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
            for (int k = 0; k < ids.size(); k++) {
                UUID id = ids.get(k);
                String from = dsl.select(APPOINTMENTS.STATUS)
                        .from(APPOINTMENTS)
                        .where(APPOINTMENTS.ID.eq(id))
                        .forUpdate()
                        .fetchSingle(APPOINTMENTS.STATUS);
                releaseReservations(id);
                dsl.update(APPOINTMENT_ITEMS)
                        .set(APPOINTMENT_ITEMS.STATUS, "CANCELLED")
                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(id))
                        .execute();
                dsl.update(APPOINTMENTS)
                        .set(APPOINTMENTS.STATUS, "CANCELLED")
                        .set(APPOINTMENTS.CANCELLED_AT, at)
                        .set(APPOINTMENTS.CANCELLED_BY, "ORGANIZATION")
                        .set(APPOINTMENTS.CANCEL_REASON, reason == null || reason.isBlank() ? null : reason.strip())
                        .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                        .where(APPOINTMENTS.ID.eq(id))
                        .execute();
                history(orgId, id, from, "CANCELLED", actorUserId, reason);
                boolean tell = notify && (scope != CancelScope.FOLLOWING || k == 0);
                outbox.publish(new DomainEvent(
                        "scheduling.appointment",
                        id,
                        "appointment.cancelled",
                        1,
                        orgId,
                        Map.of("appointmentId", id, "notify", tell, "by", "ORGANIZATION")));
            }
            if (scope == CancelScope.FOLLOWING && first.getSeriesId() != null) {
                dsl.update(APPOINTMENT_SERIES)
                        .set(APPOINTMENT_SERIES.STATUS, "ENDED")
                        .where(APPOINTMENT_SERIES.ID.eq(first.getSeriesId()))
                        .execute();
            }
            return ids;
        });
    }

    private UUID deskCustomer(UUID orgId, Who who, String source) {
        if (who.customerId() != null) {
            return dsl.select(CUSTOMERS.ID)
                    .from(CUSTOMERS)
                    .where(CUSTOMERS.ID.eq(who.customerId()), CUSTOMERS.STATUS.eq("ACTIVE"))
                    .fetchOptional(CUSTOMERS.ID)
                    .orElseThrow(() -> new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "There is no such customer."));
        }
        if (who.phone() != null) {
            UUID known = dsl.select(CUSTOMERS.ID)
                    .from(CUSTOMERS)
                    .where(CUSTOMERS.PHONE_E164.eq(who.phone()), CUSTOMERS.STATUS.eq("ACTIVE"))
                    .orderBy(CUSTOMERS.FIRST_SEEN_AT)
                    .limit(1)
                    .fetchOne(CUSTOMERS.ID);
            if (known != null) {
                return known;
            }
        }
        if (who.name() == null || who.name().isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the customer's name.");
        }
        UUID id = Ids.newId();
        dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.ID, id)
                .set(CUSTOMERS.ORG_ID, orgId)
                .set(CUSTOMERS.DISPLAY_NAME, who.name().strip())
                .set(CUSTOMERS.PHONE_E164, who.phone())
                .set(CUSTOMERS.SOURCE, source)
                .execute();
        return id;
    }

    /**
     * The front desk moves an appointment (a new time, optionally another person for every service). The old time is
     * freed first in the same transaction; prices stay as booked. {@code allowOverlap} books anyway.
     */
    void deskMove(
            UUID orgId,
            UUID actorUserId,
            UUID appointmentId,
            Instant start,
            UUID staffId,
            boolean allowOverlap,
            boolean notify,
            Instant now) {
        transactions.executeWithoutResult(status -> {
            var a = dsl.selectFrom(APPOINTMENTS)
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .forUpdate()
                    .fetchOne();
            if (a == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
            }
            if (!List.of("CONFIRMED", "REQUESTED", "ARRIVED").contains(a.getStatus())) {
                throw new ApiException(ProblemCode.IMMUTABLE, "Only upcoming appointments can be moved.");
            }
            var rows = dsl.selectFrom(APPOINTMENT_ITEMS)
                    .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                    .orderBy(APPOINTMENT_ITEMS.SEQ)
                    .fetch();
            List<PlanItem> old = new ArrayList<>();
            List<Availability.Item> items = new ArrayList<>();
            for (var i : rows) {
                List<PlanAddon> addons = dsl.selectFrom(APPOINTMENT_ITEM_ADDONS)
                        .where(APPOINTMENT_ITEM_ADDONS.APPOINTMENT_ITEM_ID.eq(i.getId()))
                        .orderBy(APPOINTMENT_ITEM_ADDONS.ADDON_ID)
                        .fetch(x ->
                                new PlanAddon(x.getAddonId(), x.getName(), x.getDurationMinutes(), x.getPriceMinor()));
                old.add(new PlanItem(
                        i.getServiceVariantId(),
                        null,
                        i.getStaffId(),
                        i.getStaffName(),
                        i.getStaffRequested(),
                        i.getServiceName(),
                        i.getVariantName(),
                        null,
                        null,
                        i.getQuotedPriceMinor(),
                        i.getCurrencyCode().trim(),
                        addons,
                        List.of(),
                        List.of()));
                items.add(new Availability.Item(
                        i.getServiceVariantId(),
                        addons.stream().map(PlanAddon::id).toList(),
                        staffId != null ? staffId : i.getStaffId()));
            }
            releaseReservations(appointmentId);
            Availabilities.Chosen chosen = availabilities
                    .at(a.getLocationId(), items, null, start, now, true)
                    .orElse(null);
            boolean forced = false;
            if (chosen == null) {
                if (!allowOverlap) {
                    throw new ApiException(
                            ProblemCode.SLOT_TAKEN, "That time is not free for this person. Book anyway to overlap.");
                }
                chosen = availabilities.forced(a.getLocationId(), items, start, now);
                forced = true;
            }
            Plan fresh = plan(orgId, a.getLocationId(), chosen, now);
            List<PlanItem> kept = new ArrayList<>();
            for (int k = 0; k < fresh.items().size(); k++) {
                PlanItem n = fresh.items().get(k);
                PlanItem o = old.get(k);
                kept.add(new PlanItem(
                        n.variantId(),
                        n.serviceId(),
                        n.staffId(),
                        n.staffName(),
                        o.staffRequested(),
                        o.serviceName(),
                        o.variantName(),
                        n.start(),
                        n.end(),
                        o.priceMinor(),
                        o.currency(),
                        o.addons(),
                        n.stages(),
                        n.units()));
            }
            Plan plan = new Plan(
                    fresh.locationId(),
                    fresh.timezone(),
                    fresh.deliveryMode(),
                    fresh.start(),
                    fresh.end(),
                    kept,
                    fresh.blocks(),
                    fresh.policy(),
                    fresh.totalMinor(),
                    fresh.currency());
            dsl.deleteFrom(APPOINTMENT_ITEMS)
                    .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                    .execute();
            writeItems(orgId, appointmentId, plan, forced);
            dsl.update(APPOINTMENTS)
                    .set(APPOINTMENTS.STARTS_AT, utc(plan.start()))
                    .set(APPOINTMENTS.ENDS_AT, utc(plan.end()))
                    .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .execute();
            history(
                    orgId,
                    appointmentId,
                    a.getStatus(),
                    a.getStatus(),
                    actorUserId,
                    "Moved by the business from " + a.getStartsAt().toInstant() + (forced ? " (overlap)" : ""));
            outbox.publish(new DomainEvent(
                    "scheduling.appointment",
                    appointmentId,
                    "appointment.rescheduled",
                    1,
                    orgId,
                    Map.of("appointmentId", appointmentId, "notify", notify)));
        });
    }

    /** A block of a person's time (break, meeting, …); refused ({@code SLOT_TAKEN}) when it overlaps their bookings. */
    UUID block(
            UUID orgId,
            UUID actorUserId,
            UUID locationId,
            UUID staffId,
            Instant start,
            Instant end,
            String reason,
            String title) {
        if (!end.isAfter(start)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A block ends after it starts.");
        }
        return transactions.execute(status -> {
            UUID id = Ids.newId();
            dsl.insertInto(CALENDAR_BLOCKS)
                    .set(CALENDAR_BLOCKS.ID, id)
                    .set(CALENDAR_BLOCKS.ORG_ID, orgId)
                    .set(CALENDAR_BLOCKS.LOCATION_ID, locationId)
                    .set(CALENDAR_BLOCKS.STAFF_ID, staffId)
                    .set(Map.of(CALENDAR_BLOCKS.DURING, range(start, end)))
                    .set(CALENDAR_BLOCKS.REASON, reason)
                    .set(CALENDAR_BLOCKS.TITLE, title == null || title.isBlank() ? null : title.strip())
                    .set(CALENDAR_BLOCKS.CREATED_BY, actorUserId)
                    .execute();
            dsl.insertInto(SCHEDULE_RESERVATIONS)
                    .set(SCHEDULE_RESERVATIONS.ORG_ID, orgId)
                    .set(SCHEDULE_RESERVATIONS.LOCATION_ID, locationId)
                    .set(SCHEDULE_RESERVATIONS.KIND, "STAFF")
                    .set(SCHEDULE_RESERVATIONS.STAFF_ID, staffId)
                    .set(Map.of(SCHEDULE_RESERVATIONS.DURING, range(start, end)))
                    .set(SCHEDULE_RESERVATIONS.STATUS, "BLOCKED")
                    .set(SCHEDULE_RESERVATIONS.CALENDAR_BLOCK_ID, id)
                    .execute(); // overlapping a booking fails here: 23P01 → SLOT_TAKEN
            blockChanged(orgId, id, locationId);
            return id;
        });
    }

    void unblock(UUID blockId) {
        transactions.executeWithoutResult(status -> {
            var block = dsl.selectFrom(CALENDAR_BLOCKS)
                    .where(CALENDAR_BLOCKS.ID.eq(blockId))
                    .fetchOne();
            if (block == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such block.");
            }
            block.delete();
            blockChanged(block.getOrgId(), blockId, block.getLocationId());
        });
    }

    /** Calendars showing the location reload (live updates). */
    private void blockChanged(UUID orgId, UUID blockId, UUID locationId) {
        outbox.publish(new DomainEvent(
                "scheduling.calendar_block",
                blockId,
                "calendar.block_changed",
                1,
                orgId,
                Map.of("blockId", blockId, "locationId", locationId)));
    }

    // --- the business answers a request ----------------------------------------------------------------------------

    /**
     * Accepts or declines a booking request ({@code REQUESTED}). Declining frees the time. Call with the business's
     * context bound; the caller checked the member may manage appointments.
     */
    String decide(UUID orgId, UUID actorUserId, UUID appointmentId, boolean accept, String reason) {
        return transactions.execute(status -> {
            var a = dsl.selectFrom(APPOINTMENTS)
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .forUpdate()
                    .fetchOne();
            if (a == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
            }
            if (!"REQUESTED".equals(a.getStatus())) {
                throw new ApiException(ProblemCode.IMMUTABLE, "Only booking requests can be accepted or declined.");
            }
            String to = accept ? "CONFIRMED" : "DECLINED";
            if (!accept) {
                releaseReservations(appointmentId);
                dsl.update(APPOINTMENT_ITEMS)
                        .set(APPOINTMENT_ITEMS.STATUS, "CANCELLED")
                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                        .execute();
            }
            dsl.update(APPOINTMENTS)
                    .set(APPOINTMENTS.STATUS, to)
                    .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .execute();
            history(orgId, appointmentId, "REQUESTED", to, actorUserId, reason);
            outbox.publish(new DomainEvent(
                    "scheduling.appointment",
                    appointmentId,
                    accept ? "appointment.accepted" : "appointment.declined",
                    1,
                    orgId,
                    Map.of("appointmentId", appointmentId)));
            return to;
        });
    }

    // --- the person's own appointments ---------------------------------------------------------------------------

    record MineItem(
            UUID variantId,
            String serviceName,
            String variantName,
            String staffName,
            Instant start,
            Instant end,
            long priceMinor,
            String currency) {}

    record Mine(
            UUID id,
            UUID locationId,
            String reference,
            String status,
            Instant start,
            Instant end,
            String timezone,
            List<MineItem> items) {}

    /**
     * Appointments the person booked or is the customer of, across businesses (row-level security limits them to the
     * person's own). Call with the person's consumer context bound.
     */
    List<Mine> mine(int limit) {
        return transactions.execute(status -> {
            var appointments = dsl.selectFrom(APPOINTMENTS)
                    .orderBy(APPOINTMENTS.STARTS_AT.desc())
                    .limit(limit)
                    .fetch();
            Map<UUID, List<MineItem>> items = new HashMap<>();
            dsl.selectFrom(APPOINTMENT_ITEMS)
                    .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.in(appointments.map(a -> a.getId())))
                    .orderBy(APPOINTMENT_ITEMS.APPOINTMENT_ID, APPOINTMENT_ITEMS.SEQ)
                    .forEach(i -> items.computeIfAbsent(i.getAppointmentId(), k -> new ArrayList<>())
                            .add(new MineItem(
                                    i.getServiceVariantId(),
                                    i.getServiceName(),
                                    i.getVariantName(),
                                    i.getStaffName(),
                                    i.getStartsAt().toInstant(),
                                    i.getEndsAt().toInstant(),
                                    i.getQuotedPriceMinor(),
                                    i.getCurrencyCode().trim())));
            return appointments.map(a -> new Mine(
                    a.getId(),
                    a.getLocationId(),
                    a.getReference(),
                    a.getStatus(),
                    a.getStartsAt().toInstant(),
                    a.getEndsAt().toInstant(),
                    a.getTimezone(),
                    items.getOrDefault(a.getId(), List.of())));
        });
    }

    /**
     * The business's record of this person: theirs if they booked before, a walk-in record with the same verified
     * phone (now linked to their account), or a new one.
     */
    UUID customer(UUID orgId, People.Person person) {
        UUID existing = dsl.select(CUSTOMERS.ID)
                .from(CUSTOMERS)
                .where(CUSTOMERS.USER_ID.eq(person.userId()), CUSTOMERS.STATUS.eq("ACTIVE"))
                .fetchOne(CUSTOMERS.ID);
        if (existing != null) {
            return notBlocked(existing);
        }
        if (person.phone() != null) {
            UUID walkIn = dsl.select(CUSTOMERS.ID)
                    .from(CUSTOMERS)
                    .where(
                            CUSTOMERS.PHONE_E164.eq(person.phone()),
                            CUSTOMERS.USER_ID.isNull(),
                            CUSTOMERS.STATUS.eq("ACTIVE"))
                    .orderBy(CUSTOMERS.FIRST_SEEN_AT)
                    .limit(1)
                    .fetchOne(CUSTOMERS.ID);
            if (walkIn != null) {
                notBlocked(walkIn);
                dsl.update(CUSTOMERS)
                        .set(CUSTOMERS.USER_ID, person.userId())
                        .where(CUSTOMERS.ID.eq(walkIn))
                        .execute();
                return walkIn;
            }
        }
        String name = person.displayName() != null
                ? person.displayName()
                : String.join(
                                " ",
                                java.util.stream.Stream.of(person.firstName(), person.lastName())
                                        .filter(Objects::nonNull)
                                        .toList())
                        .strip();
        if (name.isEmpty()) {
            name = person.phone() != null ? person.phone() : Objects.requireNonNullElse(person.email(), "Customer");
        }
        UUID id = Ids.newId();
        dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.ID, id)
                .set(CUSTOMERS.ORG_ID, orgId)
                .set(CUSTOMERS.USER_ID, person.userId())
                .set(CUSTOMERS.FIRST_NAME, person.firstName())
                .set(CUSTOMERS.LAST_NAME, person.lastName())
                .set(CUSTOMERS.DISPLAY_NAME, name)
                .set(CUSTOMERS.PHONE_E164, person.phone())
                .set(CUSTOMERS.EMAIL, person.email())
                .set(CUSTOMERS.PREFERRED_LANGUAGE, person.language())
                .set(CUSTOMERS.SOURCE, "DIRECT_LINK")
                .execute();
        return id;
    }

    /**
     * Online, a service that needs a patch test is booked only with a passed test on file that is still valid at the
     * visit (crm.patch_tests, plan step 2.6). The front desk is not held to this.
     */
    private void requirePatchTests(UUID customerId, List<UUID> variantIds, Instant at) {
        var needed = dsl.selectDistinct(SERVICES.ID)
                .from(SERVICES)
                .join(SERVICE_VARIANTS)
                .on(SERVICE_VARIANTS.SERVICE_ID.eq(SERVICES.ID))
                .where(SERVICE_VARIANTS.ID.in(variantIds), SERVICES.REQUIRES_PATCH_TEST.isTrue())
                .fetch(SERVICES.ID);
        for (UUID service : needed) {
            boolean valid = dsl.fetchExists(
                    PATCH_TESTS,
                    PATCH_TESTS
                            .CUSTOMER_ID
                            .eq(customerId)
                            .and(PATCH_TESTS.SERVICE_ID.eq(service))
                            .and(PATCH_TESTS.RESULT.eq("PASS"))
                            .and(PATCH_TESTS.VALID_UNTIL.ge(utc(at))));
            if (!valid) {
                throw new ApiException(ProblemCode.PATCH_TEST_REQUIRED);
            }
        }
    }

    /** A client the business blocked books by phone only (P-13), never online. */
    private UUID notBlocked(UUID customerId) {
        if (dsl.fetchExists(CUSTOMERS, CUSTOMERS.ID.eq(customerId).and(CUSTOMERS.IS_BLOCKED.isTrue()))) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN, "This business takes your bookings by phone. Please call them.");
        }
        return customerId;
    }

    /** A short reference people can read out over the phone ("K7M2QX"), unique in the business. */
    private String reference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder code = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                code.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
            }
            String candidate = code.toString();
            if (!dsl.fetchExists(APPOINTMENTS, APPOINTMENTS.REFERENCE.eq(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find a free booking reference");
    }

    private String pick(JSONB text, String language) {
        if (text == null) {
            return "";
        }
        Map<String, String> map = json.readValue(text.data(), TEXT);
        if (map.containsKey(language)) {
            return map.get(language);
        }
        if (map.containsKey("en")) {
            return map.get("en");
        }
        return map.values().stream().findFirst().orElse("");
    }
}
