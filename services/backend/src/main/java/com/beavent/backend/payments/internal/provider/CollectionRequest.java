package com.beavent.backend.payments.internal.provider;

import java.net.URI;
import java.time.Instant;

/**
 * Collect {@code amountMinor} for one payment attempt.
 *
 * @param reference our unique reference (the attempt id): ArifPay's {@code nonce}, Chapa's {@code tx_ref}
 * @param description what is paid for, shown on the provider's page
 * @param phone the payer's number in E.164, or null
 * @param returnUrl where the browser goes after paying (never proof of payment)
 * @param notifyUrl where the provider calls us
 * @param expiresAt when the provider should stop accepting payment
 * @param split for {@code PROVIDER_SPLIT} only: the seller's subaccount and the platform's share; null otherwise
 */
public record CollectionRequest(
        String reference,
        long amountMinor,
        String currency,
        String description,
        String phone,
        String email,
        String language,
        URI returnUrl,
        URI cancelUrl,
        URI errorUrl,
        URI notifyUrl,
        Instant expiresAt,
        Split split) {

    public record Split(String subAccountId, long platformShareMinor) {}
}
