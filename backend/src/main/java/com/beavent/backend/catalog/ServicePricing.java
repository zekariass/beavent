package com.beavent.backend.catalog;

import java.time.Instant;
import java.util.UUID;

/** Price and duration of a service variant in context. Call inside a transaction with the organization's context. */
public interface ServicePricing {

    enum Source {
        STAFF_AND_LOCATION,
        STAFF,
        LEVEL_AND_LOCATION,
        LEVEL,
        LOCATION,
        DEFAULT
    }

    record Price(
            UUID variantId, String priceType, long priceMinor, String currency, int durationMinutes, Source source) {}

    /**
     * The most specific price at {@code at}: staff + location, staff, staff's pricing level + location, pricing level,
     * location, then the variant's own price. Durations follow the same match when it sets one.
     *
     * @param locationId may be {@code null}
     * @param staffId may be {@code null} (any team member)
     * @throws com.beavent.backend.platform.ApiException NOT_FOUND for an unknown variant
     */
    Price resolve(UUID variantId, UUID locationId, UUID staffId, Instant at);
}
