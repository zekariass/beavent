package com.beavent.backend.payments;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Value a payer can spend on an order instead of money through the provider: a business's gift card, or the customer's
 * balance with that business (plan 3.8). Implemented by the module that keeps it (retention). Every call joins the
 * caller's transaction, so spending and the payment it pays for commit together.
 */
public interface StoredValue {

    enum Kind {
        GIFT_CARD,
        BALANCE
    }

    /** What can be spent: a card (found by its code) or a balance. */
    record Source(Kind kind, UUID id, UUID orgId, long availableMinor, String currency) {}

    /** The business's gift card with this code, when it can be spent now (found, active, not expired, something left). */
    Optional<Source> giftCard(UUID orgId, String code, Instant now);

    /** The customer's balance with the business, when there is something in it. */
    Optional<Source> balance(UUID orgId, UUID customerId, String currency);

    /** Spends {@code amountMinor}; {@code CONFLICT} when there is not that much left. */
    void spend(Source source, long amountMinor, UUID orderId, UUID attemptId, UUID actorUserId);

    /** Gives back what a refunded tender had spent. */
    void restoreGiftCard(UUID orgId, UUID giftCardId, long amountMinor, UUID orderId, UUID attemptId);

    void restoreBalance(
            UUID orgId,
            UUID customerId,
            String currency,
            long amountMinor,
            UUID orderId,
            UUID attemptId,
            UUID refundId);

    /** A refund kept as credit: adds to the customer's balance with the business. */
    void creditBalance(
            UUID orgId,
            UUID customerId,
            String currency,
            long amountMinor,
            UUID orderId,
            UUID attemptId,
            UUID refundId);
}
