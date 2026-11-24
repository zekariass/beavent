package com.beavent.backend.tenancy.internal;

import static com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATION_CLOSURES;
import static com.beavent.db.tenant.Tables.SERVICE_AREAS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.tenant.tables.records.LocationsRecord;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Location closures (renovation, training days …) and at-home service areas. */
@Component
public class LocationExtras {

    static final String SETTINGS = "org.settings.manage";
    private static final Field<OffsetDateTime> START =
            DSL.field("lower({0})", OffsetDateTime.class, LOCATION_CLOSURES.CLOSED_DURING);
    private static final Field<OffsetDateTime> END =
            DSL.field("upper({0})", OffsetDateTime.class, LOCATION_CLOSURES.CLOSED_DURING);
    private static final Field<Double> LATITUDE = DSL.field("ST_Y({0}::geometry)", Double.class, SERVICE_AREAS.CENTER);
    private static final Field<Double> LONGITUDE = DSL.field("ST_X({0}::geometry)", Double.class, SERVICE_AREAS.CENTER);

    private final DSLContext dsl;
    private final TransactionTemplate transactions;

    LocationExtras(DSLContext dsl, TransactionTemplate transactions) {
        this.dsl = dsl;
        this.transactions = transactions;
    }

    // --- closures --------------------------------------------------------------------------------------------

