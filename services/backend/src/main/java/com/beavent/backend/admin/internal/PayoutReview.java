package com.beavent.backend.admin.internal;

import static com.beavent.db.payments.Tables.PAYOUT_ACCOUNTS;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.payments.SellerPayments;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payout accounts waiting for platform review (plan 3.4): until one is verified a business cannot take online payments.
 * Seeing the queue needs {@code admin.payments.view}; deciding, and seeing a full account number to check it against
 * the bank's letter, need {@code admin.payouts.approve} and a fresh code (step-up). Staff cannot decide accounts of
 * organizations they belong to. Every decision and every look at a number is audited with its reason.
 */
@Component
public class PayoutReview {

    static final String VIEW = "admin.payments.view";
    static final String DECIDE = "admin.payouts.approve";

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final SellerPayments sellers;
    private final Audit audit;

    PayoutReview(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            SellerPayments sellers,
            Audit audit) {
        this.system = system;
        this.transactions = transactions;
        this.sellers = sellers;
        this.audit = audit;
    }

    /** Oldest first, so nobody waits longest; pending by default. */
    public AdminBodies.Page<AdminBodies.PayoutAccountSummary> queue(
            StaffAccess staff, String status, String cursor, int limit) {
        staff.require(VIEW);
        Condition condition = staff.marketFilter(VIEW, ORGANIZATIONS.HOME_MARKET_ID)
                .and(PAYOUT_ACCOUNTS.VERIFICATION_STATUS.eq(status == null ? "PENDING" : status));
        if (cursor != null) {
            condition = condition.and(PAYOUT_ACCOUNTS.ID.gt(Directory.cursorId(cursor)));
        }
        List<AdminBodies.PayoutAccountSummary> rows = summaries()
                .where(condition)
                .orderBy(PAYOUT_ACCOUNTS.ID)
                .limit(limit + 1)
                .fetch(PayoutReview::summary);
        return Directory.page(rows, limit, AdminBodies.PayoutAccountSummary::id);
    }

    /** The full number, once, for the reviewer: a fresh code first, then an audit entry saying who looked and why. */
    public AdminBodies.AccountNumber reveal(StaffAccess staff, UUID accountId, String reason) {
        Record row = visible(staff, DECIDE, accountId);
        Assurance.requireStepUp();
        String number = sellers.accountNumber(accountId);
        transactions.executeWithoutResult(status -> audit.record(new Audit.Entry(
                Audit.Actor.PLATFORM_STAFF,
                staff.userId(),
                row.get(PAYOUT_ACCOUNTS.ORG_ID),
                "payout_account.number_revealed",
                "payments.payout_account",
                accountId.toString(),
                null,
                null,
                reason.strip())));
        return new AdminBodies.AccountNumber(number);
    }

    public AdminBodies.PayoutAccountSummary decide(StaffAccess staff, UUID accountId, AdminBodies.Decision decision) {
        Record row = visible(staff, DECIDE, accountId);
        Assurance.requireStepUp();
        if (decision.decision().equals("REQUEST_INFO")) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A payout account is approved or rejected.");
        }
        UUID orgId = row.get(PAYOUT_ACCOUNTS.ORG_ID);
        if (system.fetchExists(
                MEMBERS,
                MEMBERS.ORG_ID.eq(orgId).and(MEMBERS.USER_ID.eq(staff.userId())).and(MEMBERS.STATUS.ne("REMOVED")))) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN, "You belong to this organization; another reviewer must decide.");
        }
        boolean approve = decision.decision().equals("APPROVE");
        String before = row.get(PAYOUT_ACCOUNTS.VERIFICATION_STATUS);
        sellers.decide(accountId, staff.userId(), approve, decision.reason());
        transactions.executeWithoutResult(status -> audit.record(new Audit.Entry(
                Audit.Actor.PLATFORM_STAFF,
                staff.userId(),
                orgId,
                "payout_account." + (approve ? "approved" : "rejected"),
                "payments.payout_account",
                accountId.toString(),
                Map.of("status", before),
                Map.of("status", approve ? "VERIFIED" : "FAILED"),
                decision.reason().strip())));
        return summary(visible(staff, VIEW, accountId));
    }

    private org.jooq.SelectOnConditionStep<Record> summaries() {
        return system.select(
                        PAYOUT_ACCOUNTS.asterisk(),
                        ORGANIZATIONS.DISPLAY_NAME,
                        ORGANIZATIONS.HOME_MARKET_ID,
                        ORGANIZATIONS.VERIFICATION_STATUS,
                        MARKETS.CODE,
                        LEGAL_ENTITIES.REGISTERED_NAME,
                        LEGAL_ENTITIES.VERIFICATION_STATUS)
                .from(PAYOUT_ACCOUNTS)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(PAYOUT_ACCOUNTS.ORG_ID))
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .join(LEGAL_ENTITIES)
                .on(LEGAL_ENTITIES.ID.eq(PAYOUT_ACCOUNTS.LEGAL_ENTITY_ID));
    }

    private Record visible(StaffAccess staff, String permission, UUID accountId) {
        Record row = summaries().where(PAYOUT_ACCOUNTS.ID.eq(accountId)).fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        staff.requireInMarket(permission, row.get(ORGANIZATIONS.HOME_MARKET_ID));
        return row;
    }

    private static AdminBodies.PayoutAccountSummary summary(Record r) {
        return new AdminBodies.PayoutAccountSummary(
                r.get(PAYOUT_ACCOUNTS.ID),
                r.get(PAYOUT_ACCOUNTS.ORG_ID),
                r.get(ORGANIZATIONS.DISPLAY_NAME),
                r.get(MARKETS.CODE),
                r.get(ORGANIZATIONS.VERIFICATION_STATUS),
                r.get(LEGAL_ENTITIES.REGISTERED_NAME),
                r.get(LEGAL_ENTITIES.VERIFICATION_STATUS),
                r.get(PAYOUT_ACCOUNTS.KIND),
                r.get(PAYOUT_ACCOUNTS.INSTITUTION_NAME),
                r.get(PAYOUT_ACCOUNTS.ACCOUNT_NAME),
                r.get(PAYOUT_ACCOUNTS.ACCOUNT_NUMBER_MASKED),
                r.get(PAYOUT_ACCOUNTS.VERIFICATION_STATUS),
                r.get(PAYOUT_ACCOUNTS.CREATED_AT).toInstant(),
                r.get(PAYOUT_ACCOUNTS.PAYOUTS_ALLOWED_AFTER).toInstant(),
                r.get(PAYOUT_ACCOUNTS.DECISION_NOTE));
    }
}
