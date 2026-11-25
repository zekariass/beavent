package com.beavent.backend.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.commerce.internal.PromotionEngine;
import com.beavent.backend.commerce.internal.PromotionEngine.Applied;
import com.beavent.backend.commerce.internal.PromotionEngine.Line;
import com.beavent.backend.commerce.internal.PromotionEngine.Promo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Promotions on an order (docs/04, S-10): exact, never above what a line costs, best offer alone unless stackable. */
class PromotionEngineTest {

    private static final UUID BRAIDS = UUID.randomUUID();
    private static final UUID WASH = UUID.randomUUID();

    private static Promo promo(
            String kind, String percent, Long amount, Long max, Long min, boolean stackable, Set<UUID> services) {
        return new Promo(
                UUID.randomUUID(),
                null,
                kind,
                percent == null ? null : new BigDecimal(percent),
                amount,
                max,
                min,
                stackable,
                "ORGANIZATION",
                0,
                services,
                Set.of());
    }

    private static List<Line> basket() {
        return List.of(
                new Line(0, "SERVICE", BRAIDS, null, 1, 180_000),
                new Line(1, "ADDON", null, null, 1, 30_000),
                new Line(2, "SERVICE", WASH, null, 2, 45_000));
    }

    @Test
    void percentOffTakesThatShareOfEachLine() {
        List<Applied> a =
                PromotionEngine.apply(basket(), List.of(promo("PERCENT_OFF", "10", null, null, null, false, Set.of())));
        assertThat(a).singleElement().satisfies(x -> {
            assertThat(x.discounts()).containsExactly(18_000, 3_000, 9_000);
            assertThat(x.totalMinor()).isEqualTo(30_000);
        });
    }

    @Test
    void amountOffIsSharedExactlyAcrossTheTargetedLines() {
        List<Applied> a = PromotionEngine.apply(
                basket(), List.of(promo("AMOUNT_OFF", null, 10_000L, null, null, false, Set.of(BRAIDS, WASH))));
        assertThat(a).singleElement().satisfies(x -> {
            assertThat(x.totalMinor()).isEqualTo(10_000);
            assertThat(x.discounts()[1]).isZero(); // the add-on is not a targeted service
            assertThat(x.discounts()[0] + x.discounts()[2]).isEqualTo(10_000);
            assertThat(x.discounts()[0]).isEqualTo(6_667); // 180,000 : 90,000 = 2 : 1 of 10,000, largest remainder
        });
    }

    @Test
    void amountOffNeverTakesMoreThanTheLinesCost() {
        List<Applied> a = PromotionEngine.apply(
                List.of(new Line(0, "SERVICE", BRAIDS, null, 1, 5_000)),
                List.of(promo("AMOUNT_OFF", null, 50_000L, null, null, false, Set.of())));
        assertThat(a.getFirst().totalMinor()).isEqualTo(5_000);
    }

    @Test
    void fixedPriceAndFreeItem() {
        assertThat(PromotionEngine.apply(
                        basket(), List.of(promo("FIXED_PRICE", null, 150_000L, null, null, false, Set.of(BRAIDS)))))
                .singleElement()
                .satisfies(x -> assertThat(x.discounts()).containsExactly(30_000, 0, 0));
        // No targets: the cheapest discountable unit in the basket (the add-on), one of it.
        assertThat(PromotionEngine.apply(
                        basket(), List.of(promo("FREE_ITEM", null, null, null, null, false, Set.of()))))
                .singleElement()
                .satisfies(x -> assertThat(x.discounts()).containsExactly(0, 30_000, 0));
        // Targeted at the wash: one of its two units is free.
        assertThat(PromotionEngine.apply(
                        basket(), List.of(promo("FREE_ITEM", null, null, null, null, false, Set.of(WASH)))))
                .singleElement()
                .satisfies(x -> assertThat(x.discounts()).containsExactly(0, 0, 45_000));
    }

    @Test
    void maximumDiscountAndMinimumOrderAreHonoured() {
        assertThat(PromotionEngine.apply(
                        basket(), List.of(promo("PERCENT_OFF", "50", null, 20_000L, null, false, Set.of()))))
                .singleElement()
                .satisfies(x -> assertThat(x.totalMinor()).isEqualTo(20_000));
        assertThat(PromotionEngine.apply(
                        basket(), List.of(promo("PERCENT_OFF", "10", null, null, 1_000_000L, false, Set.of()))))
                .isEmpty();
    }

