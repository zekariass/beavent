package com.beavent.backend.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * What a paid order is for, implemented by the module that sold it (appointments today; tickets and gift cards later).
 * Called once per captured payment, in the payment's transaction.
 */
public interface PaymentFulfilment {

    enum Outcome {
        /** The order is confirmed (or was already). */
        CONFIRMED,
        /** The order cannot be honoured any more (the time is gone, it was cancelled): the money goes back. */
        REFUND,
        /** This module does not own the order. */
        NOT_MINE
    }

    /**
     * @param late true when the order had already expired or been closed before the payment arrived
     */
    Outcome onPaid(UUID orgId, UUID orderId, long paidMinor, boolean late, Instant now);

    /** Whether what was sold has been delivered: the seller's money is released only after it (plan 3.5). */
    enum DeliveryState {
        /** Done: the service took place (or the time has passed without a complaint). */
        DELIVERED,
        /** Not yet: still ahead, or waiting for the business to confirm. */
        PENDING,
        /** Will not happen (cancelled, no-show, expired): the money waits for a decision on the refund. */
        VOID
    }

    /** @param endsAt when the service ends (or ended); the payout delay counts from it */
    record Delivery(DeliveryState state, Instant endsAt) {}

    /** The delivery state of an order this module sold; {@code null} for an order that is not its own. */
    default Delivery delivery(UUID orgId, UUID orderId, Instant now) {
        return null;
    }
}
