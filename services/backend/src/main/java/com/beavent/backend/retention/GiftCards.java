package com.beavent.backend.retention;

import com.beavent.backend.payments.PaymentLinks;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A business's gift cards (plan 3.8): the programme it sells (amounts, validity), the cards bought and delivered, and
 * the card a customer buys online. The value is the business's own: the money for a sold card is collected like any
 * payment and belongs to the business; spending a card pays for a booking without new money arriving.
 */
public interface GiftCards {

    /**
     * What the business sells.
     *
     * @param presetsMinor amounts offered as buttons; any amount between the limits may be chosen too
     * @param validityMonths how long a card is valid after it is delivered; null for no expiry
     * @param sellOnline whether customers can buy cards on the business's page
     */
    record Program(
            UUID id,
            String name,
            String currency,
            List<Long> presetsMinor,
            long minMinor,
            long maxMinor,
            Integer validityMonths,
            boolean sellOnline) {}

    record ProgramInput(
            String name,
            List<Long> presetsMinor,
            long minMinor,
            long maxMinor,
            Integer validityMonths,
            boolean sellOnline) {}

    /** The business's programme, or {@code null} when it has none yet. */
    Program program(UUID orgId);

    /** Creates or updates the business's programme. */
    Program saveProgram(UUID orgId, ProgramInput input);

    /**
     * One card as the business sees it; the code is never shown again after delivery.
     *
     * @param status {@code PENDING_DELIVERY}, {@code ACTIVE}, {@code DEPLETED}, {@code EXPIRED} or {@code VOID}
     */
    record CardRow(
            UUID id,
            String codeLast4,
            long initialMinor,
            long balanceMinor,
            String currency,
            String status,
            String recipientName,
            String purchaserName,
            Instant createdAt,
            Instant deliverAt,
            Instant deliveredAt,
            Instant expiresAt) {}

    /** Paid cards, newest first. */
    List<CardRow> cards(UUID orgId, UUID beforeCardId, int limit);

    /** Voids a card that was issued by mistake or lost: what is left on it is written off. Audited. */
    void voidCard(UUID orgId, UUID cardId, UUID actorUserId, String reason);

    /**
     * What a customer buys.
     *
     * @param recipientPhone E.164, or null
     * @param deliverAt when to send it; null for as soon as it is paid
     */
    record Purchase(
            UUID locationId,
            long amountMinor,
            String recipientName,
            String recipientPhone,
            String recipientEmail,
            String message,
            Instant deliverAt) {}

    /**
     * The card is created, waiting for payment, with a link to pay (no sign-in needed beyond the buyer's own).
     *
     * @param link the payment link of the order
     */
    record Purchased(UUID cardId, UUID orderId, PaymentLinks.Created link) {}

    /**
     * Starts buying a card: places the order (30 minutes to pay) and makes the payment link. The card is delivered
     * after the payment is confirmed. Call with the business's context bound for the buyer.
     *
     * @throws com.beavent.backend.platform.ApiException {@code VALIDATION_FAILED} for an amount outside the programme's
     *     limits or a recipient without a way to be reached, {@code CONFLICT} when the business does not sell cards
     */
    Purchased purchase(UUID orgId, UUID buyerUserId, Purchase purchase, Instant now);

    // --- jobs (run by a worker; callable with a chosen time) --------------------------------------------------------

    /** Issues a code to every paid card that is due and sends it to its recipient; returns how many. */
    int deliverDue(Instant now);

    /** Writes off cards that passed their end; returns how many. */
    int expireDue(Instant now);

    /** Closes orders for cards nobody paid in time and voids those cards; returns how many. */
    int closeUnpaid(Instant now);
}
