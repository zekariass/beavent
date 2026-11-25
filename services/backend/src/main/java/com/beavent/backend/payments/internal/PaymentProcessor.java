package com.beavent.backend.payments.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_STATUS_HISTORY;
import static com.beavent.db.commerce.Tables.PAYMENT_SCHEDULE;
import static com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS;
import static com.beavent.db.payments.Tables.PAYMENT_INTENTS;
import static com.beavent.db.payments.Tables.PROVIDER_ACCOUNTS;
import static com.beavent.db.payments.Tables.REFUNDS;

import com.beavent.backend.payments.PaymentFulfilment;
import com.beavent.backend.payments.internal.provider.AttemptStatus;
import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import com.beavent.backend.payments.internal.provider.ProviderException;
import com.beavent.backend.payments.internal.provider.StatusResult;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.SystemDb;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves a payment attempt forward from what the provider says (docs/04 §6.1, §7), the only place a payment is marked
 * as succeeded. A browser redirect or a notification is never proof: every transition starts from a server-side
 * lookup with our key whose amount, currency and reference match our records. Transitions only move forward; a late
 * success of an abandoned attempt is honoured if the order can still be fulfilled, and refunded if not; a second
 * success for an intent already paid is recorded as a duplicate and queued for refund (docs/04 §5).
 */