    public List<Bodies.Closure> closures(OrgAccess access, UUID locationId, LocalDate from, LocalDate to) {
        if (from == null
                || to == null
                || to.isBefore(from)
                || from.plusDays(400).isBefore(to)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give from/to dates up to 400 days apart.");
        }
        return transactions.execute(status -> {
            location(access, locationId);
            return dsl.select(LOCATION_CLOSURES.ID, START, END, LOCATION_CLOSURES.REASON)
                    .from(LOCATION_CLOSURES)
                    .where(
                            LOCATION_CLOSURES.LOCATION_ID.eq(locationId),
                            DSL.condition(
                                    "{0} && tstzrange({1}, {2})",
                                    LOCATION_CLOSURES.CLOSED_DURING,
                                    DSL.val(from.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)),
                                    DSL.val(to.plusDays(2).atStartOfDay().atOffset(ZoneOffset.UTC))))
                    .orderBy(START)
                    .fetch(r -> new Bodies.Closure(
                            r.get(LOCATION_CLOSURES.ID),
                            r.get(START).toInstant(),
                            r.get(END).toInstant(),
                            r.get(LOCATION_CLOSURES.REASON)));
        });
    }

    public Bodies.Closure addClosure(OrgAccess access, UUID locationId, Bodies.ClosureInput input) {
        access.require(SETTINGS);
        if (!input.endsAt().isAfter(input.startsAt())
                || Duration.between(input.startsAt(), input.endsAt()).toDays() > 366) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "A closure must end after it starts (at most a year).");
        }
        return transactions.execute(status -> {
            location(access, locationId);
            UUID id = dsl.insertInto(LOCATION_CLOSURES)
                    .set(LOCATION_CLOSURES.ORG_ID, access.orgId())
                    .set(LOCATION_CLOSURES.LOCATION_ID, locationId)
                    .set(LOCATION_CLOSURES.CLOSED_DURING, (Object) DSL.field(
                            "tstzrange({0}, {1}, '[)')",
                            Object.class,
                            DSL.val(OffsetDateTime.ofInstant(input.startsAt(), ZoneOffset.UTC)),
                            DSL.val(OffsetDateTime.ofInstant(input.endsAt(), ZoneOffset.UTC))))
                    .set(LOCATION_CLOSURES.REASON, input.reason())
                    .returning(LOCATION_CLOSURES.ID)
                    .fetchSingle(LOCATION_CLOSURES.ID);
            return new Bodies.Closure(id, input.startsAt(), input.endsAt(), input.reason());
        });
    }

    public void removeClosure(OrgAccess access, UUID locationId, UUID closureId) {
        access.require(SETTINGS);
        transactions.executeWithoutResult(status -> {
            int deleted = dsl.deleteFrom(LOCATION_CLOSURES)
                    .where(LOCATION_CLOSURES.ID.eq(closureId), LOCATION_CLOSURES.LOCATION_ID.eq(locationId))
                    .execute();
            if (deleted == 0) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
        });
    }

    // --- service areas ----------------------------------------------------------------------------------------

    public List<Bodies.ServiceArea> serviceAreas(OrgAccess access, UUID locationId) {
        return transactions.execute(status -> {
            location(access, locationId);
            return dsl.select(SERVICE_AREAS.asterisk(), LATITUDE, LONGITUDE)
                    .from(SERVICE_AREAS)
                    .where(SERVICE_AREAS.LOCATION_ID.eq(locationId))
                    .orderBy(SERVICE_AREAS.NAME)
                    .fetch(this::area);
        });
    }

    public Bodies.ServiceArea saveServiceArea(
            OrgAccess access, UUID locationId, UUID areaId, Bodies.ServiceAreaInput input) {
        access.require(SETTINGS);
        boolean circle = input.center() != null || input.radiusMeters() != null;
        if (circle && (input.center() == null || input.radiusMeters() == null)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A circle needs both a center and a radius.");
        }
        if (!circle && input.adminAreaId() == null) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "Give an administrative area or a center and radius.");
        }
        return transactions.execute(status -> {
            LocationsRecord location = location(access, locationId);
            if (input.adminAreaId() != null
                    && !dsl.fetchExists(ADMINISTRATIVE_AREAS, ADMINISTRATIVE_AREAS.ID.eq(input.adminAreaId()))) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown administrative area.");
            }
            // One statement: the table's check needs center and radius together.
            java.util.Map<Field<?>, Object> values = new java.util.LinkedHashMap<>();
            values.put(SERVICE_AREAS.NAME, input.name().strip());
            values.put(SERVICE_AREAS.ADMIN_AREA_ID, input.adminAreaId());
            values.put(SERVICE_AREAS.RADIUS_M, input.radiusMeters());
            values.put(SERVICE_AREAS.TRAVEL_FEE_MINOR, input.travelFeeMinor());
            values.put(SERVICE_AREAS.CURRENCY_CODE, location.getCurrencyCode());
            values.put(SERVICE_AREAS.TRAVEL_MINUTES, input.travelMinutes().shortValue());
            values.put(SERVICE_AREAS.IS_ACTIVE, input.isActive() == null || input.isActive());
            values.put(
                    SERVICE_AREAS.CENTER,
                    input.center() == null
                            ? null
                            : DSL.field(
                                    "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography",
                                    Object.class,
                                    DSL.val(input.center().longitude()),
                                    DSL.val(input.center().latitude())));
            UUID id;
            if (areaId == null) {
                values.put(SERVICE_AREAS.ORG_ID, access.orgId());
                values.put(SERVICE_AREAS.LOCATION_ID, locationId);
                id = dsl.insertInto(SERVICE_AREAS)
                        .set(values)
                        .returning(SERVICE_AREAS.ID)
                        .fetchSingle(SERVICE_AREAS.ID);
            } else {
                int updated = dsl.update(SERVICE_AREAS)
                        .set(values)
                        .where(SERVICE_AREAS.ID.eq(areaId), SERVICE_AREAS.LOCATION_ID.eq(locationId))
                        .execute();
                if (updated == 0) {
                    throw new ApiException(ProblemCode.NOT_FOUND);
                }
                id = areaId;
            }
            return dsl.select(SERVICE_AREAS.asterisk(), LATITUDE, LONGITUDE)
                    .from(SERVICE_AREAS)
                    .where(SERVICE_AREAS.ID.eq(id))
                    .fetchSingle(this::area);
        });
    }

    public void removeServiceArea(OrgAccess access, UUID locationId, UUID areaId) {
        access.require(SETTINGS);
        transactions.executeWithoutResult(status -> {
            int deleted = dsl.deleteFrom(SERVICE_AREAS)
                    .where(SERVICE_AREAS.ID.eq(areaId), SERVICE_AREAS.LOCATION_ID.eq(locationId))
                    .execute();
            if (deleted == 0) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
        });
    }

    private Bodies.ServiceArea area(org.jooq.Record r) {
        Double latitude = r.get(LATITUDE);
        return new Bodies.ServiceArea(
                r.get(SERVICE_AREAS.ID),
                r.get(SERVICE_AREAS.LOCATION_ID),
                r.get(SERVICE_AREAS.NAME),
                r.get(SERVICE_AREAS.ADMIN_AREA_ID),
                latitude == null ? null : new Bodies.GeoPoint(latitude, r.get(LONGITUDE)),
                r.get(SERVICE_AREAS.RADIUS_M),
                r.get(SERVICE_AREAS.TRAVEL_FEE_MINOR),
                r.get(SERVICE_AREAS.CURRENCY_CODE),
                r.get(SERVICE_AREAS.TRAVEL_MINUTES),
                r.get(SERVICE_AREAS.IS_ACTIVE));
    }

    /** The location, if the member may work there (404 otherwise, like any unknown row). */
    private LocationsRecord location(OrgAccess access, UUID locationId) {
        LocationsRecord location =
                dsl.selectFrom(LOCATIONS).where(LOCATIONS.ID.eq(locationId)).fetchOne();
        if (location == null || !access.canAccessLocation(locationId)) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return location;
    }
}
