package com.beavent.backend.ledger.internal;

import static com.beavent.db.ledger.Tables.ACCOUNTS;
import static com.beavent.db.ledger.Tables.BALANCES;
import static com.beavent.db.ledger.Tables.ENTRIES;
import static com.beavent.db.ledger.Tables.TRANSACTIONS;

import com.beavent.backend.ledger.Ledger;
import com.beavent.backend.platform.SystemDb;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * The ledger over the system database role. Posting joins the caller's transaction; the database refuses an
 * unbalanced transaction at commit (V1_13), so a wrong posting never survives.
 */
@Component
class LedgerService implements Ledger {

    /** Which side of the books each account normally sits on, and who owns it. */
    private static final Map<Code, String> NORMAL_SIDE = Map.ofEntries(
            Map.entry(Code.PROVIDER_CLEARING, "DEBIT"),
            Map.entry(Code.SELLER_PENDING, "CREDIT"),
            Map.entry(Code.SELLER_AVAILABLE, "CREDIT"),
            Map.entry(Code.SELLER_RESERVE, "CREDIT"),
            Map.entry(Code.SELLER_FEES_RECEIVABLE, "DEBIT"),
            Map.entry(Code.PLATFORM_FEE_REVENUE, "CREDIT"),
            Map.entry(Code.PLATFORM_PROCESSING_COST, "DEBIT"),
            Map.entry(Code.PLATFORM_PROMO_EXPENSE, "DEBIT"),
            Map.entry(Code.TAX_PAYABLE, "CREDIT"),
            Map.entry(Code.PAYOUTS_IN_TRANSIT, "CREDIT"),
            Map.entry(Code.REFUNDS_PAYABLE, "CREDIT"),
            Map.entry(Code.CUSTOMER_REFUND_PAYABLE, "CREDIT"),
            Map.entry(Code.CASH_AT_SELLER, "DEBIT"),
            Map.entry(Code.SELLER_RECEIVABLE, "DEBIT"),
            Map.entry(Code.SUSPENSE, "DEBIT"));

    private final DSLContext system;

    LedgerService(@SystemDb DSLContext system) {
        this.system = system;
    }

    @Override
    public UUID post(Posting posting) {
        if (posting.lines().size() < 2) {
            throw new IllegalArgumentException("a ledger transaction has at least two lines");
        }
        long sum = posting.lines().stream().mapToLong(Line::amountMinor).sum();
        if (sum != 0) {
            throw new IllegalArgumentException(
                    "ledger transaction " + posting.idempotencyKey() + " is unbalanced by " + sum);
        }
        Links links = posting.links() == null ? Links.NONE : posting.links();
        UUID id = system.insertInto(TRANSACTIONS)
                .set(TRANSACTIONS.KIND, posting.kind())
                .set(TRANSACTIONS.ORG_ID, posting.orgId())
                .set(TRANSACTIONS.PAYMENT_ATTEMPT_ID, links.attemptId())
                .set(TRANSACTIONS.REFUND_ID, links.refundId())
                .set(TRANSACTIONS.PAYOUT_ID, links.payoutId())
                .set(TRANSACTIONS.ORDER_ID, links.orderId())
                .set(TRANSACTIONS.IDEMPOTENCY_KEY, posting.idempotencyKey())
                .set(TRANSACTIONS.DESCRIPTION, posting.description())
                .onConflict(TRANSACTIONS.IDEMPOTENCY_KEY)
                .doNothing()
                .returning(TRANSACTIONS.ID)
                .fetchOptional(TRANSACTIONS.ID)
                .orElse(null);
        if (id == null) {
            // Already posted: the same key means the same posting.
            return system.select(TRANSACTIONS.ID)
                    .from(TRANSACTIONS)
                    .where(TRANSACTIONS.IDEMPOTENCY_KEY.eq(posting.idempotencyKey()))
                    .fetchSingle(TRANSACTIONS.ID);
        }
        for (Line line : posting.lines()) {
            system.insertInto(ENTRIES)
                    .set(ENTRIES.TRANSACTION_ID, id)
                    .set(ENTRIES.ACCOUNT_ID, account(line.account(), posting.currency()))
                    .set(ENTRIES.AMOUNT_MINOR, line.amountMinor())
                    .set(ENTRIES.CURRENCY_CODE, posting.currency())
                    .execute();
        }
        return id;
    }