@Component
class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    /** Waits between lookups of an open attempt (docs/04 §6.2): 5 s, 10 s, 15 s, 30 s, then every minute. */
    private static final int[] POLL_SECONDS = {5, 10, 15, 30, 60};
    /** An attempt whose create call never got an answer is given this long to be found at the provider. */
    private static final Duration CREATE_GRACE = Duration.ofMinutes(2);

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final ActiveProvider providers;
    private final List<PaymentFulfilment> fulfilments;
    private final Outbox outbox;
    private final Captures captures;

    PaymentProcessor(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            ActiveProvider providers,
            List<PaymentFulfilment> fulfilments,
            Outbox outbox,
            Captures captures) {
        this.captures = captures;
        this.system = system;
        this.transactions = transactions;
        this.providers = providers;
        this.fulfilments = fulfilments;
        this.outbox = outbox;
    }

    /** The adapter of the provider an attempt was made with (the active one, or an earlier one after a switch). */
    PaymentProviderAdapter adapterOf(UUID attemptId) {
        String code = system.select(PROVIDER_ACCOUNTS.PROVIDER_CODE)
                .from(PAYMENT_ATTEMPTS)
                .join(PROVIDER_ACCOUNTS)
                .on(PROVIDER_ACCOUNTS.ID.eq(PAYMENT_ATTEMPTS.PROVIDER_ACCOUNT_ID))
                .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                .fetchOne(PROVIDER_ACCOUNTS.PROVIDER_CODE);
        PaymentProviderAdapter adapter =
                code == null ? null : providers.byCode(code).orElse(null);
        if (adapter == null) throw new IllegalStateException("No adapter for the provider of attempt " + attemptId);
        return adapter;
    }

    /**
     * Asks the provider about the attempt (outside any transaction) and applies the answer. A provider that cannot
     * be reached only delays the next look.
     */
    void poll(UUID attemptId, Instant now) {
        var attempt = system.select(
                        PAYMENT_ATTEMPTS.STATUS,
                        PAYMENT_ATTEMPTS.PROVIDER_REFERENCE,
                        PAYMENT_ATTEMPTS.INITIATED_AT,
                        PAYMENT_INTENTS.EXPIRES_AT)
                .from(PAYMENT_ATTEMPTS)
                .join(PAYMENT_INTENTS)
                .on(PAYMENT_INTENTS.ID.eq(PAYMENT_ATTEMPTS.INTENT_ID))
                .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                .fetchOne();
        if (attempt == null) return;
        StatusResult result;
        try {
            PaymentProviderAdapter adapter = adapterOf(attemptId);
            result = attempt.value2() != null
                    ? adapter.queryCollection(attempt.value2())
                    : adapter.findCollection(attemptId.toString());
        } catch (ProviderException e) {
            log.warn("Looking up attempt {} failed: {}", attemptId, e.getMessage());
            reschedule(attemptId, now);
            return;
        }
        if (result == null) {
            // Nothing under our reference: the create call never reached the provider (or not yet).
            boolean old = attempt.value3().toInstant().plus(CREATE_GRACE).isBefore(now);
            if (old) fail(attemptId, "PROVIDER_UNAVAILABLE", "The provider has no payment under our reference", now);
            else reschedule(attemptId, now);
            return;
        }
        apply(attemptId, result, now);
        // An unpaid attempt past the intent's time is closed after this last look.
        transactions.executeWithoutResult(status -> {
            var open = system.select(PAYMENT_ATTEMPTS.STATUS)
                    .from(PAYMENT_ATTEMPTS)
                    .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                    .fetchSingle(PAYMENT_ATTEMPTS.STATUS);
            if (!AttemptStatus.valueOf(open).isFinal()
                    && attempt.value4().toInstant().isBefore(now)) {
                close(attemptId, "EXPIRED", now);
            }
        });
    }

    /** Applies what the provider said about an attempt, atomically; safe to repeat and to receive out of order. */
    void apply(UUID attemptId, StatusResult result, Instant now) {
        transactions.executeWithoutResult(status -> {
            var a = system.selectFrom(PAYMENT_ATTEMPTS)
                    .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                    .forUpdate()
                    .fetchOne();
            if (a == null) return;
            String current = a.getStatus();
            if (current.equals("SUCCEEDED") || current.equals("SUCCEEDED_DUPLICATE")) return;
            if (result.reference() != null && !result.reference().equals(attemptId.toString())) {
                log.error("Provider answered attempt {} with another reference {}", attemptId, result.reference());
                mismatch(a.getId(), "REFERENCE_MISMATCH:" + result.reference(), now);
                return;
            }
            switch (result.status()) {
                case SUCCEEDED -> {
                    boolean same = result.amountMinor() != null
                            && result.amountMinor().longValue()
                                    == a.getAmountMinor().longValue()
                            && a.getCurrencyCode().trim().equals(result.currency());
                    if (!same) {
                        log.error(
                                "Attempt {} paid {} {} but we asked {} {}",
                                attemptId,
                                result.amountMinor(),
                                result.currency(),
                                a.getAmountMinor(),
                                a.getCurrencyCode());
                        mismatch(a.getId(), "AMOUNT_MISMATCH:" + result.amountMinor() + result.currency(), now);
                        return;
                    }
                    succeed(a, result, now);
                }
                case FAILED, CANCELLED, EXPIRED -> {
                    if (!AttemptStatus.valueOf(current).isFinal()) {
                        system.update(PAYMENT_ATTEMPTS)
                                .set(PAYMENT_ATTEMPTS.STATUS, result.status().name())
                                .set(PAYMENT_ATTEMPTS.PROVIDER_STATUS_RAW, result.rawStatus())
                                .set(
                                        PAYMENT_ATTEMPTS.FAILURE_CODE,
                                        result.failure() == null
                                                ? null
                                                : result.failure().name())
                                .set(PAYMENT_ATTEMPTS.COMPLETED_AT, now.atOffset(ZoneOffset.UTC))
                                .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, (OffsetDateTime) null)
                                .set(PAYMENT_ATTEMPTS.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                                .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                                .execute();
                        reopenIntent(a.getIntentId());
                    }
                }
                default -> {
                    // Still with the payer or the provider: move forward only, and look again later.
                    if (!AttemptStatus.valueOf(current).isFinal()) {
                        String next = result.status() == AttemptStatus.PROCESSING || current.equals("PROCESSING")
                                ? "PROCESSING"
                                : "AWAITING_CUSTOMER";
                        int polls = a.getPollCount() + 1;
                        system.update(PAYMENT_ATTEMPTS)
                                .set(PAYMENT_ATTEMPTS.STATUS, next)
                                .set(PAYMENT_ATTEMPTS.PROVIDER_STATUS_RAW, result.rawStatus())
                                .set(PAYMENT_ATTEMPTS.POLL_COUNT, (short) polls)
                                .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, nextPoll(polls, now))
                                .set(PAYMENT_ATTEMPTS.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                                .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                                .execute();
                    }
                }
            }
        });
    }

    private void succeed(
            com.beavent.db.payments.tables.records.PaymentAttemptsRecord a, StatusResult result, Instant now) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        var intent = system.selectFrom(PAYMENT_INTENTS)
                .where(PAYMENT_INTENTS.ID.eq(a.getIntentId()))
                .forUpdate()
                .fetchSingle();
        if (intent.getStatus().equals("SUCCEEDED")) {
            // The payer approved a second prompt: record it and queue its refund (docs/04 §5).
            system.update(PAYMENT_ATTEMPTS)
                    .set(PAYMENT_ATTEMPTS.STATUS, "SUCCEEDED_DUPLICATE")
                    .set(PAYMENT_ATTEMPTS.PROVIDER_STATUS_RAW, result.rawStatus())
                    .set(PAYMENT_ATTEMPTS.COMPLETED_AT, at)
                    .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, (OffsetDateTime) null)
                    .where(PAYMENT_ATTEMPTS.ID.eq(a.getId()))
                    .execute();
            // Requested, not approved: the money was never counted as captured, so finance decides (slice 3.6).
            refund(
                    a,
                    intent.getOrderId(),
                    a.getAmountMinor(),
                    "DUPLICATE_PAYMENT",
                    "REQUESTED",
                    "duplicate:" + a.getId());
            captures.receivedForRefund(a, intent.getOrderId());
            publish(a.getOrgId(), intent.getOrderId(), "payment.duplicate_received", a, a.getAmountMinor());
            return;
        }
        system.update(PAYMENT_ATTEMPTS)
                .set(PAYMENT_ATTEMPTS.STATUS, "SUCCEEDED")
                .set(PAYMENT_ATTEMPTS.PROVIDER_STATUS_RAW, result.rawStatus())
                .set(PAYMENT_ATTEMPTS.COMPLETED_AT, at)
                .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, (OffsetDateTime) null)
                .set(PAYMENT_ATTEMPTS.UPDATED_AT, at)
                .where(PAYMENT_ATTEMPTS.ID.eq(a.getId()))
                .execute();
        // Captured amount: the database keeps the order's paid total and payment status in step (a trigger).
        system.update(PAYMENT_INTENTS)
                .set(PAYMENT_INTENTS.STATUS, "SUCCEEDED")
                .set(PAYMENT_INTENTS.CAPTURED_MINOR, a.getAmountMinor())
                .set(PAYMENT_INTENTS.SUCCEEDED_AT, at)
                .set(PAYMENT_INTENTS.UPDATED_AT, at)
                .where(PAYMENT_INTENTS.ID.eq(intent.getId()))
                .execute();
        if (intent.getPaymentScheduleId() != null) {
            system.update(PAYMENT_SCHEDULE)
                    .set(PAYMENT_SCHEDULE.STATUS, "PAID")
                    .set(PAYMENT_SCHEDULE.COLLECTED_MINOR, a.getAmountMinor())
                    .where(PAYMENT_SCHEDULE.ID.eq(intent.getPaymentScheduleId()))
                    .execute();
        }
        UUID orderId = intent.getOrderId();
        String orderStatus = orderId == null
                ? null
                : system.select(ORDERS.STATUS)
                        .from(ORDERS)
                        .where(ORDERS.ID.eq(orderId))
                        .forUpdate()
                        .fetchOne(ORDERS.STATUS);
        boolean late = orderStatus != null && !orderStatus.equals("PENDING_PAYMENT");
        PaymentFulfilment.Outcome outcome = PaymentFulfilment.Outcome.NOT_MINE;
        if (orderId != null) {
            for (PaymentFulfilment f : fulfilments) {
                PaymentFulfilment.Outcome o = f.onPaid(a.getOrgId(), orderId, a.getAmountMinor(), late, now);
                if (o != PaymentFulfilment.Outcome.NOT_MINE) {
                    outcome = o;
                    break;
                }
            }
        }
        if (outcome == PaymentFulfilment.Outcome.CONFIRMED) {
            system.update(ORDERS)
                    .set(ORDERS.STATUS, "CONFIRMED")
                    .set(ORDERS.CONFIRMED_AT, at)
                    .set(ORDERS.VERSION, ORDERS.VERSION.plus(1))
                    .where(ORDERS.ID.eq(orderId))
                    .execute();
            system.insertInto(ORDER_STATUS_HISTORY)
                    .set(ORDER_STATUS_HISTORY.ORG_ID, a.getOrgId())
                    .set(ORDER_STATUS_HISTORY.ORDER_ID, orderId)
                    .set(ORDER_STATUS_HISTORY.FROM_STATUS, orderStatus)
                    .set(ORDER_STATUS_HISTORY.TO_STATUS, "CONFIRMED")
                    .set(ORDER_STATUS_HISTORY.ACTOR_KIND, "SYSTEM")
                    .set(ORDER_STATUS_HISTORY.REASON, late ? "Paid late; the time was still free" : "Payment received")
                    .execute();
            captures.confirmed(a, orderId, now);
            publish(a.getOrgId(), orderId, "payment.succeeded", a, a.getAmountMinor());
        } else {
            // Nothing to honour any more (the time went, it was cancelled): the whole payment goes back.
            refund(a, orderId, a.getAmountMinor(), "ORDER_ADJUSTMENT", "APPROVED", "unfulfilled:" + intent.getId());
            captures.receivedForRefund(a, orderId);
            publish(a.getOrgId(), orderId, "payment.refund_owed", a, a.getAmountMinor());
        }
    }

    private void refund(
            com.beavent.db.payments.tables.records.PaymentAttemptsRecord a,
            UUID orderId,
            long amountMinor,
            String reason,
            String status,
            String key) {
        system.insertInto(REFUNDS)
                .set(REFUNDS.ORG_ID, a.getOrgId())
                .set(REFUNDS.INTENT_ID, a.getIntentId())
                .set(REFUNDS.ATTEMPT_ID, a.getId())
                .set(REFUNDS.ORDER_ID, orderId)
                .set(REFUNDS.AMOUNT_MINOR, amountMinor)
                .set(REFUNDS.CURRENCY_CODE, a.getCurrencyCode().trim())
                .set(REFUNDS.REASON, reason)
                .set(REFUNDS.METHOD, "MANUAL_BANK_TRANSFER")
                .set(REFUNDS.STATUS, status)
                .set(REFUNDS.REQUESTED_BY, "SYSTEM")
                .set(REFUNDS.IDEMPOTENCY_KEY, key)
                .onConflictDoNothing()
                .execute();
    }

    private void publish(
            UUID orgId,
            UUID orderId,
            String type,
            com.beavent.db.payments.tables.records.PaymentAttemptsRecord a,
            long amount) {
        outbox.publish(new DomainEvent(
                "payments.attempt",
                a.getId(),
                type,
                1,
                orgId,
                Map.of(
                        "attemptId", a.getId(),
                        "intentId", a.getIntentId(),
                        "orderId", orderId == null ? "" : orderId,
                        "amountMinor", amount,
                        "currency", a.getCurrencyCode().trim())));
    }

    /** A paid answer that does not match what we asked for is never acted on: it waits for a person (docs/04 §14). */
    private void mismatch(UUID attemptId, String note, Instant now) {
        system.update(PAYMENT_ATTEMPTS)
                .set(PAYMENT_ATTEMPTS.STATUS, "UNKNOWN")
                .set(PAYMENT_ATTEMPTS.FAILURE_CODE, "UNKNOWN_ERROR")
                .set(PAYMENT_ATTEMPTS.FAILURE_MESSAGE, note)
                .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, (OffsetDateTime) null)
                .set(PAYMENT_ATTEMPTS.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                .execute();
    }

    /** The attempt failed before it began (the provider never had it). */
    void fail(UUID attemptId, String failureCode, String message, Instant now) {
        transactions.executeWithoutResult(status -> {
            var intentId = system.update(PAYMENT_ATTEMPTS)
                    .set(PAYMENT_ATTEMPTS.STATUS, "FAILED")
                    .set(PAYMENT_ATTEMPTS.FAILURE_CODE, failureCode)
                    .set(PAYMENT_ATTEMPTS.FAILURE_MESSAGE, message)
                    .set(PAYMENT_ATTEMPTS.COMPLETED_AT, now.atOffset(ZoneOffset.UTC))
                    .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, (OffsetDateTime) null)
                    .set(PAYMENT_ATTEMPTS.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                    .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                    .and(PAYMENT_ATTEMPTS.STATUS.in("CREATED", "AWAITING_CUSTOMER", "PROCESSING", "UNKNOWN"))
                    .returning(PAYMENT_ATTEMPTS.INTENT_ID)
                    .fetchOne();
            if (intentId != null) reopenIntent(intentId.getIntentId());
        });
    }

    /** Closes an unpaid attempt (the intent ran out of time). */
    void close(UUID attemptId, String finalStatus, Instant now) {
        system.update(PAYMENT_ATTEMPTS)
                .set(PAYMENT_ATTEMPTS.STATUS, finalStatus)
                .set(PAYMENT_ATTEMPTS.COMPLETED_AT, now.atOffset(ZoneOffset.UTC))
                .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, (OffsetDateTime) null)
                .set(PAYMENT_ATTEMPTS.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                .and(PAYMENT_ATTEMPTS.STATUS.in("CREATED", "AWAITING_CUSTOMER", "PROCESSING", "UNKNOWN"))
                .execute();
    }

    /** A failed attempt leaves the intent open for another try while it still has time. */
    private void reopenIntent(UUID intentId) {
        system.update(PAYMENT_INTENTS)
                .set(PAYMENT_INTENTS.STATUS, "REQUIRES_METHOD")
                .where(PAYMENT_INTENTS.ID.eq(intentId))
                .and(PAYMENT_INTENTS.STATUS.in("PROCESSING", "REQUIRES_ACTION"))
                .andNotExists(DSL.selectOne()
                        .from(PAYMENT_ATTEMPTS)
                        .where(PAYMENT_ATTEMPTS.INTENT_ID.eq(intentId))
                        .and(PAYMENT_ATTEMPTS.STATUS.in("CREATED", "AWAITING_CUSTOMER", "PROCESSING")))
                .execute();
    }

    private void reschedule(UUID attemptId, Instant now) {
        transactions.executeWithoutResult(status -> {
            Short polls = system.select(PAYMENT_ATTEMPTS.POLL_COUNT)
                    .from(PAYMENT_ATTEMPTS)
                    .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                    .fetchOne(PAYMENT_ATTEMPTS.POLL_COUNT);
            int next = (polls == null ? 0 : polls) + 1;
            system.update(PAYMENT_ATTEMPTS)
                    .set(PAYMENT_ATTEMPTS.POLL_COUNT, (short) next)
                    .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, nextPoll(next, now))
                    .where(PAYMENT_ATTEMPTS.ID.eq(attemptId))
                    .execute();
        });
    }

    static OffsetDateTime nextPoll(int pollCount, Instant now) {
        int seconds = POLL_SECONDS[Math.min(Math.max(pollCount - 1, 0), POLL_SECONDS.length - 1)];
        return now.plusSeconds(seconds).atOffset(ZoneOffset.UTC);
    }
}
