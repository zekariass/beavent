package com.beavent.backend.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Which start times can be booked at a location, and with whom (docs/08-availability-engine.md). The answer is
 * advisory: the reservation ledger's exclusion constraints decide at booking time. Call inside a transaction with the
 * organization's context (a public request binds it from the location).
 */
public interface Availability {

    /**
     * One service in the booking.
     *
     * @param addonIds add-ons offered with the service; they lengthen the appointment
     * @param staffId the chosen person, or {@code null} for "anyone available"
     */
    record Item(UUID variantId, List<UUID> addonIds, UUID staffId) {
        public Item {
            addonIds = addonIds == null ? List.of() : List.copyOf(addonIds);
        }
    }

    /**
     * @param from first local date (inclusive) in the location's time zone
     * @param to last local date (inclusive)
     * @param serviceAreaId set for an at-home booking (travel time of that area applies); {@code null} = at the venue
     */
    record Query(UUID locationId, List<Item> items, LocalDate from, LocalDate to, UUID serviceAreaId) {
        public Query {
            items = List.copyOf(items);
        }
    }

    /** The person assigned to one item of an offered slot (same order as the query's items). */
    record Assigned(UUID variantId, UUID staffId) {}

    /** A bookable start. {@code end} is when the customer is done. */
    record Offer(Instant start, Instant end, List<Assigned> items) {}

    record Result(ZoneId zone, List<Offer> slots) {}

    /**
     * @param now the request's clock (notice and horizon are measured from it)
     * @throws com.beavent.backend.platform.ApiException {@code NOT_FOUND} for an unknown location,
     *     {@code REFERENCE_NOT_FOUND} / {@code CONSTRAINT_VIOLATION} when a service, add-on, person or area cannot be
     *     booked here
     */
    Result find(Query query, Instant now);
}
