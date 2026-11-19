package com.beavent.backend.tenancy.internal;

import static com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS;
import static com.beavent.db.ref.Tables.COUNTRIES;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATION_HOURS;
import static com.beavent.db.tenant.Tables.PLACES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.CursorPage;
import com.beavent.backend.platform.Cursors;
import com.beavent.backend.platform.ETags;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.tenant.tables.records.LegalEntitiesRecord;
import com.beavent.db.tenant.tables.records.LocationsRecord;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Locations (branches, studios, box offices) with their premises place and opening hours. */
@Component
public class Locations {

    private static final Field<Double> LATITUDE = DSL.field("ST_Y({0}::geometry)", Double.class, PLACES.GEO);
    private static final Field<Double> LONGITUDE = DSL.field("ST_X({0}::geometry)", Double.class, PLACES.GEO);
    private static final Map<String, String> ADDRESS_FIELDS = Map.of(
            "admin_area", "adminAreaId",
            "landmark", "landmark",
            "address_line", "addressLine",
            "city", "city",
            "postal_code", "postalCode");

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Audit audit;
    private final JsonMapper json;

    Locations(DSLContext dsl, TransactionTemplate transactions, Audit audit, JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.audit = audit;
        this.json = json;
    }

    /** Locations the member may work in, oldest first. */
    public CursorPage<Bodies.Location> list(OrgAccess access, String cursor, int limit) {
        return transactions.execute(status -> {
            Condition condition = scope(access);
            if (cursor != null) {
                condition = condition.and(LOCATIONS.ID.gt(parseCursor(cursor)));
            }
            List<UUID> ids = dsl.select(LOCATIONS.ID)
                    .from(LOCATIONS)
                    .where(condition)
                    .orderBy(LOCATIONS.ID)
                    .limit(limit + 1)
                    .fetch(LOCATIONS.ID);
            boolean more = ids.size() > limit;
            List<Bodies.Location> items =
                    ids.stream().limit(limit).map(this::view).collect(Collectors.toList());
            return new CursorPage<>(
                    items, more ? Cursors.encode(items.getLast().id().toString()) : null);
        });
    }

    public Bodies.Location get(OrgAccess access, UUID id) {
        return transactions.execute(status -> {
            if (!access.canAccessLocation(id)) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            return view(id);
        });
    }

