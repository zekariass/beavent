package com.beavent.backend.payments.internal.provider;

import java.util.List;
import java.util.Map;

/**
 * One payment provider (docs/04 §4). Stateless; translates the provider's vocabulary into normalised states and
 * failure codes. Every create call carries our reference, so a retry can never charge twice: before retrying an
 * unclear create, callers {@link #findCollection look the collection up} by that reference.
 */
public interface PaymentProviderAdapter {

    /** {@code ARIFPAY}, {@code CHAPA} (the {@code payments.providers} code). */
    String providerCode();

    /** Who holds the money with this provider: {@code PLATFORM_COLLECTS} or {@code PROVIDER_SPLIT} (docs/07 L2). */
    String collectionMode();

    /** Whether refunds can go through the provider's API; when false they are paid by hand (docs/07 §3.1). */
    boolean canRefundByApi();

    /**
     * Whether a notification's content can be acted on directly (signed by the provider). When false, a notification
     * only says "look this collection up" and the lookup decides.
     */
    boolean notificationsAreAuthoritative();

    CollectionResult createCollection(CollectionRequest request);

    /** Looks a collection up by the provider's id (session id, tx_ref). */
    StatusResult queryCollection(String providerReference);

    /** Looks a collection up by our reference (nonce, tx_ref); null when the provider has none. */
    StatusResult findCollection(String reference);

    /** Best effort: stops an unpaid collection. */
    void cancelCollection(String providerReference);

    /**
     * Checks a notification's signature over the raw body.
     *
     * @return true when signed and valid; false when the signature is wrong, or the provider does not sign
     */
    boolean verifySignature(byte[] rawBody, Map<String, String> headers);

    /** The events in a notification body (already known to be JSON). */
    List<NormalizedEvent> parseNotification(byte[] rawBody);

    /** @throws UnsupportedOperationException when {@link #canRefundByApi()} is false */
    RefundResult refund(RefundRequest request);

    /** @throws UnsupportedOperationException when {@link #canRefundByApi()} is false */
    StatusResult queryRefund(String providerReference);
}
