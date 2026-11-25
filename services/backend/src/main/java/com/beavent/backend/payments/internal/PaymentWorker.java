package com.beavent.backend.payments.internal;

import static com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS;
import static com.beavent.db.payments.Tables.WEBHOOK_EVENTS;

import com.beavent.backend.payments.internal.provider.NormalizedEvent;
import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import com.beavent.backend.payments.internal.provider.StatusResult;
import com.beavent.backend.platform.SystemDb;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps payments moving without anyone asking (docs/04 §7): stored provider notifications are turned into a lookup of
 * the attempt they name, and open attempts are looked up on a schedule, so a missing or early notification costs only
 * a few seconds. Both end in {@link PaymentProcessor#apply}, the same transition function.
 */
@Component
class PaymentWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentWorker.class);
    private static final int BATCH = 20;
    private static final int GIVE_UP_AFTER = 8;

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final ActiveProvider providers;
    private final PaymentProcessor processor;

    PaymentWorker(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            ActiveProvider providers,
            PaymentProcessor processor) {
        this.system = system;
        this.transactions = transactions;
        this.providers = providers;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${beavent.payments.worker-interval-ms:2000}")
    void run() {
        Instant now = Instant.now();
        try {
            processNotifications(now);
            pollOpenAttempts(now);
        } catch (RuntimeException e) {
            log.warn("Payment worker failed; it runs again shortly", e);
        }
    }

    int processNotifications(Instant now) {
        List<UUID> ids = system.select(WEBHOOK_EVENTS.ID)
                .from(WEBHOOK_EVENTS)
                .where(WEBHOOK_EVENTS.STATUS.in("RECEIVED", "FAILED"))
                .orderBy(WEBHOOK_EVENTS.RECEIVED_AT)
                .limit(BATCH)
                .fetch(WEBHOOK_EVENTS.ID);
        for (UUID id : ids) {
            try {
                transactions.executeWithoutResult(status -> handle(id, now));
            } catch (RuntimeException e) {
                log.warn("Notification {} could not be processed: {}", id, e.getMessage());
                transactions.executeWithoutResult(status -> failed(id, e));
            }
        }
        return ids.size();
    }

    private void handle(UUID id, Instant now) {
        var row = system.selectFrom(WEBHOOK_EVENTS)
                .where(WEBHOOK_EVENTS.ID.eq(id))
                .and(WEBHOOK_EVENTS.STATUS.in("RECEIVED", "FAILED"))
                .forUpdate()
                .skipLocked()
                .fetchOne();
        if (row == null) return;
        PaymentProviderAdapter adapter = providers.byCode(row.getProviderCode()).orElse(null);
        if (adapter == null) {
            done(id, "IGNORED", "provider not configured");
            return;
        }
        List<NormalizedEvent> events =
                adapter.parseNotification(row.getPayload().data().getBytes(StandardCharsets.UTF_8));
        boolean handled = false;
        for (NormalizedEvent event : events) {
            if (!event.kind().equals("COLLECTION")) continue;
            UUID attempt = attemptOf(event);
            if (attempt == null) continue;
            // The notification only says where to look; the lookup with our key decides (unsigned for ArifPay).
            StatusResult result = event.providerReference() != null
                    ? adapter.queryCollection(event.providerReference())
                    : adapter.findCollection(attempt.toString());
            if (result != null) processor.apply(attempt, result, now);
            handled = true;
        }
        done(id, handled ? "PROCESSED" : "IGNORED", handled ? null : "no matching payment attempt");
    }

    private UUID attemptOf(NormalizedEvent event) {
        if (event.reference() != null) {
            try {
                UUID id = UUID.fromString(event.reference());
                if (system.fetchExists(PAYMENT_ATTEMPTS, PAYMENT_ATTEMPTS.ID.eq(id))) return id;
            } catch (IllegalArgumentException ignored) {
                // not ours
            }
        }
        if (event.providerReference() != null) {
            return system.select(PAYMENT_ATTEMPTS.ID)
                    .from(PAYMENT_ATTEMPTS)
                    .where(PAYMENT_ATTEMPTS.PROVIDER_REFERENCE.eq(event.providerReference()))
                    .limit(1)
                    .fetchOne(PAYMENT_ATTEMPTS.ID);
        }
        return null;
    }

    private void done(UUID id, String status, String note) {
        system.update(WEBHOOK_EVENTS)
                .set(WEBHOOK_EVENTS.STATUS, status)
                .set(WEBHOOK_EVENTS.PROCESSED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .set(WEBHOOK_EVENTS.LAST_ERROR, note)
                .set(WEBHOOK_EVENTS.ATTEMPTS, WEBHOOK_EVENTS.ATTEMPTS.plus(1))
                .where(WEBHOOK_EVENTS.ID.eq(id))
                .execute();
    }

    private void failed(UUID id, RuntimeException e) {
        Short attempts = system.select(WEBHOOK_EVENTS.ATTEMPTS)
                .from(WEBHOOK_EVENTS)
                .where(WEBHOOK_EVENTS.ID.eq(id))
                .fetchOne(WEBHOOK_EVENTS.ATTEMPTS);
        boolean dead = attempts != null && attempts + 1 >= GIVE_UP_AFTER;
        system.update(WEBHOOK_EVENTS)
                .set(WEBHOOK_EVENTS.STATUS, dead ? "DEAD" : "FAILED")
                .set(WEBHOOK_EVENTS.ATTEMPTS, WEBHOOK_EVENTS.ATTEMPTS.plus(1))
                .set(WEBHOOK_EVENTS.LAST_ERROR, String.valueOf(e.getMessage()))
                .where(WEBHOOK_EVENTS.ID.eq(id))
                .execute();
    }

    int pollOpenAttempts(Instant now) {
        List<UUID> ids = system.select(PAYMENT_ATTEMPTS.ID)
                .from(PAYMENT_ATTEMPTS)
                .where(PAYMENT_ATTEMPTS.STATUS.in("CREATED", "AWAITING_CUSTOMER", "PROCESSING", "UNKNOWN"))
                .and(PAYMENT_ATTEMPTS.NEXT_POLL_AT.le(now.atOffset(ZoneOffset.UTC)))
                .orderBy(PAYMENT_ATTEMPTS.NEXT_POLL_AT)
                .limit(BATCH)
                .fetch(PAYMENT_ATTEMPTS.ID);
        for (UUID id : ids) {
            try {
                processor.poll(id, now);
            } catch (RuntimeException e) {
                log.warn("Polling attempt {} failed: {}", id, e.getMessage());
            }
        }
        return ids.size();
    }
}
