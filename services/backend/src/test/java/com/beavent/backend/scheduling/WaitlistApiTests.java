package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.scheduling.internal.Waitlist;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** The waitlist (plan step 2.6, S-15): joining, offers on a freed time, booking, declining, running out. */
class WaitlistApiTests extends SchedulingTestSupport {

    @Autowired
    Waitlist waitlist;

    /** Abebe's day is full except 10:00–11:10, which a first customer books; returns that booking's id. */
    String fullDay(Shop shop, Person first) throws Exception {
        blockStaff(shop, shop.abebe(), day(), "09:00", "10:00");
        blockStaff(shop, shop.abebe(), day(), "11:10", "18:00");
        Call hold = call(
                post("/v1/public/locations/" + shop.salon().location1() + "/holds"),
                Map.of(
                        "items",
                        List.of(item(shop.variant(), shop.abebe())),
                        "start",
                        at(day(), "10:00").toInstant().toString()),
                null);
        assertThat(hold.status()).as(String.valueOf(hold.body())).isEqualTo(201);
        Call booked = call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", hold.text("holdId"),
                        "holderKey", hold.text("holderKey")),
                first.token());
        assertThat(booked.status()).isEqualTo(201);
        return booked.text("id");
    }

    Call join(Shop shop, Person person, List<LocalDate> days, List<String> parts) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", shop.salon().location1().toString());
        body.put("variantId", shop.variant());
        body.put("staffId", shop.abebe());
        body.put("days", days.stream().map(LocalDate::toString).toList());
        body.put("parts", parts);
        boolean allowed =
                days.size() <= 7 && List.of("MORNING", "AFTERNOON", "EVENING").containsAll(parts);
        return allowed
                ? call(post("/v1/me/waitlist"), body, person.token())
                : callInvalid(post("/v1/me/waitlist"), body, person.token());
    }

    JsonNode entry(Person person, String entryId) throws Exception {
        Call got = call(get("/v1/me/waitlist/" + entryId), null, person.token());
        assertThat(got.status()).as(String.valueOf(got.body())).isEqualTo(200);
        return got.body();
    }

    void cancel(Person person, String appointmentId) throws Exception {
        assertThat(call(post("/v1/me/appointments/" + appointmentId + "/cancel"), Map.of(), person.token())
                        .status())
                .isEqualTo(200);
    }

    @Test
    void aCancelledTimeIsOfferedToTheWaitingPersonWhoBooksIt() throws Exception {
        Shop shop = shop();
        Person first = newPerson();
        String appointment = fullDay(shop, first);
        Person waiting = newPerson();
        Call joined = join(shop, waiting, List.of(day()), List.of("MORNING"));
        assertThat(joined.status()).as(String.valueOf(joined.body())).isEqualTo(201);
        String entryId = joined.text("id");

        // Nothing is free yet: no offer.
        waitlist.sweep(Instant.now());
        assertThat(entry(waiting, entryId).get("status").asString()).isEqualTo("WAITING");

        // The first customer cancels: the time is offered to the person waiting, held for them, and they are told.
        cancel(first, appointment);
        waitlist.sweep(Instant.now());
        JsonNode offered = entry(waiting, entryId);
        assertThat(offered.get("status").asString()).isEqualTo("OFFERED");
        assertThat(Instant.parse(offered.get("offer").get("start").asString()))
                .isEqualTo(at(day(), "10:00").toInstant());
        BookingMessagesTests.waitFor(
                "the offer message",
                () -> sms.messagesTo(waiting.phone()).stream()
                        .anyMatch(m -> m.contains("/account/waitlist/" + entryId)));
        // Held: nobody else can take it meanwhile.
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .doesNotContain("10:00");

        // They book it: an appointment from the waitlist; the entry is done.
        Call accepted = call(post("/v1/me/waitlist/" + entryId + "/accept"), null, waiting.token());
        assertThat(accepted.status()).as(String.valueOf(accepted.body())).isEqualTo(201);
        assertThat(system.fetchValue(
                        "select source from scheduling.appointments where id = ?::uuid",
                        accepted.text("appointmentId")))
                .isEqualTo("WAITLIST");
        assertThat(call(get("/v1/me/waitlist"), null, waiting.token())
                        .body()
                        .get("items")
                        .size())
                .isZero();
    }

    @Test
    void aDeclinedOrExpiredOfferGoesToTheNextPersonAndIsNotOfferedAgain() throws Exception {
        Shop shop = shop();
        Person first = newPerson();
        String appointment = fullDay(shop, first);
        Person earlier = newPerson();
        Person later = newPerson();
        String a = join(shop, earlier, List.of(day()), List.of("MORNING")).text("id");
        String b = join(shop, later, List.of(day()), List.of("MORNING", "AFTERNOON"))
                .text("id");

        cancel(first, appointment);
        waitlist.sweep(Instant.now());
        assertThat(entry(earlier, a).get("status").asString()).isEqualTo("OFFERED");
        assertThat(entry(later, b).get("status").asString()).isEqualTo("WAITING");

        // "Not this time": the time goes to the next person; the first keeps waiting.
        assertThat(call(post("/v1/me/waitlist/" + a + "/decline"), null, earlier.token())
                        .status())
                .isEqualTo(204);
        waitlist.sweep(Instant.now());
        assertThat(entry(earlier, a).get("status").asString()).isEqualTo("WAITING");
        assertThat(entry(later, b).get("status").asString()).isEqualTo("OFFERED");

        // The second lets it run out: it is not offered to either of them again.
        system.execute(
                "update commerce.waitlist_offers set expires_at = now() - interval '1 minute', "
                        + "offered_at = now() - interval '20 minutes' where entry_id = ?::uuid and status = 'OPEN'",
                java.util.UUID.fromString(b));
        system.execute(
                "update scheduling.holds set expires_at = now() - interval '1 minute' where id in "
                        + "(select hold_id from commerce.waitlist_offers where entry_id = ?::uuid)",
                java.util.UUID.fromString(b));
        system.execute(
                "update scheduling.schedule_reservations set hold_expires_at = now() - interval '1 minute' "
                        + "where hold_id in (select hold_id from commerce.waitlist_offers where entry_id = ?::uuid)",
                java.util.UUID.fromString(b));
        waitlist.sweep(Instant.now());
        assertThat(entry(later, b).get("status").asString()).isEqualTo("WAITING");
        assertThat(entry(earlier, a).get("status").asString()).isEqualTo("WAITING");
        assertThat(call(post("/v1/me/waitlist/" + b + "/accept"), null, later.token())
                        .status())
                .isEqualTo(409);

        // The front desk sees both waiting, oldest first, with names.
        JsonNode desk = call(
                        get(shop.salon()
                                .path("/waitlist?locationId=" + shop.salon().location1())),
                        null,
                        shop.salon().owner().token())
                .body();
        assertThat(desk.get("items").size()).isEqualTo(2);
        assertThat(desk.get("items").get(0).get("id").asString()).isEqualTo(a);
    }

    @Test
    void joiningIsCheckedAndLeavingWorks() throws Exception {
        Shop shop = shop();
        Person person = newPerson();
        LocalDate d = day();
        assertThat(join(shop, person, List.of(d.minusDays(5)), List.of("MORNING"))
                        .status())
                .isEqualTo(400);
        assertThat(join(shop, person, List.of(d), List.of("NIGHT")).status()).isEqualTo(400);
        assertThat(join(
                                shop,
                                person,
                                List.of(
                                        d,
                                        d.plusDays(1),
                                        d.plusDays(2),
                                        d.plusDays(3),
                                        d.plusDays(4),
                                        d.plusDays(5),
                                        d.plusDays(6),
                                        d.plusDays(7)),
                                List.of("MORNING"))
                        .status())
                .isEqualTo(400);
        assertThat(call(
                                post("/v1/me/waitlist"),
                                Map.of(
                                        "locationId", shop.salon().location1().toString(),
                                        "variantId", shop.variant(),
                                        "days", List.of(d.toString()),
                                        "parts", List.of("MORNING")),
                                null)
                        .status())
                .isEqualTo(401);

        String entry = null;
        for (int i = 0; i < 5; i++) {
            Call joined = join(shop, person, List.of(d.plusDays(i)), List.of("EVENING"));
            assertThat(joined.status()).as(String.valueOf(joined.body())).isEqualTo(201);
            entry = joined.text("id");
        }
        assertThat(join(shop, person, List.of(d), List.of("EVENING")).status()).isEqualTo(409);
        JsonNode mine = call(get("/v1/me/waitlist"), null, person.token()).body();
        assertThat(mine.get("items").size()).isEqualTo(5);
        assertThat(mine.get("items").get(0).get("parts").toString()).isEqualTo("[\"EVENING\"]");
        assertThat(mine.get("items").get(0).get("place").get("businessName").asString())
                .startsWith("Availability");

        assertThat(call(delete("/v1/me/waitlist/" + entry), null, person.token())
                        .status())
                .isEqualTo(204);
        assertThat(call(get("/v1/me/waitlist"), null, person.token())
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(4);
        // Someone else's entry is not found.
        assertThat(call(
                                delete("/v1/me/waitlist/"
                                        + mine.get("items").get(1).get("id").asString()),
                                null,
                                newPerson().token())
                        .status())
                .isEqualTo(404);
    }
}
