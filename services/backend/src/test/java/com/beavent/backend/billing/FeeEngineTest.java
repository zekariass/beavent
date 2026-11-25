package com.beavent.backend.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.billing.FeeEngine.Fee;
import com.beavent.backend.billing.FeeEngine.Line;
import com.beavent.backend.billing.FeeEngine.Rule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fee arithmetic (docs/04 §11): percentage plus fixed, capped, VAT on the fee, per line. */
class FeeEngineTest {

    private static Rule rule(String payer, int bps, long fixed, Long min, Long max, boolean vat, String... lineTypes) {
        return new Rule(UUID.randomUUID(), "BOOKING_FEE", payer, Set.of(lineTypes), bps, fixed, min, max, vat);
    }

    @Test
    void percentageAndFixedThenCapped() {
        Rule r = rule("BUYER", 500, 1_000, 2_000L, 10_000L, true, "SERVICE");
        assertThat(FeeEngine.amount(r, 10_000)).isEqualTo(2_000); // 500 + 1,000 = 1,500 → min 2,000
        assertThat(FeeEngine.amount(r, 100_000)).isEqualTo(6_000);
        assertThat(FeeEngine.amount(r, 10_000_000)).isEqualTo(10_000); // capped
    }

    @Test
    void feesAreChargedPerLineWithVatOnTheFee() {
        Rule r = rule("BUYER", 1_000, 0, null, null, true, "SERVICE");
        List<Fee> fees = FeeEngine.assess(
                List.of(r),
                List.of(new Line(0, "SERVICE", 180_000), new Line(1, "ADDON", 30_000), new Line(2, "SERVICE", 0)),
                new BigDecimal("0.15"));
        assertThat(fees).singleElement().satisfies(f -> {
            assertThat(f.lineIndex()).isZero();
            assertThat(f.baseMinor()).isEqualTo(180_000);
            assertThat(f.amountMinor()).isEqualTo(18_000);
            assertThat(f.vatMinor()).isEqualTo(2_700);
            assertThat(f.withVatMinor()).isEqualTo(20_700);
        });
    }

    @Test
    void aRuleWithoutVatAddsNone() {
        Rule r = rule("SELLER", 0, 500, null, null, false, "TICKET");
        assertThat(FeeEngine.assess(List.of(r), List.of(new Line(0, "TICKET", 10_000)), new BigDecimal("0.15")))
                .singleElement()
                .satisfies(f -> assertThat(f.vatMinor()).isZero());
    }

    @Test
    void capsAndMonotonicityHoldForAnyRuleAndAmount() {
        Random random = new Random(3);
        for (int i = 0; i < 10_000; i++) {
            int bps = random.nextInt(0, 3_000);
            long fixed = bps == 0 ? random.nextLong(1, 5_000) : random.nextLong(0, 5_000);
            Long min = random.nextBoolean() ? random.nextLong(0, 4_000) : null;
            Long max = random.nextBoolean() ? (min == null ? 0 : min) + random.nextLong(0, 100_000) : null;
            Rule r = rule("BUYER", bps, fixed, min, max, true, "SERVICE");
            long base = random.nextLong(0, 50_000_000);
            long fee = FeeEngine.amount(r, base);
            assertThat(fee).as("run %d", i).isGreaterThanOrEqualTo(0);
            if (min != null) assertThat(fee).as("run %d min", i).isGreaterThanOrEqualTo(min);
            if (max != null) assertThat(fee).as("run %d max", i).isLessThanOrEqualTo(max);
            // A bigger sale never pays a smaller fee.
            assertThat(FeeEngine.amount(r, base + random.nextLong(0, 10_000)))
                    .as("run %d monotonic", i)
                    .isGreaterThanOrEqualTo(fee);
            // VAT on the fee is the platform's tax on exactly that fee.
            List<Fee> fees = FeeEngine.assess(
                    List.of(r), List.of(new Line(0, "SERVICE", Math.max(base, 1))), new BigDecimal("0.15"));
            for (Fee f : fees) assertThat(f.withVatMinor()).isEqualTo(f.amountMinor() + f.vatMinor());
        }
    }
}
