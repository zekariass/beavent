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
            UUID giftCardProgramId,
            UUID productVariantId) {

        /** A line that is not a product. */
        public LineInput(
                String type,
                UUID serviceVariantId,
                UUID addonId,
                UUID staffId,
                Integer parentIndex,
                String description,
                int quantity,
                long unitPriceMinor,
                UUID giftCardProgramId) {
            this(
                    type,
                    serviceVariantId,
                    addonId,
                    staffId,
                    parentIndex,
                    description,
                    quantity,
                    unitPriceMinor,
                    giftCardProgramId,
                    null);
        }

        /** A line that is not a gift card or a product. */
        public LineInput(
                String type,
                UUID serviceVariantId,
                UUID addonId,
                UUID staffId,
                Integer parentIndex,
                String description,
                int quantity,
                long unitPriceMinor) {
            this(
                    type,
                    serviceVariantId,
                    addonId,
                    staffId,
                    parentIndex,
                    description,
                    quantity,
                    unitPriceMinor,
                    null,
                    null);
        }
    }

    /**
     * What is being bought.
     *
     * @param customerId the business's client, when known (eligibility for new- or returning-client promotions)
     * @param serviceStart when the visit starts, for off-peak promotions; null when not timed
     * @param at when the order is priced (rates, rules and promotions in force then)
     * @param manualDiscountMinor a discount the front desk gives at the counter, shared between the lines in proportion
     *     to what they cost (after any promotion); never more than the lines cost
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
            Instant at,
            long manualDiscountMinor) {

        /** A basket with no manual discount. */
        public Basket(
                UUID locationId,
                UUID customerId,
                UUID buyerUserId,
                String channel,
                String currency,
                List<LineInput> lines,
                String promoCode,
                Instant serviceStart,
                Instant at) {
            this(locationId, customerId, buyerUserId, channel, currency, lines, promoCode, serviceStart, at, 0);
        }
    }

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

    /**
     * @param lineIds the id of the order line made for each line of the basket, by its index
     */
    record Placed(UUID orderId, String number, Quote quote, java.util.Map<Integer, UUID> lineIds) {}

    /** A product to add to an order: priced at {@code unitPriceMinor}, taxed as goods. */
    record ProductLine(UUID productVariantId, String description, int quantity, long unitPriceMinor) {}

    /**
     * Adds retail products to an order that stands (confirmed): priced, taxed and given their tax snapshots like any
     * line, the order's totals going up by what they cost.
     *
     * @return the id of the order line made for each product, in the order given; the total they add is the sum of the
     *     lines' totals
     */
    List<UUID> addProducts(UUID orgId, UUID orderId, List<ProductLine> products);

    /**
     * A tip left at the counter for one member of the team.
     *
     * @param staffId who it is for
     */
    record Tip(UUID staffId, String description, long amountMinor) {}

    /**
     * Adds tips to an order that stands (no tax, no platform fee): one {@code TIP} line each, and the order's total goes
     * up by their sum. The order's payment schedule is the caller's to adjust.
     */
    void addTips(UUID orgId, UUID orderId, List<Tip> tips);

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
