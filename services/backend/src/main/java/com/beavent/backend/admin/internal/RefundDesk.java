package com.beavent.backend.admin.internal;

import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS;
import static com.beavent.db.payments.Tables.PAYMENT_INTENTS;
import static com.beavent.db.payments.Tables.REFUNDS;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.payments.Refunds;
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
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds finance pays by hand (plan 3.6, ArifPay mode): a customer's deposit that goes back after a cancellation, a
 * duplicate payment, a late payment for a time that is gone. The queue shows who to pay (the customer's number) and
 * how they paid. Seeing it needs {@code admin.payments.view}; approving a duplicate and marking a refund paid need
 * {@code admin.payments.refund} and a fresh code, and are audited.
 */
@Component
public class RefundDesk {

    static final String VIEW = "admin.payments.view";
    static final String ACT = "admin.payments.refund";

    /** The queues, as conditions on the refund (what finance does next with each). */
    private static final Map<String, Condition> QUEUES = Map.of(
            "APPROVAL", REFUNDS.STATUS.eq("REQUESTED"),
            "PAY",
                    REFUNDS.STATUS
                            .eq("APPROVED")
                            .and(REFUNDS.METHOD.ne("ORIGINAL_METHOD"))
                            .or(REFUNDS.STATUS.eq("FAILED")),
            "PROCESSING",
                    REFUNDS.STATUS
                            .eq("PROCESSING")
                            .or(REFUNDS.STATUS.eq("APPROVED").and(REFUNDS.METHOD.eq("ORIGINAL_METHOD"))),
            "PAID", REFUNDS.STATUS.eq("SUCCEEDED"));

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Refunds refunds;
    private final Audit audit;

    RefundDesk(@SystemDb DSLContext system, @SystemDb TransactionTemplate transactions, Refunds refunds, Audit audit) {
        this.system = system;
        this.transactions = transactions;
        this.refunds = refunds;
        this.audit = audit;
    }

    public AdminBodies.Page<AdminBodies.RefundSummary> queue(
            StaffAccess staff, String queue, String cursor, int limit) {
        staff.require(VIEW);
        Condition inQueue = QUEUES.get(queue == null ? "PAY" : queue);
        if (inQueue == null) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown queue.");
        Condition condition =
                staff.marketFilter(VIEW, ORGANIZATIONS.HOME_MARKET_ID).and(inQueue);
        if (cursor != null) condition = condition.and(REFUNDS.ID.gt(Directory.cursorId(cursor)));
        List<AdminBodies.RefundSummary> rows = summaries()
                .where(condition)
                .orderBy(REFUNDS.ID)
                .limit(limit + 1)
                .fetch(RefundDesk::summary);
        return Directory.page(rows, limit, AdminBodies.RefundSummary::id);
    }

    public AdminBodies.RefundSummary approve(StaffAccess staff, UUID refundId, String note) {
        Record row = visible(staff, refundId);
        Assurance.requireStepUp();
        refunds.approve(refundId, staff.userId(), note == null ? null : note.strip());
        record(staff, row, "refund.approved", note);
        return summary(visible(staff, refundId));
    }

    public AdminBodies.RefundSummary pay(StaffAccess staff, UUID refundId, AdminBodies.RefundPayment body) {
        Record row = visible(staff, refundId);
        Assurance.requireStepUp();
        refunds.markPaid(
                refundId,
                staff.userId(),
                body.reference().strip(),
                body.note() == null ? null : body.note().strip());
        record(staff, row, "refund.paid", body.reference());
        return summary(visible(staff, refundId));
    }

    private void record(StaffAccess staff, Record row, String action, String reason) {
        transactions.executeWithoutResult(status -> audit.record(new Audit.Entry(
                Audit.Actor.PLATFORM_STAFF,
                staff.userId(),
                row.get(REFUNDS.ORG_ID),
                action,
                "payments.refund",
                row.get(REFUNDS.ID).toString(),
                Map.of("status", row.get(REFUNDS.STATUS)),
                Map.of("amountMinor", row.get(REFUNDS.AMOUNT_MINOR)),
                reason == null ? null : reason.strip())));
    }

    private org.jooq.SelectOnConditionStep<Record> summaries() {
        return system.select(
                        REFUNDS.asterisk(),
                        ORGANIZATIONS.DISPLAY_NAME,
                        ORGANIZATIONS.HOME_MARKET_ID,
                        MARKETS.CODE,
                        CUSTOMERS.DISPLAY_NAME,
                        DSL.coalesce(USERS.PHONE_E164, CUSTOMERS.PHONE_E164).as("customer_phone"),
                        PAYMENT_ATTEMPTS.METHOD_CODE,
                        APPOINTMENTS.REFERENCE)
                .from(REFUNDS)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(REFUNDS.ORG_ID))
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .join(PAYMENT_INTENTS)
                .on(PAYMENT_INTENTS.ID.eq(REFUNDS.INTENT_ID))
                .join(PAYMENT_ATTEMPTS)
                .on(PAYMENT_ATTEMPTS.ID.eq(REFUNDS.ATTEMPT_ID))
                .leftJoin(CUSTOMERS)
                .on(CUSTOMERS.ID.eq(PAYMENT_INTENTS.PAYER_CUSTOMER_ID))
                .leftJoin(USERS)
                .on(USERS.ID.eq(PAYMENT_INTENTS.PAYER_USER_ID))
                .leftJoin(APPOINTMENTS)
                .on(APPOINTMENTS.ORDER_ID.eq(REFUNDS.ORDER_ID));
    }

    private Record visible(StaffAccess staff, UUID refundId) {
        Record row = summaries().where(REFUNDS.ID.eq(refundId)).fetchOne();
        if (row == null) throw new ApiException(ProblemCode.NOT_FOUND);
        staff.requireInMarket(ACT, row.get(ORGANIZATIONS.HOME_MARKET_ID));
        return row;
    }

    private static AdminBodies.RefundSummary summary(Record r) {
        return new AdminBodies.RefundSummary(
                r.get(REFUNDS.ID),
                r.get(REFUNDS.ORG_ID),
                r.get(ORGANIZATIONS.DISPLAY_NAME),
                r.get(MARKETS.CODE),
                r.get(REFUNDS.AMOUNT_MINOR),
                r.get(REFUNDS.CURRENCY_CODE).trim(),
                r.get(REFUNDS.STATUS),
                r.get(REFUNDS.METHOD),
                r.get(REFUNDS.REASON),
                r.get(REFUNDS.REQUESTED_BY),
                r.get(CUSTOMERS.DISPLAY_NAME),
                r.get("customer_phone", String.class),
                r.get(PAYMENT_ATTEMPTS.METHOD_CODE),
                r.get(APPOINTMENTS.REFERENCE),
                r.get(REFUNDS.CREATED_AT).toInstant(),
                r.get(REFUNDS.SUCCEEDED_AT) == null
                        ? null
                        : r.get(REFUNDS.SUCCEEDED_AT).toInstant(),
                r.get(REFUNDS.PROVIDER_REFERENCE),
                r.get(REFUNDS.DECISION_NOTE) != null ? r.get(REFUNDS.DECISION_NOTE) : r.get(REFUNDS.FAILURE_MESSAGE));
    }
}
