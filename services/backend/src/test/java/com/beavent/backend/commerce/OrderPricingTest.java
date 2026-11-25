package com.beavent.backend.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.billing.FeeEngine;
import com.beavent.backend.commerce.Orders.LineInput;
import com.beavent.backend.commerce.Orders.PricedLine;
import com.beavent.backend.commerce.internal.OrderPricing;
import com.beavent.backend.commerce.internal.OrderPricing.Facts;
import com.beavent.backend.commerce.internal.OrderPricing.Item;
import com.beavent.backend.commerce.internal.OrderPricing.Result;
import com.beavent.backend.commerce.internal.PromotionEngine.Promo;
import com.beavent.backend.commerce.internal.TaxEngine.Rate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A whole order priced (docs/04 §11–12): the numbers satisfy the same arithmetic the database enforces on every line
 * and on the header, for any basket, rate, promotion and fee rule.
 */
class OrderPricingTest {

    private static final Rate VAT = new Rate(UUID.randomUUID(), "VAT", new BigDecimal("0.15"));
    private static final Rate TOT = new Rate(UUID.randomUUID(), "TOT", new BigDecimal("0.10"));
    private static final UUID BRAIDS = UUID.randomUUID();

    private static Item service(long unit, int quantity) {
        return new Item(
                new LineInput("SERVICE", UUID.randomUUID(), null, null, null, "Box braids", quantity, unit),
                BRAIDS,
                null,
                "SERVICE");
    }

    private static FeeEngine.Rule bookingFee(String payer, int bps, long fixed) {
        return new FeeEngine.Rule(
                UUID.randomUUID(), "BOOKING_FEE", payer, Set.of("SERVICE"), bps, fixed, null, null, true);
    }

    @Test
    void anAllInPriceInEthiopiaTaxIsInsideTheTotal() {
        Result r = OrderPricing.price(
                List.of(service(180_000, 1)), new Facts(true, Map.of("SERVICE", VAT), List.of(), List.of(), VAT));
        assertThat(r.totalMinor()).isEqualTo(180_000);
        assertThat(r.taxMinor()).isEqualTo(23_478); // 180,000 × 0.15 / 1.15
        assertThat(r.lines()).singleElement().satisfies(l -> {
            assertThat(l.taxInclusive()).isTrue();
            assertThat(l.taxCode()).isEqualTo("VAT");
        });
    }

    @Test
    void aBuyerFeeIsALineInTheAllInPriceWithTheirVatInsideIt() {
        Result r = OrderPricing.price(
                List.of(service(180_000, 1)),
                new Facts(true, Map.of("SERVICE", TOT), List.of(), List.of(bookingFee("BUYER", 500, 0)), VAT));
        // 5 % of the price the buyer pays for the service (tax included): 9,000 + 15 % VAT on the fee = 10,350.
        assertThat(r.buyerFeesMinor()).isEqualTo(10_350);
        assertThat(r.totalMinor()).isEqualTo(180_000 + 10_350);
        assertThat(r.lines()).hasSize(2);
        PricedLine fee = r.lines().get(1);
        assertThat(fee.type()).isEqualTo("BUYER_FEE");
        assertThat(fee.parentIndex()).isZero();
        assertThat(fee.totalMinor()).isEqualTo(10_350);
        assertThat(fee.taxMinor()).isEqualTo(1_350);
        assertThat(r.fees().get(0).feeLineIndex()).isEqualTo(1);
    }

    @Test
    void aSellerFeeIsNotOnTheBuyersOrder() {
        Result r = OrderPricing.price(
                List.of(service(180_000, 1)),
                new Facts(true, Map.of(), List.of(), List.of(bookingFee("SELLER", 500, 0)), VAT));
        assertThat(r.lines()).hasSize(1);
        assertThat(r.totalMinor()).isEqualTo(180_000);
        assertThat(r.sellerFeesMinor()).isEqualTo(10_350);
        assertThat(r.fees())
                .singleElement()
                .satisfies(f -> assertThat(f.feeLineIndex()).isNull());
    }

