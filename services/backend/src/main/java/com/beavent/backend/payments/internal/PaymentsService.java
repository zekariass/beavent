package com.beavent.backend.payments.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.PAYMENT_SCHEDULE;
import static com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS;
import static com.beavent.db.payments.Tables.PAYMENT_INTENTS;

import com.beavent.backend.payments.Payments;
import com.beavent.backend.payments.internal.provider.CollectionRequest;
import com.beavent.backend.payments.internal.provider.CollectionResult;
import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import com.beavent.backend.payments.internal.provider.ProviderException;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Payments}: payment schedules, starting an online payment with the active provider, and reading where it stands.
 * Runs with the system role: it serves customers of every business, and callers have already checked who may ask.
 */
@Service
class PaymentsService implements Payments {

    private static final Logger log = LoggerFactory.getLogger(PaymentsService.class);
    /** A create call younger than this may still be answering: a second start waits instead of opening another. */
    private static final long SETTING_UP_SECONDS = 20;

    /** The caller's connection, for writing the schedule in the same transaction as the order it belongs to. */
    private final DSLContext dsl;

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final ActiveProvider providers;
    private final ProviderAccounts accounts;
    private final PaymentProcessor processor;
    private final SellerAccounts sellers;
    private final PaymentsProperties props;
    private final JsonMapper json;

    PaymentsService(
            DSLContext dsl,
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            ActiveProvider providers,
            ProviderAccounts accounts,
            PaymentProcessor processor,
            SellerAccounts sellers,
            PaymentsProperties props,
            JsonMapper json) {
        this.dsl = dsl;
        this.system = system;
        this.transactions = transactions;
        this.providers = providers;
        this.accounts = accounts;
        this.processor = processor;
        this.sellers = sellers;
        this.props = props;
        this.json = json;
    }

    @Override
    public void plan(UUID orgId, UUID orderId, String currency, List<Installment> installments) {
        short seq = 1;
        for (Installment i : installments) {
            dsl.insertInto(PAYMENT_SCHEDULE)
                    .set(PAYMENT_SCHEDULE.ORG_ID, orgId)
                    .set(PAYMENT_SCHEDULE.ORDER_ID, orderId)
                    .set(PAYMENT_SCHEDULE.SEQ, seq++)
                    .set(PAYMENT_SCHEDULE.KIND, i.kind())
                    .set(PAYMENT_SCHEDULE.AMOUNT_MINOR, i.amountMinor())
                    .set(PAYMENT_SCHEDULE.CURRENCY_CODE, currency)
                    .set(PAYMENT_SCHEDULE.COLLECT_AT, i.collectAt())
                    .execute();
        }
    }

    /** What phase one of {@link #start} decided: an open attempt to hand back, or a new one to create at the provider. */
    private record Phase(
            Started resume, UUID attemptId, UUID intentId, long amountMinor, String currency, Instant expiresAt) {}

