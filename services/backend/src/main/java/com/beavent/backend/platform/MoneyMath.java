package com.beavent.backend.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Integer money arithmetic in minor units (docs/04 §11): every rounding is half up, on non-negative amounts, done once
 * per line. No floating point.
 */
public final class MoneyMath {

    private MoneyMath() {}

    /** {@code round(amount × rate)}, e.g. exclusive tax or a percentage discount; {@code rate} is a fraction. */
    public static long ofRate(long amount, BigDecimal rate) {
        requireNonNegative(amount);
        return new BigDecimal(amount)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** {@code round(amount × bps / 10 000)}. */
    public static long ofBasisPoints(long amount, long bps) {
        return ofRate(amount, BigDecimal.valueOf(bps, 4));
    }

    /** The tax inside a tax-inclusive amount: {@code round(gross × rate / (1 + rate))}. */
    public static long includedTax(long gross, BigDecimal rate) {
        requireNonNegative(gross);
        if (rate.signum() == 0) return 0;
        return new BigDecimal(gross)
                .multiply(rate)
                .divide(BigDecimal.ONE.add(rate), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * Splits {@code total} across {@code weights} in proportion, by largest remainder: the parts add up to exactly
     * {@code total}, and no part exceeds its weight when {@code total ≤ Σ weights}. Ties go to the earlier weight.
     */
    public static long[] allocate(long total, long[] weights) {
        requireNonNegative(total);
        long[] parts = new long[weights.length];
        long sum = 0;
        for (long w : weights) sum = Math.addExact(sum, requireNonNegative(w));
        if (sum == 0 || total == 0) return parts;
        BigDecimal[] remainders = new BigDecimal[weights.length];
        long given = 0;
        for (int i = 0; i < weights.length; i++) {
            BigDecimal exact = new BigDecimal(total)
                    .multiply(new BigDecimal(weights[i]))
                    .divide(new BigDecimal(sum), 12, RoundingMode.DOWN);
            parts[i] = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            remainders[i] = exact.subtract(new BigDecimal(parts[i]));
            given += parts[i];
        }
        long left = total - given;
        while (left > 0) {
            int best = -1;
            for (int i = 0; i < weights.length; i++) {
                if (weights[i] == 0) continue;
                if (best < 0 || remainders[i].compareTo(remainders[best]) > 0) best = i;
            }
            parts[best]++;
            remainders[best] = remainders[best].subtract(BigDecimal.ONE);
            left--;
        }
        return parts;
    }

    private static long requireNonNegative(long amount) {
        if (amount < 0) throw new IllegalArgumentException("negative amount " + amount);
        return amount;
    }
}
