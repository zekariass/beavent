package com.beavent.backend.payments.internal;

import static com.beavent.db.ledger.Tables.SCHEDULED_RELEASES;
import static com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS;
import static com.beavent.db.payments.Tables.PAYMENT_INTENTS;
import static com.beavent.db.payments.Tables.PROVIDER_ACCOUNTS;
import static com.beavent.db.payments.Tables.REFUNDS;

import com.beavent.backend.ledger.Ledger;
import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.ledger.Ledger.Line;
import com.beavent.backend.ledger.Ledger.Links;
import com.beavent.backend.ledger.Ledger.Posting;
import com.beavent.backend.payments.PaymentFulfilment;
import com.beavent.backend.payments.Refunds;
import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import com.beavent.backend.payments.internal.provider.ProviderException;
import com.beavent.backend.payments.internal.provider.RefundRequest;
import com.beavent.backend.payments.internal.provider.RefundResult;
import com.beavent.backend.payments.internal.provider.StatusResult;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.MoneyMath;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.payments.tables.records.PaymentAttemptsRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles orders that fell through and pays their refunds (plan 3.6). The terms come from the module that sold the
 * order ({@link PaymentFulfilment#settlement}); this class turns them into refund rows, ledger postings and a seller
 * balance that follows: the part refunded comes out of what the business is owed (waiting for release, or already
 * available), less the platform's fees on that part, which are given back. Refunds start {@code APPROVED}: they follow
 * from the terms the customer saw, so nobody has to approve them.
 */
@Component
class RefundService implements Refunds {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final Set<String> VISIBLE = Set.of("REQUESTED", "APPROVED", "PROCESSING", "FAILED", "SUCCEEDED");

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Ledger ledger;
    private final Captures captures;
    private final List<PaymentFulfilment> fulfilments;
    private final ActiveProvider providers;
    private final Outbox outbox;

    RefundService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            Ledger ledger,
            Captures captures,
            List<PaymentFulfilment> fulfilments,
            ActiveProvider providers,
            Outbox outbox) {
        this.system = system;
        this.transactions = transactions;
        this.ledger = ledger;
        this.captures = captures;
        this.fulfilments = fulfilments;
        this.providers = providers;
        this.outbox = outbox;
    }

    // --- settling an order -----------------------------------------------------------------------------------------

    @Override
    public void settleOrder(UUID orgId, UUID orderId, Instant now) {
        transactions.executeWithoutResult(status -> {
            var intents = system.selectFrom(PAYMENT_INTENTS)
                    .where(PAYMENT_INTENTS.ORG_ID.eq(orgId), PAYMENT_INTENTS.ORDER_ID.eq(orderId))
                    .and(PAYMENT_INTENTS.STATUS.eq("SUCCEEDED"))
                    .and(PAYMENT_INTENTS.CAPTURED_MINOR.gt(0L))
                    .orderBy(PAYMENT_INTENTS.CREATED_AT)
                    .forUpdate()
                    .fetch();
            if (intents.isEmpty()) return;
            long paid = intents.stream().mapToLong(i -> i.getCapturedMinor()).sum();
            PaymentFulfilment.Settlement terms = null;
            for (PaymentFulfilment f : fulfilments) {
                terms = f.settlement(orgId, orderId, paid, now);
                if (terms != null) break;
            }
            if (terms == null) return;
            long keepLeft = terms.retainMinor();
            for (var intent : intents) {
                long captured = intent.getCapturedMinor();
                long keep = Math.min(captured, keepLeft);
                keepLeft -= keep;
                if (captured - keep > 0) refundIntent(intent, captured - keep, terms, now);
            }
            // What the business keeps is released like any sale, without waiting for a visit that did not happen.
            system.update(SCHEDULED_RELEASES)
                    .set(SCHEDULED_RELEASES.SETTLED, true)
                    .set(SCHEDULED_RELEASES.STATUS, "SCHEDULED")
                    .where(SCHEDULED_RELEASES.ORG_ID.eq(orgId), SCHEDULED_RELEASES.ORDER_ID.eq(orderId))
                    .and(SCHEDULED_RELEASES.STATUS.in("SCHEDULED", "ON_HOLD"))
                    .execute();
        });
    }

    private void refundIntent(
            com.beavent.db.payments.tables.records.PaymentIntentsRecord intent,
            long refundMinor,
            PaymentFulfilment.Settlement terms,
            Instant now) {
        PaymentAttemptsRecord attempt = system.selectFrom(PAYMENT_ATTEMPTS)
                .where(PAYMENT_ATTEMPTS.INTENT_ID.eq(intent.getId()), PAYMENT_ATTEMPTS.STATUS.eq("SUCCEEDED"))
                .limit(1)
                .fetchOne();
        if (attempt == null) return;
        // The cap trigger runs before a conflict is noticed, so a repeat is recognised here.
        if (system.fetchExists(REFUNDS, REFUNDS.IDEMPOTENCY_KEY.eq("cancel:" + intent.getId()))) return;
        boolean byApi =
                adapterOf(attempt).map(PaymentProviderAdapter::canRefundByApi).orElse(false);
        UUID refundId = system.insertInto(REFUNDS)
                .set(REFUNDS.ORG_ID, intent.getOrgId())
                .set(REFUNDS.INTENT_ID, intent.getId())
                .set(REFUNDS.ATTEMPT_ID, attempt.getId())
                .set(REFUNDS.ORDER_ID, intent.getOrderId())
                .set(REFUNDS.AMOUNT_MINOR, refundMinor)
                .set(REFUNDS.CURRENCY_CODE, attempt.getCurrencyCode().trim())
                .set(REFUNDS.REASON, terms.reason())
                .set(REFUNDS.METHOD, byApi ? "ORIGINAL_METHOD" : "MANUAL_BANK_TRANSFER")
                .set(REFUNDS.STATUS, "APPROVED")
                .set(REFUNDS.REQUESTED_BY, terms.requestedBy())
                .set(REFUNDS.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
                .set(REFUNDS.DECISION_NOTE, "Under the booking's cancellation terms")
                .set(REFUNDS.IDEMPOTENCY_KEY, "cancel:" + intent.getId())
                .onConflictDoNothing()
                .returning(REFUNDS.ID)
                .fetchOptional(REFUNDS.ID)
                .orElse(null);
        if (refundId == null) return; // already refunded for this payment
        postInitiated(intent, attempt, refundId, refundMinor);
        outbox.publish(new DomainEvent(
                "payments.refund",
                refundId,
                "refund.approved",
                1,
                intent.getOrgId(),
                Map.of(
                        "refundId",
                        refundId.toString(),
                        "orderId",
                        intent.getOrderId() == null ? "" : intent.getOrderId().toString(),
                        "amountMinor",
                        refundMinor,
                        "currency",
                        attempt.getCurrencyCode().trim())));
    }

    /**
     * The refund comes out of what the business is owed: first what still waits for release, then what is already
     * available (which can go below zero and is then recovered from the next payouts). The platform's fees on the
     * refunded part are given back. With a split at source the business was paid by the provider: the platform funds
     * the refund and the business owes it.
     */
    private void postInitiated(
            com.beavent.db.payments.tables.records.PaymentIntentsRecord intent,
            PaymentAttemptsRecord attempt,
            UUID refundId,
            long refundMinor) {
        UUID orgId = intent.getOrgId();
        String currency = attempt.getCurrencyCode().trim();
        long captured = intent.getCapturedMinor();
        PlatformShare share = intent.getOrderId() == null
                ? PlatformShare.NONE
                : PlatformShare.of(system, intent.getOrderId(), captured);
        long reversed = share.totalMinor() == 0
                ? 0
                : MoneyMath.allocate(refundMinor, new long[] {share.totalMinor(), captured - share.totalMinor()})[0];
        long reversedVat =
                reversed == 0 ? 0 : MoneyMath.allocate(reversed, new long[] {share.vatMinor(), share.feeMinor()})[0];
        long reversedFee = reversed - reversedVat;
        long sellerPart = refundMinor - reversed;

        boolean split = captures.splitAtSource(attempt);
        long fromPending = 0;
        long fromAvailable = 0;
        if (sellerPart > 0 && !split) {
            fromPending = reduceUnreleased(orgId, intent.getOrderId(), sellerPart);
            fromAvailable = sellerPart - fromPending;
        }
        List<Line> lines = new ArrayList<>();
        if (split) {
            if (sellerPart > 0) lines.add(new Line(Account.seller(Code.SELLER_RECEIVABLE, orgId), sellerPart));
        } else {
            if (fromPending > 0) lines.add(new Line(Account.seller(Code.SELLER_PENDING, orgId), fromPending));
            if (fromAvailable > 0) lines.add(new Line(Account.seller(Code.SELLER_AVAILABLE, orgId), fromAvailable));
        }
        if (reversedFee > 0) lines.add(new Line(Account.platform(Code.PLATFORM_FEE_REVENUE), reversedFee));
        if (reversedVat > 0) lines.add(new Line(Account.platform(Code.TAX_PAYABLE), reversedVat));
        lines.add(new Line(Account.platform(Code.REFUNDS_PAYABLE), -refundMinor));
        ledger.post(new Posting(
                "REFUND_INITIATED",
                orgId,
                "refund-initiated:" + refundId,
                "Refund to the customer",
                new Links(attempt.getId(), refundId, null, intent.getOrderId()),
                currency,
                lines));
    }

    /**
     * Takes {@code amount} off the order's money that waits for release, oldest first; a release emptied this way is
     * cancelled. Returns how much was taken (less than {@code amount} when part was already released).
     */
    private long reduceUnreleased(UUID orgId, UUID orderId, long amount) {
        if (orderId == null) return 0;
        long left = amount;
        var rows = system.selectFrom(SCHEDULED_RELEASES)
                .where(SCHEDULED_RELEASES.ORG_ID.eq(orgId), SCHEDULED_RELEASES.ORDER_ID.eq(orderId))
                .and(SCHEDULED_RELEASES.STATUS.in("SCHEDULED", "ON_HOLD"))
                .orderBy(SCHEDULED_RELEASES.CREATED_AT)
                .forUpdate()
                .fetch();
        for (var row : rows) {
            if (left == 0) break;
            long take = Math.min(row.getAmountMinor(), left);
            left -= take;
            long remaining = row.getAmountMinor() - take;
            var update = system.update(SCHEDULED_RELEASES).set(SCHEDULED_RELEASES.SETTLED, true);
            if (remaining > 0) {
                update.set(SCHEDULED_RELEASES.AMOUNT_MINOR, remaining).set(SCHEDULED_RELEASES.STATUS, "SCHEDULED");
            } else {
                update.set(SCHEDULED_RELEASES.STATUS, "CANCELLED"); // the amount stays: it must be above zero
            }
            update.where(SCHEDULED_RELEASES.ID.eq(row.getId())).execute();
        }
        return amount - left;
    }

    @Override
    public int sweep(Instant now) {
        var held = system.selectDistinct(SCHEDULED_RELEASES.ORG_ID, SCHEDULED_RELEASES.ORDER_ID)
                .from(SCHEDULED_RELEASES)
                .where(SCHEDULED_RELEASES.STATUS.eq("ON_HOLD"), SCHEDULED_RELEASES.SETTLED.isFalse())
                .and(SCHEDULED_RELEASES.ORDER_ID.isNotNull())
                .limit(50)
                .fetch();
        int done = 0;
        for (var r : held) {
            try {
                settleOrder(r.value1(), r.value2(), now);
                done++;
            } catch (RuntimeException e) {
                log.warn("Settling order {} failed; it is tried again at the next run", r.value2(), e);
            }
        }
        return done;
    }

    // --- paying -----------------------------------------------------------------------------------------------------

    @Override
    public void approve(UUID refundId, UUID by, String note) {
        transactions.executeWithoutResult(status -> {
            var r = lock(refundId);
            if (!r.getStatus().equals("REQUESTED")) {
                throw new ApiException(ProblemCode.CONFLICT, "This refund is not waiting for approval.");
            }
            system.update(REFUNDS)
                    .set(REFUNDS.STATUS, "APPROVED")
                    .set(REFUNDS.DECIDED_BY, by)
                    .set(REFUNDS.DECIDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .set(REFUNDS.DECISION_NOTE, note)
                    .set(REFUNDS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .where(REFUNDS.ID.eq(refundId))
                    .execute();
        });
    }

    @Override
    public void markPaid(UUID refundId, UUID by, String reference, String note) {
        transactions.executeWithoutResult(status -> {
            var r = lock(refundId);
            if (!Set.of("APPROVED", "PROCESSING", "FAILED").contains(r.getStatus())) {
                throw new ApiException(ProblemCode.CONFLICT, "This refund cannot be marked paid now.");
            }
            if (by != null
                    && r.getMethod().equals("ORIGINAL_METHOD")
                    && !r.getStatus().equals("FAILED")) {
                // The provider is paying it: paying it by hand as well would refund twice.
                throw new ApiException(ProblemCode.CONFLICT, "This refund is being paid through the provider.");
            }
            OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
            system.update(REFUNDS)
                    .set(REFUNDS.STATUS, "SUCCEEDED")
                    .set(REFUNDS.SUCCEEDED_AT, at)
                    .set(REFUNDS.PROVIDER_REFERENCE, reference)
                    .set(REFUNDS.FAILURE_MESSAGE, (String) null)
                    .set(REFUNDS.DECIDED_BY, by)
                    .set(REFUNDS.DECISION_NOTE, note)
                    .set(REFUNDS.UPDATED_AT, at)
                    .where(REFUNDS.ID.eq(refundId))
                    .execute();
            UUID providerAccount = system.select(PAYMENT_ATTEMPTS.PROVIDER_ACCOUNT_ID)
                    .from(PAYMENT_ATTEMPTS)
                    .where(PAYMENT_ATTEMPTS.ID.eq(r.getAttemptId()))
                    .fetchSingle(PAYMENT_ATTEMPTS.PROVIDER_ACCOUNT_ID);
            String currency = r.getCurrencyCode().trim();
            ledger.post(new Posting(
                    "REFUND_SETTLED",
                    r.getOrgId(),
                    "refund-settled:" + refundId,
                    "Refund paid",
                    new Links(r.getAttemptId(), refundId, null, r.getOrderId()),
                    currency,
                    List.of(
                            new Line(Account.platform(Code.REFUNDS_PAYABLE), r.getAmountMinor()),
                            new Line(Account.clearing(providerAccount), -r.getAmountMinor()))));
            outbox.publish(new DomainEvent(
                    "payments.refund",
                    refundId,
                    "refund.succeeded",
                    1,
                    r.getOrgId(),
                    Map.of(
                            "refundId",
                            refundId.toString(),
                            "orderId",
                            r.getOrderId() == null ? "" : r.getOrderId().toString(),
                            "amountMinor",
                            r.getAmountMinor(),
                            "currency",
                            currency)));
        });
    }

    private com.beavent.db.payments.tables.records.RefundsRecord lock(UUID refundId) {
        var r = system.selectFrom(REFUNDS)
                .where(REFUNDS.ID.eq(refundId))
                .forUpdate()
                .fetchOne();
        if (r == null) throw new ApiException(ProblemCode.NOT_FOUND);
        return r;
    }

    // --- through the provider's API (Chapa)
    // ----------------------------------------------------------------------------

    @Override
    public int processApiRefunds(Instant now) {
        int moved = 0;
        List<UUID> start = system.select(REFUNDS.ID)
                .from(REFUNDS)
                .where(REFUNDS.STATUS.eq("APPROVED"), REFUNDS.METHOD.eq("ORIGINAL_METHOD"))
                .orderBy(REFUNDS.CREATED_AT)
                .limit(20)
                .fetch(REFUNDS.ID);
        for (UUID id : start) {
            try {
                if (startThroughApi(id)) moved++;
            } catch (RuntimeException e) {
                log.warn("Starting refund {} failed; it is tried again at the next run", id, e);
            }
        }
        List<UUID> following = system.select(REFUNDS.ID)
                .from(REFUNDS)
                .where(REFUNDS.STATUS.eq("PROCESSING"), REFUNDS.PROVIDER_REFERENCE.isNotNull())
                .orderBy(REFUNDS.UPDATED_AT)
                .limit(20)
                .fetch(REFUNDS.ID);
        for (UUID id : following) {
            try {
                if (followThroughApi(id)) moved++;
            } catch (RuntimeException e) {
                log.warn("Following refund {} failed; it is tried again at the next run", id, e);
            }
        }
        return moved;
    }

    private boolean startThroughApi(UUID refundId) {
        // Claim it first, so a second run does not ask the provider again; the call itself is outside a transaction.
        Boolean claimed = transactions.execute(status -> system.update(REFUNDS)
                        .set(REFUNDS.STATUS, "PROCESSING")
                        .set(REFUNDS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .where(REFUNDS.ID.eq(refundId), REFUNDS.STATUS.eq("APPROVED"))
                        .execute()
                == 1);
        if (!Boolean.TRUE.equals(claimed)) return false;
        var r = system.selectFrom(REFUNDS).where(REFUNDS.ID.eq(refundId)).fetchSingle();
        PaymentAttemptsRecord attempt = system.selectFrom(PAYMENT_ATTEMPTS)
                .where(PAYMENT_ATTEMPTS.ID.eq(r.getAttemptId()))
                .fetchSingle();
        var adapter = adapterOf(attempt).orElse(null);
        try {
            if (adapter == null || !adapter.canRefundByApi())
                throw new ProviderException(
                        com.beavent.backend.payments.internal.provider.FailureCode.UNKNOWN_ERROR,
                        "No refund API",
                        false);
            RefundResult result = adapter.refund(new RefundRequest(
                    attempt.getId().toString(), refundId.toString(), r.getAmountMinor(), r.getReason()));
            system.update(REFUNDS)
                    .set(REFUNDS.PROVIDER_REFERENCE, result.providerReference())
                    .where(REFUNDS.ID.eq(refundId))
                    .execute();
        } catch (ProviderException e) {
            // Finance can still pay it by hand from the queue.
            system.update(REFUNDS)
                    .set(REFUNDS.STATUS, "FAILED")
                    .set(REFUNDS.FAILURE_MESSAGE, e.getMessage())
                    .set(REFUNDS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .where(REFUNDS.ID.eq(refundId))
                    .execute();
        }
        return true;
    }

    private boolean followThroughApi(UUID refundId) {
        var r = system.selectFrom(REFUNDS).where(REFUNDS.ID.eq(refundId)).fetchSingle();
        PaymentAttemptsRecord attempt = system.selectFrom(PAYMENT_ATTEMPTS)
                .where(PAYMENT_ATTEMPTS.ID.eq(r.getAttemptId()))
                .fetchSingle();
        var adapter = adapterOf(attempt).orElse(null);
        if (adapter == null || !adapter.canRefundByApi()) return false;
        StatusResult answer;
        try {
            answer = adapter.queryRefund(r.getProviderReference());
        } catch (ProviderException e) {
            return false; // the provider cannot be reached: look again next time
        }
        switch (answer.status()) {
            case SUCCEEDED -> {
                markPaid(refundId, null, r.getProviderReference(), "Paid through the provider");
                return true;
            }
            case FAILED, CANCELLED, EXPIRED -> {
                transactions.executeWithoutResult(status -> system.update(REFUNDS)
                        .set(REFUNDS.STATUS, "FAILED")
                        .set(REFUNDS.FAILURE_MESSAGE, "The provider could not refund it")
                        .set(REFUNDS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .where(REFUNDS.ID.eq(refundId), REFUNDS.STATUS.eq("PROCESSING"))
                        .execute());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private java.util.Optional<PaymentProviderAdapter> adapterOf(PaymentAttemptsRecord attempt) {
        String code = system.select(PROVIDER_ACCOUNTS.PROVIDER_CODE)
                .from(PROVIDER_ACCOUNTS)
                .where(PROVIDER_ACCOUNTS.ID.eq(attempt.getProviderAccountId()))
                .fetchOne(PROVIDER_ACCOUNTS.PROVIDER_CODE);
        return code == null ? java.util.Optional.empty() : providers.byCode(code);
    }

    // --- what the customer sees
    // ----------------------------------------------------------------------------------------

    @Override
    public Summary summary(UUID orgId, UUID orderId) {
        var rows = system.select(REFUNDS.AMOUNT_MINOR, REFUNDS.STATUS, REFUNDS.CURRENCY_CODE)
                .from(REFUNDS)
                .where(REFUNDS.ORG_ID.eq(orgId), REFUNDS.ORDER_ID.eq(orderId))
                .and(REFUNDS.STATUS.in(VISIBLE))
                .fetch();
        if (rows.isEmpty()) {
            // Settled with nothing to give back: the business kept all of it.
            String currency = system.select(SCHEDULED_RELEASES.CURRENCY_CODE)
                    .from(SCHEDULED_RELEASES)
                    .where(SCHEDULED_RELEASES.ORG_ID.eq(orgId), SCHEDULED_RELEASES.ORDER_ID.eq(orderId))
                    .and(SCHEDULED_RELEASES.SETTLED.isTrue())
                    .limit(1)
                    .fetchOne(SCHEDULED_RELEASES.CURRENCY_CODE);
            return currency == null ? null : new Summary(0, currency.trim(), "NONE");
        }
        long total = rows.stream().mapToLong(r -> r.value1()).sum();
        boolean allSent = rows.stream().allMatch(r -> r.value2().equals("SUCCEEDED"));
        return new Summary(total, rows.get(0).value3().trim(), allSent ? "SENT" : "ON_THE_WAY");
    }
}
