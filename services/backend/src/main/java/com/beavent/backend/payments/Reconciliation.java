package com.beavent.backend.payments;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reconciling the provider's statement with our own records and certifying a provider for live use (plan 3.9, docs/04
 * §14). Finance uploads a statement for a period; its lines are matched to our payment attempts, refunds and payouts,
 * the provider's fees are posted to the ledger, money nothing explains is held in suspense, and whatever does not match
 * waits in an exceptions queue until someone explains it. The caller checks who may do what and audits it.
 */
public interface Reconciliation {

    /** A provider account the platform can reconcile (one per provider and environment). */
    record AccountRow(UUID id, String provider, String name, String environment, String currency) {}

    List<AccountRow> accounts();

    /**
     * One statement as uploaded: a CSV with a header row and the columns {@code reference}, {@code type}
     * ({@code COLLECTION}, {@code REFUND}, {@code PAYOUT}, {@code FEE}, {@code SETTLEMENT}, {@code REVERSAL},
     * {@code CHARGEBACK}, {@code ADJUSTMENT}), {@code amount} (major units), {@code date}, and optionally {@code fee}.
     * The reference is our payment's id or the provider's own reference for it.
     *
     * @param closingBalanceMinor what the provider says the account holds at the end of the period, when it says
     */
    record Upload(
            UUID providerAccountId,
            LocalDate from,
            LocalDate to,
            Long openingBalanceMinor,
            Long closingBalanceMinor,
            String csv) {}

    /**
     * @param status {@code RECONCILED} (every line explained and the balance agrees) or {@code EXCEPTIONS}
     * @param exceptions lines not matched plus payments the statement does not show
     * @param differenceMinor the statement's closing balance less the ledger's; null when no closing balance was given
     */
    record StatementRow(
            UUID id,
            UUID providerAccountId,
            String provider,
            LocalDate from,
            LocalDate to,
            String currency,
            String status,
            int lines,
            int matched,
            int exceptions,
            Long closingBalanceMinor,
            Long ledgerBalanceMinor,
            Long differenceMinor,
            Instant importedAt) {}

    /**
     * Imports and matches a statement, once per account and period.
     *
     * @throws com.beavent.backend.platform.ApiException {@code VALIDATION_FAILED} for a file that cannot be read (the
     *     message names the line), {@code CONFLICT} when the period was imported already
     */
    StatementRow importStatement(Upload upload, UUID byUserId, Instant now);

    List<StatementRow> statements(int limit);

    /**
     * Something finance has to explain.
     *
     * @param kind {@code AMOUNT_MISMATCH}, {@code MISSING_INTERNALLY} (the provider holds money we have no payment for),
     *     {@code UNMATCHED} or {@code MISSING_ON_STATEMENT} (a payment we confirmed that the statement does not show)
     * @param lineId the statement line; null for a payment missing from the statement
     * @param attemptId the payment concerned, when there is one
     */
    record ExceptionRow(
            String kind,
            UUID statementId,
            UUID lineId,
            UUID attemptId,
            String reference,
            String lineType,
            long amountMinor,
            String currency,
            Instant occurredAt,
            String note) {}

    List<ExceptionRow> exceptions(int limit);

    /**
     * Records why an exception is fine (a test payment, a fee explained by the provider, a payment checked by hand).
     * For a payment missing from the statement the note is kept as an adjustment line with no amount.
     */
    void resolve(UUID statementId, UUID lineId, UUID attemptId, UUID byUserId, String note, Instant now);

    // --- certification for live use --------------------------------------------------------------------------------

    /** One thing that has to be true before a provider goes live. */
    record Check(String code, boolean passed, String detail) {}

    /**
     * @param status the provider's capability in Ethiopia: {@code SANDBOX}, {@code LIVE} or {@code SUSPENDED}
     * @param ready every check passes
     */
    record Certification(String provider, String status, Instant verifiedAt, List<Check> checks, boolean ready) {}

    Certification certification();

    /**
     * Sets the active provider's capabilities live, once every check passes; {@code evidence} says what the live
     * low-value payment, refund and payout were (references).
     */
    void goLive(UUID byUserId, String evidence, Instant now);

    /** Suspends the provider's capabilities (no new online payments through it). */
    void suspend(UUID byUserId, String reason, Instant now);
}
