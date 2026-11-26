package com.beavent.backend.scheduling.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.commerce.Tables.ORDER_STATUS_HISTORY;
import static com.beavent.db.commerce.Tables.PAYMENT_SCHEDULE;

import com.beavent.backend.commerce.CashDrawers;
import com.beavent.backend.commerce.Receipts;
import com.beavent.backend.payments.Payments;
import com.beavent.backend.payments.Payouts;
import com.beavent.backend.payments.StoredValue;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Taking the payment for an order at the counter (plans 4.1 and 4.2), whatever was sold: gift cards, then the customer's
 * balance, as much as is due and each committed as it is taken; then the cash for the rest, the order completed (and the
 * visit, when there is one) and the receipt, together; then the platform's fees on what was paid at the venue.
 */
@Component
class Tendering {

    private static final Logger log = LoggerFactory.getLogger(Tendering.class);

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Calendar calendar;
    private final Payments payments;
    private final CashDrawers drawers;
    private final Receipts receipts;
    private final Payouts payouts;

    Tendering(
            DSLContext dsl,
            TransactionTemplate transactions,
            Calendar calendar,
            Payments payments,
            CashDrawers drawers,
            Receipts receipts,
            Payouts payouts) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.calendar = calendar;
        this.payments = payments;
        this.drawers = drawers;
        this.receipts = receipts;
        this.payouts = payouts;
    }

    Checkouts.Done collect(
            OrgAccess access,
            UUID orgId,
            UUID locationId,
            UUID orderId,
            UUID appointmentId,
            List<String> cards,
            boolean useBalance,
            Long cashTenderedMinor,
            String currency,
            long paidOnline,
            Instant now) {
        if (!cards.isEmpty() || useBalance) {
            for (String code : cards) {
                if (dueNow(orderId) == 0) break;
                payments.tenderAtCounter(orgId, orderId, StoredValue.Kind.GIFT_CARD, code, access.userId(), now);
            }
            if (useBalance && dueNow(orderId) > 0) {
                payments.tenderAtCounter(orgId, orderId, StoredValue.Kind.BALANCE, null, access.userId(), now);
            }
        }

        Checkouts.Done result = transactions.execute(status -> {
            long due = dueAtVenue(orderId);
            long handed = 0;
            long change = 0;
            if (due > 0) {
                handed = cashTenderedMinor == null ? due : cashTenderedMinor;
                if (handed < due) {
                    throw new ApiException(
                            ProblemCode.VALIDATION_FAILED,
                            "The cash is less than what is due. What the cards and balance paid is kept; "
                                    + "take the rest to finish.");
                }
                var session = drawers.current(orgId, locationId);
                if (session == null) throw new ApiException(ProblemCode.CONFLICT, "Open the cash drawer first.");
                change = handed - due;
                UUID attempt = payments.recordCash(orgId, orderId, session.id(), due, now);
                drawers.recordSale(orgId, session.id(), attempt, due, access.userId());
            }
            if (appointmentId != null) calendar.completeAtCounter(appointmentId, access.userId());
            dsl.update(ORDERS)
                    .set(ORDERS.STATUS, "COMPLETED")
                    .set(ORDERS.COMPLETED_AT, now.atOffset(ZoneOffset.UTC))
                    .set(ORDERS.VERSION, ORDERS.VERSION.plus(1))
                    .where(ORDERS.ID.eq(orderId))
                    .execute();
            dsl.insertInto(ORDER_STATUS_HISTORY)
                    .set(ORDER_STATUS_HISTORY.ORG_ID, orgId)
                    .set(ORDER_STATUS_HISTORY.ORDER_ID, orderId)
                    .set(ORDER_STATUS_HISTORY.FROM_STATUS, "CONFIRMED")
                    .set(ORDER_STATUS_HISTORY.TO_STATUS, "COMPLETED")
                    .set(ORDER_STATUS_HISTORY.ACTOR_KIND, "STAFF")
                    .set(ORDER_STATUS_HISTORY.ACTOR_USER_ID, access.userId())
                    .set(ORDER_STATUS_HISTORY.REASON, "Checked out at the counter")
                    .execute();
            Receipts.Receipt receipt = receipts.issue(orgId, orderId, now);
            var order = dsl.selectFrom(ORDERS).where(ORDERS.ID.eq(orderId)).fetchSingle();
            long tipsOnOrder = dsl.select(org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(ORDER_LINES.TOTAL_MINOR), 0))
                    .from(ORDER_LINES)
                    .where(ORDER_LINES.ORDER_ID.eq(orderId))
                    .and(ORDER_LINES.LINE_TYPE.eq("TIP"))
                    .fetchSingle(0, Long.class);
            return new Checkouts.Done(
                    orderId,
                    order.getNumber(),
                    receipt.number(),
                    currency,
                    order.getTotalMinor(),
                    paidOnline,
                    tipsOnOrder,
                    due,
                    handed,
                    change,
                    order.getDiscountMinor(),
                    tendersOf(orderId));
        });
        // The platform's fees on what was paid at the venue; a failure here is caught up by the payout worker's sweep.
        try {
            payouts.chargeVenueFees(orgId, orderId, now);
        } catch (RuntimeException e) {
            log.warn("Charging the venue fees of order {} failed; the sweep will", orderId, e);
        }
        return result;
    }

    /** {@link #dueAtVenue} in a transaction of its own (the business's context must be bound for the rows to be seen). */
    long dueNow(UUID orderId) {
        return transactions.execute(status -> dueAtVenue(orderId));
    }

    /** What is still to be paid at the counter on the order. */
    long dueAtVenue(UUID orderId) {
        return dsl.select(org.jooq.impl.DSL.coalesce(
                        org.jooq.impl.DSL.sum(PAYMENT_SCHEDULE.AMOUNT_MINOR.minus(PAYMENT_SCHEDULE.COLLECTED_MINOR)),
                        0))
                .from(PAYMENT_SCHEDULE)
                .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("AT_VENUE"))
                .and(PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                .fetchSingle(0, Long.class);
    }

    /** What was paid at the counter so far, by how it was paid. */
    List<Checkouts.Tender> tendersOf(UUID orderId) {
        return dsl.select(
                        com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS.METHOD_CODE,
                        com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS.AMOUNT_MINOR)
                .from(com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS)
                .join(com.beavent.db.payments.Tables.PAYMENT_INTENTS)
                .on(com.beavent.db.payments.Tables.PAYMENT_INTENTS.ID.eq(
                        com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS.INTENT_ID))
                .join(PAYMENT_SCHEDULE)
                .on(PAYMENT_SCHEDULE.ID.eq(com.beavent.db.payments.Tables.PAYMENT_INTENTS.PAYMENT_SCHEDULE_ID))
                .where(com.beavent.db.payments.Tables.PAYMENT_INTENTS.ORDER_ID.eq(orderId))
                .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("AT_VENUE"))
                .and(com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS.STATUS.eq("SUCCEEDED"))
                .orderBy(com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS.INITIATED_AT)
                .fetch(r -> new Checkouts.Tender(r.value1(), r.value2()));
    }
}
