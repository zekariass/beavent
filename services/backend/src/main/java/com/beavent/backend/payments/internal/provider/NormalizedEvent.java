package com.beavent.backend.payments.internal.provider;

/**
 * One event parsed from a provider notification. {@code claimedStatus} is what the notification says; whether it is
 * trusted depends on the provider ({@link PaymentProviderAdapter#notificationsAreAuthoritative()}).
 *
 * @param kind {@code COLLECTION} or {@code REFUND}
 * @param reference our reference (nonce / tx_ref / refund reference), when present
 * @param providerReference the provider's id (session id, Chapa reference, refund ref_id), when present
 */
public record NormalizedEvent(
        String kind, String eventType, String reference, String providerReference, AttemptStatus claimedStatus) {}
