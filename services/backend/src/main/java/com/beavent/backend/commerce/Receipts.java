package com.beavent.backend.commerce;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Receipts with gapless numbers per legal entity (plan 4.1; delivery and printing are plan 4.3). */
public interface Receipts {

    record Receipt(UUID id, UUID orderId, String number, Instant issuedAt) {}

    /** The receipt of a paid order: issued now with the legal entity's next number, or the one issued before. */
    Receipt issue(UUID orgId, UUID orderId, Instant now);

    /** The order's receipt; null when none was issued. */
    Receipt find(UUID orgId, UUID orderId);

    /** One line of what was sold. */
    record Line(String type, String description, int quantity, long unitMinor, long discountMinor, long totalMinor) {}

    /** Tax inside (or on top of) the prices, by code and rate. */
    record Tax(String code, java.math.BigDecimal rate, long taxableMinor, long taxMinor) {}

    /**
     * How a part of the sale was paid.
     *
     * @param method the payment method's code ({@code CASH}, {@code GIFT_CARD}, {@code STORE_CREDIT}, {@code TELEBIRR} …)
     * @param name the method's own name, for the ones the screens do not translate
     */
    record Tender(String method, String name, long amountMinor, Instant at) {}

    /** What a receipt shows: the seller, the sale, the tax and how it was paid. */
    record View(
            String number,
            Instant issuedAt,
            String orderNumber,
            String currency,
            String timezone,
            String business,
            String tin,
            String location,
            String landmark,
            String phone,
            String customer,
            boolean pricesIncludeTax,
            List<Line> lines,
            long subtotalMinor,
            long discountMinor,
            long taxMinor,
            long totalMinor,
            List<Tax> taxes,
            List<Tender> tenders) {}

    /** The receipt of an order of the business, for the front desk (the business's context bound). */
    View view(UUID orgId, UUID orderId);

    /** The receipt a link opens; null when the link is not valid (never made, or replaced by a newer one). */
    View byToken(String token);

    /**
     * Sends the customer a link to their receipt by text message (email without a phone number), in their language. A
     * new link replaces the one sent before.
     *
     * @param via {@code SMS} or {@code EMAIL}
     */
    record Sent(String via) {}

    Sent send(UUID orgId, UUID orderId, UUID byUserId, Instant now);
}
