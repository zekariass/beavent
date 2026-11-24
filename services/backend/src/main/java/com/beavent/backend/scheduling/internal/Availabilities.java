package com.beavent.backend.scheduling.internal;

import static com.beavent.db.catalog.Tables.ADDONS;
import static com.beavent.db.catalog.Tables.LOCATION_SERVICES;
import static com.beavent.db.catalog.Tables.RESOURCES;
import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_ADDONS;
import static com.beavent.db.catalog.Tables.SERVICE_RESOURCE_REQUIREMENTS;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANT_STAGES;
import static com.beavent.db.catalog.Tables.STAFF_SERVICES;
import static com.beavent.db.ref.Tables.PUBLIC_HOLIDAYS;
import static com.beavent.db.scheduling.Tables.SCHEDULE_RESERVATIONS;
import static com.beavent.db.team.Tables.STAFF;
import static com.beavent.db.team.Tables.STAFF_LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATION_CLOSURES;
import static com.beavent.db.tenant.Tables.LOCATION_HOURS;
import static com.beavent.db.tenant.Tables.PLACES;
import static com.beavent.db.tenant.Tables.SERVICE_AREAS;

import com.beavent.backend.catalog.ServicePricing;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.scheduling.Availability;
import com.beavent.backend.scheduling.BookingPolicies;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Interval;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Requirement;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Resource;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.ResourceUnit;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Stage;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.StageKind;
import com.beavent.backend.team.Rota;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the calendar facts around a location from the database and asks the {@link AvailabilityEngine} which times
 * can be booked (docs/08 §2). Everything is read in one transaction in the business's context (row-level security),
 * so one request sees one consistent calendar.
 */
@Component
class Availabilities implements Availability {

    static final int MAX_RANGE_DAYS = 14;
    static final int MAX_ITEMS = 6;

    private static final Field<OffsetDateTime> RES_START =
            DSL.field("lower({0})", OffsetDateTime.class, SCHEDULE_RESERVATIONS.DURING);
    private static final Field<OffsetDateTime> RES_END =
            DSL.field("upper({0})", OffsetDateTime.class, SCHEDULE_RESERVATIONS.DURING);
    private static final Field<OffsetDateTime> CLOSED_START =
            DSL.field("lower({0})", OffsetDateTime.class, LOCATION_CLOSURES.CLOSED_DURING);
    private static final Field<OffsetDateTime> CLOSED_END =
            DSL.field("upper({0})", OffsetDateTime.class, LOCATION_CLOSURES.CLOSED_DURING);

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final BookingPolicies policies;
    private final Rota rota;
    private final ServicePricing pricing;

