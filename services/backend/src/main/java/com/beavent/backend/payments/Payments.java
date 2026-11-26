package com.beavent.backend.payments;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Collecting what an order costs online (plan 3.3), through the deployment's provider. Payments are tied to an order:
 * its payment schedule says what is due now online (a deposit, or the full price) and what is left for the venue.
 */
public interface Payments {

    /**
     * One part of what an order costs.
     *
     * @param kind {@code DEPOSIT}, {@code BALANCE} or {@code FULL}
     * @param collectAt {@code ONLINE} now, or {@code AT_VENUE}
     */
    record Installment(String kind, long amountMinor, String collectAt) {}

    /**
     * Writes the order's payment schedule (once, when the order is placed). Call in the transaction that placed the
     * order, with the organization's context.
     */
    void plan(UUID orgId, UUID orderId, String currency, List<Installment> installments);

    /** Where to send the payer, and what comes back to them. */
    record Urls(URI success, URI cancel, URI error) {}

    /**
     * The payer.
     *
     * @param phone E.164, or null
     */
    record Payer(UUID userId, String phone, String email, String language) {}

    /**
     * An online payment started for an order.
     *
     * @param redirectUrl the provider's page for the payer; null when the provider has none to show
     */
    record Started(
            UUID intentId, UUID attemptId, URI redirectUrl, Instant expiresAt, long amountMinor, String currency) {}

    /**
     * Starts (or resumes) the online payment of an unpaid order: an open attempt is returned as it is; otherwise a
     * new one is created with the provider. Nothing is charged until the payer approves on the provider's page.
     *
     * @param description what the payer is paying for, shown on the provider's page
     * @throws com.beavent.backend.platform.ApiException {@code HOLD_EXPIRED} when the order ran out of time or is no
     *     longer waiting for payment; {@code PAYMENT_UNAVAILABLE} when the provider cannot be reached
     */
    Started start(UUID orgId, UUID orderId, Payer payer, Urls urls, String description, Instant now);

    /**
     * Where an order's online payment stands.
     *
     * @param state {@code NOT_REQUIRED}, {@code AWAITING_PAYMENT} (nothing started yet, or the payer is paying),
     *     {@code PAID}, {@code FAILED} (the last attempt was declined; the payer may try again), {@code EXPIRED}
     * @param dueNowMinor the online amount (deposit or full price)
     * @param paidMinor what has been captured
     * @param atVenueMinor what is left for the venue
     * @param redirectUrl the provider's page of the open attempt, when one is open
     */
    record Status(
            String state,
            long dueNowMinor,
            long paidMinor,
            long atVenueMinor,
            String currency,
            Instant expiresAt,
            URI redirectUrl) {}

    Status status(UUID orgId, UUID orderId, Instant now);

    /** Like {@link #status}, after asking the provider about the order's open attempt (the payer came back). */
    Status check(UUID orgId, UUID orderId, Instant now);

    /**
     * Stops collecting for an order that is no longer wanted or ran out of time: open intents and attempts are closed
     * and the provider's session cancelled where it allows. A payment that still arrives is handled as a late
     * payment ({@link PaymentFulfilment}).
     */
    void cancel(UUID orgId, UUID orderId, String reason);

    /**
     * Pays part or all of what is due online with a gift card (found by its code) or the customer's balance with the
     * business, then answers where the order's payment stands. What a card or balance cannot cover is still paid
     * through the provider; the order is honoured when all of it is paid.
     *
     * @param payerUserId the signed-in payer for a balance; null for a gift card paid through a link
     * @throws com.beavent.backend.platform.ApiException {@code NOT_FOUND} for a gift card that cannot be used,
     *     {@code CONFLICT} when something is already being paid or the order cannot take it, {@code HOLD_EXPIRED}
     */
    Status tender(UUID orgId, UUID orderId, StoredValue.Kind kind, String code, UUID payerUserId, Instant now);

    /** What the person has in their balance with the business that could be spent on this order (0 for none). */
    long balanceFor(UUID orgId, UUID orderId, UUID userId);

    /**
     * Cash taken at the counter for what an order has still to be paid at the venue (plan 4.1): the whole of it, in
     * the caller's transaction. The payment is recorded as taken (a cash attempt tied to the drawer's session);
     * nothing goes to the provider or the ledger.
     *
     * @param amountMinor what stays in the drawer (what was handed over less the change); must equal what is due
     * @return the payment's id
     * @throws com.beavent.backend.platform.ApiException {@code CONFLICT} when nothing is due at the venue, or an online
     *     payment of it is in progress
     */
    UUID recordCash(UUID orgId, UUID orderId, UUID sessionId, long amountMinor, Instant now);

    /** What the customer has in their balance with the business, in the order's currency (0 for none). */
    long customerBalance(UUID orgId, UUID customerId, String currency);

    /**
     * Pays part or all of what an order has still to be paid at the venue with a gift card (its code) or the customer's
     * balance (plan 4.2), in a transaction of its own that commits what it takes: the card or balance is spent as much as
     * is due, a part payment splits what is due. Nothing goes to the provider or the ledger (the fees on what is paid at
     * the venue are plan 4.4).
     *
     * @return what was taken
     * @throws com.beavent.backend.platform.ApiException {@code NOT_FOUND} for a card that cannot be used, {@code CONFLICT}
     *     when nothing is due at the venue or the customer has no balance
     */
    long tenderAtCounter(UUID orgId, UUID orderId, StoredValue.Kind kind, String code, UUID userId, Instant now);
}
