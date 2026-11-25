package com.beavent.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Rounding and splitting money (docs/04 §11): half up, exact sums, no part over its weight. */
class MoneyMathTest {

    private static final int RUNS = 5000;

    @Test
    void roundsHalfUpAtTheMinorUnit() {
        assertThat(MoneyMath.ofBasisPoints(1_000, 1_500)).isEqualTo(150);
        assertThat(MoneyMath.ofBasisPoints(5, 1_000)).isEqualTo(1); // 0.5 → 1
        assertThat(MoneyMath.ofBasisPoints(4, 1_000)).isEqualTo(0); // 0.4 → 0
        assertThat(MoneyMath.ofRate(333, new BigDecimal("0.15"))).isEqualTo(50); // 49.95
        assertThat(MoneyMath.ofRate(0, new BigDecimal("0.15"))).isZero();
        assertThatThrownBy(() -> MoneyMath.ofRate(-1, new BigDecimal("0.15")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void includedTaxOfTheDocsExample() {
        // docs/04 §10: Br 1,500 inside which 15 % VAT is 195.65 (not 225): gross × r / (1 + r).
        assertThat(MoneyMath.includedTax(150_000, new BigDecimal("0.15"))).isEqualTo(19_565);
        assertThat(MoneyMath.includedTax(150_000, BigDecimal.ZERO)).isZero();
    }

    @Test
    void includedTaxIsNeverMoreThanTheGrossAndRoundTripsWithinOneMinorUnit() {
        Random random = new Random(20261124);
        for (int i = 0; i < RUNS; i++) {
            long gross = random.nextLong(0, 100_000_000);
            BigDecimal rate = BigDecimal.valueOf(random.nextInt(0, 3_000), 4); // 0 – 29.99 %
            long tax = MoneyMath.includedTax(gross, rate);
            assertThat(tax).as("run %d: gross %d rate %s", i, gross, rate).isBetween(0L, gross);
            long net = gross - tax;
            // Adding the rate back to the net lands on the gross, give or take the rounding of one minor unit.
            assertThat(Math.abs(net + MoneyMath.ofRate(net, rate) - gross))
                    .as("run %d: gross %d rate %s", i, gross, rate)
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    void ofRateNeverDecreasesAsTheAmountGrows() {
        Random random = new Random(7);
        for (int i = 0; i < RUNS; i++) {
            long a = random.nextLong(0, 50_000_000);
            long b = a + random.nextLong(0, 1_000);
            BigDecimal rate = BigDecimal.valueOf(random.nextInt(0, 10_000), 4);
            assertThat(MoneyMath.ofRate(b, rate)).isGreaterThanOrEqualTo(MoneyMath.ofRate(a, rate));
        }
    }

    @Test
    void allocationAddsUpExactlyAndNoPartExceedsItsWeight() {
        Random random = new Random(42);
        for (int i = 0; i < RUNS; i++) {
            int n = random.nextInt(1, 8);
            long[] weights = new long[n];
            long sum = 0;
            for (int k = 0; k < n; k++) {
                weights[k] = random.nextInt(4) == 0 ? 0 : random.nextLong(1, 5_000_000);
                sum += weights[k];
            }
            long total = random.nextLong(0, sum + 1); // never more than the weights
            long[] parts = MoneyMath.allocate(total, weights);
            long given = 0;
            for (int k = 0; k < n; k++) {
                assertThat(parts[k]).as("run %d part %d", i, k).isBetween(0L, weights[k]);
                given += parts[k];
            }
            assertThat(given)
                    .as("run %d: %d over %s", i, total, java.util.Arrays.toString(weights))
                    .isEqualTo(sum == 0 ? 0 : total);
        }
    }

    @Test
    void allocationIsProportionalWithinOneMinorUnit() {
        long[] parts = MoneyMath.allocate(100, new long[] {1, 1, 1});
        assertThat(parts).containsExactly(34, 33, 33); // ties go to the earlier weight
        assertThat(MoneyMath.allocate(10, new long[] {3, 7})).containsExactly(3, 7);
        assertThat(MoneyMath.allocate(0, new long[] {3, 7})).containsExactly(0, 0);
        assertThat(MoneyMath.allocate(5, new long[] {0, 0})).containsExactly(0, 0);
        Random random = new Random(99);
        for (int i = 0; i < RUNS; i++) {
            long w1 = random.nextLong(1, 1_000_000);
            long w2 = random.nextLong(1, 1_000_000);
            long total = random.nextLong(0, w1 + w2 + 1);
            long[] p = MoneyMath.allocate(total, new long[] {w1, w2});
            double exact = (double) total * w1 / (w1 + w2);
            assertThat(Math.abs(p[0] - exact)).as("run %d", i).isLessThan(1.0 + 1e-6);
        }
    }
}
