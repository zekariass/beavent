package com.beavent.backend.stock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stock movements of retail products at a location (plan 4.5). Call inside a transaction with the organization's context;
 * the caller checks the permission. Quantities are whole units.
 */
public interface Stock {

    record Movement(
            UUID id,
            UUID variantId,
            String kind,
            long quantity,
            Long unitCostMinor,
            String reason,
            Instant at,
            UUID byUserId) {}

    /** Goods received (a delivery, a count that found more): needs a reason. */
    void receive(
            UUID orgId, UUID locationId, UUID variantId, long quantity, Long unitCostMinor, String reason, UUID userId);

    /**
     * A correction ({@code ADJUSTMENT}, plus or minus) or goods lost ({@code DAMAGE}, taken off): needs a reason.
     *
     * @param change what is added (positive) or taken off (negative), never zero
     * @throws com.beavent.backend.platform.ApiException {@code CONFLICT} when more would be taken off than is in stock
     */
    void adjust(UUID orgId, UUID locationId, UUID variantId, long change, String kind, String reason, UUID userId);

    /**
     * A sale: takes {@code quantity} off, tied to the order line. Nothing is done for a product whose stock is not kept.
     *
     * @throws com.beavent.backend.platform.ApiException {@code CONFLICT} when there are not that many
     */
    void sell(UUID orgId, UUID locationId, UUID variantId, long quantity, UUID orderLineId, UUID userId);

    /** A sale that did not happen: puts {@code quantity} back, tied to the order line it was taken for. */
    void restore(UUID orgId, UUID locationId, UUID variantId, long quantity, UUID orderLineId, UUID userId);

    /** How many are in stock; {@code Long.MAX_VALUE} for a product whose stock is not kept. */
    long available(UUID orgId, UUID locationId, UUID variantId);

    /** Newest first. */
    List<Movement> movements(UUID orgId, UUID locationId, UUID variantId, int limit);
}
