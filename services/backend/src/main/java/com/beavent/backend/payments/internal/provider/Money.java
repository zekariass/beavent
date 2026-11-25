package com.beavent.backend.payments.internal.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact conversion between integer minor units and the decimal amounts providers use (ETB: 2 places). */
public final class Money {

    private Money() {}

    /** {@code 180050} → {@code 1800.50}. */
    public static BigDecimal toDecimal(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    /**
     * {@code "1800.5"}, {@code 1800.50} or {@code 1800} → minor units.
     *
     * @throws ArithmeticException if the amount has more than two decimal places
     */
    public static long toMinor(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    public static long toMinor(String amount) {
        return toMinor(new BigDecimal(amount.strip()));
    }
}
