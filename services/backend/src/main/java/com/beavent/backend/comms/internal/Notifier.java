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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** A refund was paid to the customer (plan 3.6). */
    static final String REFUND_SENT = "APPOINTMENT_REFUND_SENT";
    /** A link to pay a deposit or a balance was made for the booking (plan 3.7). */
    static final String PAYMENT_LINK = "APPOINTMENT_PAYMENT_LINK";
    /** A refund was kept as credit or put back on a gift card (plan 3.8). */
    static final String REFUND_CREDITED = "APPOINTMENT_REFUND_CREDITED";
    /** A gift card arrived for the recipient (plan 3.8). */
    static final String GIFT_CARD_RECEIVED = "GIFT_CARD_RECEIVED";

    static final String CANCELLED = "APPOINTMENT_CANCELLED";
    static final String RESCHEDULED = "APPOINTMENT_RESCHEDULED";
    static final String REMINDER_24H = "APPOINTMENT_REMINDER_24H";
    static final String REMINDER_2H = "APPOINTMENT_REMINDER_2H";
    /** A time opened up for someone on the waitlist (plan 2.6, S-15). */
    static final String WAITLIST_OFFER = "APPOINTMENT_WAITLIST_OFFER";

    /** A payout account was added or changed (plan 3.4): sent to the people who manage payouts. */
    static final String PAYOUT_CHANGED = "PAYOUT_ACCOUNT_CHANGED";
    /** A payout was paid, or did not go through (plan 3.5): sent to the same people. */
    static final String PAYOUT_PAID = "PAYOUT_PAID";

    static final String PAYOUT_FAILED = "PAYOUT_FAILED";

    /** Some time after a completed visit: how was it? Leads to the review form (plan 8.3). */
    static final String REVIEW_REQUEST = "APPOINTMENT_REVIEW_REQUEST";
    /** How long after the visit the request goes. */
    private static final Duration REVIEW_REQUEST_DELAY = Duration.ofHours(2);

    /** Reviews and the marketplace: to the business, or to the author of a review (plans 8.3 and 8.1). */
    static final String REVIEW_RECEIVED = "REVIEW_RECEIVED";

    static final String REVIEW_REMOVED = "REVIEW_REMOVED";
    static final String REVIEW_RESTORED = "REVIEW_RESTORED";
    static final String REVIEW_REPORT_OUTCOME = "REVIEW_REPORT_OUTCOME";
    static final String MARKETPLACE_CHANGED = "MARKETPLACE_CHANGED";

    /** What the business hears about a booking a customer made or cancelled themselves (plan 8.4). */
    static final String BOOKING_NEW = "BOOKING_NEW";

    static final String BOOKING_REQUEST = "BOOKING_REQUEST";
    static final String BOOKING_CANCELLED = "BOOKING_CANCELLED_BY_CUSTOMER";
    /** Bookings made by the business's own desk are not news to it. */
    private static final List<String> DESK_SOURCES = List.of("WALK_IN", "PHONE", "PARTNER_PORTAL");

    static final List<String> REMINDERS = List.of(REMINDER_24H, REMINDER_2H);
    static final String CONSUMER = "comms.appointments";

    private final DSLContext system;
    private final ProcessedMessages processedMessages;
    private final JsonMapper json;
    private final String webUrl;
    private final OrgPeople people;

    Notifier(
            @SystemDb DSLContext system,
            ProcessedMessages processedMessages,
            JsonMapper json,
            @org.springframework.beans.factory.annotation.Value("${beavent.web.public-url}") String webUrl,
            OrgPeople people) {
        this.system = system;
        this.processedMessages = processedMessages;
        this.json = json;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.people = people;
    }

    @RabbitListener(queues = CommsConfiguration.PAYOUTS_QUEUE)
    void onPayoutEvent(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        String type = message.getMessageProperties().getReceivedRoutingKey();
        UUID orgId = UUID.fromString(message.getMessageProperties().<String>getHeader("org_id"));
        var body = json.readTree(message.getBody());
        switch (type) {
            case "payout_account.changed" ->
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
            case "payout.paid", "payout.failed", "payout.rejected" ->
                processedMessages.runOnce(
                        CONSUMER + ".payouts",
                        messageId,
                        () -> payoutOutcome(
                                orgId,
                                type.equals("payout.paid") ? PAYOUT_PAID : PAYOUT_FAILED,
                                body.get("payoutId").asString(),
                                body.get("amountMinor").asLong(),
                                body.get("currency").asString(),
                                body.get("institutionName").asString(),
                                body.get("last4").asString(),
                                body.get("note").asString(),
                                Instant.now()));
            default -> {
                // not for us
            }
        }
    }

    /** The people who may manage a business's payouts: its owners and the roles with the right. */
    private List<UUID> payoutPeople(UUID orgId) {
        return system.selectDistinct(DSL.field("m.user_id", UUID.class))
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
    }

    /** A payout was paid or did not go through: the people who manage payouts are told. Runs in a system transaction. */
    void payoutOutcome(
            UUID orgId,
            String code,
            String payoutId,
            long amountMinor,
            String currency,
            String institution,
            String last4,
            String note,
            Instant now) {
        String business = system.select(ORGANIZATIONS.DISPLAY_NAME)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.DISPLAY_NAME);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("business", business);
        data.put("amountMinor", amountMinor);
        data.put("currency", currency);
        data.put("institution", institution);
        data.put("last4", last4);
        data.put("note", note);
        for (UUID user : payoutPeople(orgId)) {
            system.insertInto(NOTIFICATIONS)
                    .set(NOTIFICATIONS.ID, Ids.newId())
                    .set(NOTIFICATIONS.ORG_ID, orgId)
                    .set(NOTIFICATIONS.RECIPIENT_USER_ID, user)
                    .set(NOTIFICATIONS.CATEGORY, "TRANSACTIONAL")
                    .set(NOTIFICATIONS.TEMPLATE_CODE, code)
                    .set(NOTIFICATIONS.DATA, JSONB.valueOf(json.writeValueAsString(data)))
                    .set(NOTIFICATIONS.DEDUPE_KEY, code + ":" + payoutId + ":" + user)
                    .set(NOTIFICATIONS.SEND_AFTER, now.atOffset(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute();
        }
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
        List<UUID> people = payoutPeople(orgId);
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
        if (type.equals("gift_card.delivered")) {
            UUID orgId = UUID.fromString(message.getMessageProperties().<String>getHeader("org_id"));
            String customer = body.get("recipientCustomerId").asString();
            if (customer.isBlank()) return;
            Map<String, Object> data = new LinkedHashMap<>();
            for (String key :
                    List.of("code", "sender", "message", "business", "venueUrl", "currency", "expiresAt", "cardId")) {
                data.put(key, body.get(key).asString());
            }
            data.put("amountMinor", body.get("amountMinor").asLong());
            data.put("timezone", "Africa/Addis_Ababa");
            processedMessages.runOnce(CONSUMER, messageId, () -> {
                system.insertInto(NOTIFICATIONS)
                        .set(NOTIFICATIONS.ID, Ids.newId())
                        .set(NOTIFICATIONS.ORG_ID, orgId)
                        .set(NOTIFICATIONS.RECIPIENT_CUSTOMER_ID, UUID.fromString(customer))
                        .set(NOTIFICATIONS.CATEGORY, "TRANSACTIONAL")
                        .set(NOTIFICATIONS.TEMPLATE_CODE, GIFT_CARD_RECEIVED)
                        .set(NOTIFICATIONS.DATA, JSONB.valueOf(json.writeValueAsString(data)))
                        .set(NOTIFICATIONS.DEDUPE_KEY, GIFT_CARD_RECEIVED + ":" + data.get("cardId"))
                        .set(NOTIFICATIONS.SEND_AFTER, Instant.now().atOffset(ZoneOffset.UTC))
                        .onConflictDoNothing()
                        .execute();
            });
            return;
        }
        if (type.equals("refund.credited")) {
            UUID orderId = UUID.fromString(body.get("orderId").asString());
            Map<String, Object> extra = Map.of(
                    "refundId", body.get("refundId").asString(),
                    "refundMinor", body.get("amountMinor").asLong());
            processedMessages.runOnce(CONSUMER, messageId, () -> {
                UUID appointmentId = system.select(APPOINTMENTS.ID)
                        .from(APPOINTMENTS)
                        .where(APPOINTMENTS.ORDER_ID.eq(orderId))
                        .fetchOne(APPOINTMENTS.ID);
                if (appointmentId != null) handle(type, appointmentId, true, Instant.now(), extra);
            });
            return;
        }
        if (type.equals("payment_link.created")) {
            UUID orderId = UUID.fromString(body.get("orderId").asString());
            Map<String, Object> extra = Map.of(
                    "linkId", body.get("linkId").asString(),
                    "url", webUrl + "/pay/" + body.get("token").asString(),
                    "kind", body.get("kind").asString(),
                    "paymentMinor", body.get("amountMinor").asLong(),
                    "linkExpires", body.get("expiresAt").asString());
            processedMessages.runOnce(CONSUMER, messageId, () -> {
                UUID appointmentId = system.select(APPOINTMENTS.ID)
                        .from(APPOINTMENTS)
                        .where(APPOINTMENTS.ORDER_ID.eq(orderId))
                        .fetchOne(APPOINTMENTS.ID);
                if (appointmentId != null) handle(type, appointmentId, true, Instant.now(), extra);
            });
            return;
        }
        if (type.equals("refund.succeeded")) {
            UUID orderId = UUID.fromString(body.get("orderId").asString());
            Map<String, Object> extra = Map.of(
                    "refundId", body.get("refundId").asString(),
                    "refundMinor", body.get("amountMinor").asLong());
            processedMessages.runOnce(CONSUMER, messageId, () -> {
                UUID appointmentId = system.select(APPOINTMENTS.ID)
                        .from(APPOINTMENTS)
                        .where(APPOINTMENTS.ORDER_ID.eq(orderId))
                        .fetchOne(APPOINTMENTS.ID);
                if (appointmentId != null) handle(type, appointmentId, true, Instant.now(), extra);
            });
            return;
        }
        UUID appointmentId = UUID.fromString(body.get("appointmentId").asString());
        // The front desk may book or move without telling the customer now (reminders still follow).
        boolean notify = !body.has("notify") || body.get("notify").asBoolean();
        processedMessages.runOnce(CONSUMER, messageId, () -> handle(type, appointmentId, notify, Instant.now()));
    }

    /** Runs inside a system-role transaction. */
    void handle(String eventType, UUID appointmentId, boolean notify, Instant now) {
        handle(eventType, appointmentId, notify, now, Map.of());
    }

    void handle(String eventType, UUID appointmentId, boolean notify, Instant now, Map<String, Object> extra) {
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
                        CUSTOMERS.USER_ID,
                        APPOINTMENTS.SOURCE,
                        APPOINTMENTS.CANCELLED_BY,
                        CUSTOMERS.DISPLAY_NAME)
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
        data.putAll(extra);
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
        boolean madeByCustomer = !DESK_SOURCES.contains(a.value11());

        switch (eventType) {
            case "appointment.booked", "appointment.accepted" -> {
                if (notify) add(orgId, customerId, userId, CONFIRMED, data, now, start);
                reminders(orgId, customerId, userId, data, now, start);
                if (eventType.equals("appointment.booked") && madeByCustomer) {
                    tellBusiness(BOOKING_NEW, orgId, appointmentId, a.value13(), data, now);
                }
            }
            case "appointment.requested" -> {
                add(orgId, customerId, userId, REQUESTED, data, now, start);
                if (madeByCustomer) tellBusiness(BOOKING_REQUEST, orgId, appointmentId, a.value13(), data, now);
            }
            case "appointment.declined" -> add(orgId, customerId, userId, DECLINED, data, now, start);
            case "appointment.expired" -> add(orgId, customerId, userId, EXPIRED, data, now, start);
            case "appointment.cancelled" -> {
                withdrawReminders(appointmentId);
                if (notify) add(orgId, customerId, userId, CANCELLED, data, now, start);
                if ("CUSTOMER".equals(a.value12())) {
                    tellBusiness(BOOKING_CANCELLED, orgId, appointmentId, a.value13(), data, now);
                }
            }
            case "refund.succeeded" -> add(orgId, customerId, userId, REFUND_SENT, data, now, start);
            case "payment_link.created" -> add(orgId, customerId, userId, PAYMENT_LINK, data, now, start);
            case "refund.credited" -> add(orgId, customerId, userId, REFUND_CREDITED, data, now, start);
            case "appointment.completed" -> {
                // Only a person with an account can review; a guest has nowhere to write it.
                if (userId != null) {
                    data.put("web", webUrl);
                    add(orgId, customerId, userId, REVIEW_REQUEST, data, now.plus(REVIEW_REQUEST_DELAY), start);
                }
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

    /**
     * Tells the people who run the business, and whoever does the work, about a booking a customer made, asked for or
     * cancelled. Runs inside a system transaction.
     */
    private void tellBusiness(
            String code, UUID orgId, UUID appointmentId, String customerName, Map<String, Object> visit, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>(visit);
        data.put("customer", customerName);
        data.put("orgId", orgId.toString());
        data.put("web", webUrl);
        data.put(
                "staff",
                system.select(DSL.field("s.display_name", String.class))
                        .from(APPOINTMENT_ITEMS)
                        .join(DSL.table("team.staff").as("s"))
                        .on(DSL.field("s.id", UUID.class).eq(APPOINTMENT_ITEMS.STAFF_ID))
                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(appointmentId))
                        .orderBy(APPOINTMENT_ITEMS.SEQ)
                        .fetch(0, String.class));
        Set<UUID> recipients = new LinkedHashSet<>(people.with(orgId, "appointments.manage"));
        recipients.addAll(people.doing(appointmentId));
        for (UUID user : recipients) {
            system.insertInto(NOTIFICATIONS)
                    .set(NOTIFICATIONS.ID, Ids.newId())
                    .set(NOTIFICATIONS.ORG_ID, orgId)
                    .set(NOTIFICATIONS.RECIPIENT_USER_ID, user)
                    .set(NOTIFICATIONS.CATEGORY, "OPERATIONAL")
                    .set(NOTIFICATIONS.TEMPLATE_CODE, code)
                    .set(NOTIFICATIONS.DATA, JSONB.valueOf(json.writeValueAsString(data)))
                    .set(NOTIFICATIONS.DEDUPE_KEY, code + ":" + appointmentId + ":" + data.get("startsAt") + ":" + user)
                    .set(NOTIFICATIONS.SEND_AFTER, now.atOffset(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute();
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
                .set(
                        NOTIFICATIONS.CATEGORY,
                        REMINDERS.contains(code) || REVIEW_REQUEST.equals(code) ? "REMINDER" : "TRANSACTIONAL")
                .set(NOTIFICATIONS.TEMPLATE_CODE, code)
                .set(NOTIFICATIONS.DATA, JSONB.valueOf(json.writeValueAsString(data)))
                .set(
                        NOTIFICATIONS.DEDUPE_KEY,
                        code + ":"
                                + data.getOrDefault("refundId", data.getOrDefault("offerId", data.get("appointmentId")))
                                + ":" + start)
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