    @Override
    public Started start(UUID orgId, UUID orderId, Payer payer, Urls urls, String description, Instant now) {
        PaymentProviderAdapter adapter = providers.adapter();
        if (!sellers.available(orgId)) {
            throw new ApiException(ProblemCode.PAYMENT_UNAVAILABLE, "This business cannot take online payments yet.");
        }
        Phase phase = transactions.execute(status -> begin(orgId, orderId, payer, adapter, now));
        if (phase.resume() != null) return phase.resume();
        // Chapa mode pays the seller at source: their subaccount, and the platform's share of this payment.
        CollectionRequest.Split split = null;
        if (adapter.collectionMode().equals("PROVIDER_SPLIT")) {
            String sub = sellers.subAccountOf(orgId);
            if (sub == null) sub = sellers.ensureSubAccount(orgId);
            if (sub == null) {
                processor.fail(phase.attemptId(), "INVALID_REQUEST", "No subaccount for the seller", now);
                throw new ApiException(
                        ProblemCode.PAYMENT_UNAVAILABLE, "This business cannot take online payments yet.");
            }
            split = new CollectionRequest.Split(sub, platformShare(orderId, phase.amountMinor()));
        }

        CollectionResult created;
        try {
            created = adapter.createCollection(new CollectionRequest(
                    phase.attemptId().toString(),
                    phase.amountMinor(),
                    phase.currency(),
                    description,
                    payer.phone(),
                    payer.email(),
                    payer.language(),
                    urls.success(),
                    urls.cancel(),
                    urls.error(),
                    URI.create(props.notifyBaseUrl() + "/v1/hooks/"
                            + adapter.providerCode().toLowerCase()),
                    phase.expiresAt(),
                    split));
        } catch (ProviderException e) {
            log.warn("Creating the collection for attempt {} failed: {}", phase.attemptId(), e.getMessage());
            if (e.code() == com.beavent.backend.payments.internal.provider.FailureCode.PROVIDER_UNAVAILABLE) {
                // The request may have reached the provider: leave it to the poller, which looks it up by our
                // reference.
                transactions.executeWithoutResult(status -> system.update(PAYMENT_ATTEMPTS)
                        .set(PAYMENT_ATTEMPTS.STATUS, "UNKNOWN")
                        .set(PAYMENT_ATTEMPTS.FAILURE_CODE, e.code().name())
                        .set(PAYMENT_ATTEMPTS.FAILURE_MESSAGE, e.getMessage())
                        .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, now.plusSeconds(10).atOffset(ZoneOffset.UTC))
                        .where(PAYMENT_ATTEMPTS.ID.eq(phase.attemptId()))
                        .execute());
            } else {
                processor.fail(phase.attemptId(), e.code().name(), e.getMessage(), now);
            }
            throw new ApiException(ProblemCode.PAYMENT_UNAVAILABLE);
        }
        transactions.executeWithoutResult(status -> {
            system.update(PAYMENT_ATTEMPTS)
                    .set(PAYMENT_ATTEMPTS.STATUS, created.status().name())
                    .set(PAYMENT_ATTEMPTS.PROVIDER_REFERENCE, created.providerReference())
                    .set(PAYMENT_ATTEMPTS.NEXT_ACTION, nextAction(created.redirectUrl()))
                    .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, PaymentProcessor.nextPoll(1, now))
                    .set(PAYMENT_ATTEMPTS.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                    .where(PAYMENT_ATTEMPTS.ID.eq(phase.attemptId()))
                    .execute();
            system.update(PAYMENT_INTENTS)
                    .set(PAYMENT_INTENTS.STATUS, "REQUIRES_ACTION")
                    .where(PAYMENT_INTENTS.ID.eq(phase.intentId()))
                    .and(PAYMENT_INTENTS.STATUS.in("REQUIRES_METHOD", "PROCESSING"))
                    .execute();
        });
        return new Started(
                phase.intentId(),
                phase.attemptId(),
                created.redirectUrl(),
                phase.expiresAt(),
                phase.amountMinor(),
                phase.currency());
    }

    /**
     * The platform's share of one payment of an order (Chapa mode): the fees assessed on the order, with their VAT,
     * in proportion to the part of the order this payment covers.
     */
    private long platformShare(UUID orderId, long paymentMinor) {
        return PlatformShare.of(system, orderId, paymentMinor).totalMinor();
    }

    /** Phase one, in a transaction: checks the order, finds the open attempt or creates the intent and a new attempt. */
    private Phase begin(UUID orgId, UUID orderId, Payer payer, PaymentProviderAdapter adapter, Instant now) {
        var order = system.select(ORDERS.STATUS, ORDERS.EXPIRES_AT, ORDERS.CURRENCY_CODE, ORDERS.CUSTOMER_ID)
                .from(ORDERS)
                .where(ORDERS.ID.eq(orderId).and(ORDERS.ORG_ID.eq(orgId)))
                .forUpdate()
                .fetchOne();
        if (order == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such order.");
        Instant expires = order.value2() == null ? null : order.value2().toInstant();
        if (!order.value1().equals("PENDING_PAYMENT") || expires == null || !expires.isAfter(now)) {
            throw new ApiException(ProblemCode.HOLD_EXPIRED, "This booking is no longer waiting for payment.");
        }
        var due = system.selectFrom(PAYMENT_SCHEDULE)
                .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("ONLINE"))
                .and(PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                .orderBy(PAYMENT_SCHEDULE.SEQ)
                .limit(1)
                .fetchOne();
        if (due == null) throw new ApiException(ProblemCode.CONFLICT, "Nothing is due online for this booking.");
        String currency = order.value3().trim();

        var intent = system.selectFrom(PAYMENT_INTENTS)
                .where(PAYMENT_INTENTS.PAYMENT_SCHEDULE_ID.eq(due.getId()))
                .forUpdate()
                .fetchOne();
        UUID intentId;
        if (intent == null) {
            intentId = Ids.newId();
            system.insertInto(PAYMENT_INTENTS)
                    .set(PAYMENT_INTENTS.ID, intentId)
                    .set(PAYMENT_INTENTS.ORG_ID, orgId)
                    .set(PAYMENT_INTENTS.PURPOSE, "ORDER")
                    .set(PAYMENT_INTENTS.ORDER_ID, orderId)
                    .set(PAYMENT_INTENTS.PAYMENT_SCHEDULE_ID, due.getId())
                    .set(PAYMENT_INTENTS.COLLECTION_MODE, adapter.collectionMode())
                    .set(PAYMENT_INTENTS.AMOUNT_MINOR, due.getAmountMinor())
                    .set(PAYMENT_INTENTS.CURRENCY_CODE, currency)
                    .set(PAYMENT_INTENTS.STATUS, "REQUIRES_METHOD")
                    .set(PAYMENT_INTENTS.PAYER_USER_ID, payer.userId())
                    .set(PAYMENT_INTENTS.PAYER_CUSTOMER_ID, order.value4())
                    .set(PAYMENT_INTENTS.IDEMPOTENCY_KEY, "schedule:" + due.getId())
                    .set(PAYMENT_INTENTS.EXPIRES_AT, order.value2())
                    .execute();
        } else {
            intentId = intent.getId();
            if (intent.getStatus().equals("SUCCEEDED")) {
                throw new ApiException(ProblemCode.CONFLICT, "This booking is already paid.");
            }
        }
        // An attempt already open is handed back as it is (the payer reopens the same page).
        var open = system.selectFrom(PAYMENT_ATTEMPTS)
                .where(PAYMENT_ATTEMPTS.INTENT_ID.eq(intentId))
                .and(PAYMENT_ATTEMPTS.STATUS.in("CREATED", "AWAITING_CUSTOMER", "PROCESSING", "UNKNOWN"))
                .orderBy(PAYMENT_ATTEMPTS.INITIATED_AT.desc())
                .limit(1)
                .fetchOne();
        if (open != null) {
            URI redirect = redirectOf(open.getNextAction());
            if (redirect != null) {
                return new Phase(
                        new Started(intentId, open.getId(), redirect, expires, due.getAmountMinor(), currency),
                        null,
                        intentId,
                        due.getAmountMinor(),
                        currency,
                        expires);
            }
            // Only a create call that may still be answering makes the payer wait; an unclear one is superseded.
            if (open.getStatus().equals("CREATED")
                    && open.getInitiatedAt()
                            .toInstant()
                            .plusSeconds(SETTING_UP_SECONDS)
                            .isAfter(now)) {
                throw new ApiException(ProblemCode.CONFLICT, "Payment is being set up. Try again in a moment.");
            }
        }
        UUID attemptId = Ids.newId();
        system.insertInto(PAYMENT_ATTEMPTS)
                .set(PAYMENT_ATTEMPTS.ID, attemptId)
                .set(PAYMENT_ATTEMPTS.ORG_ID, orgId)
                .set(PAYMENT_ATTEMPTS.INTENT_ID, intentId)
                .set(
                        PAYMENT_ATTEMPTS.PROVIDER_ACCOUNT_ID,
                        accounts.platformAccount(adapter.providerCode()).orElseThrow())
                .set(PAYMENT_ATTEMPTS.METHOD_CODE, "PROVIDER_CHECKOUT")
                .set(PAYMENT_ATTEMPTS.INTERACTION, "REDIRECT")
                .set(PAYMENT_ATTEMPTS.AMOUNT_MINOR, due.getAmountMinor())
                .set(PAYMENT_ATTEMPTS.CURRENCY_CODE, currency)
                .set(PAYMENT_ATTEMPTS.STATUS, "CREATED")
                .set(PAYMENT_ATTEMPTS.IDEMPOTENCY_KEY, "attempt:" + attemptId)
                .set(PAYMENT_ATTEMPTS.NEXT_POLL_AT, now.plusSeconds(60).atOffset(ZoneOffset.UTC))
                .execute();
        system.update(PAYMENT_INTENTS)
                .set(PAYMENT_INTENTS.STATUS, "PROCESSING")
                .where(PAYMENT_INTENTS.ID.eq(intentId))
                .and(PAYMENT_INTENTS.STATUS.eq("REQUIRES_METHOD"))
                .execute();
        return new Phase(null, attemptId, intentId, due.getAmountMinor(), currency, expires);
    }

    @Override
    public Status status(UUID orgId, UUID orderId, Instant now) {
        return transactions.execute(s -> readStatus(orgId, orderId, now));
    }

    @Override
    public Status check(UUID orgId, UUID orderId, Instant now) {
        List<UUID> open = transactions.execute(s -> system.select(PAYMENT_ATTEMPTS.ID)
                .from(PAYMENT_ATTEMPTS)
                .join(PAYMENT_INTENTS)
                .on(PAYMENT_INTENTS.ID.eq(PAYMENT_ATTEMPTS.INTENT_ID))
                .where(PAYMENT_INTENTS.ORDER_ID.eq(orderId).and(PAYMENT_INTENTS.ORG_ID.eq(orgId)))
                .and(PAYMENT_ATTEMPTS.STATUS.in("AWAITING_CUSTOMER", "PROCESSING", "UNKNOWN"))
                .orderBy(PAYMENT_ATTEMPTS.INITIATED_AT.desc())
                .fetch(PAYMENT_ATTEMPTS.ID));
        // The provider is asked outside any transaction; an answer is applied in its own.
        for (UUID attemptId : open) {
            try {
                processor.poll(attemptId, now);
            } catch (RuntimeException e) {
                log.warn("Checking attempt {} failed", attemptId, e);
            }
        }
        return status(orgId, orderId, now);
    }

    private Status readStatus(UUID orgId, UUID orderId, Instant now) {
        var order = system.select(ORDERS.STATUS, ORDERS.EXPIRES_AT, ORDERS.CURRENCY_CODE, ORDERS.PAID_MINOR)
                .from(ORDERS)
                .where(ORDERS.ID.eq(orderId).and(ORDERS.ORG_ID.eq(orgId)))
                .fetchOne();
        if (order == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such order.");
        List<com.beavent.db.commerce.tables.records.PaymentScheduleRecord> rows = system.selectFrom(PAYMENT_SCHEDULE)
                .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                .orderBy(PAYMENT_SCHEDULE.SEQ)
                .fetch();
        var online =
                rows.stream().filter(r -> r.getCollectAt().equals("ONLINE")).findFirst();
        String currency = order.value3().trim();
        long atVenue = rows.stream()
                .filter(r -> r.getCollectAt().equals("AT_VENUE"))
                .mapToLong(r -> r.getAmountMinor() - r.getCollectedMinor())
                .sum();
        Instant expires = order.value2() == null ? null : order.value2().toInstant();
        if (online.isEmpty()) return new Status("NOT_REQUIRED", 0, 0, atVenue, currency, expires, null);
        long dueNow = online.get().getAmountMinor();
        long paid = order.value4();
        if (online.get().getStatus().equals("PAID")) {
            return new Status("PAID", dueNow, paid, atVenue, currency, expires, null);
        }
        var attempt = system.select(PAYMENT_ATTEMPTS.STATUS, PAYMENT_ATTEMPTS.NEXT_ACTION)
                .from(PAYMENT_ATTEMPTS)
                .join(PAYMENT_INTENTS)
                .on(PAYMENT_INTENTS.ID.eq(PAYMENT_ATTEMPTS.INTENT_ID))
                .where(PAYMENT_INTENTS.PAYMENT_SCHEDULE_ID.eq(online.get().getId()))
                .orderBy(PAYMENT_ATTEMPTS.INITIATED_AT.desc())
                .limit(1)
                .fetchOne();
        boolean ranOut = !order.value1().equals("PENDING_PAYMENT") || (expires != null && !expires.isAfter(now));
        if (ranOut) return new Status("EXPIRED", dueNow, paid, atVenue, currency, expires, null);
        String last = attempt == null ? null : attempt.value1();
        if (last != null && last.equals("FAILED")) {
            return new Status("FAILED", dueNow, paid, atVenue, currency, expires, null);
        }
        URI redirect =
                attempt != null && List.of("AWAITING_CUSTOMER", "PROCESSING").contains(last)
                        ? redirectOf(attempt.value2())
                        : null;
        return new Status("AWAITING_PAYMENT", dueNow, paid, atVenue, currency, expires, redirect);
    }

    @Override
    public void cancel(UUID orgId, UUID orderId, String reason) {
        Instant now = Instant.now();
        boolean expired = "EXPIRED".equals(reason);
        List<Object[]> toCancel = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            for (var intent : system.selectFrom(PAYMENT_INTENTS)
                    .where(PAYMENT_INTENTS.ORDER_ID.eq(orderId).and(PAYMENT_INTENTS.ORG_ID.eq(orgId)))
                    .and(PAYMENT_INTENTS.STATUS.in("REQUIRES_METHOD", "PROCESSING", "REQUIRES_ACTION"))
                    .forUpdate()
                    .fetch()) {
                for (var a : system.selectFrom(PAYMENT_ATTEMPTS)
                        .where(PAYMENT_ATTEMPTS.INTENT_ID.eq(intent.getId()))
                        .and(PAYMENT_ATTEMPTS.STATUS.in("CREATED", "AWAITING_CUSTOMER", "PROCESSING", "UNKNOWN"))
                        .fetch()) {
                    toCancel.add(new Object[] {a.getId(), a.getProviderReference()});
                    processor.close(a.getId(), expired ? "EXPIRED" : "CANCELLED", now);
                }
                system.update(PAYMENT_INTENTS)
                        .set(PAYMENT_INTENTS.STATUS, expired ? "EXPIRED" : "CANCELLED")
                        .where(PAYMENT_INTENTS.ID.eq(intent.getId()))
                        .execute();
            }
        });
        // Best effort at the provider: a payment that still arrives is handled as a late payment.
        for (Object[] c : toCancel) {
            if (c[1] == null) continue;
            try {
                processor.adapterOf((UUID) c[0]).cancelCollection((String) c[1]);
            } catch (RuntimeException e) {
                log.warn("Cancelling the provider session of attempt {} failed: {}", c[0], e.getMessage());
            }
        }
    }

    private JSONB nextAction(URI redirect) {
        return redirect == null
                ? null
                : JSONB.valueOf(json.writeValueAsString(java.util.Map.of("redirectUrl", redirect.toString())));
    }

    private URI redirectOf(JSONB nextAction) {
        if (nextAction == null) return null;
        JsonNode node = json.readTree(nextAction.data());
        String url = node.path("redirectUrl").asString("");
        return url.isBlank() ? null : URI.create(url);
    }

    @SuppressWarnings("unused")
    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
