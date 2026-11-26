package com.beavent.backend.comms.internal;

import static com.beavent.db.comms.Tables.DELIVERIES;
import static com.beavent.db.comms.Tables.NOTIFICATIONS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.comms.NotificationSettings;
import com.beavent.backend.platform.EmailGateway;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.PushGateway;
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
    /** Messages the person can switch off or move out of quiet hours. */
    static final java.util.Set<String> OPTIONAL =
            java.util.Set.of(Notifier.REMINDER_24H, Notifier.REMINDER_2H, Notifier.REVIEW_REQUEST);
    /** A reminder is not worth sending later than this before the visit. */
    private static final Duration LATEST_REMINDER = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {};

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final SmsGateway sms;
    private final EmailGateway email;
    private final JsonMapper json;
    private final NotificationSettings settings;
    private final PushGateway push;

    Dispatcher(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            SmsGateway sms,
            EmailGateway email,
            JsonMapper json,
            NotificationSettings settings,
            PushGateway push) {
        this.system = system;
        this.transactions = transactions;
        this.sms = sms;
        this.email = email;
        this.json = json;
        this.settings = settings;
        this.push = push;
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
                        NOTIFICATIONS
                                .TEMPLATE_CODE
                                .like("APPOINTMENT_%")
                                .or(NOTIFICATIONS.TEMPLATE_CODE.like("PAYOUT_%"))
                                .or(NOTIFICATIONS.TEMPLATE_CODE.like("GIFT_CARD_%"))
                                .or(NOTIFICATIONS.TEMPLATE_CODE.like("RECEIPT_%"))
                                .or(NOTIFICATIONS.TEMPLATE_CODE.like("REVIEW_%"))
                                .or(NOTIFICATIONS.TEMPLATE_CODE.like("MARKETPLACE_%")),
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
            // A customer's contact details, or (for messages to a person's own account) their verified ones.
            var customer = n.getRecipientCustomerId() != null
                    ? system.select(CUSTOMERS.PHONE_E164, CUSTOMERS.EMAIL, CUSTOMERS.USER_ID)
                            .from(CUSTOMERS)
                            .where(CUSTOMERS.ID.eq(n.getRecipientCustomerId()))
                            .fetchOne()
                    : system.select(
                                    DSL.when(USERS.PHONE_VERIFIED_AT.isNotNull(), USERS.PHONE_E164),
                                    DSL.when(USERS.EMAIL_VERIFIED_AT.isNotNull(), USERS.EMAIL.cast(String.class)),
                                    USERS.ID)
                            .from(USERS)
                            .where(USERS.ID.eq(n.getRecipientUserId()))
                            .fetchOne();
            // What the person chose about reminders and review requests (transactional messages ignore it).
            NotificationSettings.Settings prefs = null;
            UUID person = customer == null ? null : customer.value3();
            if (stale == null && person != null && OPTIONAL.contains(n.getTemplateCode())) {
                prefs = settings.get(person);
                Instant resume = prefs.quietHours() ? QuietHours.end(prefs, data, now) : null;
                if (resume != null) {
                    Instant start = data.get("startsAt") == null
                            ? null
                            : Instant.parse(String.valueOf(data.get("startsAt")));
                    boolean reminder = Notifier.REMINDERS.contains(n.getTemplateCode());
                    if (reminder && (start == null || resume.isAfter(start.minus(LATEST_REMINDER)))) {
                        stale = "quiet hours";
                    } else {
                        // Wait until quiet hours end; the notification comes round again then.
                        system.update(NOTIFICATIONS)
                                .set(NOTIFICATIONS.SEND_AFTER, resume.atOffset(ZoneOffset.UTC))
                                .where(NOTIFICATIONS.ID.eq(n.getId()))
                                .execute();
                        continue;
                    }
                }
            }
            List<String[]> channels = new ArrayList<>();
            if (stale == null && customer != null) {
                // A person with the phone app set up gets a push notification; for reminders and review requests it
                // replaces the (paid) text message, which stays for everything else.
                boolean hasPhone = person != null && !devices(person).isEmpty();
                boolean pushed = hasPhone && (prefs == null || prefs.remindersPush());
                if (pushed) channels.add(new String[] {"PUSH", "this phone"});
                boolean replacesSms = pushed && OPTIONAL.contains(n.getTemplateCode());
                if (customer.value1() != null && (prefs == null || prefs.remindersSms()) && !replacesSms)
                    channels.add(new String[] {"SMS", mask(customer.value1())});
                if (customer.value2() != null && (prefs == null || prefs.remindersEmail()))
                    channels.add(new String[] {"EMAIL", mask(customer.value2())});
                if (channels.isEmpty() && prefs != null && (hasPhone || customer.value1() != null || customer.value2() != null)) {
                    stale = "turned off by the person";
                }
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
        if (code.startsWith("PAYOUT_")
                || code.startsWith("GIFT_CARD_")
                || code.startsWith("RECEIPT_")
                || code.startsWith("REVIEW_")
                || code.startsWith("MARKETPLACE_")) {
            return null;
        }
        if (code.equals(Notifier.WAITLIST_OFFER)) {
            String status = system.fetchValue(
                                    "select status from commerce.waitlist_offers where id = ?::uuid",
                                    data.get("offerId"))
                            instanceof String s
                    ? s
                    : null;
            return "OPEN".equals(status) ? null : "offer closed";
        }
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
            case Notifier.REVIEW_REQUEST ->
                !"COMPLETED".equals(a.value1())
                        ? "visit not completed"
                        : system.fetchExists(
                                        DSL.table("discovery.reviews"),
                                        DSL.field("appointment_id", UUID.class)
                                                .eq(UUID.fromString(String.valueOf(data.get("appointmentId")))))
                                ? "already reviewed"
                                : null;
            case Notifier.PAYMENT_LINK ->
                List.of("CANCELLED", "EXPIRED", "DECLINED", "NO_SHOW").contains(a.value1()) ? "booking closed" : null;
            default -> null;
        };
    }

    /** The push tokens of the person's phones that can receive notifications. */
    private List<String> devices(UUID user) {
        return system.resultQuery(
                        "SELECT push_token FROM iam.devices WHERE user_id = ? AND push_token IS NOT NULL"
                                + " AND disabled_at IS NULL ORDER BY last_seen_at DESC NULLS LAST",
                        user)
                .fetch(0, String.class);
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
            String language,
            UUID user) {}

    private List<Claimed> claim(Instant now) {
        return transactions.execute(status -> {
            OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
            var rows = system.select(
                            DELIVERIES.ID,
                            DELIVERIES.CHANNEL,
                            DELIVERIES.ATTEMPTS,
                            NOTIFICATIONS.TEMPLATE_CODE,
                            NOTIFICATIONS.DATA,
                            DSL.coalesce(CUSTOMERS.PHONE_E164, USERS.PHONE_E164),
                            DSL.coalesce(CUSTOMERS.EMAIL.cast(String.class), USERS.EMAIL.cast(String.class)),
                            DSL.coalesce(
                                    CUSTOMERS.PREFERRED_LANGUAGE,
                                    USERS.PREFERRED_LANGUAGE,
                                    ORGANIZATIONS.DEFAULT_LANGUAGE),
                            DSL.coalesce(CUSTOMERS.USER_ID, NOTIFICATIONS.RECIPIENT_USER_ID))
                    .from(DELIVERIES)
                    .join(NOTIFICATIONS)
                    .on(NOTIFICATIONS.ID.eq(DELIVERIES.NOTIFICATION_ID))
                    .leftJoin(CUSTOMERS)
                    .on(CUSTOMERS.ID.eq(NOTIFICATIONS.RECIPIENT_CUSTOMER_ID))
                    .leftJoin(USERS)
                    .on(USERS.ID.eq(NOTIFICATIONS.RECIPIENT_USER_ID))
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
                        r.value8(),
                        r.value9()));
            }
            return claimed;
        });
    }

    /** Where tapping a push notification leads in the app, or nothing. */
    private static Map<String, String> route(String code, Map<String, Object> data) {
        if (code.equals(Notifier.REVIEW_REQUEST) || code.equals(Notifier.REVIEW_REMOVED) || code.equals(Notifier.REVIEW_RESTORED)) {
            return Map.of("route", "/reviews");
        }
        if (code.startsWith("APPOINTMENT_") && !code.equals(Notifier.WAITLIST_OFFER) && data.get("appointmentId") != null) {
            return Map.of("route", "/appointment/" + data.get("appointmentId"));
        }
        return Map.of();
    }

    /** Sends to every phone the person has set up; phones that cannot receive any more are forgotten. */
    private boolean sendPush(Claimed c, Texts.Message message) {
        List<String> tokens = c.user() == null ? List.of() : devices(c.user());
        if (tokens.isEmpty()) {
            finish(c, "SUPPRESSED", "no phone set up");
            return false;
        }
        Map<String, PushGateway.Outcome> outcomes =
                push.send(tokens, new PushGateway.Message(message.subject(), message.text(), route(c.code(), c.data())));
        boolean any = false;
        for (var outcome : outcomes.entrySet()) {
            if (outcome.getValue() == PushGateway.Outcome.SENT) {
                any = true;
            } else {
                system.execute("UPDATE iam.devices SET push_token = NULL, updated_at = now() WHERE push_token = ?", outcome.getKey());
            }
        }
        if (!any) {
            finish(c, "SUPPRESSED", "phone cannot receive notifications");
            return false;
        }
        transactions.executeWithoutResult(status -> system.update(DELIVERIES)
                .set(DELIVERIES.STATUS, "SENT")
                .set(DELIVERIES.SENT_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .setNull(DELIVERIES.NEXT_ATTEMPT_AT)
                .where(DELIVERIES.ID.eq(c.deliveryId()))
                .execute());
        return true;
    }

    private void finish(Claimed c, String status, String error) {
        transactions.executeWithoutResult(s -> system.update(DELIVERIES)
                .set(DELIVERIES.STATUS, status)
                .set(DELIVERIES.ERROR, error)
                .setNull(DELIVERIES.NEXT_ATTEMPT_AT)
                .where(DELIVERIES.ID.eq(c.deliveryId()))
                .execute());
    }

    private boolean send(Claimed c) {
        try {
            Texts.Message message = Texts.render(c.code(), c.language(), c.data());
            if ("PUSH".equals(c.channel())) {
                return sendPush(c, message);
            } else if ("SMS".equals(c.channel())) {
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
