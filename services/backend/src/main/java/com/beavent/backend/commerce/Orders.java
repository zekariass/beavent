package com.beavent.backend.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pricing and placing orders (plan 3.2). Call inside a transaction with the organization's context. Prices, discounts,
 * tax and the platform's fees are computed by the same code for a quote and for the order placed from it.
 */
public interface Orders {

    /**
     * One line to sell. Catalogue references decide the tax category and promotion targeting.
     *
     * @param type {@code SERVICE}, {@code ADDON}, {@code TRAVEL_FEE} …
     * @param parentIndex for an add-on, the index of its service line in the basket
     */
    record LineInput(
            String type,
            UUID serviceVariantId,
            UUID addonId,
            UUID staffId,
            Integer parentIndex,
            String description,
            int quantity,
            long unitPriceMinor,
            UUID giftCardProgramId) {

        /** A line that is not a gift card. */
        public LineInput(
                String type,
                UUID serviceVariantId,
                UUID addonId,
                UUID staffId,
                Integer parentIndex,
                String description,
                int quantity,
                long unitPriceMinor) {
            this(type, serviceVariantId, addonId, staffId, parentIndex, description, quantity, unitPriceMinor, null);
        }
    }

    /**
     * What is being bought.
     *
     * @param customerId the business's client, when known (eligibility for new- or returning-client promotions)
     * @param serviceStart when the visit starts, for off-peak promotions; null when not timed
     * @param at when the order is priced (rates, rules and promotions in force then)
     */
    record Basket(
            UUID locationId,
            UUID customerId,
            UUID buyerUserId,
            String channel,
            String currency,
            List<LineInput> lines,
            String promoCode,
            Instant serviceStart,
            Instant at) {}

    /**
     * A priced line; fee lines ({@code BUYER_FEE}) are added after the basket's lines, pointing at the line they are
     * charged on.
     */
    record PricedLine(
            int index,
            String type,
            Integer parentIndex,
            String description,
            int quantity,
            long unitPriceMinor,
            long discountMinor,
            long taxMinor,
            long totalMinor,
            boolean taxInclusive,
            String taxCategory,
            String taxCode,
            BigDecimal taxRate) {}

    /**
     * What happened to a promo code the buyer typed: {@code APPLIED}, {@code UNKNOWN}, {@code EXPIRED},
     * {@code USED_UP}, {@code NOT_ELIGIBLE} (e.g. new clients only) or {@code NOT_APPLICABLE} (nothing it discounts,
     * or a better offer that does not combine).
     */
    record PromoResult(String code, String status, long discountMinor) {}

    /**
     * @param buyerFeesMinor the buyer-paid fees, VAT included (already inside {@code totalMinor})
     * @param sellerFeesMinor seller-paid fees with VAT (not shown to the buyer)
     * @param promo null when no code was typed
     */
    record Quote(
            String currency,
            boolean pricesIncludeTax,
            List<PricedLine> lines,
            long subtotalMinor,
            long discountMinor,
            long taxMinor,
            long buyerFeesMinor,
            long totalMinor,
            long sellerFeesMinor,
            PromoResult promo) {}

    record Placed(UUID orderId, String number, Quote quote) {}

    Quote quote(UUID orgId, Basket basket);

    /**
     * Places the order with the quote's numbers, frozen: lines, tax snapshots, promotion redemptions, fee assessments
     * and the next order number of the location's legal entity.
     *
     * @param status {@code PENDING_PAYMENT} (needs {@code expiresAt}) or {@code CONFIRMED}
     * @param actorUserId who placed it (the buyer, or a team member at the desk)
     */
    Placed place(UUID orgId, Basket basket, String status, Instant expiresAt, UUID actorUserId);
}