    @Test
    void theBestOfferWinsAloneUnlessTheyStack() {
        Promo ten = promo("PERCENT_OFF", "10", null, null, null, false, Set.of());
        Promo twenty = promo("PERCENT_OFF", "20", null, null, null, false, Set.of());
        List<Applied> alone = PromotionEngine.apply(basket(), List.of(ten, twenty));
        assertThat(alone).singleElement().satisfies(x -> assertThat(x.promo()).isSameAs(twenty));

        Promo stackA = promo("PERCENT_OFF", "20", null, null, null, true, Set.of());
        Promo stackB = promo("AMOUNT_OFF", null, 5_000L, null, null, true, Set.of());
        List<Applied> both = PromotionEngine.apply(basket(), List.of(stackA, stackB, ten));
        assertThat(both).hasSize(2);
        assertThat(both.stream().mapToLong(Applied::totalMinor).sum()).isEqualTo(60_000 + 5_000);
    }

    @Test
    void fundingFollowsWhoPaysForTheOffer() {
        Promo platform = new Promo(
                UUID.randomUUID(),
                null,
                "PERCENT_OFF",
                new BigDecimal("10"),
                null,
                null,
                null,
                false,
                "PLATFORM",
                0,
                Set.of(),
                Set.of());
        Promo shared = new Promo(
                UUID.randomUUID(),
                null,
                "PERCENT_OFF",
                new BigDecimal("10"),
                null,
                null,
                null,
                false,
                "SHARED",
                2_500,
                Set.of(),
                Set.of());
        assertThat(PromotionEngine.apply(basket(), List.of(platform)).getFirst().platformFundedMinor())
                .isEqualTo(30_000);
        assertThat(PromotionEngine.apply(basket(), List.of(shared)).getFirst().platformFundedMinor())
                .isEqualTo(7_500);
    }

    @Test
    void forRandomBasketsNothingIsDiscountedBelowZeroAndFeesAreNeverDiscounted() {
        Random random = new Random(24);
        String[] types = {"SERVICE", "ADDON", "PRODUCT", "TICKET", "BUYER_FEE", "TIP"};
        String[] kinds = {"PERCENT_OFF", "AMOUNT_OFF", "FIXED_PRICE", "FREE_ITEM"};
        for (int i = 0; i < 5_000; i++) {
            List<Line> lines = new ArrayList<>();
            int n = random.nextInt(1, 6);
            for (int k = 0; k < n; k++) {
                lines.add(new Line(
                        k,
                        types[random.nextInt(types.length)],
                        random.nextBoolean() ? BRAIDS : WASH,
                        null,
                        random.nextInt(1, 4),
                        random.nextLong(0, 500_000)));
            }
            List<Promo> promos = new ArrayList<>();
            for (int k = random.nextInt(1, 4); k > 0; k--) {
                String kind = kinds[random.nextInt(kinds.length)];
                promos.add(promo(
                        kind,
                        kind.equals("PERCENT_OFF") ? String.valueOf(random.nextInt(1, 101)) : null,
                        kind.equals("PERCENT_OFF") || kind.equals("FREE_ITEM") ? null : random.nextLong(1, 400_000),
                        random.nextBoolean() ? random.nextLong(1, 300_000) : null,
                        random.nextBoolean() ? random.nextLong(1, 200_000) : null,
                        random.nextBoolean(),
                        random.nextBoolean() ? Set.of() : Set.of(BRAIDS)));
            }
            long[] total = new long[n];
            for (Applied a : PromotionEngine.apply(lines, promos)) {
                long sum = 0;
                for (int k = 0; k < n; k++) {
                    assertThat(a.discounts()[k]).as("run %d line %d", i, k).isGreaterThanOrEqualTo(0);
                    total[k] += a.discounts()[k];
                    sum += a.discounts()[k];
                    if (!List.of("SERVICE", "ADDON", "PRODUCT", "TICKET")
                            .contains(lines.get(k).type())) {
                        assertThat(a.discounts()[k])
                                .as(
                                        "run %d: %s is never discounted",
                                        i, lines.get(k).type())
                                .isZero();
                    }
                }
                assertThat(a.totalMinor()).as("run %d", i).isEqualTo(sum);
                if (a.promo().maxDiscountMinor() != null)
                    assertThat(sum).isLessThanOrEqualTo(a.promo().maxDiscountMinor());
                assertThat(a.platformFundedMinor()).isBetween(0L, sum);
            }
            for (int k = 0; k < n; k++) {
                long gross = lines.get(k).unitPriceMinor() * lines.get(k).quantity();
                assertThat(total[k])
                        .as("run %d line %d discounts together stay within the price", i, k)
                        .isLessThanOrEqualTo(gross);
            }
        }
    }
}
