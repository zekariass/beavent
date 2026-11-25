package com.beavent.backend.comms.internal;

import static com.beavent.db.comms.Tables.DELIVERIES;
import static com.beavent.db.comms.Tables.NOTIFICATIONS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.tenant.Tables.PLACES;

import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProcessedMessages;
import com.beavent.backend.platform.SystemDb;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns booking events into notifications for the customer: what happened now, and reminders 24 hours and 2 hours
 * before a confirmed visit. A change of time or a cancellation withdraws reminders not yet sent. Each notification
 * carries everything its text needs, and a dedupe key (code, appointment, start), so a redelivered event adds nothing.
 */
@Component
class Notifier {

    static final String CONFIRMED = "APPOINTMENT_CONFIRMED";
    static final String REQUESTED = "APPOINTMENT_REQUESTED";
    static final String DECLINED = "APPOINTMENT_DECLINED";
    static final String EXPIRED = "APPOINTMENT_EXPIRED";
    static final String CANCELLED = "APPOINTMENT_CANCELLED";
    static final String RESCHEDULED = "APPOINTMENT_RESCHEDULED";
    static final String REMINDER_24H = "APPOINTMENT_REMINDER_24H";
    static final String REMINDER_2H = "APPOINTMENT_REMINDER_2H";
    /** A time opened up for someone on the waitlist (plan 2.6, S-15). */
    static final String WAITLIST_OFFER = "APPOINTMENT_WAITLIST_OFFER";

    /** A payout account was added or changed (plan 3.4): sent to the people who manage payouts. */
    static final String PAYOUT_CHANGED = "PAYOUT_ACCOUNT_CHANGED";

    static final List<String> REMINDERS = List.of(REMINDER_24H, REMINDER_2H);
    static final String CONSUMER = "comms.appointments";

    private final DSLContext system;
    private final ProcessedMessages processedMessages;
    private final JsonMapper json;
    private final String webUrl;

