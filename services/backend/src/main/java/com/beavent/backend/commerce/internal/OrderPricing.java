package com.beavent.backend.commerce.internal;

import com.beavent.backend.billing.FeeEngine;
import com.beavent.backend.commerce.Orders;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prices a basket, pure (docs/04 §11–12): promotions on the gross line prices, then tax per line on what is left
 * (inclusive or exclusive), then the platform's fees per line on what the buyer pays for it. Buyer-paid fees become
 * {@code BUYER_FEE} lines (with the platform's VAT inside them) so the price shown is all-in (DD6). Every line
 * satisfies the database's arithmetic: {@code total = unit × quantity − discount (+ tax when exclusive)}.
 */
public final class OrderPricing {

    private OrderPricing() {}

    /** A basket line with what pricing needs to know about its catalogue item. */
    public record Item(Orders.LineInput input, UUID serviceId, UUID categoryId, String taxCategory) {}

    /**
     * @param ratesByCategory the seller's tax rate per tax category (absent: no tax on that category)
     * @param platformFeeRate the platform's VAT on its own fees, for {@code BUYER_FEE} lines (null: none)
     */
    public record Facts(
            boolean pricesIncludeTax,
            Map<String, TaxEngine.Rate> ratesByCategory,
            List<PromotionEngine.Promo> promotions,
            List<FeeEngine.Rule> feeRules,
            TaxEngine.Rate platformFeeRate,
            long manualDiscountMinor) {

        public Facts(
                boolean pricesIncludeTax,
                Map<String, TaxEngine.Rate> ratesByCategory,
                List<PromotionEngine.Promo> promotions,
                List<FeeEngine.Rule> feeRules,
                TaxEngine.Rate platformFeeRate) {
            this(pricesIncludeTax, ratesByCategory, promotions, feeRules, platformFeeRate, 0);
        }
    }

    /** The fee and, for a buyer-paid one, the index of the {@code BUYER_FEE} line that shows it. */
    public record ChargedFee(FeeEngine.Fee fee, Integer feeLineIndex) {}

    public record Result(
            List<Orders.PricedLine> lines,
            List<PromotionEngine.Applied> promotions,
            List<ChargedFee> fees,
            long subtotalMinor,
            long discountMinor,
            long taxMinor,
            long buyerFeesMinor,
            long totalMinor,
            long sellerFeesMinor) {}

    public static Result price(List<Item> items, Facts facts) {
        List<PromotionEngine.Line> promoLines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            promoLines.add(new PromotionEngine.Line(
                    i,
                    it.input().type(),
                    it.serviceId(),
                    it.categoryId(),
                    it.input().quantity(),
                    it.input().unitPriceMinor()));
        }
        List<PromotionEngine.Applied> applied = PromotionEngine.apply(promoLines, facts.promotions());
        long[] discounts = new long[items.size()];
        for (PromotionEngine.Applied a : applied) {
            for (int i = 0; i < discounts.length; i++) discounts[i] += a.discounts()[i];
        }

        shareManualDiscount(items, discounts, facts.manualDiscountMinor());

        List<Orders.PricedLine> lines = new ArrayList<>();
        List<FeeEngine.Line> feeBase = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            Orders.LineInput in = it.input();
            long gross = Math.multiplyExact(in.unitPriceMinor(), in.quantity());
            TaxEngine.Rate rate = facts.ratesByCategory().get(it.taxCategory());
            TaxEngine.Taxed taxed = TaxEngine.tax(gross - discounts[i], rate, facts.pricesIncludeTax());
            lines.add(new Orders.PricedLine(
                    i,
                    in.type(),
                    in.parentIndex(),
                    in.description(),
                    in.quantity(),
                    in.unitPriceMinor(),
                    discounts[i],
                    taxed.taxMinor(),
                    taxed.totalMinor(),
                    facts.pricesIncludeTax(),
                    it.taxCategory(),
                    taxed.taxMinor() > 0 ? rate.taxCode() : null,
                    taxed.taxMinor() > 0 ? rate.rate() : null));
            feeBase.add(new FeeEngine.Line(i, in.type(), taxed.totalMinor()));
        }

        BigDecimal vat = facts.platformFeeRate() == null
                ? BigDecimal.ZERO
                : facts.platformFeeRate().rate();
        List<ChargedFee> fees = new ArrayList<>();
        long buyerFees = 0;
        long sellerFees = 0;
        for (FeeEngine.Fee fee : FeeEngine.assess(facts.feeRules(), feeBase, vat)) {
            if (!"BUYER".equals(fee.payer())) {
                sellerFees += fee.withVatMinor();
                fees.add(new ChargedFee(fee, null));
                continue;
            }
            int index = lines.size();
            long unit = facts.pricesIncludeTax() ? fee.withVatMinor() : fee.amountMinor();
            lines.add(new Orders.PricedLine(
                    index,
                    "BUYER_FEE",
                    fee.lineIndex(),
                    feeDescription(fee.feeCode()),
                    1,
                    unit,
                    0,
                    fee.vatMinor(),
                    fee.withVatMinor(),
                    facts.pricesIncludeTax(),
                    "PLATFORM_FEE",
                    fee.vatMinor() > 0 ? facts.platformFeeRate().taxCode() : null,
                    fee.vatMinor() > 0 ? facts.platformFeeRate().rate() : null));
            buyerFees += fee.withVatMinor();
            fees.add(new ChargedFee(fee, index));
        }

        long subtotal = 0;
        long discount = 0;
        long tax = 0;
        long total = 0;
        for (Orders.PricedLine l : lines) {
            subtotal += Math.multiplyExact(l.unitPriceMinor(), l.quantity());
            discount += l.discountMinor();
            tax += l.taxMinor();
            total += l.totalMinor();
        }
        return new Result(lines, applied, fees, subtotal, discount, tax, buyerFees, total, sellerFees);
    }

    /**
     * A discount given by hand is shared between the lines it applies to in proportion to what is left of their price (the first line
     * with the largest share takes the odd coins), and is never more than they cost.
     */
    static void shareManualDiscount(List<Item> items, long[] discounts, long manualMinor) {
        if (manualMinor <= 0) return;
        long[] room = new long[items.size()];
        long roomTotal = 0;
        // A discount on a visit is on its services; on a sale of goods alone, on the goods.
        boolean services = items.stream()
                .anyMatch(it ->
                        it.input().type().equals("SERVICE") || it.input().type().equals("ADDON"));
        for (int i = 0; i < room.length; i++) {
            Orders.LineInput in = items.get(i).input();
            boolean eligible =
                    !services || in.type().equals("SERVICE") || in.type().equals("ADDON");
            room[i] = eligible ? Math.multiplyExact(in.unitPriceMinor(), in.quantity()) - discounts[i] : 0;
            roomTotal += room[i];
        }
        long want = Math.min(manualMinor, roomTotal);
        if (want <= 0) return;
        long given = 0;
        for (int i = 0; i < room.length; i++) {
            long share = Math.multiplyExact(want, room[i]) / roomTotal;
            discounts[i] += share;
            room[i] -= share;
            given += share;
        }
        // What is left after rounding down goes one coin at a time to the lines with the most room left.
        for (long left = want - given; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < room.length; i++) if (room[i] > room[best]) best = i;
            discounts[best]++;
            room[best]--;
        }
    }

    /** The snapshot wording of a fee line (the order keeps it; screens translate by type). */
    static String feeDescription(String feeCode) {
        return switch (feeCode) {
            case "BOOKING_FEE" -> "Booking fee";
            case "TICKET_SERVICE_FEE" -> "Service fee";
            case "PAYMENT_PROCESSING" -> "Payment processing fee";
            default -> "Fee";
        };
    }
}
