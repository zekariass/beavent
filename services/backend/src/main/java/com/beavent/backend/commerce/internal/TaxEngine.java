package com.beavent.backend.commerce.internal;

import com.beavent.backend.platform.MoneyMath;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tax on one line (docs/04 §12), pure. Prices that include tax (Ethiopia) keep the line total and take the tax out of
 * it; prices without tax add it on top. One rounding per line, half up.
 */
public final class TaxEngine {

    private TaxEngine() {}

    /** A rate in force: {@code rate} is a fraction (0.15 for 15 %). */
    public record Rate(UUID id, String taxCode, BigDecimal rate) {}

    /** {@code total = taxable + tax}, and with inclusive prices {@code total} is the amount charged. */
    public record Taxed(long taxableMinor, long taxMinor, long totalMinor) {}

    /**
     * @param amountMinor the line's price after discounts ({@code unit × quantity − discount})
     * @param rate null when the seller charges no tax on this line
     */
    public static Taxed tax(long amountMinor, Rate rate, boolean pricesIncludeTax) {
        if (rate == null || rate.rate().signum() == 0) return new Taxed(amountMinor, 0, amountMinor);
        if (pricesIncludeTax) {
            long tax = MoneyMath.includedTax(amountMinor, rate.rate());
            return new Taxed(amountMinor - tax, tax, amountMinor);
        }
        long tax = MoneyMath.ofRate(amountMinor, rate.rate());
        return new Taxed(amountMinor, tax, Math.addExact(amountMinor, tax));
    }
}
