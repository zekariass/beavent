package com.beavent.backend.discovery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Being on the marketplace: a business asks to be listed, platform staff list it, send the request back or take it off
 * (plan step 8.1). A business that is not listed still has its own booking link and venue page; listing makes it
 * findable in search. Statuses: {@code HIDDEN} (the start), {@code PENDING_REVIEW}, {@code LISTED}, {@code DELISTED}
 * (taken off by staff; only staff can list it again).
 */
public interface Marketplace {

    /**
     * One thing a business needs before it can be listed.
     *
     * @param code {@code LOCATION} (an active location) or {@code SERVICE} (an active service people can book online
     *     at it)
     */
    record Requirement(String code, boolean met) {}

    /**
     * @param note why staff sent the request back or took the business off, until it asks again
     * @param canRequest the business may ask now: hidden, ready, and not taken off by staff
     */
    record Status(String status, String note, Instant changedAt, List<Requirement> requirements, boolean canRequest) {}

    /** What staff decide about a business. */
    enum Decision {
        /** Make it findable: from a request, from hidden, or back from taken-off. Needs every requirement met. */
        LIST,
        /** Send a request back (to hidden), with the reason. */
        REJECT,
        /** Take a listed business off the marketplace, with the reason. */
        DELIST
    }

    /** The result of a staff decision: what it was and what it became. */
    record Decided(String before, String after) {}

    Status status(UUID orgId);

    /** Asks to be listed. Hidden and ready; otherwise {@code CONFLICT} or {@code CONSTRAINT_VIOLATION}. */
    Status request(UUID orgId, UUID userId);

    /** Takes back a request, or takes a listed business off (it can ask again). */
    Status withdraw(UUID orgId, UUID userId);

    /**
     * Applies a staff decision and refreshes the business's listings at once.
     *
     * @throws com.beavent.backend.platform.ApiException {@code CONFLICT} when the decision does not fit the status
     *     (rejecting something not asked for, taking off something not listed) or the business is suspended, closed or
     *     restricted; {@code CONSTRAINT_VIOLATION} when listing a business that is not ready
     */
    Decided decide(UUID orgId, UUID staffUserId, Decision decision, String reason);
}
