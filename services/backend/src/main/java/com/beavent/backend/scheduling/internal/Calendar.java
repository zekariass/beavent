package com.beavent.backend.scheduling.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEM_STAGES;
import static com.beavent.db.scheduling.Tables.CALENDAR_BLOCKS;
import static com.beavent.db.team.Tables.STAFF;
import static com.beavent.db.team.Tables.STAFF_LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.team.Rota;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The business's calendar (plan step 2.5, P-12 / B-1 / B-2): for a location and a few days, each team member's
 * working time, the appointments with their stages (processing time drawn lighter), and blocks. Also the front desk's
 * status changes (arrived, started, completed, no-show).
 *
 * <p>Who sees what follows the roles: {@code calendar.view_all} sees everyone at the location, {@code
 * calendar.view_own} only their own column. Phone numbers need {@code customers.contact_details}.
 */
@Component
public class Calendar {

    static final int MAX_DAYS = 7;
    static final String VIEW_ALL = "calendar.view_all";
    static final String VIEW_OWN = "calendar.view_own";
    static final String MANAGE = "appointments.manage";
    static final String MANAGE_OWN = "appointments.manage_own";
    static final String CONTACT = "customers.contact_details";
    /** Appointments that are not on the calendar any more. */
    static final List<String> OFF_CALENDAR = List.of("CANCELLED", "EXPIRED", "DECLINED");

    private static final Field<OffsetDateTime> STAGE_START =
            DSL.field("lower({0})", OffsetDateTime.class, APPOINTMENT_ITEM_STAGES.DURING);
    private static final Field<OffsetDateTime> STAGE_END =
            DSL.field("upper({0})", OffsetDateTime.class, APPOINTMENT_ITEM_STAGES.DURING);
    private static final Field<OffsetDateTime> BLOCK_START =
            DSL.field("lower({0})", OffsetDateTime.class, CALENDAR_BLOCKS.DURING);
    private static final Field<OffsetDateTime> BLOCK_END =
            DSL.field("upper({0})", OffsetDateTime.class, CALENDAR_BLOCKS.DURING);

    public record Span(Instant start, Instant end) {}

    public record Column(UUID staffId, String name, String color, List<Span> working) {}

    public record StageBody(String kind, boolean staffBusy, Instant start, Instant end) {}

    /**
     * @param serviceName as booked (the business's language then)
     * @param names the service's and option's current names per language, for the reader's language
     */
    public record ItemBody(
            UUID id,
            UUID staffId,
            String serviceName,
            String variantName,
            Map<String, Map<String, String>> names,
            Instant start,
            Instant end,
            String status,
            List<StageBody> stages) {}

    public record CustomerBody(UUID id, String name, String phone) {}

    public record AppointmentBody(
            UUID id,
            String reference,
            String status,
            String source,
            Instant start,
            Instant end,
            String note,
            CustomerBody customer,
            List<ItemBody> items) {}

    public record BlockBody(
            UUID id, UUID staffId, UUID resourceId, Instant start, Instant end, String reason, String title) {}

    public record Body(
            UUID locationId,
            String timezone,
            LocalDate from,
            LocalDate to,
            List<Column> staff,
            List<AppointmentBody> appointments,
            List<BlockBody> blocks) {}

    private static final tools.jackson.core.type.TypeReference<Map<String, String>> TEXT =
            new tools.jackson.core.type.TypeReference<>() {};

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Rota rota;
    private final Outbox outbox;
    private final tools.jackson.databind.json.JsonMapper json;

    Calendar(
            DSLContext dsl,
            TransactionTemplate transactions,
            Rota rota,
            Outbox outbox,
            tools.jackson.databind.json.JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.rota = rota;
        this.outbox = outbox;
        this.json = json;
    }

