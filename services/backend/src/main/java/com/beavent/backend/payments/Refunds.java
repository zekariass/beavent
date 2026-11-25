package com.beavent.backend.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * Giving deposits back (plan 3.6, docs/04 §8). When a booking is cancelled, declined or not attended, the business's
 * terms decide what it keeps and the rest is refunded. With Chapa the provider's refund API pays it; with ArifPay
 * finance pays it by hand from a queue and marks it paid. Every step posts to the ledger.
 */
public interface Refunds {

    /**
     * Settles an order that fell through, once: refunds what the terms give back, records what the business keeps and
     * adjusts what it is owed. Safe to repeat; does nothing for an order that is still on.
     */
    void settleOrder(UUID orgId, UUID orderId, Instant now);

    /** Settles the orders whose money was put on hold for a decision (the safety net behind the events). */
    int sweep(Instant now);

    /** Starts approved refunds through the provider's API and follows the ones it is working on; returns how many moved. */
    int processApiRefunds(Instant now);

    /**
     * What has been refunded or is on its way for an order.
     *
     * @param state {@code ON_THE_WAY}, {@code SENT} (all of it paid), {@code CREDITED} (all of it back on a gift card or in
     *     the customer's balance) or {@code NONE} (settled, and the business kept all of it)
     */
    record Summary(long amountMinor, String currency, String state) {}

    /** The order's refund as the customer sees it; {@code null} while the order is not settled and nothing is refunded. */
    Summary summary(UUID orgId, UUID orderId);

    // --- the finance desk (the caller checks who may, and audits it) ----------------------------------------------

    /** Approves a refund that waits for a decision (a duplicate payment). */
    void approve(UUID refundId, UUID byUserId, String note);

    /**
     * Records that the refund was paid to the customer by hand ({@code reference}: the transfer's reference); the
     * money leaves the provider account in the ledger and the customer is told.
     */
    void markPaid(UUID refundId, UUID byUserId, String reference, String note);
}
