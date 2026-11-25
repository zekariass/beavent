package com.beavent.backend.scheduling.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.commerce.Tables.WAITLIST_ENTRIES;
import static com.beavent.db.commerce.Tables.WAITLIST_OFFERS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.HOLDS;
import static com.beavent.db.team.Tables.STAFF;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.identity.People;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.scheduling.Availability;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.backend.tenancy.PublicLocations;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The waitlist for fully booked times (plan step 2.6, S-15). A person joins for a service (and maybe a person) on up
 * to seven days and parts of the day. When a matching time is free — a cancellation, a move, a removed block, new
 * working hours — the oldest waiting entry gets an offer: the time is held for {@link #OFFER_TIME} and the person is
 * told by text message. Booking it turns the hold into an appointment (source {@code WAITLIST}); declining or letting
 * it run out hands the time to the next person (a time is never offered twice to the same entry).
 */
@Component
public class Waitlist {

    /** How long an offered time is held for the person. */
    public static final Duration OFFER_TIME = Duration.ofMinutes(15);

    static final int MAX_ACTIVE = 5;
    static final int MAX_DAYS = 7;
    static final int DAYS_AHEAD = 60;
    private static final int SWEEP_BATCH = 50;
    private static final List<String> ACTIVE = List.of("WAITING", "OFFERED");
    private static final Logger log = LoggerFactory.getLogger(Waitlist.class);

    /** Parts of the day as the booking screens group times (morning before 12, afternoon before 17, evening). */
    enum Part {
        MORNING(0, 12),
        AFTERNOON(12, 17),
        EVENING(17, 24);

        final int from;
        final int to;

        Part(int from, int to) {
            this.from = from;
            this.to = to;
        }

        static Part of(int hour) {
            return hour < 12 ? MORNING : hour < 17 ? AFTERNOON : EVENING;
        }
    }

    public record OfferBody(UUID id, Instant start, Instant end, Instant expiresAt, String staffName) {}

    public record EntryBody(
            UUID id,
            String status,
            UUID locationId,
            String timezone,
            BookingApi.PlaceBody place,
            UUID variantId,
            Map<String, String> serviceName,
            Map<String, String> variantName,
            UUID staffId,
            String staffName,
            String customerName,
            List<LocalDate> days,
            List<String> parts,
            OfferBody offer,
            Instant createdAt) {}

    public record Entries(List<EntryBody> items) {}

    public record Accepted(UUID appointmentId, String reference, String status) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final DSLContext system;
    private final TransactionTemplate systemTransactions;
    private final Availability availability;
    private final Bookings bookings;
    private final People people;
    private final PublicLocations locations;
    private final Outbox outbox;
    private final tools.jackson.databind.json.JsonMapper json;

    Waitlist(
            DSLContext dsl,
            TransactionTemplate transactions,
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate systemTransactions,
            Availability availability,
            Bookings bookings,
            People people,
            PublicLocations locations,
            Outbox outbox,
            tools.jackson.databind.json.JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.system = system;
        this.systemTransactions = systemTransactions;
        this.availability = availability;
        this.bookings = bookings;
        this.people = people;
        this.locations = locations;
        this.outbox = outbox;
        this.json = json;
    }

    // --- joining and leaving -----------------------------------------------------------------------------------------

    /**
     * Puts the signed-in person on the waitlist of a location for one service (and maybe one person). Days are the
     * location's local dates (today up to {@value #DAYS_AHEAD} days ahead, at most {@value #MAX_DAYS}).
     */
    public UUID join(
            UUID userId,
            PublicLocations.Bookable place,
            UUID variantId,
            UUID staffId,
            List<LocalDate> days,
            List<String> parts,
            Instant now) {
        ZoneId zone = ZoneId.of(place.timezone());
        LocalDate today = now.atZone(zone).toLocalDate();
        TreeSet<LocalDate> chosenDays = new TreeSet<>(days == null ? List.of() : days);
        if (chosenDays.isEmpty() || chosenDays.size() > MAX_DAYS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose 1 to " + MAX_DAYS + " days.");
        }
        if (chosenDays.first().isBefore(today) || chosenDays.last().isAfter(today.plusDays(DAYS_AHEAD))) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "Choose days from today up to " + DAYS_AHEAD + " days ahead.");
        }
        Set<Part> chosenParts = new LinkedHashSet<>();
        for (String p : parts == null ? List.<String>of() : parts) {
            try {
                chosenParts.add(Part.valueOf(p));
            } catch (IllegalArgumentException e) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED, "Parts of the day are MORNING, AFTERNOON, EVENING.");
            }
        }
        if (chosenParts.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a part of the day.");
        }
        List<String> windows = new ArrayList<>();
        for (LocalDate day : chosenDays) {
            for (Part part : Part.values()) {
                if (!chosenParts.contains(part)) continue;
                Instant from =
                        day.atTime(LocalTime.of(part.from, 0)).atZone(zone).toInstant();
                Instant to = part.to == 24
                        ? day.plusDays(1).atStartOfDay(zone).toInstant()
                        : day.atTime(LocalTime.of(part.to, 0)).atZone(zone).toInstant();
                if (to.isAfter(now)) {
                    windows.add("[" + from + "," + to + ")");
                }
            }
        }
        if (windows.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Those times have already passed.");
        }
        // The service must be one this place books online (and the person one who does it): the engine says so.
        availability.find(
                new Availability.Query(
                        place.locationId(),
                        List.of(new Availability.Item(variantId, List.of(), staffId)),
                        chosenDays.first(),
                        chosenDays.first(),
                        null),
                now);
        return transactions.execute(status -> {
            People.Person person = people.find(userId).orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED));
            UUID customerId = bookings.customer(place.orgId(), person);
            int active = dsl.fetchCount(
                    WAITLIST_ENTRIES, WAITLIST_ENTRIES.USER_ID.eq(userId).and(WAITLIST_ENTRIES.STATUS.in(ACTIVE)));
            if (active >= MAX_ACTIVE) {
                throw new ApiException(
                        ProblemCode.CONFLICT, "You are on " + MAX_ACTIVE + " waitlists here already. Leave one first.");
            }
            UUID id = Ids.newId();
            dsl.execute(
                    "insert into commerce.waitlist_entries (id, org_id, customer_id, user_id, contact_phone_e164, "
                            + "location_id, service_variant_id, staff_id, preferred_windows, status) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, tstzmultirange(variadic ?::text[]::tstzrange[]), 'WAITING')",
                    id,
                    place.orgId(),
                    customerId,
                    userId,
                    person.phone(),
                    place.locationId(),
                    variantId,
                    staffId,
                    windows.toArray(String[]::new));
            return id;
        });
    }

    /** The person's active waitlist entries across businesses, with any open offer. */
    public Entries mine(UUID userId) {
        Map<UUID, List<UUID>> byOrg = new LinkedHashMap<>();
        RequestContext.forUser(userId)
                .run(() -> transactions.executeWithoutResult(status -> dsl.select(
                                WAITLIST_ENTRIES.ORG_ID, WAITLIST_ENTRIES.ID)
                        .from(WAITLIST_ENTRIES)
                        .where(
                                WAITLIST_ENTRIES.USER_ID.eq(userId),
                                WAITLIST_ENTRIES.STATUS.in(ACTIVE),
                                WAITLIST_ENTRIES.SERVICE_VARIANT_ID.isNotNull())
                        .orderBy(WAITLIST_ENTRIES.CREATED_AT.desc())
                        .forEach(r -> byOrg.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                                .add(r.value2()))));
        List<EntryBody> items = new ArrayList<>();
        byOrg.forEach((orgId, ids) -> items.addAll(
                RequestContext.forOrganization(userId, orgId).call(() -> transactions.execute(status -> read(ids)))));
        return new Entries(items);
    }

    /** The person's entry, wherever it is. */
    public EntryBody one(UUID userId, UUID entryId) {
        UUID orgId = orgOfEntry(userId, entryId);
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> transactions.execute(status -> {
                    requireOwn(userId, entryId);
                    return read(List.of(entryId)).getFirst();
                }));
    }

    /** The business's waiting list at a location (front desk). */
    public Entries forDesk(OrgAccess access, UUID locationId) {
        access.require(Calendar.VIEW_ALL);
        return new Entries(transactions.execute(status -> read(dsl.select(WAITLIST_ENTRIES.ID)
                .from(WAITLIST_ENTRIES)
                .where(WAITLIST_ENTRIES.LOCATION_ID.eq(locationId), WAITLIST_ENTRIES.STATUS.in(ACTIVE))
                .orderBy(WAITLIST_ENTRIES.CREATED_AT)
                .fetch(WAITLIST_ENTRIES.ID))));
    }

    public void leave(UUID userId, UUID entryId) {
        UUID orgId = orgOfEntry(userId, entryId);
        RequestContext.forOrganization(userId, orgId)
                .run(() -> transactions.executeWithoutResult(status -> {
                    requireOwn(userId, entryId);
                    closeOpenOffer(entryId, "DECLINED");
                    dsl.update(WAITLIST_ENTRIES)
                            .set(WAITLIST_ENTRIES.STATUS, "CANCELLED")
                            .set(WAITLIST_ENTRIES.UPDATED_AT, OffsetDateTime.now())
                            .where(WAITLIST_ENTRIES.ID.eq(entryId), WAITLIST_ENTRIES.STATUS.in(ACTIVE))
                            .execute();
                }));
    }

    // --- offers ------------------------------------------------------------------------------------------------------

    /** Books the offered time; the hold becomes an appointment from the waitlist. */
    public Accepted accept(UUID userId, UUID entryId, Instant now) {
        UUID orgId = orgOfEntry(userId, entryId);
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> transactions.execute(status -> {
                    requireOwn(userId, entryId);
                    var offer = openOffer(entryId);
                    if (offer == null
                            || !offer.get(WAITLIST_OFFERS.EXPIRES_AT)
                                    .toInstant()
                                    .isAfter(now)) {
                        throw new ApiException(ProblemCode.HOLD_EXPIRED, "This time was offered to the next person.");
                    }
                    UUID holdId = offer.get(DSL.field(DSL.name("hold_id"), UUID.class));
                    String key = dsl.select(HOLDS.HOLDER_KEY)
                            .from(HOLDS)
                            .where(HOLDS.ID.eq(holdId))
                            .fetchSingle(HOLDS.HOLDER_KEY);
                    Bookings.Booked booked = bookings.confirm(orgId, userId, holdId, key, null, now);
                    dsl.update(APPOINTMENTS)
                            .set(APPOINTMENTS.SOURCE, "WAITLIST")
                            .where(APPOINTMENTS.ID.eq(booked.appointmentId()))
                            .execute();
                    dsl.execute(
                            "update commerce.waitlist_offers set status = 'ACCEPTED', appointment_id = ? where id = ?",
                            booked.appointmentId(),
                            offer.get(WAITLIST_OFFERS.ID));
                    dsl.update(WAITLIST_ENTRIES)
                            .set(WAITLIST_ENTRIES.STATUS, "CONVERTED")
                            .set(WAITLIST_ENTRIES.UPDATED_AT, OffsetDateTime.now())
                            .where(WAITLIST_ENTRIES.ID.eq(entryId))
                            .execute();
                    return new Accepted(booked.appointmentId(), booked.reference(), booked.status());
                }));
    }

    /** "Not this time": the time goes to the next person; the entry keeps waiting for another. */
    public void decline(UUID userId, UUID entryId) {
        UUID orgId = orgOfEntry(userId, entryId);
        RequestContext.forOrganization(userId, orgId)
                .run(() -> transactions.executeWithoutResult(status -> {
                    requireOwn(userId, entryId);
                    if (closeOpenOffer(entryId, "DECLINED")) {
                        dsl.update(WAITLIST_ENTRIES)
                                .set(WAITLIST_ENTRIES.STATUS, "WAITING")
                                .set(WAITLIST_ENTRIES.UPDATED_AT, OffsetDateTime.now())
                                .where(WAITLIST_ENTRIES.ID.eq(entryId), WAITLIST_ENTRIES.STATUS.eq("OFFERED"))
                                .execute();
                    }
                }));
    }

    private org.jooq.Record openOffer(UUID entryId) {
        return dsl.select(WAITLIST_OFFERS.asterisk())
                .from(WAITLIST_OFFERS)
                .where(WAITLIST_OFFERS.ENTRY_ID.eq(entryId), WAITLIST_OFFERS.STATUS.eq("OPEN"))
                .forUpdate()
                .fetchOne();
    }

    /** Closes the entry's open offer and lets go of its hold; false when there was none. */
    private boolean closeOpenOffer(UUID entryId, String to) {
        var offer = openOffer(entryId);
        if (offer == null) {
            return false;
        }
        UUID holdId = offer.get(DSL.field(DSL.name("hold_id"), UUID.class));
        if (holdId != null) {
            String key = dsl.select(HOLDS.HOLDER_KEY)
                    .from(HOLDS)
                    .where(HOLDS.ID.eq(holdId))
                    .fetchOne(HOLDS.HOLDER_KEY);
            if (key != null) {
                bookings.release(holdId, key);
            }
        }
        dsl.update(WAITLIST_OFFERS)
                .set(WAITLIST_OFFERS.STATUS, to)
                .where(WAITLIST_OFFERS.ID.eq(offer.get(WAITLIST_OFFERS.ID)))
                .execute();
        return true;
    }

    private UUID orgOfEntry(UUID userId, UUID entryId) {
        return RequestContext.forUser(userId)
                .call(() -> transactions.execute(status -> dsl.select(WAITLIST_ENTRIES.ORG_ID)
                        .from(WAITLIST_ENTRIES)
                        .where(WAITLIST_ENTRIES.ID.eq(entryId), WAITLIST_ENTRIES.USER_ID.eq(userId))
                        .fetchOptional(WAITLIST_ENTRIES.ORG_ID)))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such waitlist entry."));
    }

    private void requireOwn(UUID userId, UUID entryId) {
        if (!dsl.fetchExists(
                WAITLIST_ENTRIES, WAITLIST_ENTRIES.ID.eq(entryId).and(WAITLIST_ENTRIES.USER_ID.eq(userId)))) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such waitlist entry.");
        }
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    private record Span(Instant start, Instant end) {}

    private Map<UUID, List<Span>> windows(DSLContext db, Collection<UUID> entryIds) {
        Map<UUID, List<Span>> out = new HashMap<>();
        if (entryIds.isEmpty()) return out;
        db.fetch(
                        "select e.id, lower(r), upper(r) from commerce.waitlist_entries e, "
                                + "unnest(e.preferred_windows) r where e.id = any(?) order by lower(r)",
                        (Object) entryIds.toArray(UUID[]::new))
                .forEach(r -> out.computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
                        .add(new Span(
                                r.get(1, OffsetDateTime.class).toInstant(),
                                r.get(2, OffsetDateTime.class).toInstant())));
        return out;
    }

    private static final tools.jackson.core.type.TypeReference<Map<String, String>> TEXT =
            new tools.jackson.core.type.TypeReference<>() {};

    private Map<String, String> text(org.jooq.JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), TEXT);
    }

    private List<EntryBody> read(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, List<Span>> spans = windows(dsl, ids);
        var rows = dsl.select(
                        WAITLIST_ENTRIES.ID,
                        WAITLIST_ENTRIES.STATUS,
                        WAITLIST_ENTRIES.LOCATION_ID,
                        LOCATIONS.TIMEZONE,
                        WAITLIST_ENTRIES.SERVICE_VARIANT_ID,
                        SERVICES.NAME_I18N,
                        SERVICE_VARIANTS.NAME_I18N,
                        WAITLIST_ENTRIES.STAFF_ID,
                        STAFF.DISPLAY_NAME,
                        CUSTOMERS.DISPLAY_NAME,
                        WAITLIST_ENTRIES.CREATED_AT)
                .from(WAITLIST_ENTRIES)
                .join(LOCATIONS)
                .on(LOCATIONS.ID.eq(WAITLIST_ENTRIES.LOCATION_ID))
                .join(SERVICE_VARIANTS)
                .on(SERVICE_VARIANTS.ID.eq(WAITLIST_ENTRIES.SERVICE_VARIANT_ID))
                .join(SERVICES)
                .on(SERVICES.ID.eq(SERVICE_VARIANTS.SERVICE_ID))
                .leftJoin(STAFF)
                .on(STAFF.ID.eq(WAITLIST_ENTRIES.STAFF_ID))
                .leftJoin(CUSTOMERS)
                .on(CUSTOMERS.ID.eq(WAITLIST_ENTRIES.CUSTOMER_ID))
                .where(WAITLIST_ENTRIES.ID.in(ids))
                .orderBy(WAITLIST_ENTRIES.CREATED_AT)
                .fetch();
        Map<UUID, OfferBody> offers = new HashMap<>();
        dsl.fetch(
                        "select o.entry_id, o.id, lower(o.offered_slot), upper(o.offered_slot), o.expires_at, "
                                + "(h.plan->'items'->0->>'staffName') from commerce.waitlist_offers o "
                                + "left join scheduling.holds h on h.id = o.hold_id "
                                + "where o.entry_id = any(?) and o.status = 'OPEN'",
                        (Object) ids.toArray(UUID[]::new))
                .forEach(r -> offers.put(
                        r.get(0, UUID.class),
                        new OfferBody(
                                r.get(1, UUID.class),
                                r.get(2, OffsetDateTime.class).toInstant(),
                                r.get(3, OffsetDateTime.class).toInstant(),
                                r.get(4, OffsetDateTime.class).toInstant(),
                                r.get(5, String.class))));
        Map<UUID, PublicLocations.Named> names = locations.names(rows.map(r -> r.value3()));
        List<EntryBody> out = new ArrayList<>();
        for (var r : rows) {
            ZoneId zone = ZoneId.of(r.value4());
            TreeSet<LocalDate> days = new TreeSet<>();
            Set<String> parts = new LinkedHashSet<>();
            for (Span s : spans.getOrDefault(r.value1(), List.of())) {
                var local = s.start().atZone(zone);
                days.add(local.toLocalDate());
                parts.add(Part.of(local.getHour()).name());
            }
            List<String> orderedParts = new ArrayList<>();
            for (Part p : Part.values()) if (parts.contains(p.name())) orderedParts.add(p.name());
            PublicLocations.Named n = names.get(r.value3());
            out.add(new EntryBody(
                    r.value1(),
                    r.value2(),
                    r.value3(),
                    r.value4(),
                    n == null
                            ? null
                            : new BookingApi.PlaceBody(
                                    n.locationId(),
                                    n.businessName(),
                                    n.businessSlug(),
                                    n.locationName(),
                                    n.locationSlug(),
                                    n.landmark(),
                                    n.phone()),
                    r.value5(),
                    text(r.value6()),
                    text(r.value7()),
                    r.value8(),
                    r.value9(),
                    r.value10(),
                    List.copyOf(days),
                    orderedParts,
                    offers.get(r.value1()),
                    r.value11().toInstant()));
        }
        return out;
    }

    // --- the sweep: expiring and offering ------------------------------------------------------------------------

    /** Runs with the system role across businesses (BookingSweeps); each entry is offered in its own transaction. */
    public int sweep(Instant now) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        systemTransactions.executeWithoutResult(status -> {
            // Offers that ran out: the entry waits again (its hold runs out by itself).
            system.execute(
                    "with gone as (update commerce.waitlist_offers set status = 'EXPIRED' "
                            + "where status = 'OPEN' and expires_at <= ?::timestamptz returning entry_id) "
                            + "update commerce.waitlist_entries set status = 'WAITING', updated_at = now() "
                            + "where status = 'OFFERED' and id in (select entry_id from gone)",
                    at);
            // Entries whose every window has passed are over.
            system.execute(
                    "update commerce.waitlist_entries set status = 'EXPIRED', updated_at = now() "
                            + "where status = 'WAITING' and service_variant_id is not null "
                            + "and upper(preferred_windows) <= ?::timestamptz",
                    at);
        });
        var waiting = systemTransactions.execute(status -> system.select(WAITLIST_ENTRIES.ID, WAITLIST_ENTRIES.ORG_ID)
                .from(WAITLIST_ENTRIES)
                .where(
                        WAITLIST_ENTRIES.STATUS.eq("WAITING"),
                        WAITLIST_ENTRIES.SERVICE_VARIANT_ID.isNotNull(),
                        DSL.condition("upper({0}) > {1}", WAITLIST_ENTRIES.PREFERRED_WINDOWS, DSL.val(at)))
                .orderBy(WAITLIST_ENTRIES.CREATED_AT)
                .limit(SWEEP_BATCH)
                .fetch());
        int offered = 0;
        for (var w : waiting) {
            try {
                if (Boolean.TRUE.equals(RequestContext.forOrganization(null, w.value2())
                        .call(() -> offer(w.value2(), w.value1(), now)))) {
                    offered++;
                }
            } catch (RuntimeException e) {
                log.debug("No waitlist offer for {} this time: {}", w.value1(), e.getMessage());
            }
        }
        return offered;
    }

    /** Offers the entry the earliest free time in its windows that it was not offered before. */
    Boolean offer(UUID orgId, UUID entryId, Instant now) {
        return transactions.execute(status -> {
            var entry = dsl.selectFrom(WAITLIST_ENTRIES)
                    .where(WAITLIST_ENTRIES.ID.eq(entryId), WAITLIST_ENTRIES.STATUS.eq("WAITING"))
                    .forUpdate()
                    .skipLocked()
                    .fetchOne();
            if (entry == null) {
                return false;
            }
            List<Span> spans = windows(dsl, List.of(entryId)).getOrDefault(entryId, List.of()).stream()
                    .filter(s -> s.end().isAfter(now))
                    .toList();
            if (spans.isEmpty()) {
                return false;
            }
            String timezone = dsl.select(LOCATIONS.TIMEZONE)
                    .from(LOCATIONS)
                    .where(LOCATIONS.ID.eq(entry.getLocationId()))
                    .fetchSingle(LOCATIONS.TIMEZONE);
            ZoneId zone = ZoneId.of(timezone);
            Set<Instant> offeredBefore = new java.util.HashSet<>(dsl.fetch(
                            "select lower(offered_slot) from commerce.waitlist_offers where entry_id = ? "
                                    + "and offered_slot is not null",
                            entryId)
                    .map(r -> r.get(0, OffsetDateTime.class).toInstant()));
            LocalDate from = spans.getFirst().start().atZone(zone).toLocalDate();
            LocalDate to = spans.getLast().start().atZone(zone).toLocalDate();
            LocalDate today = now.atZone(zone).toLocalDate();
            if (from.isBefore(today)) from = today;
            Availability.Result result = availability.find(
                    new Availability.Query(
                            entry.getLocationId(),
                            List.of(new Availability.Item(entry.getServiceVariantId(), List.of(), entry.getStaffId())),
                            from,
                            to,
                            null),
                    now);
            Availability.Offer slot = result.slots().stream()
                    .filter(s -> !offeredBefore.contains(s.start()))
                    .filter(s -> spans.stream()
                            .anyMatch(w ->
                                    !s.start().isBefore(w.start()) && s.start().isBefore(w.end())))
                    .findFirst()
                    .orElse(null);
            if (slot == null) {
                return false;
            }
            Bookings.Held held = bookings.hold(
                    orgId,
                    entry.getLocationId(),
                    List.of(new Availability.Item(
                            entry.getServiceVariantId(),
                            List.of(),
                            slot.items().getFirst().staffId())),
                    null,
                    slot.start(),
                    now,
                    OFFER_TIME);
            UUID offerId = Ids.newId();
            dsl.execute(
                    "insert into commerce.waitlist_offers (id, org_id, entry_id, offered_at, expires_at, offered_slot, "
                            + "hold_id, status) values (?, ?, ?, ?::timestamptz, ?::timestamptz, "
                            + "tstzrange(?::timestamptz, ?::timestamptz, '[)'), ?, 'OPEN')",
                    offerId,
                    orgId,
                    entryId,
                    now.atOffset(ZoneOffset.UTC),
                    held.expiresAt().atOffset(ZoneOffset.UTC),
                    slot.start().atOffset(ZoneOffset.UTC),
                    slot.end().atOffset(ZoneOffset.UTC),
                    held.holdId());
            dsl.update(WAITLIST_ENTRIES)
                    .set(WAITLIST_ENTRIES.STATUS, "OFFERED")
                    .set(WAITLIST_ENTRIES.UPDATED_AT, OffsetDateTime.now())
                    .where(WAITLIST_ENTRIES.ID.eq(entryId))
                    .execute();
            outbox.publish(new DomainEvent(
                    "commerce.waitlist_offer",
                    offerId,
                    "waitlist.offered",
                    1,
                    orgId,
                    Map.of("offerId", offerId, "entryId", entryId)));
            return true;
        });
    }
}
