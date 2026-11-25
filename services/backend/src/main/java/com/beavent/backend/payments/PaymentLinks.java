package com.beavent.backend.payments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payment links (plan 3.7, docs/04 §6.4): a link pays one payment of an order without the payer signing in, so a phone
 * booking can ask for its deposit by text message and a balance can be collected after the visit. The link carries a
 * random token (only its hash is kept); anyone holding it may pay that one payment, nothing else.
 */
public interface PaymentLinks {

    /**
     * A new link.
     *
     * @param token the secret part of the link's address, shown once
     * @param url the whole address the customer opens
     * @param kind {@code DEPOSIT}, {@code FULL} or {@code BALANCE}
     */
    record Created(
            UUID id, String token, String url, Instant expiresAt, long amountMinor, String currency, String kind) {}

    /**
     * Makes a link for what is due next on the order: the deposit (or full price) of an order waiting for payment, or
     * the balance of a confirmed booking. Any earlier live link for the same payment is revoked.
     *
     * @throws com.beavent.backend.platform.ApiException {@code CONFLICT} when nothing is due,
     *     {@code PAYMENT_UNAVAILABLE} when the business cannot take online payments
     */
    Created create(UUID orgId, UUID orderId, UUID createdBy, Instant now);

    /**
     * What the payer sees.
     *
     * @param state {@code OPEN}, {@code PAID} or {@code EXPIRED} (also a booking that was cancelled meanwhile)
     * @param purpose {@code DEPOSIT}, {@code FULL} or {@code BALANCE}
     */
    record Info(
            String state,
            String purpose,
            long amountMinor,
            String currency,
            Instant expiresAt,
            String business,
            String location,
            String reference,
            Instant startsAt,
            String timezone,
            List<String> items) {}

    /** @return {@code null} for a token that is not a link */
    Info info(String token, Instant now);

    /**
     * Starts (or resumes) the payment the link is for and answers where to send the payer.
     *
     * @throws com.beavent.backend.platform.ApiException {@code HOLD_EXPIRED} for a link that is no longer open,
     *     {@code PAYMENT_UNAVAILABLE} when the provider cannot be reached
     */
    Payments.Started start(String token, String language, Payments.Urls urls, Instant now);

    /** After the payer came back: asks the provider about the open attempt, then answers like {@link #info}. */
    Info check(String token, Instant now);
}
