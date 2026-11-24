package com.beavent.backend.comms.internal;

import static com.beavent.db.comms.Tables.DELIVERIES;
import static com.beavent.db.comms.Tables.NOTIFICATIONS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.platform.EmailGateway;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.SmsGateway;
import com.beavent.backend.platform.SystemDb;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers due notifications. Step 1 decides the channels (SMS to a verified phone, email to an address) or
 * suppresses a message that no longer applies (a reminder for a visit that moved or was cancelled). Step 2 claims
 * queued deliveries with a short lease, sends them outside any transaction (never call a provider while holding row
 * locks), then records the result; failures retry with back-off, five attempts in all.
 */
@Component
class Dispatcher {

    static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int BATCH = 50;
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {};

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final SmsGateway sms;
    private final EmailGateway email;
    private final JsonMapper json;

    Dispatcher(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            SmsGateway sms,
            EmailGateway email,
            JsonMapper json) {
        this.system = system;
        this.transactions = transactions;
        this.sms = sms;
        this.email = email;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${beavent.comms.dispatch-interval-ms:5000}")
    void run() {
        try {
            dispatch(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Notification dispatch failed; it runs again shortly", e);
        }
    }

    /** One round; returns how many deliveries were sent. */
    int dispatch(Instant now) {
        transactions.executeWithoutResult(status -> plan(now));
        int sent = 0;
        for (Claimed c : claim(now)) {
            if (send(c)) {
                sent++;
            }
        }
        return sent;
    }

    // --- step 1: channels for due notifications -----------------------------------------------------------------

    private void plan(Instant now) {
        var due = system.selectFrom(NOTIFICATIONS)
                .where(
                        NOTIFICATIONS.SEND_AFTER.le(now.atOffset(ZoneOffset.UTC)),
                        NOTIFICATIONS.TEMPLATE_CODE.like("APPOINTMENT_%"),
                        DSL.notExists(DSL.selectOne()
                                .from(DELIVERIES)
                                .where(DELIVERIES.NOTIFICATION_ID.eq(NOTIFICATIONS.ID))))
                .orderBy(NOTIFICATIONS.SEND_AFTER)
                .limit(BATCH)
                .forUpdate()
                .skipLocked()
                .fetch();
        for (var n : due) {
            Map<String, Object> data = json.readValue(n.getData().data(), DATA);
            String stale = stale(n.getTemplateCode(), data);
            var customer = system.select(CUSTOMERS.PHONE_E164, CUSTOMERS.EMAIL)
                    .from(CUSTOMERS)
                    .where(CUSTOMERS.ID.eq(n.getRecipientCustomerId()))
                    .fetchOne();
            List<String[]> channels = new ArrayList<>();
            if (stale == null && customer != null) {
                if (customer.value1() != null) channels.add(new String[] {"SMS", mask(customer.value1())});
                if (customer.value2() != null) channels.add(new String[] {"EMAIL", mask(customer.value2())});
            }
            if (channels.isEmpty()) {
                system.insertInto(DELIVERIES)
                        .set(DELIVERIES.ID, Ids.newId())
                        .set(DELIVERIES.ORG_ID, n.getOrgId())
                        .set(DELIVERIES.NOTIFICATION_ID, n.getId())
                        .set(DELIVERIES.CHANNEL, "SMS")
                        .set(DELIVERIES.DESTINATION_MASKED, "-")
                        .set(DELIVERIES.STATUS, "SUPPRESSED")
                        .set(DELIVERIES.ERROR, stale != null ? stale : "no phone or email")
                        .execute();
                continue;
            }
            for (String[] channel : channels) {
                system.insertInto(DELIVERIES)
                        .set(DELIVERIES.ID, Ids.newId())
                        .set(DELIVERIES.ORG_ID, n.getOrgId())
                        .set(DELIVERIES.NOTIFICATION_ID, n.getId())
                        .set(DELIVERIES.CHANNEL, channel[0])
                        .set(DELIVERIES.DESTINATION_MASKED, channel[1])
                        .execute();
            }
        }
    }

    /** Why a message no longer applies, or {@code null}. */
    private String stale(String code, Map<String, Object> data) {
        var a = system.select(APPOINTMENTS.STATUS, APPOINTMENTS.STARTS_AT)
                .from(APPOINTMENTS)
                .where(APPOINTMENTS.ID.eq(UUID.fromString(String.valueOf(data.get("appointmentId")))))
                .fetchOne();
        if (a == null) return "appointment gone";
        boolean sameTime = a.value2().toInstant().equals(Instant.parse(String.valueOf(data.get("startsAt"))));
        return switch (code) {
            case Notifier.CONFIRMED, Notifier.RESCHEDULED, Notifier.REMINDER_24H, Notifier.REMINDER_2H ->
                "CONFIRMED".equals(a.value1()) && sameTime ? null : "appointment changed";
            case Notifier.REQUESTED -> "REQUESTED".equals(a.value1()) ? null : "request already answered";
            default -> null;
        };
    }

    static String mask(String destination) {
        int at = destination.indexOf('@');
        if (at > 0) {
            return destination.charAt(0) + "•••" + destination.substring(at);
        }
        return destination.length() <= 6
                ? "•••"
                : destination.substring(0, 5) + "•••" + destination.substring(destination.length() - 2);
    }

    // --- step 2: send ------------------------------------------------------------------------------------------

    private record Claimed(
            UUID deliveryId,
            String channel,
            int attempts,
            String code,
            Map<String, Object> data,
            String phone,
            String email,
            String language) {}

    private List<Claimed> claim(Instant now) {
        return transactions.execute(status -> {
            OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
            var rows = system.select(
                            DELIVERIES.ID,
                            DELIVERIES.CHANNEL,
                            DELIVERIES.ATTEMPTS,
                            NOTIFICATIONS.TEMPLATE_CODE,
                            NOTIFICATIONS.DATA,
                            CUSTOMERS.PHONE_E164,
                            CUSTOMERS.EMAIL,
                            DSL.coalesce(CUSTOMERS.PREFERRED_LANGUAGE, ORGANIZATIONS.DEFAULT_LANGUAGE))
                    .from(DELIVERIES)
                    .join(NOTIFICATIONS)
                    .on(NOTIFICATIONS.ID.eq(DELIVERIES.NOTIFICATION_ID))
                    .join(CUSTOMERS)
                    .on(CUSTOMERS.ID.eq(NOTIFICATIONS.RECIPIENT_CUSTOMER_ID))
                    .join(ORGANIZATIONS)
                    .on(ORGANIZATIONS.ID.eq(NOTIFICATIONS.ORG_ID))
                    .where(
                            DELIVERIES.STATUS.eq("QUEUED"),
                            DELIVERIES.NEXT_ATTEMPT_AT.isNull().or(DELIVERIES.NEXT_ATTEMPT_AT.le(at)))
                    .orderBy(DELIVERIES.CREATED_AT)
                    .limit(BATCH)
                    .forUpdate()
                    .of(DELIVERIES)
                    .skipLocked()
                    .fetch();
            List<Claimed> claimed = new ArrayList<>();
            for (var r : rows) {
                system.update(DELIVERIES)
                        .set(DELIVERIES.NEXT_ATTEMPT_AT, now.plus(LEASE).atOffset(ZoneOffset.UTC))
                        .set(DELIVERIES.ATTEMPTS, (short) (r.value3() + 1))
                        .where(DELIVERIES.ID.eq(r.value1()))
                        .execute();
                claimed.add(new Claimed(
                        r.value1(),
                        r.value2(),
                        r.value3() + 1,
                        r.value4(),
                        json.readValue(r.value5().data(), DATA),
                        r.value6(),
                        r.value7(),
                        r.value8()));
            }
            return claimed;
        });
    }

    private boolean send(Claimed c) {
        try {
            Texts.Message message = Texts.render(c.code(), c.language(), c.data());
            if ("SMS".equals(c.channel())) {
                sms.send(c.phone(), SmsGateway.Channel.SMS, message.text());
            } else {
                email.send(c.email(), message.subject(), message.text());
            }
            transactions.executeWithoutResult(status -> system.update(DELIVERIES)
                    .set(DELIVERIES.STATUS, "SENT")
                    .set(DELIVERIES.SENT_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .setNull(DELIVERIES.NEXT_ATTEMPT_AT)
                    .where(DELIVERIES.ID.eq(c.deliveryId()))
                    .execute());
            return true;
        } catch (RuntimeException e) {
            boolean last = c.attempts() >= MAX_ATTEMPTS;
            log.warn(
                    "Delivery {} ({}) failed, attempt {}: {}",
                    c.deliveryId(),
                    c.channel(),
                    c.attempts(),
                    e.getMessage());
            transactions.executeWithoutResult(status -> system.update(DELIVERIES)
                    .set(DELIVERIES.STATUS, last ? "FAILED" : "QUEUED")
                    .set(DELIVERIES.ERROR, String.valueOf(e.getMessage()))
                    .set(
                            DELIVERIES.NEXT_ATTEMPT_AT,
                            last
                                    ? null
                                    : OffsetDateTime.now(ZoneOffset.UTC)
                                            .plusMinutes((long) c.attempts() * c.attempts()))
                    .where(DELIVERIES.ID.eq(c.deliveryId()))
                    .execute());
            return false;
        }
    }
}
