package com.beavent.backend.commerce.internal;

import com.beavent.backend.platform.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Promotions on an order, pure. Each promotion discounts the lines it targets (no targets: every discountable line);
 * discounts never exceed what is left of a line, are capped by the promotion's maximum and are split exactly (largest
 * remainder). A promotion that does not stack is used alone: the one that saves the buyer most wins, a code the buyer
 * typed winning ties; stackable promotions are added after it on what is left.
 */
public final class PromotionEngine {

    private PromotionEngine() {}

    /** Line types a promotion can discount (fees, tips and donations are never discounted). */
    static final Set<String> DISCOUNTABLE = Set.of("SERVICE", "ADDON", "PRODUCT", "TICKET");

    /**
     * A promotion already found valid for this buyer and time (status, dates, limits and eligibility are checked by
     * the caller).
     *
     * @param codeId the promo code the buyer typed, or null for an automatic promotion
     * @param fixedOrAmountMinor {@code AMOUNT_OFF}: the amount off the order; {@code FIXED_PRICE}: the unit price
     */
    public record Promo(
            UUID id,
            UUID codeId,
            String kind,
            BigDecimal percentOff,
            Long fixedOrAmountMinor,
            Long maxDiscountMinor,
            Long minOrderMinor,
            boolean stackable,
            String fundedBy,
            int platformShareBps,
            Set<UUID> services,
            Set<UUID> categories) {

        boolean targetsEverything() {
            return services.isEmpty() && categories.isEmpty();
        }
    }

    public record Line(int index, String type, UUID serviceId, UUID categoryId, int quantity, long unitPriceMinor) {
        long gross() {
            return Math.multiplyExact(unitPriceMinor, quantity);
        }
    }

    /** A promotion as applied: the discount on each line (by line index) and who pays for it. */
    public record Applied(Promo promo, long[] discounts, long totalMinor, long platformFundedMinor) {}

    public static List<Applied> apply(List<Line> lines, List<Promo> candidates) {
        long[] none = new long[lines.size()];
        List<Applied> alone = new ArrayList<>();
        for (Promo p : candidates) {
            Applied a = one(lines, p, none);
            if (a != null) alone.add(a);
        }
        if (alone.isEmpty()) return List.of();
        Applied best = alone.stream()
                .max(Comparator.comparingLong(Applied::totalMinor)
                        .thenComparing(a -> a.promo().codeId() != null))
                .orElseThrow();
        List<Applied> applied = new ArrayList<>();
        applied.add(best);
        if (!best.promo().stackable()) return applied;
        long[] used = best.discounts().clone();
        List<Applied> rest = alone.stream()
                .filter(a -> a != best && a.promo().stackable())
                .sorted(Comparator.comparingLong(Applied::totalMinor).reversed())
                .toList();
        for (Applied candidate : rest) {
            Applied a = one(lines, candidate.promo(), used);
            if (a == null) continue;
            applied.add(a);
            for (int i = 0; i < used.length; i++) used[i] += a.discounts()[i];
        }
        return applied;
    }

    /** One promotion on what is left of each line after {@code used}; null when it saves nothing. */
    static Applied one(List<Line> lines, Promo p, long[] used) {
        long orderGross = 0;
        for (Line l : lines) if (DISCOUNTABLE.contains(l.type())) orderGross += l.gross();
        if (p.minOrderMinor() != null && orderGross < p.minOrderMinor()) return null;
        long[] left = new long[lines.size()];
        boolean[] eligible = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            left[i] = l.gross() - used[i];
            eligible[i] = DISCOUNTABLE.contains(l.type())
                    && left[i] > 0
                    && (p.targetsEverything()
                            || (l.serviceId() != null && p.services().contains(l.serviceId()))
                            || (l.categoryId() != null && p.categories().contains(l.categoryId())));
        }
        long[] d = new long[lines.size()];
        switch (p.kind()) {
            case "PERCENT_OFF" -> {
                BigDecimal fraction = p.percentOff().movePointLeft(2);
                for (int i = 0; i < d.length; i++) if (eligible[i]) d[i] = MoneyMath.ofRate(left[i], fraction);
            }
            case "AMOUNT_OFF" -> {
                long[] weights = new long[d.length];
                long eligibleLeft = 0;
                for (int i = 0; i < d.length; i++) {
                    if (eligible[i]) {
                        weights[i] = left[i];
                        eligibleLeft += left[i];
                    }
                }
                d = MoneyMath.allocate(Math.min(p.fixedOrAmountMinor(), eligibleLeft), weights);
            }
            case "FIXED_PRICE" -> {
                for (int i = 0; i < d.length; i++) {
                    if (!eligible[i]) continue;
                    long atFixed = Math.multiplyExact(
                            p.fixedOrAmountMinor(), lines.get(i).quantity());
                    d[i] = Math.max(0, left[i] - atFixed);
                }
            }
            case "FREE_ITEM" -> {
                int cheapest = -1;
                for (int i = 0; i < d.length; i++) {
                    if (eligible[i]
                            && (cheapest < 0
                                    || lines.get(i).unitPriceMinor()
                                            < lines.get(cheapest).unitPriceMinor())) {
                        cheapest = i;
                    }
                }
                if (cheapest >= 0) d[cheapest] = Math.min(lines.get(cheapest).unitPriceMinor(), left[cheapest]);
            }
            default -> throw new IllegalArgumentException("Unknown promotion kind " + p.kind());
        }
        long total = 0;
        for (long x : d) total += x;
        if (p.maxDiscountMinor() != null && total > p.maxDiscountMinor()) {
            d = MoneyMath.allocate(p.maxDiscountMinor(), d);
            total = p.maxDiscountMinor();
        }
        if (total == 0) return null;
        long platform = switch (p.fundedBy()) {
            case "PLATFORM" -> total;
            case "SHARED" -> MoneyMath.ofBasisPoints(total, p.platformShareBps());
            default -> 0;
        };
        return new Applied(p, d, total, platform);
    }
}
