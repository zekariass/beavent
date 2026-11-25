package com.beavent.backend.billing;

import com.beavent.backend.platform.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fee arithmetic (docs/04 §11), pure: per line, {@code round(base × bps) + fixed}, half up, then capped by the rule's
 * minimum and maximum; VAT on the fee (the platform's own output tax) on top when the rule says so. Per-line rounding
 * keeps refunds of single lines exact.
 */
public final class FeeEngine {

    private FeeEngine() {}

    /**
     * An active fee rule.
     *
     * @param appliesTo the order line types it is charged on ({@code SERVICE}, {@code TICKET} …)
     */
    public record Rule(
            UUID id,
            String feeCode,
            String payer,
            Set<String> appliesTo,
            int percentBps,
            long fixedMinor,
            Long minMinor,
            Long maxMinor,
            boolean vatApplies) {}

    /** An order line a fee may be charged on: what the buyer pays for it, after discounts and with tax. */
    public record Line(int index, String lineType, long totalMinor) {}

    /** One fee on one line. {@code amountMinor} excludes VAT; the buyer or seller pays {@code amount + vat}. */
    public record Fee(
            UUID ruleId, String feeCode, String payer, int lineIndex, long baseMinor, long amountMinor, long vatMinor) {
        public long withVatMinor() {
            return amountMinor + vatMinor;
        }
    }

    /** The fee a rule charges on {@code base}: percentage and fixed part, then the cap. */
    public static long amount(Rule rule, long base) {
        long fee = Math.addExact(MoneyMath.ofBasisPoints(base, rule.percentBps()), rule.fixedMinor());
        if (rule.minMinor() != null) fee = Math.max(fee, rule.minMinor());
        if (rule.maxMinor() != null) fee = Math.min(fee, rule.maxMinor());
        return fee;
    }

    /**
     * Every fee the rules charge on the lines. Lines that cost nothing carry no fee.
     *
     * @param platformVatRate the platform's VAT rate on its fees (a fraction; zero when it charges none)
     */
    public static List<Fee> assess(List<Rule> rules, List<Line> lines, BigDecimal platformVatRate) {
        List<Fee> fees = new ArrayList<>();
        for (Line line : lines) {
            if (line.totalMinor() <= 0) continue;
            for (Rule rule : rules) {
                if (!rule.appliesTo().contains(line.lineType())) continue;
                long amount = amount(rule, line.totalMinor());
                if (amount <= 0) continue;
                long vat = rule.vatApplies() ? MoneyMath.ofRate(amount, platformVatRate) : 0;
                fees.add(
                        new Fee(rule.id(), rule.feeCode(), rule.payer(), line.index(), line.totalMinor(), amount, vat));
            }
        }
        return fees;
    }
}
