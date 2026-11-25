package com.beavent.backend.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.commerce.internal.TaxEngine;
import com.beavent.backend.commerce.internal.TaxEngine.Rate;
import com.beavent.backend.commerce.internal.TaxEngine.Taxed;
import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Inclusive and exclusive tax on one line (docs/04 §12). */
class TaxEngineTest {

    private static Rate rate(String r) {
        return new Rate(UUID.randomUUID(), "VAT", new BigDecimal(r));
    }

    @Test
    void inclusivePricesKeepTheTotalAndTakeTheTaxOutOfIt() {
        Taxed t = TaxEngine.tax(150_000, rate("0.15"), true);
        assertThat(t.totalMinor()).isEqualTo(150_000);
        assertThat(t.taxMinor()).isEqualTo(19_565);
        assertThat(t.taxableMinor()).isEqualTo(130_435);
    }

    @Test
    void exclusivePricesAddTheTaxOnTop() {
        Taxed t = TaxEngine.tax(130_435, rate("0.15"), false);
        assertThat(t.taxMinor()).isEqualTo(19_565); // 19,565.25 rounds to 19,565
        assertThat(t.totalMinor()).isEqualTo(150_000);
        assertThat(t.taxableMinor()).isEqualTo(130_435);
    }

    @Test
    void noRateMeansNoTax() {
        assertThat(TaxEngine.tax(10_000, null, true)).isEqualTo(new Taxed(10_000, 0, 10_000));
        assertThat(TaxEngine.tax(10_000, rate("0"), false)).isEqualTo(new Taxed(10_000, 0, 10_000));
    }

    @Test
    void forEveryAmountAndRateTheLineArithmeticHolds() {
        Random random = new Random(1124);
        for (int i = 0; i < 10_000; i++) {
            long amount = random.nextLong(0, 100_000_000);
            Rate r = rate(BigDecimal.valueOf(random.nextInt(1, 3_000), 4).toPlainString());
            boolean inclusive = random.nextBoolean();
            Taxed t = TaxEngine.tax(amount, r, inclusive);
            assertThat(t.taxableMinor() + t.taxMinor()).as("run %d", i).isEqualTo(t.totalMinor());
            assertThat(t.taxMinor()).isBetween(0L, t.totalMinor());
            // The database's line rule: total = price − discount (+ tax when exclusive).
            assertThat(t.totalMinor()).as("run %d", i).isEqualTo(inclusive ? amount : amount + t.taxMinor());
        }
    }
}
