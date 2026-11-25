package com.beavent.backend.admin.internal;

import static com.beavent.db.payments.Tables.PAYOUTS;
import static com.beavent.db.payments.Tables.PAYOUT_ACCOUNTS;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.payments.Payouts;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The finance desk for seller payouts (plan 3.5, docs/04 §9). Seeing the queue needs {@code admin.payments.view};
 * approving, the bank batch (it carries full account numbers) and marking a payout paid or failed need
 * {@code admin.payouts.approve} and a fresh code. Staff cannot touch payouts of organizations they belong to. Every
 * step is audited.
 */
@Component
public class PayoutDesk {

    static final String VIEW = "admin.payments.view";
    static final String DECIDE = "admin.payouts.approve";

    /** The queues finance works through, as the sets of payout states they hold. */
    private static final Map<String, Set<String>> QUEUES = Map.of(
            "APPROVAL", Set.of("PENDING_APPROVAL"),
            "READY", Set.of("SCHEDULED", "APPROVED"),
            "PROCESSING", Set.of("PROCESSING"),
            "PAID", Set.of("PAID"),
            "FAILED", Set.of("FAILED", "CANCELLED", "RETURNED"));

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Payouts payouts;
    private final Audit audit;

    PayoutDesk(@SystemDb DSLContext system, @SystemDb TransactionTemplate transactions, Payouts payouts, Audit audit) {
        this.system = system;
        this.transactions = transactions;
        this.payouts = payouts;
        this.audit = audit;
    }

    /** Oldest first; the queue defaults to what waits for approval. */
    public AdminBodies.Page<AdminBodies.PayoutSummary> queue(
            StaffAccess staff, String queue, String cursor, int limit) {
        staff.require(VIEW);
        Set<String> states = QUEUES.get(queue == null ? "APPROVAL" : queue);
        if (states == null) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown queue.");
        Condition condition = staff.marketFilter(VIEW, ORGANIZATIONS.HOME_MARKET_ID)
                .and(PAYOUTS.PURPOSE.eq("SETTLEMENT"))
                .and(PAYOUTS.STATUS.in(states));
        if (cursor != null) condition = condition.and(PAYOUTS.ID.gt(Directory.cursorId(cursor)));
        List<AdminBodies.PayoutSummary> rows = summaries()
                .where(condition)
                .orderBy(PAYOUTS.ID)
                .limit(limit + 1)
                .fetch(PayoutDesk::summary);
        return Directory.page(rows, limit, AdminBodies.PayoutSummary::id);
    }

