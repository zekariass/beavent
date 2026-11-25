package com.beavent.backend.ledger;

import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.ledger.Ledger.Line;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The postings of docs/04 §10 as pure functions: amounts in, lines out, no database. Each returned list balances to
 * zero, has no zero line, and is posted as one ledger transaction. Debit is positive, credit negative.
 */
public final class Postings {

    private Postings() {}

    /** A planned transaction: its kind and lines; the caller adds the key, the links and the currency. */
    public record Planned(String kind, String suffix, List<Line> lines) {}

    /**
     * A captured payment of {@code grossMinor}, of which the platform keeps {@code feeMinor} plus {@code vatMinor} on
     * the fee.
     *
     * <p>Beavent collects ({@code splitAtSource} false): the whole payment is the seller's until the fees are taken
     * out. Split at source: the seller was paid by the provider, and only the platform's share arrives here.
     */
    public static List<Planned> captured(
            boolean splitAtSource, UUID orgId, Account clearing, long grossMinor, long feeMinor, long vatMinor) {
        check(grossMinor > 0, "a payment is more than zero");
        check(feeMinor >= 0 && vatMinor >= 0, "fees are not negative");
        long share = feeMinor + vatMinor;
        check(share <= grossMinor, "the platform's share cannot exceed the payment");
        List<Planned> out = new ArrayList<>();
        Account pending = Account.seller(Code.SELLER_PENDING, orgId);
        if (splitAtSource) {
            if (share > 0) {
                List<Line> lines = new ArrayList<>();
                lines.add(new Line(clearing, share));
                addFeeLines(lines, feeMinor, vatMinor);
                out.add(new Planned("PAYMENT_CAPTURED", "capture", lines));
            }
            return out;
        }
        out.add(new Planned(
                "PAYMENT_CAPTURED",
                "capture",
                List.of(new Line(clearing, grossMinor), new Line(pending, -grossMinor))));
        if (share > 0) {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(pending, share));
            addFeeLines(lines, feeMinor, vatMinor);
            out.add(new Planned("FEES_ASSESSED", "fees", lines));
        }
        return out;
    }

    /**
     * Money received that is not the seller's to keep (a duplicate payment, a late one for a time that is gone): it is
     * owed back to the payer.
     */
    public static Planned receivedForRefund(Account clearing, long grossMinor) {
        check(grossMinor > 0, "a payment is more than zero");
        return new Planned(
                "PAYMENT_CAPTURED",
                "capture",
                List.of(new Line(clearing, grossMinor), new Line(Account.platform(Code.REFUNDS_PAYABLE), -grossMinor)));
    }

    /** The service was delivered: the seller's share can be paid out. */
    public static Planned released(UUID orgId, long netMinor) {
        check(netMinor > 0, "a release is more than zero");
        return new Planned(
                "FUNDS_RELEASED",
                "release",
                List.of(
                        new Line(Account.seller(Code.SELLER_PENDING, orgId), netMinor),
                        new Line(Account.seller(Code.SELLER_AVAILABLE, orgId), -netMinor)));
    }

    /** A payout is created: the money leaves the seller's available balance for the money on its way. */
    public static Planned payoutInitiated(UUID orgId, long amountMinor) {
        check(amountMinor > 0, "a payout is more than zero");
        return new Planned(
                "PAYOUT_INITIATED",
                "initiated",
                List.of(
                        new Line(Account.seller(Code.SELLER_AVAILABLE, orgId), amountMinor),
                        new Line(Account.platform(Code.PAYOUTS_IN_TRANSIT), -amountMinor)));
    }

    /** Finance paid it: the money has left the provider account. */
    public static Planned payoutSettled(Account clearing, long amountMinor) {
        check(amountMinor > 0, "a payout is more than zero");
        return new Planned(
                "PAYOUT_SETTLED",
                "settled",
                List.of(
                        new Line(Account.platform(Code.PAYOUTS_IN_TRANSIT), amountMinor),
                        new Line(clearing, -amountMinor)));
    }

    /** The payout did not happen (rejected, or the bank refused it): the money is available to the seller again. */
    public static Planned payoutFailed(UUID orgId, long amountMinor) {
        check(amountMinor > 0, "a payout is more than zero");
        return new Planned(
                "PAYOUT_FAILED",
                "failed",
                List.of(
                        new Line(Account.platform(Code.PAYOUTS_IN_TRANSIT), amountMinor),
                        new Line(Account.seller(Code.SELLER_AVAILABLE, orgId), -amountMinor)));
    }

    /** The bank returned a payout that had been paid: the money is back at the provider and the seller's again. */
    public static Planned payoutReturned(Account clearing, UUID orgId, long amountMinor) {
        check(amountMinor > 0, "a payout is more than zero");
        return new Planned(
                "PAYOUT_FAILED",
                "returned",
                List.of(
                        new Line(clearing, amountMinor),
                        new Line(Account.seller(Code.SELLER_AVAILABLE, orgId), -amountMinor)));
    }

    private static void addFeeLines(List<Line> lines, long feeMinor, long vatMinor) {
        if (feeMinor > 0) lines.add(new Line(Account.platform(Code.PLATFORM_FEE_REVENUE), -feeMinor));
        if (vatMinor > 0) lines.add(new Line(Account.platform(Code.TAX_PAYABLE), -vatMinor));
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
