package com.beavent.backend.marketplace.internal;

import static com.beavent.db.catalog.Tables.ADDONS;
import static com.beavent.db.catalog.Tables.GALLERY_ITEMS;
import static com.beavent.db.catalog.Tables.LOCATION_SERVICES;
import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_ADDONS;
import static com.beavent.db.catalog.Tables.SERVICE_GROUPS;
import static com.beavent.db.catalog.Tables.SERVICE_PRICES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.catalog.Tables.STAFF_SERVICES;
import static com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static com.beavent.db.team.Tables.STAFF;
import static com.beavent.db.team.Tables.STAFF_LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATION_CLOSURES;
import static com.beavent.db.tenant.Tables.LOCATION_HOURS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.tenant.Tables.PLACES;

import com.beavent.backend.files.FileAccess;
import com.beavent.backend.marketplace.Venues;
import com.beavent.backend.scheduling.BookingPolicies;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reads a venue page's content from the business's tables (read-only). */
@Component
class VenueReader implements Venues {

    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};
    private static final Field<Double> LATITUDE = DSL.field("ST_Y({0}::geometry)", Double.class, PLACES.GEO);
    private static final Field<Double> LONGITUDE = DSL.field("ST_X({0}::geometry)", Double.class, PLACES.GEO);
    private static final Field<OffsetDateTime> CLOSED_START =
            DSL.field("lower({0})", OffsetDateTime.class, LOCATION_CLOSURES.CLOSED_DURING);
    private static final Field<OffsetDateTime> CLOSED_END =
            DSL.field("upper({0})", OffsetDateTime.class, LOCATION_CLOSURES.CLOSED_DURING);

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final FileAccess files;
    private final BookingPolicies policies;
    private final com.beavent.backend.payments.SellerPayments sellers;
    private final JsonMapper json;

    VenueReader(
            DSLContext dsl,
            TransactionTemplate transactions,
            FileAccess files,
            BookingPolicies policies,
            com.beavent.backend.payments.SellerPayments sellers,
            JsonMapper json) {
        this.dsl = dsl;
        this.sellers = sellers;
        this.transactions = transactions;
        this.files = files;
        this.policies = policies;
        this.json = json;
    }

    private Map<String, String> text(JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), TEXT);
    }

    private Image image(UUID fileId, Map<String, String> alt) {
        if (fileId == null) {
            return null;
        }
        return files.publicImage(fileId)
                .map(i -> new Image(i.url(), i.variants(), alt))
                .orElse(null);
    }

    @Override
    public Optional<Venue> venue(UUID locationId, boolean onlineBooking, boolean listed, Instant now) {
        return transactions.execute(status -> read(locationId, onlineBooking, listed, now));
    }

    private Optional<Venue> read(UUID locationId, boolean onlineBooking, boolean listed, Instant now) {
        Record row = dsl.select(
                        ORGANIZATIONS.SLUG,
                        ORGANIZATIONS.DISPLAY_NAME,
                        ORGANIZATIONS.DESCRIPTION_I18N,
                        ORGANIZATIONS.LOGO_FILE_ID,
                        ORGANIZATIONS.COVER_FILE_ID,
                        CATEGORIES.NAME_I18N,
                        LOCATIONS.ID,
                        LOCATIONS.ORG_ID,
                        LOCATIONS.SLUG,
                        LOCATIONS.NAME,
                        LOCATIONS.PHONE,
                        LOCATIONS.TIMEZONE,
                        LOCATIONS.SERVICE_MODE,
                        LOCATIONS.CLOSED_ON_PUBLIC_HOLIDAYS,
                        PLACES.LANDMARK,
                        PLACES.ADDRESS_LINE,
                        PLACES.CITY,
                        ADMINISTRATIVE_AREAS.NAME_I18N,
                        LATITUDE,
                        LONGITUDE,
                        ORGANIZATIONS.CONTACT_PHONE)
                .from(LOCATIONS)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(LOCATIONS.ORG_ID))
                .join(PLACES)
                .on(PLACES.ID.eq(LOCATIONS.PLACE_ID))
                .leftJoin(CATEGORIES)
                .on(CATEGORIES.ID.eq(ORGANIZATIONS.PRIMARY_CATEGORY_ID))
                .leftJoin(ADMINISTRATIVE_AREAS)
                .on(ADMINISTRATIVE_AREAS.ID.eq(PLACES.ADMIN_AREA_ID))
                .where(LOCATIONS.ID.eq(locationId))
                .fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        UUID orgId = row.get(LOCATIONS.ORG_ID);

        List<Hours> hours = dsl.select(LOCATION_HOURS.ISO_WEEKDAY, LOCATION_HOURS.OPENS_AT, LOCATION_HOURS.CLOSES_AT)
                .from(LOCATION_HOURS)
                .where(LOCATION_HOURS.LOCATION_ID.eq(locationId))
                .orderBy(LOCATION_HOURS.ISO_WEEKDAY, LOCATION_HOURS.OPENS_AT)
                .fetch(r -> new Hours(r.value1(), r.value2(), r.value3()));
        List<Closure> closures = dsl.select(CLOSED_START, CLOSED_END, LOCATION_CLOSURES.REASON)
                .from(LOCATION_CLOSURES)
                .where(
                        LOCATION_CLOSURES.LOCATION_ID.eq(locationId),
                        DSL.condition(
                                "upper({0}) > {1}",
                                LOCATION_CLOSURES.CLOSED_DURING, DSL.val(now.atOffset(ZoneOffset.UTC))))
                .orderBy(CLOSED_START)
                .limit(5)
                .fetch(r -> new Closure(
                        r.value1() == null ? null : r.value1().toInstant(),
                        r.value2() == null ? null : r.value2().toInstant(),
                        r.value3()));

        // The business's own photos first (its cover, then this location's), then the rest of the gallery.
        List<Image> gallery = new ArrayList<>();
        Image cover = image(row.get(ORGANIZATIONS.COVER_FILE_ID), Map.of());
        if (cover != null) {
            gallery.add(cover);
        }
        dsl.selectFrom(GALLERY_ITEMS)
                .where(GALLERY_ITEMS
                        .LOCATION_ID
                        .eq(locationId)
                        .or(DSL.and(
                                GALLERY_ITEMS.LOCATION_ID.isNull(),
                                GALLERY_ITEMS.SERVICE_ID.isNull(),
                                GALLERY_ITEMS.STAFF_ID.isNull(),
                                GALLERY_ITEMS.PRODUCT_ID.isNull(),
                                GALLERY_ITEMS.EVENT_ID.isNull())))
                .orderBy(GALLERY_ITEMS.SORT_ORDER, GALLERY_ITEMS.CREATED_AT)
                .limit(24)
                .forEach(item -> {
                    Image image = image(item.getFileId(), text(item.getAltTextI18n()));
                    if (image != null && gallery.stream().noneMatch(g -> g.url().equals(image.url()))) {
                        gallery.add(image);
                    }
                });

        List<Group> groups = groups(locationId);
        boolean anyBookable = groups.stream().anyMatch(g -> !g.services().isEmpty());
        List<Person> team = team(locationId);
        BookingPolicies.Policy p = policies.effective(locationId, null);
        // A business that cannot take online payments yet is paid at the venue, whatever its policy says.
        boolean online = sellers.available(orgId);
        List<Other> others = dsl.select(LOCATIONS.SLUG, LOCATIONS.NAME, PLACES.LANDMARK)
                .from(LOCATIONS)
                .join(PLACES)
                .on(PLACES.ID.eq(LOCATIONS.PLACE_ID))
                .where(LOCATIONS.ORG_ID.eq(orgId), LOCATIONS.ID.ne(locationId), LOCATIONS.STATUS.eq("ACTIVE"))
                .orderBy(LOCATIONS.CREATED_AT, LOCATIONS.ID)
                .fetch(r -> new Other(r.value1(), r.value2(), r.value3()));

        String phone =
                row.get(LOCATIONS.PHONE) != null ? row.get(LOCATIONS.PHONE) : row.get(ORGANIZATIONS.CONTACT_PHONE);
        return Optional.of(new Venue(
                row.get(ORGANIZATIONS.SLUG),
                row.get(ORGANIZATIONS.DISPLAY_NAME),
                text(row.get(ORGANIZATIONS.DESCRIPTION_I18N)),
                text(row.get(CATEGORIES.NAME_I18N)),
                image(row.get(ORGANIZATIONS.LOGO_FILE_ID), Map.of()),
                locationId,
                row.get(LOCATIONS.SLUG),
                row.get(LOCATIONS.NAME),
                phone,
                row.get(LOCATIONS.TIMEZONE),
                row.get(LOCATIONS.SERVICE_MODE),
                row.get(PLACES.LANDMARK),
                row.get(PLACES.ADDRESS_LINE),
                row.get(PLACES.CITY),
                text(row.get(ADMINISTRATIVE_AREAS.NAME_I18N)),
                row.get(LATITUDE),
                row.get(LONGITUDE),
                row.get(LOCATIONS.CLOSED_ON_PUBLIC_HOLIDAYS),
                hours,
                closures,
                onlineBooking && anyBookable,
                listed,
                gallery,
                groups,
                team,
                new Policy(
                        p.requiresApproval(),
                        p.allowPayAtVenue() || !online,
                        online ? p.depositMode() : "NONE",
                        online ? p.depositPercent() : null,
                        online ? p.depositFixedMinor() : null,
                        p.depositAppliesTo(),
                        p.currency(),
                        p.cancellationWindowMinutes(),
                        p.lateCancelFeePercent(),
                        p.noShowFeePercent()),
                others));
    }

    /** Active, online-bookable services offered here, grouped like the menu; ungrouped services come last. */
    private List<Group> groups(UUID locationId) {
        var services = dsl.selectFrom(SERVICES)
                .where(
                        SERVICES.STATUS.eq("ACTIVE"),
                        SERVICES.ONLINE_BOOKING.isTrue(),
                        DSL.exists(DSL.selectOne()
                                .from(LOCATION_SERVICES)
                                .where(
                                        LOCATION_SERVICES.SERVICE_ID.eq(SERVICES.ID),
                                        LOCATION_SERVICES.LOCATION_ID.eq(locationId))))
                .orderBy(SERVICES.SORT_ORDER, SERVICES.ID)
                .fetch();
        if (services.isEmpty()) {
            return List.of();
        }
        List<UUID> serviceIds = services.map(s -> s.getId());
        var variantRows = dsl.selectFrom(SERVICE_VARIANTS)
                .where(SERVICE_VARIANTS.SERVICE_ID.in(serviceIds), SERVICE_VARIANTS.STATUS.eq("ACTIVE"))
                .orderBy(SERVICE_VARIANTS.SORT_ORDER, SERVICE_VARIANTS.ID)
                .fetch();
        // Prices people may pay instead of the standard one (per person, level or location), valid now.
        Map<UUID, long[]> range = new HashMap<>();
        for (var v : variantRows) {
            range.put(v.getId(), new long[] {v.getPriceMinor(), v.getPriceMinor()});
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.select(SERVICE_PRICES.VARIANT_ID, SERVICE_PRICES.PRICE_MINOR)
                .from(SERVICE_PRICES)
                .where(
                        SERVICE_PRICES.VARIANT_ID.in(range.keySet()),
                        SERVICE_PRICES.VALID_FROM.le(now),
                        SERVICE_PRICES.VALID_TO.gt(now),
                        SERVICE_PRICES.LOCATION_ID.isNull().or(SERVICE_PRICES.LOCATION_ID.eq(locationId)))
                .forEach(r -> {
                    long[] r2 = range.get(r.value1());
                    r2[0] = Math.min(r2[0], r.value2());
                    r2[1] = Math.max(r2[1], r.value2());
                });
        Map<UUID, List<Variant>> variants = new HashMap<>();
        for (var v : variantRows) {
            long[] r = range.get(v.getId());
            variants.computeIfAbsent(v.getServiceId(), k -> new ArrayList<>())
                    .add(new Variant(
                            v.getId(),
                            text(v.getNameI18n()),
                            v.getDurationMinutes(),
                            v.getPriceType(),
                            r[0],
                            r[1],
                            v.getCurrencyCode().trim()));
        }
        Map<UUID, List<AddOn>> addOns = new HashMap<>();
        dsl.select(
                        SERVICE_ADDONS.SERVICE_ID,
                        ADDONS.ID,
                        ADDONS.NAME_I18N,
                        ADDONS.DURATION_MINUTES,
                        ADDONS.PRICE_MINOR,
                        ADDONS.CURRENCY_CODE)
                .from(SERVICE_ADDONS)
                .join(ADDONS)
                .on(ADDONS.ID.eq(SERVICE_ADDONS.ADDON_ID))
                .where(SERVICE_ADDONS.SERVICE_ID.in(serviceIds), ADDONS.STATUS.eq("ACTIVE"))
                .orderBy(ADDONS.ID)
                .forEach(r -> addOns.computeIfAbsent(r.value1(), k -> new ArrayList<>())
                        .add(new AddOn(
                                r.value2(),
                                text(r.value3()),
                                r.value4(),
                                r.value5(),
                                r.value6().trim())));

        Map<UUID, Group> byGroup = new LinkedHashMap<>();
        Map<UUID, Map<String, String>> groupNames = new HashMap<>();
        dsl.selectFrom(SERVICE_GROUPS)
                .orderBy(SERVICE_GROUPS.SORT_ORDER, SERVICE_GROUPS.ID)
                .forEach(g -> groupNames.put(g.getId(), text(g.getNameI18n())));
        Map<UUID, List<Service>> grouped = new LinkedHashMap<>();
        groupNames.keySet().forEach(id -> grouped.put(id, new ArrayList<>()));
        List<Service> loose = new ArrayList<>();
        for (var s : services) {
            List<Variant> vs = variants.getOrDefault(s.getId(), List.of());
            if (vs.isEmpty()) {
                continue; // nothing to book
            }
            Service service = new Service(
                    s.getId(),
                    text(s.getNameI18n()),
                    text(s.getDescriptionI18n()),
                    text(s.getAftercareI18n()),
                    s.getAudience(),
                    Arrays.asList(s.getDeliveryModes()),
                    s.getRequiresPatchTest(),
                    s.getPatchTestValidityDays() == null ? null : (int) s.getPatchTestValidityDays(),
                    vs,
                    addOns.getOrDefault(s.getId(), List.of()));
            if (s.getGroupId() != null && grouped.containsKey(s.getGroupId())) {
                grouped.get(s.getGroupId()).add(service);
            } else {
                loose.add(service);
            }
        }
        List<Group> result = new ArrayList<>();
        grouped.forEach((id, list) -> {
            if (!list.isEmpty()) {
                result.add(new Group(id, groupNames.get(id), list));
            }
        });
        if (!loose.isEmpty()) {
            result.add(new Group(null, Map.of(), loose));
        }
        return result;
    }

    private List<Person> team(UUID locationId) {
        var rows = dsl.select(STAFF.ID, STAFF.DISPLAY_NAME, STAFF.JOB_TITLE, STAFF.AVATAR_FILE_ID, STAFF.LANGUAGES)
                .from(STAFF)
                .join(STAFF_LOCATIONS)
                .on(STAFF_LOCATIONS.STAFF_ID.eq(STAFF.ID), STAFF_LOCATIONS.LOCATION_ID.eq(locationId))
                .where(STAFF.STATUS.eq("ACTIVE"), STAFF.IS_BOOKABLE.isTrue(), STAFF.BOOKABLE_ONLINE.isTrue())
                .orderBy(STAFF.SORT_ORDER, STAFF.DISPLAY_NAME, STAFF.ID)
                .fetch();
        Map<UUID, List<UUID>> done = new HashMap<>();
        dsl.select(STAFF_SERVICES.STAFF_ID, STAFF_SERVICES.SERVICE_ID)
                .from(STAFF_SERVICES)
                .where(STAFF_SERVICES.STAFF_ID.in(rows.map(r -> r.value1())))
                .forEach(r ->
                        done.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(r.value2()));
        return rows.map(r -> new Person(
                r.value1(),
                r.value2(),
                r.value3(),
                image(r.value4(), Map.of()),
                Arrays.asList(r.value5()),
                done.getOrDefault(r.value1(), List.of())));
    }
}