    Availabilities(
            DSLContext dsl,
            TransactionTemplate transactions,
            BookingPolicies policies,
            Rota rota,
            ServicePricing pricing) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.policies = policies;
        this.rota = rota;
        this.pricing = pricing;
    }

    record Place(ZoneId zone, String serviceMode, boolean closedOnHolidays, String countryCode, UUID areaId) {}

    /** One requested service, ready for the engine. */
    record Planned(
            UUID variantId,
            UUID serviceId,
            List<UUID> addonIds,
            boolean staffRequested,
            AvailabilityEngine.Item engineItem,
            List<UUID> staff) {}

    /** Everything one question needs: what each item is, and the engine's request and facts. */
    record Question(
            Query query,
            Place place,
            int travelMinutes,
            List<Planned> planned,
            AvailabilityEngine.Request request,
            AvailabilityEngine.Facts facts) {}

    /** A bookable slot, laid out, with the question that found it. */
    record Chosen(Question question, AvailabilityEngine.Slot slot, List<AvailabilityEngine.ItemLayout> layout) {}

    @Override
    public Result find(Query query, Instant now) {
        return transactions.execute(status -> {
            Question q = question(query, now, false);
            List<Offer> offers = new ArrayList<>();
            for (AvailabilityEngine.Slot slot : AvailabilityEngine.slots(q.request(), q.facts())) {
                List<Assigned> assigned = new ArrayList<>();
                for (int i = 0; i < q.planned().size(); i++) {
                    assigned.add(new Assigned(
                            q.planned().get(i).variantId(),
                            slot.assignments().get(i).staffId()));
                }
                offers.add(new Offer(slot.start(), slot.end(), assigned));
            }
            return new Result(q.place().zone(), offers);
        });
    }

    /**
     * The slot that starts exactly at {@code start}, if it can be booked now. Runs in the caller's transaction, so a
     * booking checks and writes against one view of the calendar (the database constraints still decide races).
     */
    Optional<Chosen> at(UUID locationId, List<Item> items, UUID serviceAreaId, Instant start, Instant now) {
        return at(locationId, items, serviceAreaId, start, now, false);
    }

    /**
     * Like {@link #at(UUID, List, UUID, Instant, Instant)}; {@code staffSide} applies the front desk's rules: any
     * five-minute start, no notice or horizon, services hidden from online booking and people not bookable online
     * included (working time, opening hours and the calendar still apply).
     */
    Optional<Chosen> at(
            UUID locationId, List<Item> items, UUID serviceAreaId, Instant start, Instant now, boolean staffSide) {
        LocalDate date = start.atZone(place(locationId).zone()).toLocalDate();
        Question q = question(new Query(locationId, items, date, date, serviceAreaId), now, staffSide);
        return AvailabilityEngine.slots(q.request(), q.facts()).stream()
                .filter(slot -> slot.start().equals(start))
                .findFirst()
                .map(slot -> new Chosen(q, slot, AvailabilityEngine.layout(q.request(), slot)));
    }

    /**
     * The front desk's "book anyway": lays the items out at {@code start} with the chosen people without asking whether
     * the time is free (the reservations are written as deliberate overlaps). Resources are not held.
     */
    Chosen forced(UUID locationId, List<Item> items, Instant start, Instant now) {
        for (Item item : items) {
            if (item.staffId() == null) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED, "Choose a person for each service to book anyway.");
            }
        }
        LocalDate date = start.atZone(place(locationId).zone()).toLocalDate();
        Question q = question(new Query(locationId, items, date, date, null), now, true);
        List<AvailabilityEngine.Item> bare = q.planned().stream()
                .map(p -> new AvailabilityEngine.Item(
                        p.engineItem().stages(),
                        p.engineItem().bufferBeforeMinutes(),
                        p.engineItem().bufferAfterMinutes(),
                        p.engineItem().candidateStaff(),
                        List.of()))
                .toList();
        AvailabilityEngine.Request r = q.request();
        AvailabilityEngine.Request request = new AvailabilityEngine.Request(
                bare, r.from(), r.to(), r.zone(), r.now(), 0, 3650, 5, r.onPremises(), r.travelMinutes());
        List<AvailabilityEngine.Assignment> assignments = items.stream()
                .map(i -> new AvailabilityEngine.Assignment(i.staffId(), List.of()))
                .toList();
        AvailabilityEngine.Slot probe = new AvailabilityEngine.Slot(start, start, assignments);
        List<AvailabilityEngine.ItemLayout> layout = AvailabilityEngine.layout(request, probe);
        AvailabilityEngine.Slot slot =
                new AvailabilityEngine.Slot(start, layout.getLast().end(), assignments);
        Question forcedQuestion =
                new Question(q.query(), q.place(), q.travelMinutes(), q.planned(), request, q.facts());
        return new Chosen(forcedQuestion, slot, layout);
    }

    private Question question(Query query, Instant now, boolean staffSide) {
        if (query.items().isEmpty() || query.items().size() > MAX_ITEMS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose between 1 and " + MAX_ITEMS + " services.");
        }
        if (query.to().isBefore(query.from())
                || Duration.between(
                                        query.from().atStartOfDay(),
                                        query.to().plusDays(1).atStartOfDay())
                                .toDays()
                        > MAX_RANGE_DAYS) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "Ask for at most " + MAX_RANGE_DAYS + " days at a time.");
        }
        Place place = place(query.locationId());
        boolean onPremises = query.serviceAreaId() == null;
        int travelMinutes = onPremises ? 0 : travelMinutes(query.locationId(), query.serviceAreaId());

        // The grid and the weekday are in the location's zone; reservations are looked up a day around the range.
        ZoneId zone = place.zone();
        Instant windowStart = query.from().minusDays(1).atStartOfDay(zone).toInstant();
        Instant windowEnd = query.to().plusDays(2).atStartOfDay(zone).toInstant();

        List<Planned> planned = new ArrayList<>();
        int minNotice = 0;
        int maxAdvance = Integer.MAX_VALUE;
        int slotInterval = 5;
        for (Item item : query.items()) {
            Planned p = plan(item, query, place, onPremises, now, staffSide);
            planned.add(p);
            if (!staffSide) {
                BookingPolicies.Policy policy = policies.effective(query.locationId(), p.serviceId());
                minNotice = Math.max(minNotice, policy.minNoticeMinutes());
                maxAdvance = Math.min(maxAdvance, policy.maxAdvanceDays());
                slotInterval = Math.max(slotInterval, policy.slotIntervalMinutes());
            }
        }
        if (staffSide) {
            maxAdvance = 3650; // the front desk may book far ahead
        }

        Set<UUID> everyone = new LinkedHashSet<>();
        planned.forEach(p -> everyone.addAll(p.staff()));
        AvailabilityEngine.Facts facts = facts(query, place, everyone, windowStart, windowEnd, now, onPremises);

        AvailabilityEngine.Request request = new AvailabilityEngine.Request(
                planned.stream().map(Planned::engineItem).toList(),
                query.from(),
                query.to(),
                zone,
                now,
                minNotice,
                maxAdvance,
                slotInterval,
                onPremises,
                travelMinutes);
        return new Question(query, place, travelMinutes, planned, request, facts);
    }

    // --- the location ----------------------------------------------------------------------------------------

    Place place(UUID locationId) {
        return dsl.select(
                        LOCATIONS.TIMEZONE,
                        LOCATIONS.SERVICE_MODE,
                        LOCATIONS.CLOSED_ON_PUBLIC_HOLIDAYS,
                        PLACES.COUNTRY_CODE,
                        PLACES.ADMIN_AREA_ID)
                .from(LOCATIONS)
                .join(PLACES)
                .on(PLACES.ID.eq(LOCATIONS.PLACE_ID))
                .where(LOCATIONS.ID.eq(locationId), LOCATIONS.STATUS.eq("ACTIVE"), LOCATIONS.ONLINE_BOOKING.isTrue())
                .fetchOptional(r -> new Place(
                        ZoneId.of(r.value1()),
                        r.value2(),
                        r.value3(),
                        r.value4().trim(),
                        r.value5()))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This location does not take bookings."));
    }

    private int travelMinutes(UUID locationId, UUID serviceAreaId) {
        return dsl.select(SERVICE_AREAS.TRAVEL_MINUTES)
                .from(SERVICE_AREAS)
                .where(
                        SERVICE_AREAS.ID.eq(serviceAreaId),
                        SERVICE_AREAS.LOCATION_ID.eq(locationId),
                        SERVICE_AREAS.IS_ACTIVE.isTrue())
                .fetchOptional(r -> (int) r.value1())
                .orElseThrow(() ->
                        new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "This business does not visit that area."));
    }

    // --- one requested service -------------------------------------------------------------------------------

    private Planned plan(Item item, Query query, Place place, boolean onPremises, Instant now, boolean staffSide) {
        UUID locationId = query.locationId();
        var row = dsl.select(
                        SERVICES.ID,
                        SERVICES.DELIVERY_MODES,
                        SERVICE_VARIANTS.BUFFER_BEFORE_MIN,
                        SERVICE_VARIANTS.BUFFER_AFTER_MIN)
                .from(SERVICE_VARIANTS)
                .join(SERVICES)
                .on(SERVICES.ID.eq(SERVICE_VARIANTS.SERVICE_ID))
                .where(
                        SERVICE_VARIANTS.ID.eq(item.variantId()),
                        SERVICE_VARIANTS.STATUS.eq("ACTIVE"),
                        SERVICES.STATUS.eq("ACTIVE"),
                        staffSide ? DSL.noCondition() : SERVICES.ONLINE_BOOKING.isTrue(),
                        DSL.exists(DSL.selectOne()
                                .from(LOCATION_SERVICES)
                                .where(
                                        LOCATION_SERVICES.SERVICE_ID.eq(SERVICES.ID),
                                        LOCATION_SERVICES.LOCATION_ID.eq(locationId))))
                .fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "That service is not bookable here.");
        }
        UUID serviceId = row.value1();
        List<String> modes = Arrays.asList(row.value2());
        boolean venue = !place.serviceMode().equals("MOBILE_ONLY");
        boolean visits = !place.serviceMode().equals("ON_PREMISES");
        if (onPremises ? !(modes.contains("ON_PREMISES") && venue) : !(modes.contains("AT_CUSTOMER") && visits)) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION,
                    onPremises ? "That service is not offered at the venue." : "That service is not offered at home.");
        }

        List<Stage> stages = dsl.selectFrom(SERVICE_VARIANT_STAGES)
                .where(SERVICE_VARIANT_STAGES.VARIANT_ID.eq(item.variantId()))
                .orderBy(SERVICE_VARIANT_STAGES.SEQ)
                .fetch(s -> new Stage(StageKind.valueOf(s.getKind()), s.getDurationMinutes(), s.getStaffBusy()));
        int stagesMinutes = stages.stream().mapToInt(Stage::minutes).sum();

        int addonMinutes = addonMinutes(serviceId, item);
        List<Requirement> requirements = dsl.selectFrom(SERVICE_RESOURCE_REQUIREMENTS)
                .where(SERVICE_RESOURCE_REQUIREMENTS.VARIANT_ID.eq(item.variantId()))
                .orderBy(SERVICE_RESOURCE_REQUIREMENTS.ID)
                .fetch(r -> new Requirement(
                        r.getResourceId(),
                        r.getResourceKind(),
                        r.getUnits(),
                        r.getStageSeq() == null ? null : (int) r.getStageSeq()));

        List<UUID> candidates = candidates(serviceId, locationId, query, staffSide);
        if (item.staffId() != null && !candidates.contains(item.staffId())) {
            throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "That person does not offer this service here.");
        }
        // Per-person prices can carry a different duration. The engine plans one duration per service, so "anyone"
        // means the people whose duration is the location's; a chosen person is planned with their own.
        int base = pricing.resolve(item.variantId(), locationId, null, now).durationMinutes();
        List<UUID> usable = new ArrayList<>();
        int minutes = base;
        if (item.staffId() != null) {
            minutes = pricing.resolve(item.variantId(), locationId, item.staffId(), now)
                    .durationMinutes();
            usable.add(item.staffId());
        } else {
            for (UUID staffId : candidates) {
                if (pricing.resolve(item.variantId(), locationId, staffId, now).durationMinutes() == base) {
                    usable.add(staffId);
                }
            }
        }
        List<Stage> shape = stages.isEmpty() || stagesMinutes != minutes
                ? new ArrayList<>(List.of(new Stage(StageKind.SERVICE, minutes, true)))
                : new ArrayList<>(stages);
        if (addonMinutes > 0) {
            shape.add(new Stage(StageKind.SERVICE, addonMinutes, true));
        }
        return new Planned(
                item.variantId(),
                serviceId,
                item.addonIds(),
                item.staffId() != null,
                new AvailabilityEngine.Item(shape, row.value3(), row.value4(), usable, requirements),
                usable);
    }

    private int addonMinutes(UUID serviceId, Item item) {
        Set<UUID> wanted = new HashSet<>(item.addonIds());
        if (wanted.isEmpty()) {
            return 0;
        }
        Map<UUID, Integer> found = dsl.select(ADDONS.ID, ADDONS.DURATION_MINUTES)
                .from(ADDONS)
                .join(SERVICE_ADDONS)
                .on(SERVICE_ADDONS.ADDON_ID.eq(ADDONS.ID), SERVICE_ADDONS.SERVICE_ID.eq(serviceId))
                .where(ADDONS.ID.in(wanted), ADDONS.STATUS.eq("ACTIVE"))
                .fetchMap(ADDONS.ID, r -> (int) r.get(ADDONS.DURATION_MINUTES));
        if (found.size() != wanted.size()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "An add-on is not offered with that service.");
        }
        return found.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** People who do the service here and can be booked online, in the order that breaks ties between them. */
    private List<UUID> candidates(UUID serviceId, UUID locationId, Query query, boolean staffSide) {
        Condition employed = STAFF.STARTED_ON
                .isNull()
                .or(STAFF.STARTED_ON.le(query.to()))
                .and(STAFF.ENDED_ON.isNull().or(STAFF.ENDED_ON.ge(query.from())));
        return dsl.select(STAFF.ID)
                .from(STAFF)
                .join(STAFF_SERVICES)
                .on(STAFF_SERVICES.STAFF_ID.eq(STAFF.ID), STAFF_SERVICES.SERVICE_ID.eq(serviceId))
                .join(STAFF_LOCATIONS)
                .on(STAFF_LOCATIONS.STAFF_ID.eq(STAFF.ID), STAFF_LOCATIONS.LOCATION_ID.eq(locationId))
                .where(
                        STAFF.STATUS.eq("ACTIVE"),
                        STAFF.IS_BOOKABLE.isTrue(),
                        staffSide ? DSL.noCondition() : STAFF.BOOKABLE_ONLINE.isTrue(),
                        employed)
                .orderBy(STAFF.SORT_ORDER, STAFF.DISPLAY_NAME, STAFF.ID)
                .fetch(STAFF.ID);
    }

    // --- the calendar ------------------------------------------------------------------------------------------

    private AvailabilityEngine.Facts facts(
            Query query,
            Place place,
            Set<UUID> staff,
            Instant windowStart,
            Instant windowEnd,
            Instant now,
            boolean onPremises) {
        Map<UUID, List<Interval>> working = new HashMap<>();
        if (!staff.isEmpty()) {
            for (Rota.Day day : rota.schedule(
                    staff,
                    query.locationId(),
                    query.from().minusDays(1),
                    query.to().plusDays(1))) {
                for (Rota.Interval i : day.intervals()) {
                    working.computeIfAbsent(day.staffId(), k -> new ArrayList<>())
                            .add(new Interval(i.startsAt(), i.endsAt()));
                }
            }
        }
        List<Resource> resources = dsl.select(RESOURCES.ID, RESOURCES.KIND, RESOURCES.UNITS)
                .from(RESOURCES)
                .where(RESOURCES.LOCATION_ID.eq(query.locationId()), RESOURCES.IS_ACTIVE.isTrue())
                .orderBy(RESOURCES.ID)
                .fetch(r -> new Resource(r.value1(), r.value2(), r.value3()));

        Map<UUID, List<Interval>> staffReserved = new HashMap<>();
        Map<ResourceUnit, List<Interval>> resourceReserved = new HashMap<>();
        Set<UUID> resourceIds = new HashSet<>();
        resources.forEach(r -> resourceIds.add(r.id()));
        Condition mine = SCHEDULE_RESERVATIONS
                .KIND
                .eq("STAFF")
                .and(SCHEDULE_RESERVATIONS.STAFF_ID.in(staff))
                .or(SCHEDULE_RESERVATIONS.KIND.eq("RESOURCE").and(SCHEDULE_RESERVATIONS.RESOURCE_ID.in(resourceIds)));
        // Live reservations only: a hold that has run out of time no longer blocks (the database still counts it until
        // the expiry job releases it, which booking handles; here it just must not hide a free time).
        Condition live = SCHEDULE_RESERVATIONS
                .STATUS
                .in("CONFIRMED", "BLOCKED")
                .or(SCHEDULE_RESERVATIONS
                        .STATUS
                        .eq("HELD")
                        .and(SCHEDULE_RESERVATIONS.HOLD_EXPIRES_AT.gt(now.atOffset(java.time.ZoneOffset.UTC))));
        if (!staff.isEmpty() || !resourceIds.isEmpty()) {
            dsl.select(
                            SCHEDULE_RESERVATIONS.STAFF_ID,
                            SCHEDULE_RESERVATIONS.RESOURCE_ID,
                            SCHEDULE_RESERVATIONS.RESOURCE_UNIT,
                            RES_START,
                            RES_END)
                    .from(SCHEDULE_RESERVATIONS)
                    .where(
                            mine,
                            live,
                            DSL.condition(
                                    "{0} && tstzrange({1}, {2})",
                                    SCHEDULE_RESERVATIONS.DURING,
                                    DSL.val(windowStart.atOffset(java.time.ZoneOffset.UTC)),
                                    DSL.val(windowEnd.atOffset(java.time.ZoneOffset.UTC))))
                    .forEach(r -> {
                        Interval interval =
                                new Interval(r.value4().toInstant(), r.value5().toInstant());
                        if (r.value1() != null) {
                            staffReserved
                                    .computeIfAbsent(r.value1(), k -> new ArrayList<>())
                                    .add(interval);
                        } else {
                            resourceReserved
                                    .computeIfAbsent(new ResourceUnit(r.value2(), r.value3()), k -> new ArrayList<>())
                                    .add(interval);
                        }
                    });
        }
        List<Interval> open = onPremises ? openTime(query, place, windowStart, windowEnd) : List.of();
        return new AvailabilityEngine.Facts(working, staffReserved, open, resources, resourceReserved);
    }

    /** Opening hours of each local day in the range, minus public holidays (when the location closes on them) and closures. */
    private List<Interval> openTime(Query query, Place place, Instant windowStart, Instant windowEnd) {
        Map<Integer, List<LocalHours>> hours = new HashMap<>();
        dsl.select(LOCATION_HOURS.ISO_WEEKDAY, LOCATION_HOURS.OPENS_AT, LOCATION_HOURS.CLOSES_AT)
                .from(LOCATION_HOURS)
                .where(LOCATION_HOURS.LOCATION_ID.eq(query.locationId()))
                .forEach(r -> hours.computeIfAbsent((int) r.value1(), k -> new ArrayList<>())
                        .add(new LocalHours(r.value2(), r.value3())));
        LocalDate first = query.from().minusDays(1);
        LocalDate last = query.to().plusDays(1);
        Set<LocalDate> holidays = new HashSet<>();
        if (place.closedOnHolidays()) {
            dsl.select(PUBLIC_HOLIDAYS.HOLIDAY_DATE)
                    .from(PUBLIC_HOLIDAYS)
                    .where(
                            PUBLIC_HOLIDAYS.COUNTRY_CODE.eq(place.countryCode()),
                            PUBLIC_HOLIDAYS.HOLIDAY_DATE.between(first, last),
                            place.areaId() == null
                                    ? PUBLIC_HOLIDAYS.ADMIN_AREA_ID.isNull()
                                    : PUBLIC_HOLIDAYS
                                            .ADMIN_AREA_ID
                                            .isNull()
                                            .or(PUBLIC_HOLIDAYS.ADMIN_AREA_ID.eq(place.areaId())))
                    .forEach(r -> holidays.add(r.value1()));
        }
        List<Interval> open = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (holidays.contains(date)) {
                continue;
            }
            for (LocalHours h : hours.getOrDefault(date.getDayOfWeek().getValue(), List.of())) {
                open.add(new Interval(
                        ZonedDateTime.of(date, h.opens(), place.zone()).toInstant(),
                        ZonedDateTime.of(date, h.closes(), place.zone()).toInstant()));
            }
        }
        List<Interval> closures = new ArrayList<>();
        dsl.select(CLOSED_START, CLOSED_END)
                .from(LOCATION_CLOSURES)
                .where(
                        LOCATION_CLOSURES.LOCATION_ID.eq(query.locationId()),
                        DSL.condition(
                                "{0} && tstzrange({1}, {2})",
                                LOCATION_CLOSURES.CLOSED_DURING,
                                DSL.val(windowStart.atOffset(java.time.ZoneOffset.UTC)),
                                DSL.val(windowEnd.atOffset(java.time.ZoneOffset.UTC))))
                .forEach(r -> closures.add(new Interval(
                        r.value1() == null ? windowStart : r.value1().toInstant(),
                        r.value2() == null ? windowEnd : r.value2().toInstant())));
        return Intervals.minus(open, closures);
    }

    private record LocalHours(java.time.LocalTime opens, java.time.LocalTime closes) {}
}
