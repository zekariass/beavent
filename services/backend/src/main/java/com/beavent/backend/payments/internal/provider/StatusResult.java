package com.beavent.backend.payments.internal.provider;

/**
 * The provider's answer to "what happened to this collection or refund", from a server-side lookup with our key.
 * Fulfilment checks {@code amountMinor}, {@code currency} and {@code reference} against our records, never only the
 * status.
 *
 * @param reference our reference as the provider echoes it (nonce / tx_ref)
 * @param rawStatus the provider's own status text, kept for support
 */
public record StatusResult(
        AttemptStatus status,
        Long amountMinor,
        String currency,
        String reference,
        String providerReference,
        FailureCode failure,
        String rawStatus) {}
