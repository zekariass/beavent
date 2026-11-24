package com.beavent.backend.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the business behind a public link so that anonymous reads (venue page, availability, booking) can run in
 * that business's database context, read-only (docs/05 §5: "checkout or public page for organization X").
 *
 * <p>Only locations that accept online bookings of a business in good standing resolve; everything else looks like a
 * location that does not exist.
 */
public interface PublicLocations {

    /**
     * @param orgId the business; bind {@code RequestContext.forOrganization(null, orgId)} for the reads that follow
     * @param timezone IANA zone of the location
     */
    record Bookable(UUID orgId, UUID locationId, String timezone) {}

    /** The location if it is active, takes online bookings and its business is active or still onboarding. */
    Optional<Bookable> findBookable(UUID locationId);
}