    public Bodies.Location create(UUID orgId, Bodies.LocationInput input, UUID userId) {
        validateHours(input.hours());
        return transactions.execute(status -> {
            LegalEntitiesRecord entity = legalEntity(input.legalEntityId());
            validateAddress(input.address(), entity.getCountryCode());
            String timezone = zone(input.timezone());
            UUID placeId = dsl.insertInto(PLACES)
                    .set(PLACES.OWNER_ORG_ID, orgId)
                    .set(PLACES.KIND, "PREMISES")
                    .set(placeValues(input, timezone))
                    .returning(PLACES.ID)
                    .fetchSingle(PLACES.ID);
            LocationsRecord location = dsl.newRecord(LOCATIONS);
            location.setOrgId(orgId);
            location.setPlaceId(placeId);
            location.setSlug(freeSlug(input.slug(), input.name(), null));
            apply(location, input, entity, timezone);
            location.store();
            writeHours(orgId, location.getId(), input.hours());
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "location.created",
                    "tenant.location",
                    location.getId().toString(),
                    null,
                    Map.of("name", location.getName()),
                    null));
            return view(location.getId());
        });
    }

    public Bodies.Location update(UUID orgId, UUID id, Bodies.LocationInput input, String ifMatch, UUID userId) {
        validateHours(input.hours());
        return transactions.execute(status -> {
            LocationsRecord location = dsl.selectFrom(LOCATIONS)
                    .where(LOCATIONS.ID.eq(id))
                    .forUpdate()
                    .fetchOne();
            if (location == null) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            ETags.requireMatch(ifMatch, Support.version(location.getUpdatedAt()));
            LegalEntitiesRecord entity = legalEntity(input.legalEntityId());
            validateAddress(input.address(), entity.getCountryCode());
            String timezone = zone(input.timezone());
            dsl.update(PLACES)
                    .set(placeValues(input, timezone))
                    .where(PLACES.ID.eq(location.getPlaceId()))
                    .execute();
            if (input.slug() != null && !input.slug().equals(location.getSlug())) {
                location.setSlug(freeSlug(input.slug(), input.name(), id));
            }
            apply(location, input, entity, timezone);
            // Always move the version on (hours live in another table); the trigger sets the actual time.
            location.setUpdatedAt(java.time.OffsetDateTime.now());
            location.store();
            dsl.deleteFrom(LOCATION_HOURS)
                    .where(LOCATION_HOURS.LOCATION_ID.eq(id))
                    .execute();
            writeHours(orgId, id, input.hours());
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "location.updated",
                    "tenant.location",
                    id.toString(),
                    null,
                    Map.of("name", location.getName()),
                    null));
            return view(id);
        });
    }

    private Condition scope(OrgAccess access) {
        return access.allLocations() ? DSL.noCondition() : LOCATIONS.ID.in(access.locationIds());
    }

    private static UUID parseCursor(String cursor) {
        try {
            return UUID.fromString(Cursors.decode(cursor, 1).getFirst());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid cursor.");
        }
    }

    private LegalEntitiesRecord legalEntity(UUID id) {
        LegalEntitiesRecord entity =
                dsl.selectFrom(LEGAL_ENTITIES).where(LEGAL_ENTITIES.ID.eq(id)).fetchOne();
        if (entity == null) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown legal entity.");
        }
        return entity;
    }

    private static String zone(String timezone) {
        try {
            return ZoneId.of(timezone).getId();
        } catch (DateTimeException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown time zone " + timezone + ".");
        }
    }

    /** Address rules from the country's address format (e.g. Ethiopia: sub-city/woreda and a landmark). */
    private void validateAddress(Bodies.Address address, String legalEntityCountry) {
        if (!address.countryCode().equals(legalEntityCountry)) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION, "The location must be in the legal entity's country.");
        }
        var format = dsl.select(COUNTRIES.ADDRESS_FORMAT)
                .from(COUNTRIES)
                .where(COUNTRIES.CODE.eq(address.countryCode()))
                .fetchOne(COUNTRIES.ADDRESS_FORMAT);
        JsonNode required = format == null ? null : json.readTree(format.data()).get("required");
        if (required != null) {
            for (JsonNode field : required.values()) {
                String apiField = ADDRESS_FIELDS.get(field.asString());
                if (apiField != null && blank(addressValue(address, apiField))) {
                    throw new ApiException(
                            ProblemCode.VALIDATION_FAILED,
                            "address." + apiField + " is required for addresses in " + address.countryCode() + ".");
                }
            }
        }
        if (address.geo() == null && blank(address.addressLine()) && blank(address.landmark())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give a landmark, a street address or a map pin.");
        }
        if (address.adminAreaId() != null) {
            boolean known = dsl.fetchExists(
                    ADMINISTRATIVE_AREAS,
                    ADMINISTRATIVE_AREAS
                            .ID
                            .eq(address.adminAreaId())
                            .and(ADMINISTRATIVE_AREAS.COUNTRY_CODE.eq(address.countryCode()))
                            .and(ADMINISTRATIVE_AREAS.IS_ACTIVE));
            if (!known) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown administrative area.");
            }
        }
    }

    private static Object addressValue(Bodies.Address address, String field) {
        return switch (field) {
            case "adminAreaId" -> address.adminAreaId();
            case "landmark" -> address.landmark();
            case "addressLine" -> address.addressLine();
            case "city" -> address.city();
            default -> address.postalCode();
        };
    }

    private static boolean blank(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private static void validateHours(List<Bodies.OpeningHours> hours) {
        if (hours == null) {
            return;
        }
        List<Bodies.OpeningHours> sorted = new ArrayList<>(hours);
        sorted.sort(Comparator.comparing(Bodies.OpeningHours::isoWeekday).thenComparing(Bodies.OpeningHours::opensAt));
        Bodies.OpeningHours previous = null;
        for (Bodies.OpeningHours interval : sorted) {
            if (interval.closesAt().compareTo(interval.opensAt()) <= 0) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Closing time must be after opening time.");
            }
            if (previous != null
                    && previous.isoWeekday().equals(interval.isoWeekday())
                    && interval.opensAt().compareTo(previous.closesAt()) < 0) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Opening hours on the same day overlap.");
            }
            previous = interval;
        }
    }

    /** Name, address and pin of the location's premises (one statement, so the place check sees them all). */
    private static Map<Field<?>, Object> placeValues(Bodies.LocationInput input, String timezone) {
        Bodies.Address address = input.address();
        Map<Field<?>, Object> values = new java.util.LinkedHashMap<>();
        values.put(PLACES.NAME, input.name().strip());
        values.put(PLACES.COUNTRY_CODE, address.countryCode());
        values.put(PLACES.TIMEZONE, timezone);
        values.put(PLACES.ADMIN_AREA_ID, address.adminAreaId());
        values.put(PLACES.ADDRESS_LINE, address.addressLine());
        values.put(PLACES.LANDMARK, address.landmark());
        values.put(PLACES.CITY, address.city());
        values.put(PLACES.POSTAL_CODE, address.postalCode());
        values.put(
                PLACES.GEO,
                address.geo() == null
                        ? null
                        : DSL.field(
                                "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography",
                                Object.class,
                                DSL.val(address.geo().longitude()),
                                DSL.val(address.geo().latitude())));
        return values;
    }

    private void writeHours(UUID orgId, UUID locationId, List<Bodies.OpeningHours> hours) {
        if (hours == null) {
            return;
        }
        for (Bodies.OpeningHours interval : hours) {
            dsl.insertInto(LOCATION_HOURS)
                    .set(LOCATION_HOURS.ORG_ID, orgId)
                    .set(LOCATION_HOURS.LOCATION_ID, locationId)
                    .set(LOCATION_HOURS.ISO_WEEKDAY, interval.isoWeekday().shortValue())
                    .set(LOCATION_HOURS.OPENS_AT, LocalTime.parse(interval.opensAt()))
                    .set(LOCATION_HOURS.CLOSES_AT, LocalTime.parse(interval.closesAt()))
                    .execute();
        }
    }

    private static void apply(
            LocationsRecord location, Bodies.LocationInput input, LegalEntitiesRecord entity, String timezone) {
        location.setLegalEntityId(entity.getId());
        location.setName(input.name().strip());
        location.setPhone(input.phone());
        location.setEmail(input.email());
        location.setTimezone(timezone);
        location.setCurrencyCode(entity.getFunctionalCurrency());
        location.setServiceMode(input.serviceMode() == null ? "ON_PREMISES" : input.serviceMode());
        location.setOnlineBooking(input.onlineBooking() == null || input.onlineBooking());
        location.setClosedOnPublicHolidays(input.closedOnPublicHolidays() == null || input.closedOnPublicHolidays());
    }

    /** The requested slug (409 if taken), or one derived from the name with a numeric suffix if needed. */
    private String freeSlug(String requested, String name, UUID self) {
        Condition notSelf = self == null ? DSL.noCondition() : LOCATIONS.ID.ne(self);
        if (requested != null) {
            if (dsl.fetchExists(LOCATIONS, LOCATIONS.SLUG.eq(requested).and(notSelf))) {
                throw new ApiException(ProblemCode.CONFLICT, "Another location already uses this address.");
            }
            return requested;
        }
        String base = Support.slugify(name, 50);
        if (base.isEmpty()) {
            base = "location";
        }
        String candidate = base;
        for (int n = 2; dsl.fetchExists(LOCATIONS, LOCATIONS.SLUG.eq(candidate).and(notSelf)); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    Bodies.Location view(UUID id) {
        var row = dsl.select(
                        LOCATIONS.asterisk(),
                        PLACES.COUNTRY_CODE,
                        PLACES.ADMIN_AREA_ID,
                        PLACES.ADDRESS_LINE,
                        PLACES.LANDMARK,
                        PLACES.CITY,
                        PLACES.POSTAL_CODE,
                        LATITUDE,
                        LONGITUDE)
                .from(LOCATIONS)
                .join(PLACES)
                .on(PLACES.ID.eq(LOCATIONS.PLACE_ID))
                .where(LOCATIONS.ID.eq(id))
                .fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        LocationsRecord location = row.into(LOCATIONS);
        Double latitude = row.get(LATITUDE);
        Bodies.Address address = new Bodies.Address(
                row.get(PLACES.COUNTRY_CODE),
                row.get(PLACES.ADMIN_AREA_ID),
                row.get(PLACES.ADDRESS_LINE),
                row.get(PLACES.LANDMARK),
                row.get(PLACES.CITY),
                row.get(PLACES.POSTAL_CODE),
                latitude == null ? null : new Bodies.GeoPoint(latitude, row.get(LONGITUDE)));
        List<Bodies.OpeningHours> hours = dsl.selectFrom(LOCATION_HOURS)
                .where(LOCATION_HOURS.LOCATION_ID.eq(id))
                .orderBy(LOCATION_HOURS.ISO_WEEKDAY, LOCATION_HOURS.OPENS_AT)
                .fetch(h -> new Bodies.OpeningHours(
                        (int) h.getIsoWeekday(),
                        h.getOpensAt().toString().substring(0, 5),
                        h.getClosesAt().toString().substring(0, 5)));
        return new Bodies.Location(
                location.getId(),
                location.getName(),
                location.getSlug(),
                location.getLegalEntityId(),
                address,
                location.getTimezone(),
                location.getPhone(),
                location.getEmail(),
                location.getServiceMode(),
                location.getOnlineBooking(),
                hours,
                location.getClosedOnPublicHolidays(),
                location.getPlaceId(),
                location.getCurrencyCode(),
                location.getStatus(),
                location.getMarketplaceListed(),
                Support.version(location.getUpdatedAt()));
    }
}