    private Map<String, String> text(org.jooq.JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), TEXT);
    }

    /** The calendar of a location from {@code from} to {@code to} (local dates, at most a week). */
    public Body read(OrgAccess access, UUID locationId, LocalDate from, LocalDate to) {
        boolean all = access.can(VIEW_ALL);
        if (!all && !access.can(VIEW_OWN)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Your role does not show the calendar.");
        }
        if (!access.canAccessLocation(locationId)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such location.");
        }
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to.plusDays(1))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Ask for one to " + MAX_DAYS + " days.");
        }
        return transactions.execute(status -> {
            String timezone = dsl.select(LOCATIONS.TIMEZONE)
                    .from(LOCATIONS)
                    .where(LOCATIONS.ID.eq(locationId))
                    .fetchOptional(LOCATIONS.TIMEZONE)
                    .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such location."));
            ZoneId zone = ZoneId.of(timezone);
            OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
            OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

            Condition who = STAFF.STATUS.eq("ACTIVE").and(STAFF.IS_BOOKABLE.isTrue());
            if (!all) {
                who = who.and(STAFF.MEMBER_ID.eq(access.memberId()));
            }
            var people = dsl.select(STAFF.ID, STAFF.DISPLAY_NAME, STAFF.CALENDAR_COLOR)
                    .from(STAFF)
                    .join(STAFF_LOCATIONS)
                    .on(STAFF_LOCATIONS.STAFF_ID.eq(STAFF.ID), STAFF_LOCATIONS.LOCATION_ID.eq(locationId))
                    .where(who)
                    .orderBy(STAFF.SORT_ORDER, STAFF.DISPLAY_NAME, STAFF.ID)
                    .fetch();
            Set<UUID> staffIds = new HashSet<>(people.map(r -> r.value1()));
            Map<UUID, List<Span>> working = new HashMap<>();
            if (!staffIds.isEmpty()) {
                for (Rota.Day day : rota.schedule(staffIds, locationId, from, to)) {
                    for (Rota.Interval i : day.intervals()) {
                        working.computeIfAbsent(day.staffId(), k -> new ArrayList<>())
                                .add(new Span(i.startsAt(), i.endsAt()));
                    }
                }
            }
            List<Column> columns = people.map(
                    r -> new Column(r.value1(), r.value2(), r.value3(), working.getOrDefault(r.value1(), List.of())));

            Condition appointmentWhere = APPOINTMENTS
                    .LOCATION_ID
                    .eq(locationId)
                    .and(APPOINTMENTS.STATUS.notIn(OFF_CALENDAR))
                    .and(APPOINTMENTS.STARTS_AT.lt(end))
                    .and(APPOINTMENTS.ENDS_AT.gt(start));
            if (!all) {
                appointmentWhere = appointmentWhere.and(DSL.exists(DSL.selectOne()
                        .from(APPOINTMENT_ITEMS)
                        .where(
                                APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(APPOINTMENTS.ID),
                                APPOINTMENT_ITEMS.STAFF_ID.in(staffIds))));
            }
            var appointments = dsl.select(
                            APPOINTMENTS.ID,
                            APPOINTMENTS.REFERENCE,
                            APPOINTMENTS.STATUS,
                            APPOINTMENTS.SOURCE,
                            APPOINTMENTS.STARTS_AT,
                            APPOINTMENTS.ENDS_AT,
                            APPOINTMENTS.CUSTOMER_NOTE,
                            CUSTOMERS.ID,
                            CUSTOMERS.DISPLAY_NAME,
                            CUSTOMERS.PHONE_E164)
                    .from(APPOINTMENTS)
                    .join(CUSTOMERS)
                    .on(CUSTOMERS.ID.eq(APPOINTMENTS.CUSTOMER_ID))
                    .where(appointmentWhere)
                    .orderBy(APPOINTMENTS.STARTS_AT, APPOINTMENTS.ID)
                    .fetch();
            List<UUID> ids = appointments.map(r -> r.value1());
            Map<UUID, List<StageBody>> stages = new HashMap<>();
            dsl.select(
                            APPOINTMENT_ITEM_STAGES.APPOINTMENT_ITEM_ID,
                            APPOINTMENT_ITEM_STAGES.KIND,
                            APPOINTMENT_ITEM_STAGES.STAFF_BUSY,
                            STAGE_START,
                            STAGE_END)
                    .from(APPOINTMENT_ITEM_STAGES)
                    .join(APPOINTMENT_ITEMS)
                    .on(APPOINTMENT_ITEMS.ID.eq(APPOINTMENT_ITEM_STAGES.APPOINTMENT_ITEM_ID))
                    .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.in(ids))
                    .orderBy(APPOINTMENT_ITEM_STAGES.APPOINTMENT_ITEM_ID, APPOINTMENT_ITEM_STAGES.SEQ)
                    .forEach(r -> stages.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                            .add(new StageBody(
                                    r.value2(),
                                    r.value3(),
                                    r.value4().toInstant(),
                                    r.value5().toInstant())));
            // Current menu names per language (the booked name is a snapshot in the business's language).
            Map<UUID, Map<String, Map<String, String>>> menuNames = new HashMap<>();
            dsl.select(SERVICE_VARIANTS.ID, SERVICES.NAME_I18N, SERVICE_VARIANTS.NAME_I18N)
                    .from(SERVICE_VARIANTS)
                    .join(SERVICES)
                    .on(SERVICES.ID.eq(SERVICE_VARIANTS.SERVICE_ID))
                    .where(SERVICE_VARIANTS.ID.in(DSL.select(APPOINTMENT_ITEMS.SERVICE_VARIANT_ID)
                            .from(APPOINTMENT_ITEMS)
                            .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.in(ids))))
                    .forEach(r -> menuNames.put(
                            r.value1(), Map.of("service", text(r.value2()), "variant", text(r.value3()))));
            Map<UUID, List<ItemBody>> items = new HashMap<>();
            dsl.selectFrom(APPOINTMENT_ITEMS)
                    .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.in(ids))
                    .orderBy(APPOINTMENT_ITEMS.APPOINTMENT_ID, APPOINTMENT_ITEMS.SEQ)
                    .forEach(i -> items.computeIfAbsent(i.getAppointmentId(), k -> new ArrayList<>())
                            .add(new ItemBody(
                                    i.getId(),
                                    i.getStaffId(),
                                    i.getServiceName(),
                                    i.getVariantName(),
                                    menuNames.getOrDefault(i.getServiceVariantId(), Map.of()),
                                    i.getStartsAt().toInstant(),
                                    i.getEndsAt().toInstant(),
                                    i.getStatus(),
                                    stages.getOrDefault(i.getId(), List.of()))));
            boolean contact = access.can(CONTACT);
            List<AppointmentBody> appointmentBodies = appointments.map(r -> new AppointmentBody(
                    r.value1(),
                    r.value2(),
                    r.value3(),
                    r.value4(),
                    r.value5().toInstant(),
                    r.value6().toInstant(),
                    r.value7(),
                    new CustomerBody(r.value8(), r.value9(), contact ? r.value10() : null),
                    items.getOrDefault(r.value1(), List.of())));

            Condition blockWhere = CALENDAR_BLOCKS
                    .LOCATION_ID
                    .eq(locationId)
                    .and(DSL.condition(
                            "{0} && tstzrange({1}, {2})", CALENDAR_BLOCKS.DURING, DSL.val(start), DSL.val(end)));
            if (!all) {
                blockWhere = blockWhere.and(CALENDAR_BLOCKS.STAFF_ID.in(staffIds));
            }
            List<BlockBody> blocks = dsl.select(
                            CALENDAR_BLOCKS.ID,
                            CALENDAR_BLOCKS.STAFF_ID,
                            CALENDAR_BLOCKS.RESOURCE_ID,
                            BLOCK_START,
                            BLOCK_END,
                            CALENDAR_BLOCKS.REASON,
                            CALENDAR_BLOCKS.TITLE)
                    .from(CALENDAR_BLOCKS)
                    .where(blockWhere)
                    .orderBy(BLOCK_START)
                    .fetch(r -> new BlockBody(
                            r.value1(),
                            r.value2(),
                            r.value3(),
                            r.value4().toInstant(),
                            r.value5().toInstant(),
                            r.value6(),
                            r.value7()));
            return new Body(locationId, timezone, from, to, columns, appointmentBodies, blocks);
        });
    }

    // --- who may change whose time --------------------------------------------------------------------------

    /**
     * The front desk ({@code appointments.manage}) may book, move and block for anyone; a professional ({@code
     * appointments.manage_own}) only in their own column.
     */
    public void requireDesk(OrgAccess access, java.util.Collection<UUID> staffIds) {
        if (access.can(MANAGE)) {
            return;
        }
        boolean own = access.can(MANAGE_OWN)
                && transactions.execute(status ->
                        dsl.fetchCount(STAFF, STAFF.ID.in(staffIds).and(STAFF.MEMBER_ID.eq(access.memberId())))
                                == new HashSet<>(staffIds).size());
        if (!own) {
            throw new ApiException(ProblemCode.FORBIDDEN, "You can change only your own calendar.");
        }
    }

    /** The people an appointment is with (for {@link #requireDesk}). */
    public List<UUID> staffOf(UUID appointmentId) {
        return transactions.execute(status -> dsl.selectDistinct(APPOINTMENT_ITEMS.STAFF_ID)
                .from(APPOINTMENT_ITEMS)
                .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId), APPOINTMENT_ITEMS.STAFF_ID.isNotNull())
                .fetch(APPOINTMENT_ITEMS.STAFF_ID));
    }

    /** The person a block is for. */
    public UUID staffOfBlock(UUID blockId) {
        return transactions.execute(status -> dsl.select(CALENDAR_BLOCKS.STAFF_ID)
                .from(CALENDAR_BLOCKS)
                .where(CALENDAR_BLOCKS.ID.eq(blockId))
                .fetchOptional(CALENDAR_BLOCKS.STAFF_ID)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such block.")));
    }

    public record CustomerHit(UUID id, String name, String phone) {}

    /** Customers whose name or phone matches {@code query} (the front desk picking who a booking is for). */
    public List<CustomerHit> findCustomers(OrgAccess access, String query) {
        access.require("customers.view");
        String q = query == null ? "" : query.strip();
        if (q.length() < 2) {
            return List.of();
        }
        boolean contact = access.can(CONTACT);
        String digits = q.replaceAll("[^0-9]", "");
        return transactions.execute(status -> dsl.select(CUSTOMERS.ID, CUSTOMERS.DISPLAY_NAME, CUSTOMERS.PHONE_E164)
                .from(CUSTOMERS)
                .where(
                        CUSTOMERS.STATUS.eq("ACTIVE"),
                        CUSTOMERS
                                .DISPLAY_NAME
                                .containsIgnoreCase(q)
                                .or(
                                        digits.length() >= 4
                                                ? CUSTOMERS.PHONE_E164.contains(digits.replaceFirst("^0", ""))
                                                : DSL.falseCondition()))
                .orderBy(CUSTOMERS.LAST_VISIT_AT.desc().nullsLast(), CUSTOMERS.DISPLAY_NAME)
                .limit(20)
                .fetch(r -> new CustomerHit(r.value1(), r.value2(), contact ? r.value3() : null)));
    }

    // --- status at the front desk ----------------------------------------------------------------------------

    /** Allowed moves from each status (undoing a mistake goes through the history, not here). */
    private static final Map<String, Set<String>> NEXT = Map.of(
            "CONFIRMED", Set.of("ARRIVED", "IN_PROGRESS", "COMPLETED", "NO_SHOW"),
            "ARRIVED", Set.of("IN_PROGRESS", "COMPLETED", "NO_SHOW"),
            "IN_PROGRESS", Set.of("COMPLETED"));

    private static final Map<String, String> ITEM_STATUS =
            Map.of("IN_PROGRESS", "STARTED", "COMPLETED", "DONE", "NO_SHOW", "NO_SHOW");

    /**
     * Arrived, started, completed or no-show. Managers and the front desk ({@code appointments.manage}) change any
     * appointment; a professional ({@code appointments.manage_own}) only appointments that are all theirs. No-show only
     * once the visit should have started.
     */
    public String changeStatus(OrgAccess access, UUID appointmentId, String to, Instant now) {
        return transactions.execute(status -> {
            var a = dsl.selectFrom(APPOINTMENTS)
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .forUpdate()
                    .fetchOne();
            if (a == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
            }
            if (!access.canAccessLocation(a.getLocationId())) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
            }
            if (!access.can(MANAGE)) {
                boolean own = access.can(MANAGE_OWN)
                        && !dsl.fetchExists(DSL.selectOne()
                                .from(APPOINTMENT_ITEMS)
                                .leftJoin(STAFF)
                                .on(STAFF.ID.eq(APPOINTMENT_ITEMS.STAFF_ID))
                                .where(
                                        APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId),
                                        STAFF.MEMBER_ID.isDistinctFrom(access.memberId())));
                if (!own) {
                    throw new ApiException(ProblemCode.FORBIDDEN, "You can change only your own appointments.");
                }
            }
            String from = a.getStatus();
            if (!NEXT.getOrDefault(from, Set.of()).contains(to)) {
                throw new ApiException(
                        ProblemCode.IMMUTABLE, "An appointment that is " + from + " cannot become " + to + ".");
            }
            if (to.equals("NO_SHOW") && a.getStartsAt().toInstant().isAfter(now)) {
                throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "Mark a no-show only after the start time.");
            }
            dsl.update(APPOINTMENTS)
                    .set(APPOINTMENTS.STATUS, to)
                    .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                    .where(APPOINTMENTS.ID.eq(appointmentId))
                    .execute();
            if (ITEM_STATUS.containsKey(to)) {
                dsl.update(APPOINTMENT_ITEMS)
                        .set(APPOINTMENT_ITEMS.STATUS, ITEM_STATUS.get(to))
                        .where(
                                APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId),
                                APPOINTMENT_ITEMS.STATUS.ne("CANCELLED"))
                        .execute();
            }
            if (to.equals("COMPLETED")) {
                dsl.update(CUSTOMERS)
                        .set(CUSTOMERS.LAST_VISIT_AT, a.getStartsAt())
                        .where(CUSTOMERS.ID.eq(a.getCustomerId()))
                        .execute();
            }
            Bookings.history(dsl, a.getOrgId(), appointmentId, from, to, access.userId(), null);
            outbox.publish(new DomainEvent(
                    "scheduling.appointment",
                    appointmentId,
                    "appointment." + to.toLowerCase(java.util.Locale.ROOT),
                    1,
                    a.getOrgId(),
                    Map.of("appointmentId", appointmentId, "from", from, "to", to)));
            return to;
        });
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