    /** The account's id; it is created on first use. */
    private UUID account(Account a, String currency) {
        String owner =
                a.orgId() != null ? "ORGANIZATION" : a.providerAccountId() != null ? "PROVIDER_ACCOUNT" : "PLATFORM";
        system.insertInto(ACCOUNTS)
                .set(ACCOUNTS.OWNER_TYPE, owner)
                .set(ACCOUNTS.ORG_ID, a.orgId())
                .set(ACCOUNTS.PROVIDER_ACCOUNT_ID, a.providerAccountId())
                .set(ACCOUNTS.CODE, a.code().name())
                .set(ACCOUNTS.CURRENCY_CODE, currency)
                .set(ACCOUNTS.NORMAL_SIDE, NORMAL_SIDE.get(a.code()))
                .onConflictDoNothing()
                .execute();
        return system.select(ACCOUNTS.ID)
                .from(ACCOUNTS)
                .where(ACCOUNTS.OWNER_TYPE.eq(owner))
                .and(a.orgId() == null ? ACCOUNTS.ORG_ID.isNull() : ACCOUNTS.ORG_ID.eq(a.orgId()))
                .and(
                        a.providerAccountId() == null
                                ? ACCOUNTS.PROVIDER_ACCOUNT_ID.isNull()
                                : ACCOUNTS.PROVIDER_ACCOUNT_ID.eq(a.providerAccountId()))
                .and(ACCOUNTS.CODE.eq(a.code().name()))
                .and(ACCOUNTS.CURRENCY_CODE.eq(currency))
                .fetchSingle(ACCOUNTS.ID);
    }

    @Override
    public long balance(Account a, String currency) {
        Long balance = system.select(BALANCES.BALANCE_MINOR)
                .from(BALANCES)
                .join(ACCOUNTS)
                .on(ACCOUNTS.ID.eq(BALANCES.ACCOUNT_ID))
                .where(ACCOUNTS.CODE.eq(a.code().name()))
                .and(ACCOUNTS.CURRENCY_CODE.eq(currency))
                .and(a.orgId() == null ? ACCOUNTS.ORG_ID.isNull() : ACCOUNTS.ORG_ID.eq(a.orgId()))
                .and(
                        a.providerAccountId() == null
                                ? ACCOUNTS.PROVIDER_ACCOUNT_ID.isNull()
                                : ACCOUNTS.PROVIDER_ACCOUNT_ID.eq(a.providerAccountId()))
                .fetchOne(BALANCES.BALANCE_MINOR);
        return balance == null ? 0 : balance;
    }

    @Override
    public SellerBalance sellerBalance(UUID orgId, String currency) {
        return new SellerBalance(
                -balance(Account.seller(Code.SELLER_PENDING, orgId), currency),
                -balance(Account.seller(Code.SELLER_AVAILABLE, orgId), currency),
                -balance(Account.seller(Code.SELLER_RESERVE, orgId), currency));
    }

    @Override
    public List<StatementLine> statement(UUID orgId, String currency, UUID before, int limit) {
        var net = DSL.sum(ENTRIES.AMOUNT_MINOR);
        return system.select(
                        TRANSACTIONS.ID,
                        TRANSACTIONS.OCCURRED_AT,
                        TRANSACTIONS.KIND,
                        TRANSACTIONS.DESCRIPTION,
                        TRANSACTIONS.ORDER_ID,
                        net)
                .from(TRANSACTIONS)
                .join(ENTRIES)
                .on(ENTRIES.TRANSACTION_ID.eq(TRANSACTIONS.ID))
                .join(ACCOUNTS)
                .on(ACCOUNTS.ID.eq(ENTRIES.ACCOUNT_ID))
                .where(ACCOUNTS.ORG_ID.eq(orgId))
                .and(ACCOUNTS.CURRENCY_CODE.eq(currency))
                .and(ACCOUNTS.CODE.in(
                        Code.SELLER_PENDING.name(), Code.SELLER_AVAILABLE.name(), Code.SELLER_RESERVE.name()))
                .and(before == null ? DSL.noCondition() : TRANSACTIONS.ID.lt(before))
                .groupBy(
                        TRANSACTIONS.ID,
                        TRANSACTIONS.OCCURRED_AT,
                        TRANSACTIONS.KIND,
                        TRANSACTIONS.DESCRIPTION,
                        TRANSACTIONS.ORDER_ID)
                .having(net.ne(java.math.BigDecimal.ZERO))
                .orderBy(TRANSACTIONS.ID.desc())
                .limit(limit)
                .fetch(r -> new StatementLine(
                        r.get(TRANSACTIONS.ID),
                        r.get(TRANSACTIONS.OCCURRED_AT).toInstant(),
                        r.get(TRANSACTIONS.KIND),
                        r.get(TRANSACTIONS.DESCRIPTION),
                        -r.get(net).longValueExact(),
                        r.get(TRANSACTIONS.ORDER_ID)));
    }
}
