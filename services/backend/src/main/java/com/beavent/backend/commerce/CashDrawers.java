package com.beavent.backend.commerce;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The cash drawer of a location (plan 4.1, docs/02 §2.6): opened with a float, kept by sales and by pay-ins, pay-outs and
 * drops, closed with a count that is compared with what should be there. Call inside a transaction with the
 * organization's context; the caller checks {@code pos.cash_drawer}.
 */
public interface CashDrawers {

    /** What went in or out of the drawer; amounts are signed (plus into the drawer). */
    record Movement(UUID id, String kind, long amountMinor, String note, Instant at, UUID byUserId) {}

    /**
     * A session of a drawer.
     *
     * @param expectedMinor what should be in the drawer: the float and every movement
     * @param countedMinor what was counted when it closed; null while open
     * @param varianceMinor counted less expected; null while open
     * @param movements newest first; empty in a history listing
     */
    record Session(
            UUID id,
            UUID locationId,
            String drawerName,
            String currency,
            String status,
            Instant openedAt,
            UUID openedBy,
            long openingFloatMinor,
            long salesMinor,
            long payInMinor,
            long payOutMinor,
            long expectedMinor,
            Long countedMinor,
            Long varianceMinor,
            Instant closedAt,
            List<Movement> movements) {}

    /** One session of any status, without its movements for a closed one; {@code NOT_FOUND} for one that is not the business's. */
    Session find(UUID orgId, UUID sessionId);

    /** The open session of the location's drawer; null when it is closed. */
    Session current(UUID orgId, UUID locationId);

    /**
     * Opens the location's drawer (created on first use) with the cash put in it.
     *
     * @throws com.beavent.backend.platform.ApiException {@code CONFLICT} when it is open already
     */
    Session open(UUID orgId, UUID locationId, long openingFloatMinor, UUID userId);

    /**
     * Cash put in ({@code PAY_IN}), taken out for an expense ({@code PAY_OUT}) or taken to the safe ({@code DROP});
     * {@code amountMinor} is positive.
     */
    Session movement(UUID orgId, UUID sessionId, String kind, long amountMinor, String note, UUID userId);

    /** Counts and closes the drawer; the answer holds what was expected and the variance. */
    Session close(UUID orgId, UUID sessionId, long countedMinor, UUID userId);

    /** Closed sessions of the location, newest first. */
    List<Session> history(UUID orgId, UUID locationId, int limit);

    /** Cash taken for an order: the amount that stays in the drawer (what was handed over less the change). */
    void recordSale(UUID orgId, UUID sessionId, UUID attemptId, long amountMinor, UUID userId);
}
