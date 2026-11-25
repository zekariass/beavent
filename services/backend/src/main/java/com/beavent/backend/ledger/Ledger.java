package com.beavent.backend.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Posts to and reads the ledger. Signed minor units: debit positive, credit negative. {@link #post} joins the caller's
 * transaction, so a posting commits or rolls back with the business change that caused it.
 */
public interface Ledger {

    enum Code {
        PROVIDER_CLEARING,
        SELLER_PENDING,
        SELLER_AVAILABLE,
        SELLER_RESERVE,
        SELLER_FEES_RECEIVABLE,
        PLATFORM_FEE_REVENUE,
        PLATFORM_PROCESSING_COST,
        PLATFORM_PROMO_EXPENSE,
        TAX_PAYABLE,
        PAYOUTS_IN_TRANSIT,
        REFUNDS_PAYABLE,
        CUSTOMER_REFUND_PAYABLE,
        CASH_AT_SELLER,
        SELLER_RECEIVABLE,
        SUSPENSE
    }

    /** An account: a seller's (org), a provider account's (clearing) or the platform's (neither). */
    record Account(Code code, UUID orgId, UUID providerAccountId) {

        public static Account seller(Code code, UUID orgId) {
            return new Account(code, orgId, null);
        }

        public static Account clearing(UUID providerAccountId) {
            return new Account(Code.PROVIDER_CLEARING, null, providerAccountId);
        }

        public static Account platform(Code code) {
            return new Account(code, null, null);
        }
    }

    record Line(Account account, long amountMinor) {}

    /** What caused the posting; every field is optional. */
    record Links(UUID attemptId, UUID refundId, UUID payoutId, UUID orderId) {
        public static final Links NONE = new Links(null, null, null, null);
    }

    /**
     * One ledger transaction. {@code idempotencyKey} makes posting safe to repeat: a second posting with the same key
     * changes nothing and answers the first one's id.
     */
    record Posting(
            String kind,
            UUID orgId,
            String idempotencyKey,
            String description,
            Links links,
            String currency,
            List<Line> lines) {}

    UUID post(Posting posting);

    /** The account's balance, signed (debit positive); zero when it has never been used. */
    long balance(Account account, String currency);

    /** What the platform owes a seller, as positive amounts: not yet released, ready to pay out, held back. */
    record SellerBalance(long pendingMinor, long availableMinor, long reserveMinor) {}

    SellerBalance sellerBalance(UUID orgId, String currency);

    /** One transaction as the seller sees it: its net effect on what the platform owes them (positive = more). */
    record StatementLine(
            UUID transactionId, Instant occurredAt, String kind, String description, long amountMinor, UUID orderId) {}

    /** Newest first; transactions that only move money between the seller's own balances are left out. */
    List<StatementLine> statement(UUID orgId, String currency, UUID beforeTransactionId, int limit);
}