    @Test
    void aDiscountComesBeforeTaxAndFees() {
        Promo tenOff = new Promo(
                UUID.randomUUID(),
                null,
                "PERCENT_OFF",
                new BigDecimal("10"),
                null,
                null,
                null,
                false,
                "ORGANIZATION",
                0,
                Set.of(),
                Set.of());
        Result r = OrderPricing.price(
                List.of(service(100_000, 1)),
                new Facts(false, Map.of("SERVICE", VAT), List.of(tenOff), List.of(bookingFee("BUYER", 1_000, 0)), VAT));
        assertThat(r.discountMinor()).isEqualTo(10_000);
        PricedLine service = r.lines().get(0);
        assertThat(service.taxMinor()).isEqualTo(13_500); // 15 % of 90,000
        assertThat(service.totalMinor()).isEqualTo(103_500);
        // Fee: 10 % of what the buyer pays for the line (103,500) = 10,350, VAT 1,553 (1,552.5 rounds up).
        assertThat(r.buyerFeesMinor()).isEqualTo(10_350 + 1_553);
    }

    @Test
    void forRandomOrdersTheDatabaseArithmeticHolds() {
        Random random = new Random(3_2);
        for (int i = 0; i < 5_000; i++) {
            boolean inclusive = random.nextBoolean();
            List<Item> items = new ArrayList<>();
            for (int k = random.nextInt(1, 5); k > 0; k--) {
                items.add(service(random.nextLong(0, 600_000), random.nextInt(1, 4)));
            }
            Rate rate = random.nextInt(4) == 0 ? null : random.nextBoolean() ? VAT : TOT;
            List<Promo> promos = new ArrayList<>();
            if (random.nextBoolean()) {
                promos.add(new Promo(
                        UUID.randomUUID(),
                        null,
                        random.nextBoolean() ? "PERCENT_OFF" : "AMOUNT_OFF",
                        new BigDecimal(random.nextInt(1, 101)),
                        random.nextLong(1, 300_000),
                        random.nextBoolean() ? random.nextLong(1, 200_000) : null,
                        null,
                        false,
                        "ORGANIZATION",
                        0,
                        Set.of(),
                        Set.of()));
            }
            // PERCENT_OFF needs a percentage and AMOUNT_OFF an amount; the generator gives both, the engine reads its
            // own.
            List<FeeEngine.Rule> rules = new ArrayList<>();
            if (random.nextBoolean())
                rules.add(bookingFee("BUYER", random.nextInt(0, 2_000), random.nextLong(0, 3_000) + 1));
            if (random.nextBoolean()) rules.add(bookingFee("SELLER", random.nextInt(1, 1_500), 0));
            Result r = OrderPricing.price(
                    items, new Facts(inclusive, rate == null ? Map.of() : Map.of("SERVICE", rate), promos, rules, VAT));

            long sumTotal = 0;
            long sumTax = 0;
            long sumDiscount = 0;
            long sumGross = 0;
            for (PricedLine l : r.lines()) {
                long gross = l.unitPriceMinor() * l.quantity();
                // The checks the database makes on every order line.
                assertThat(l.discountMinor())
                        .as("run %d line %d discount", i, l.index())
                        .isBetween(0L, gross);
                assertThat(l.totalMinor())
                        .as("run %d line %d total = price − discount (+ tax when exclusive)", i, l.index())
                        .isEqualTo(gross - l.discountMinor() + (l.taxInclusive() ? 0 : l.taxMinor()));
                assertThat(l.taxMinor()).isBetween(0L, l.totalMinor());
                sumTotal += l.totalMinor();
                sumTax += l.taxMinor();
                sumDiscount += l.discountMinor();
                sumGross += gross;
            }
            // And on the header: totals are the sums of the lines (commerce.assert_order_totals).
            assertThat(r.totalMinor()).as("run %d", i).isEqualTo(sumTotal);
            assertThat(r.taxMinor()).isEqualTo(sumTax);
            assertThat(r.discountMinor()).isEqualTo(sumDiscount);
            assertThat(r.subtotalMinor()).isEqualTo(sumGross);
            assertThat(r.totalMinor()).isEqualTo(sumGross - sumDiscount + (inclusive ? 0 : sumTax));
            // Buyer fees are exactly the BUYER_FEE lines, and never inside the discounted base.
            assertThat(r.buyerFeesMinor())
                    .isEqualTo(r.lines().stream()
                            .filter(l -> l.type().equals("BUYER_FEE"))
                            .mapToLong(PricedLine::totalMinor)
                            .sum());
        }
    }
}