    Notifier(
            @SystemDb DSLContext system,
            ProcessedMessages processedMessages,
            JsonMapper json,
            @org.springframework.beans.factory.annotation.Value("${beavent.web.public-url}") String webUrl) {
        this.system = system;
        this.processedMessages = processedMessages;
        this.json = json;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    @RabbitListener(queues = CommsConfiguration.PAYOUTS_QUEUE)
    void onPayoutEvent(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        if (!"payout_account.changed".equals(message.getMessageProperties().getReceivedRoutingKey())) return;
        UUID orgId = UUID.fromString(message.getMessageProperties().<String>getHeader("org_id"));
        var body = json.readTree(message.getBody());
        processedMessages.runOnce(
                CONSUMER + ".payouts",
                messageId,
                () -> payoutChanged(
                        orgId,
                        body.get("payoutAccountId").asString(),
                        body.get("institutionName").asString(),
                        body.get("last4").asString(),
                        body.get("allowedAfter").asString(),
                        Instant.now()));
    }

    /**
     * Tells everyone who may manage the business's payouts (the owners, and roles with the right) that the payout
     * account changed and when payouts to it start. A security message: it goes by every verified channel they have.
     * Runs in a system transaction.
     */
    void payoutChanged(
            UUID orgId, String accountId, String institution, String last4, String allowedAfter, Instant now) {
        String business = system.select(ORGANIZATIONS.DISPLAY_NAME)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.DISPLAY_NAME);
        List<UUID> people = system.selectDistinct(DSL.field("m.user_id", UUID.class))
                .from(DSL.table("tenant.members").as("m"))
                .leftJoin(DSL.table("tenant.role_permissions").as("rp"))
                .on(DSL.field("rp.role_id", UUID.class).eq(DSL.field("m.role_id", UUID.class)))
                .where(
                        DSL.field("m.org_id", UUID.class).eq(orgId),
                        DSL.field("m.status", String.class).eq("ACTIVE"),
                        DSL.field("m.is_owner", Boolean.class)
                                .isTrue()
                                .or(DSL.field("rp.permission_code", String.class)
                                        .eq("payouts.manage")))
                .fetch(0, UUID.class);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("business", business);
        data.put("institution", institution);
        data.put("last4", last4);
        data.put("allowedAfter", allowedAfter);
        data.put("timezone", "Africa/Addis_Ababa");
        for (UUID user : people) {
            system.insertInto(NOTIFICATIONS)
                    .set(NOTIFICATIONS.ID, Ids.newId())
                    .set(NOTIFICATIONS.ORG_ID, orgId)
                    .set(NOTIFICATIONS.RECIPIENT_USER_ID, user)
                    .set(NOTIFICATIONS.CATEGORY, "SECURITY")
                    .set(NOTIFICATIONS.TEMPLATE_CODE, PAYOUT_CHANGED)
                    .set(NOTIFICATIONS.DATA, JSONB.valueOf(json.writeValueAsString(data)))
                    .set(NOTIFICATIONS.DEDUPE_KEY, PAYOUT_CHANGED + ":" + accountId + ":" + user)
                    .set(NOTIFICATIONS.SEND_AFTER, now.atOffset(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute();
        }
    }

    @RabbitListener(queues = CommsConfiguration.WAITLIST_QUEUE)
    void onWaitlistEvent(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        var body = json.readTree(message.getBody());
        UUID offerId = UUID.fromString(body.get("offerId").asString());
        processedMessages.runOnce(CONSUMER + ".waitlist", messageId, () -> offered(offerId, Instant.now()));
    }

    /** The offer message: the time, how long it is held, and the link to book it. Runs in a system transaction. */
    void offered(UUID offerId, Instant now) {
        var o = system.fetchOne(
                "select o.org_id, e.id, e.customer_id, e.user_id, lower(o.offered_slot), o.expires_at, l.timezone, "
                        + "g.display_name, p.landmark from commerce.waitlist_offers o "
                        + "join commerce.waitlist_entries e on e.id = o.entry_id "
                        + "join tenant.locations l on l.id = e.location_id "
                        + "join tenant.places p on p.id = l.place_id "
                        + "join tenant.organizations g on g.id = o.org_id where o.id = ? and o.status = 'OPEN'",
                offerId);
        if (o == null || o.get(2) == null) {
            return;
        }
        Instant start = o.get(4, java.time.OffsetDateTime.class).toInstant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offerId", offerId.toString());
        data.put("entryId", o.get(1, UUID.class).toString());
        data.put("startsAt", start.toString());
        data.put(
                "expiresAt",
                o.get(5, java.time.OffsetDateTime.class).toInstant().toString());
        data.put("timezone", o.get(6, String.class));
        data.put("business", o.get(7, String.class));
        data.put("landmark", o.get(8, String.class));
        data.put("web", webUrl);
        add(o.get(0, UUID.class), o.get(2, UUID.class), o.get(3, UUID.class), WAITLIST_OFFER, data, now, start);
    }

    @RabbitListener(queues = CommsConfiguration.APPOINTMENTS_QUEUE)
    void onAppointmentEvent(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        String type = message.getMessageProperties().getReceivedRoutingKey();
        var body = json.readTree(message.getBody());
        UUID appointmentId = UUID.fromString(body.get("appointmentId").asString());
        // The front desk may book or move without telling the customer now (reminders still follow).
        boolean notify = !body.has("notify") || body.get("notify").asBoolean();
        processedMessages.runOnce(CONSUMER, messageId, () -> handle(type, appointmentId, notify, Instant.now()));
    }

    /** Runs inside a system-role transaction. */
    void handle(String eventType, UUID appointmentId, boolean notify, Instant now) {
        var a = system.select(
                        APPOINTMENTS.ORG_ID,
                        APPOINTMENTS.CUSTOMER_ID,
                        APPOINTMENTS.REFERENCE,
                        APPOINTMENTS.STARTS_AT,
                        APPOINTMENTS.TIMEZONE,
                        ORGANIZATIONS.DISPLAY_NAME,
                        LOCATIONS.NAME,
                        PLACES.LANDMARK,
                        LOCATIONS.PHONE,
                        CUSTOMERS.USER_ID)
                .from(APPOINTMENTS)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(APPOINTMENTS.ORG_ID))
                .join(LOCATIONS)
                .on(LOCATIONS.ID.eq(APPOINTMENTS.LOCATION_ID))
                .join(PLACES)
                .on(PLACES.ID.eq(LOCATIONS.PLACE_ID))
                .join(CUSTOMERS)
                .on(CUSTOMERS.ID.eq(APPOINTMENTS.CUSTOMER_ID))
                .where(APPOINTMENTS.ID.eq(appointmentId))
                .fetchOne();
        if (a == null) {
            return;
        }
        Instant start = a.value4().toInstant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appointmentId", appointmentId.toString());
        data.put("reference", a.value3());
        data.put("startsAt", start.toString());
        data.put("timezone", a.value5());
        data.put("business", a.value6());
        data.put("location", a.value7());
        data.put("landmark", a.value8());
        data.put("phone", a.value9());
        data.put(
                "services",
                system.select(APPOINTMENT_ITEMS.SERVICE_NAME)
                        .from(APPOINTMENT_ITEMS)
                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                        .orderBy(APPOINTMENT_ITEMS.SEQ)
                        .fetch(APPOINTMENT_ITEMS.SERVICE_NAME));
        UUID orgId = a.value1();
        UUID customerId = a.value2();
        UUID userId = a.value10();

        switch (eventType) {
            case "appointment.booked", "appointment.accepted" -> {
                if (notify) add(orgId, customerId, userId, CONFIRMED, data, now, start);
                reminders(orgId, customerId, userId, data, now, start);
            }
            case "appointment.requested" -> add(orgId, customerId, userId, REQUESTED, data, now, start);
            case "appointment.declined" -> add(orgId, customerId, userId, DECLINED, data, now, start);
            case "appointment.expired" -> add(orgId, customerId, userId, EXPIRED, data, now, start);
            case "appointment.cancelled" -> {
                withdrawReminders(appointmentId);
                if (notify) add(orgId, customerId, userId, CANCELLED, data, now, start);
            }
            case "appointment.rescheduled" -> {
                withdrawReminders(appointmentId);
                if (notify) add(orgId, customerId, userId, RESCHEDULED, data, now, start);
                reminders(orgId, customerId, userId, data, now, start);
            }
            default -> {
                // other appointment events carry no customer message
            }
        }
    }

    private void reminders(
            UUID orgId, UUID customerId, UUID userId, Map<String, Object> data, Instant now, Instant start) {
        for (var reminder :
                List.of(Map.entry(REMINDER_24H, Duration.ofHours(24)), Map.entry(REMINDER_2H, Duration.ofHours(2)))) {
            Instant at = start.minus(reminder.getValue());
            if (at.isAfter(now)) {
                add(orgId, customerId, userId, reminder.getKey(), data, at, start);
            }
        }
    }

    private void add(
            UUID orgId,
            UUID customerId,
            UUID userId,
            String code,
            Map<String, Object> data,
            Instant sendAfter,
            Instant start) {
        system.insertInto(NOTIFICATIONS)
                .set(NOTIFICATIONS.ID, Ids.newId())
                .set(NOTIFICATIONS.ORG_ID, orgId)
                .set(NOTIFICATIONS.RECIPIENT_USER_ID, userId)
                .set(NOTIFICATIONS.RECIPIENT_CUSTOMER_ID, customerId)
                .set(NOTIFICATIONS.CATEGORY, REMINDERS.contains(code) ? "REMINDER" : "TRANSACTIONAL")
                .set(NOTIFICATIONS.TEMPLATE_CODE, code)
                .set(NOTIFICATIONS.DATA, JSONB.valueOf(json.writeValueAsString(data)))
                .set(
                        NOTIFICATIONS.DEDUPE_KEY,
                        code + ":" + data.getOrDefault("offerId", data.get("appointmentId")) + ":" + start)
                .set(NOTIFICATIONS.SEND_AFTER, sendAfter.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();
    }

    /** Reminders not yet handed to a channel are withdrawn (the visit moved or was cancelled). */
    private void withdrawReminders(UUID appointmentId) {
        system.deleteFrom(NOTIFICATIONS)
                .where(
                        NOTIFICATIONS.TEMPLATE_CODE.in(REMINDERS),
                        DSL.field("{0}->>'appointmentId'", String.class, NOTIFICATIONS.DATA)
                                .eq(appointmentId.toString()),
                        DSL.notExists(DSL.selectOne()
                                .from(DELIVERIES)
                                .where(DELIVERIES.NOTIFICATION_ID.eq(NOTIFICATIONS.ID))))
                .execute();
    }
}