    public AdminBodies.PayoutSummary decide(StaffAccess staff, UUID payoutId, AdminBodies.Decision decision) {
        Record row = visible(staff, payoutId);
        Assurance.requireStepUp();
        if (decision.decision().equals("REQUEST_INFO")) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A payout is approved or rejected.");
        }
        notOwn(staff, row.get(PAYOUTS.ORG_ID));
        boolean approve = decision.decision().equals("APPROVE");
        payouts.decide(payoutId, staff.userId(), approve, decision.reason().strip());
        record(staff, row, approve ? "payout.approved" : "payout.rejected", decision.reason());
        return summary(visible(staff, payoutId));
    }

    public AdminBodies.PayoutSummary settle(StaffAccess staff, UUID payoutId, AdminBodies.PayoutSettlement body) {
        Record row = visible(staff, payoutId);
        Assurance.requireStepUp();
        notOwn(staff, row.get(PAYOUTS.ORG_ID));
        Payouts.Settlement outcome = Payouts.Settlement.valueOf(body.outcome());
        if (outcome == Payouts.Settlement.PAID
                && (body.reference() == null || body.reference().isBlank())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the bank's reference for the payment.");
        }
        if (outcome != Payouts.Settlement.PAID
                && (body.reason() == null || body.reason().strip().length() < 5)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say why (at least 5 characters).");
        }
        payouts.settle(
                payoutId,
                staff.userId(),
                outcome,
                body.reference() == null ? null : body.reference().strip(),
                body.reason() == null ? null : body.reason().strip());
        record(
                staff,
                row,
                "payout." + outcome.name().toLowerCase(java.util.Locale.ROOT),
                outcome == Payouts.Settlement.PAID ? body.reference() : body.reason());
        return summary(visible(staff, payoutId));
    }

    /**
     * The bank batch: the payouts that are ready to pay in the staff member's markets (not their own organizations'),
     * as a CSV with full account numbers. A fresh code first; the export is audited with how many payouts it holds.
     */
    public String export(StaffAccess staff, List<UUID> only) {
        staff.require(DECIDE);
        Assurance.requireStepUp();
        Condition ready = staff.marketFilter(DECIDE, ORGANIZATIONS.HOME_MARKET_ID)
                .and(PAYOUTS.PURPOSE.eq("SETTLEMENT"))
                .and(PAYOUTS.STATUS.in(QUEUES.get("READY")))
                .andNotExists(org.jooq
                        .impl
                        .DSL
                        .selectOne()
                        .from(MEMBERS)
                        .where(MEMBERS.ORG_ID.eq(PAYOUTS.ORG_ID))
                        .and(MEMBERS.USER_ID.eq(staff.userId()))
                        .and(MEMBERS.STATUS.ne("REMOVED")));
        if (only != null && !only.isEmpty()) ready = ready.and(PAYOUTS.ID.in(only));
        List<UUID> ids = summaries().where(ready).orderBy(PAYOUTS.ID).fetch(PAYOUTS.ID);
        String csv = payouts.exportBatch(staff.userId(), ids);
        transactions.executeWithoutResult(status -> audit.record(new Audit.Entry(
                Audit.Actor.PLATFORM_STAFF,
                staff.userId(),
                null,
                "payout.batch_exported",
                "payments.payout",
                "batch",
                null,
                Map.of("payouts", ids.size()),
                null)));
        return csv;
    }

    private void notOwn(StaffAccess staff, UUID orgId) {
        if (system.fetchExists(
                MEMBERS,
                MEMBERS.ORG_ID.eq(orgId).and(MEMBERS.USER_ID.eq(staff.userId())).and(MEMBERS.STATUS.ne("REMOVED")))) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN,
                    "You belong to this organization; another reviewer must handle its payouts.");
        }
    }

    private void record(StaffAccess staff, Record row, String action, String reason) {
        transactions.executeWithoutResult(status -> audit.record(new Audit.Entry(
                Audit.Actor.PLATFORM_STAFF,
                staff.userId(),
                row.get(PAYOUTS.ORG_ID),
                action,
                "payments.payout",
                row.get(PAYOUTS.ID).toString(),
                Map.of("status", row.get(PAYOUTS.STATUS)),
                Map.of("amountMinor", row.get(PAYOUTS.AMOUNT_MINOR)),
                reason == null ? null : reason.strip())));
    }

    private org.jooq.SelectOnConditionStep<Record> summaries() {
        return system.select(
                        PAYOUTS.asterisk(),
                        ORGANIZATIONS.DISPLAY_NAME,
                        ORGANIZATIONS.HOME_MARKET_ID,
                        MARKETS.CODE,
                        PAYOUT_ACCOUNTS.INSTITUTION_NAME,
                        PAYOUT_ACCOUNTS.ACCOUNT_NAME,
                        PAYOUT_ACCOUNTS.ACCOUNT_NUMBER_MASKED)
                .from(PAYOUTS)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(PAYOUTS.ORG_ID))
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .join(PAYOUT_ACCOUNTS)
                .on(PAYOUT_ACCOUNTS.ID.eq(PAYOUTS.PAYOUT_ACCOUNT_ID));
    }

    private Record visible(StaffAccess staff, UUID payoutId) {
        Record row = summaries()
                .where(PAYOUTS.ID.eq(payoutId), PAYOUTS.PURPOSE.eq("SETTLEMENT"))
                .fetchOne();
        if (row == null) throw new ApiException(ProblemCode.NOT_FOUND);
        staff.requireInMarket(DECIDE, row.get(ORGANIZATIONS.HOME_MARKET_ID));
        return row;
    }

    private static AdminBodies.PayoutSummary summary(Record r) {
        return new AdminBodies.PayoutSummary(
                r.get(PAYOUTS.ID),
                r.get(PAYOUTS.ORG_ID),
                r.get(ORGANIZATIONS.DISPLAY_NAME),
                r.get(MARKETS.CODE),
                r.get(PAYOUTS.AMOUNT_MINOR),
                r.get(PAYOUTS.CURRENCY_CODE).trim(),
                r.get(PAYOUTS.STATUS),
                List.of(r.get(PAYOUTS.APPROVAL_REASONS)),
                r.get(PAYOUT_ACCOUNTS.INSTITUTION_NAME),
                r.get(PAYOUT_ACCOUNTS.ACCOUNT_NAME),
                r.get(PAYOUT_ACCOUNTS.ACCOUNT_NUMBER_MASKED),
                r.get(PAYOUTS.CREATED_AT).toInstant(),
                r.get(PAYOUTS.EXPORTED_AT) == null
                        ? null
                        : r.get(PAYOUTS.EXPORTED_AT).toInstant(),
                r.get(PAYOUTS.PAID_AT) == null ? null : r.get(PAYOUTS.PAID_AT).toInstant(),
                r.get(PAYOUTS.PROVIDER_REFERENCE),
                r.get(PAYOUTS.DECISION_NOTE) != null ? r.get(PAYOUTS.DECISION_NOTE) : r.get(PAYOUTS.FAILURE_MESSAGE));
    }
}
