package com.beavent.backend.discovery;

import java.time.Instant;
import java.util.UUID;

/**
 * The projector: keeps {@code discovery.listings} in step with the places and events it describes. It runs on a
 * schedule (every minute by default), so a change shows in search within about a minute; the methods here let a
 * caller refresh one thing now (tests, an admin action, a business that just got approved).
 */
public interface Listings {

    /**
     * What a refresh changed.
     *
     * @param listed listings now visible in search
     * @param unlisted listings hidden because the place or event is no longer eligible
     */
    record Result(int listed, int unlisted) {}

    /** Projects one place, or hides it when it is not eligible (business listed and active, location active). */
    Result refreshLocation(UUID locationId, Instant now);

    /** Projects one event, or hides it when it is not public and on sale, or is over. */
    Result refreshEvent(UUID eventId, Instant now);

    /** Everything: all eligible places and events, and hides what no longer qualifies. */
    Result refreshAll(Instant now);
}
