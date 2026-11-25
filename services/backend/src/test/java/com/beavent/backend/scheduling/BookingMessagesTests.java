package com.beavent.backend.scheduling;

import static com.beavent.db.comms.Tables.DELIVERIES;
import static com.beavent.db.comms.Tables.NOTIFICATIONS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * Messages around a booking (plan step 2.3): what the customer receives, in the business's language when the person
 * has not chosen one, with Ethiopian and international time; reminders that follow the visit when it moves or is
 * cancelled; requests that are answered or expire.
 */
class BookingMessagesTests extends SchedulingTestSupport {

    static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        waitFor(what, condition, 20_000);
    }

    static void waitFor(String what, BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofMillis(timeoutMillis));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    Call book(Shop shop, String hhmm, Person person) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(shop.variant(), shop.abebe())));
        body.put("start", at(day(), hhmm).toInstant().toString());
        Call held = call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), body, null);
        assertThat(held.status()).as(String.valueOf(held.body())).isEqualTo(201);
        Call booked = call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", held.text("holdId"),
                        "holderKey", held.text("holderKey")),
                person.token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        return booked;
    }

    List<String> reminders(String appointmentId) {
        return system.select(NOTIFICATIONS.TEMPLATE_CODE, NOTIFICATIONS.SEND_AFTER)
                .from(NOTIFICATIONS)
                .where(
                        DSL.field("{0}->>'appointmentId'", String.class, NOTIFICATIONS.DATA)
                                .eq(appointmentId),
                        NOTIFICATIONS.TEMPLATE_CODE.like("%REMINDER%"))
                .orderBy(NOTIFICATIONS.SEND_AFTER)
                .fetch(r -> r.value1() + " " + r.value2().toInstant());
    }

    /** A person who chose Amharic (people sign up with English unless they choose). */
    Person amharicSpeaker() throws Exception {
        Person person = newPerson();
        language(person, "am");
        return person;
    }

    void language(Person person, String code) throws Exception {
        Call changed = call(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/v1/me")
                        .contentType("application/merge-patch+json"),
                Map.of("preferredLanguage", code),
                person.token());
        assertThat(changed.status()).as(String.valueOf(changed.body())).isEqualTo(200);
    }

    boolean received(Person person, String fragment) {
        return sms.messagesTo(person.phone()).stream().anyMatch(m -> m.contains(fragment));
    }

    @Test
    void theCustomerIsToldAndRemindedAndRemindersFollowTheVisit() throws Exception {
        Shop shop = shop();
        Person person = amharicSpeaker();
        Call booked = book(shop, "10:00", person);
        String id = booked.text("id");
        String reference = booked.text("reference");

        // The person reads Amharic: Ethiopian time first, international beside it.
        waitFor("the confirmation", () -> received(person, reference));
        String confirmation = sms.messagesTo(person.phone()).stream()
                .filter(m -> m.contains(reference))
                .findFirst()
                .orElseThrow();
        assertThat(confirmation)
                .contains("ተረጋግጧል")
                .contains("ጠዋት 4:00 (10:00 AM)")
                .contains("Near Edna Mall");

        Instant start = at(day(), "10:00").toInstant();
        assertThat(reminders(id))
                .containsExactly(
                        "APPOINTMENT_REMINDER_24H " + start.minus(Duration.ofHours(24)),
                        "APPOINTMENT_REMINDER_2H " + start.minus(Duration.ofHours(2)));

        // Moving the visit replaces the reminders and says so.
        Call moved = call(
                post("/v1/me/appointments/" + id + "/reschedule"),
                Map.of("start", at(day(), "12:00").toInstant().toString()),
                person.token());
        assertThat(moved.status()).isEqualTo(200);
        Instant noon = at(day(), "12:00").toInstant();
        waitFor(
                "new reminders",
                () -> reminders(id).contains("APPOINTMENT_REMINDER_2H " + noon.minus(Duration.ofHours(2))));
        assertThat(reminders(id)).hasSize(2);
        waitFor("the reschedule message", () -> received(person, "ተዛውሯል"));

        // Cancelling withdraws them.
        assertThat(call(post("/v1/me/appointments/" + id + "/cancel"), Map.of(), person.token())
                        .status())
                .isEqualTo(200);
        waitFor("no reminders", () -> reminders(id).isEmpty());
        waitFor("the cancellation message", () -> received(person, "ተሰርዟል"));
    }

    @Test
    void englishSpeakersGetEnglishWithEthiopianTimeBeside() throws Exception {
        Shop shop = shop();
        Person person = newPerson();
        call(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/v1/me")
                        .contentType("application/merge-patch+json"),
                Map.of("preferredLanguage", "en"),
                person.token());
        Call booked = book(shop, "15:30", person);
        waitFor("the confirmation", () -> received(person, booked.text("reference")));
        assertThat(sms.lastMessageTo(person.phone()))
                .contains("is confirmed for")
                .contains("3:30 PM (9:30 in the afternoon)");
    }

    @Test
    void requestsAreAnsweredByTheBusinessOrExpire() throws Exception {
        Shop shop = shop();
        call(
                put(shop.salon().path("/booking-policies")),
                Map.of("requiresApproval", true),
                shop.salon().owner().token());
        Person first = amharicSpeaker();
        Call requested = book(shop, "09:00", first);
        assertThat(requested.text("status")).isEqualTo("REQUESTED");
        waitFor("the request receipt", () -> received(first, "ጥያቄዎን"));

        // A professional (own calendar only) may not answer; the owner declines; the time is freed and the customer
        // told.
        String path = shop.salon().path("/appointments/" + requested.text("id"));
        assertThat(call(
                                post(path + "/decline"),
                                Map.of(),
                                newMember(shop.salon(), "PROFESSIONAL").token())
                        .status())
                .isEqualTo(403);
        Call declined = call(
                post(path + "/decline"),
                Map.of("reason", "Fully booked"),
                shop.salon().owner().token());
        assertThat(declined.text("status")).isEqualTo("DECLINED");
        assertThat(call(post(path + "/accept"), null, shop.salon().owner().token())
                        .text("code"))
                .isEqualTo("IMMUTABLE");
        waitFor("the decline message", () -> received(first, "መቀበል አልቻለም"));
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .contains("09:00");

        // Accepted: confirmed, with reminders.
        Person second = amharicSpeaker();
        Call another = book(shop, "11:00", second);
        Call accepted = call(
                post(shop.salon().path("/appointments/" + another.text("id") + "/accept")),
                null,
                shop.salon().owner().token());
        assertThat(accepted.text("status")).isEqualTo("CONFIRMED");
        waitFor("the confirmation", () -> received(second, "ተረጋግጧል"));
        waitFor("reminders", () -> reminders(another.text("id")).size() == 2);

        // Not answered within a day: the sweep expires it, frees the time and tells the customer.
        Person third = amharicSpeaker();
        Call late = book(shop, "14:00", third);
        system.update(APPOINTMENTS)
                .set(APPOINTMENTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusHours(25))
                .where(APPOINTMENTS.ID.eq(UUID.fromString(late.text("id"))))
                .execute();
        UUID lateId = UUID.fromString(late.text("id"));
        waitFor(
                "the expiry",
                () -> "EXPIRED"
                        .equals(system.select(APPOINTMENTS.STATUS)
                                .from(APPOINTMENTS)
                                .where(APPOINTMENTS.ID.eq(lateId))
                                .fetchSingle(APPOINTMENTS.STATUS)));
        assertThat(call(get("/v1/me/appointments/" + lateId), null, third.token())
                        .text("status"))
                .isEqualTo("EXPIRED");
        waitFor("the expiry message", () -> received(third, "ምላሽ አልሰጠም"));
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .contains("14:00");
        assertThat(system.fetchCount(DELIVERIES, DELIVERIES.STATUS.eq("FAILED")))
                .isZero();
    }
}
