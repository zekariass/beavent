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
}
