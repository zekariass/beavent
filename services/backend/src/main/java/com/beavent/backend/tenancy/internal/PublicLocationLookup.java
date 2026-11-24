package com.beavent.backend.tenancy.internal;

import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.tenancy.PublicLocations;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Looks a public location up with the system role: the caller has no workspace yet, that is what this finds. */
@Component
class PublicLocationLookup implements PublicLocations {

    private final DSLContext system;

    PublicLocationLookup(@SystemDb DSLContext system) {
        this.system = system;
    }

    @Override
    public Optional<Bookable> findBookable(UUID locationId) {
        return system.select(LOCATIONS.ORG_ID, LOCATIONS.TIMEZONE)
                .from(LOCATIONS)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(LOCATIONS.ORG_ID))
                .where(
                        LOCATIONS.ID.eq(locationId),
                        LOCATIONS.STATUS.eq("ACTIVE"),
                        LOCATIONS.ONLINE_BOOKING.isTrue(),
                        ORGANIZATIONS.STATUS.in("ACTIVE", "ONBOARDING"))
                .fetchOptional(r -> new Bookable(r.value1(), locationId, r.value2()));
    }
}
