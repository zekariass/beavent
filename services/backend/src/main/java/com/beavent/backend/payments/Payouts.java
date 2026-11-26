package com.beavent.backend.payments;

import com.beavent.backend.ledger.Ledger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seller balances and payouts (plan 3.5, docs/04 §9). In ArifPay mode Beavent holds the sellers' money: it is released
 * for payout after the service is delivered, a payout run turns a seller's available balance into a payout on their
 * schedule, finance approves what the rules hold back, pays the rest in a bank batch and marks each payout paid or
 * failed. Every step posts to the ledger.
 */
public interface Payouts {

    // --- jobs (run by a worker; callable with a chosen time) -------------------------------------------------------

    /** Releases the money of delivered services that is due; returns how many releases were looked at. */
    int releaseDue(Instant now);

    /** The payout run: one payout per eligible seller whose schedule is due; returns how many were created. */
    int createDue(Instant now);

    // --- the seller's view -----------------------------------------------------------------------------------------

    /**
     * @param onTheWayMinor in payouts that are created but not paid yet
     * @param paidOutMinor all payouts that were paid
     * @param schedule {@code DAILY}, {@code WEEKLY}, {@code MONTHLY} or {@code MANUAL}
     * @param weekday 1 (Monday) to 7 for a weekly schedule, else null
     */
    record Overview(
            String currency,
            long pendingMinor,
            long availableMinor,
            long onTheWayMinor,
            long paidOutMinor,
            String schedule,
            Integer weekday,
            long minPayoutMinor,
            long feesOwedMinor) {}

    Overview overview(UUID orgId);

    /**
     * Charges the platform's fees on what was paid at the venue for an order (cash, gift cards, credit; plan 4.4): the
     * fees assessed on the order less what its online payments already took, as a receivable from the seller. Once per
     * order, and nothing for tips (they carry no fee). Safe to repeat.
     *
     * @return the fees charged, with VAT; 0 when there is nothing to charge
     */
    long chargeVenueFees(UUID orgId, UUID orderId, Instant now);

    /** Charges the venue fees of completed orders that were missed (the safety net behind the checkout). */
    int sweepVenueFees(Instant now);

    record PayoutRow(
            UUID id,
            String status,
            long amountMinor,
            String currency,
            String institutionName,
            String accountMasked,
            Instant createdAt,
            Instant paidAt,
            String failureMessage) {}

    List<PayoutRow> payouts(UUID orgId, int limit);

    List<Ledger.StatementLine> statement(UUID orgId, UUID beforeTransactionId, int limit);

    // --- the finance desk (the caller checks who may do it, and audits it) ------------------------------------------

    /** Approves or rejects a payout that waits for approval; rejecting gives the money back to the seller. */
    void decide(UUID payoutId, UUID reviewerUserId, boolean approve, String note);

    /**
     * The bank batch: a CSV of the payouts that are ready to pay ({@code null} for all, else only the named ones, which
     * may be none), with the full
     * account numbers; the payouts become {@code PROCESSING}.
     */
    String exportBatch(UUID byUserId, List<UUID> payoutIds);

    enum Settlement {
        PAID,
        FAILED,
        /** The bank sent a paid payout back. */
        RETURNED
    }

    /** Records what happened to a payout in the bank; a failed or returned payout gives the money back. */
    void settle(UUID payoutId, UUID byUserId, Settlement outcome, String reference, String reason);
}
