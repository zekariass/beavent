package com.beavent.backend.scheduling;

import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.HOLDS;
import static com.beavent.db.scheduling.Tables.SCHEDULE_RESERVATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Booking (plan step 2.3): hold a time, book it as a signed-in person, and see it. The reservation ledger's exclusion
 * constraints decide races; these tests check that the API keeps them and the engine in agreement.
 */
class BookingApiTests extends SchedulingTestSupport {

    static Instant time(LocalDate date, String hhmm) {
        return at(date, hhmm).toInstant();
    }

    Call hold(Shop shop, Instant start, Map<String, Object>... items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(items));
        body.put("start", start.toString());
        return call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), body, null);
    }

    Call book(Shop shop, Call hold, String token) throws Exception {
        return call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", hold.text("holdId"),
                        "holderKey", hold.text("holderKey"),
                        "note", "First visit"),
                token);
    }

    @Test
    void aPersonHoldsATimeAndBooksIt() throws Exception {
        Shop shop = shop();
        Instant ten = time(day(), "10:00");
        Call held = hold(shop, ten, item(shop.variant(), shop.abebe()));
        assertThat(held.status()).as(String.valueOf(held.body())).isEqualTo(201);
        JsonNode booking = held.body().get("booking");
        assertThat(booking.get("start").asString()).isEqualTo(ten.toString());
        assertThat(booking.get("end").asString())
                .isEqualTo(ten.plusSeconds(3600).toString());
        assertThat(booking.get("totalMinor").asLong()).isEqualTo(50_000);
        assertThat(booking.get("currency").asString()).isEqualTo("ETB");
        JsonNode first = booking.get("items").get(0);
        assertThat(first.get("staffName").asString()).isEqualTo("Abebe");
        assertThat(first.get("staffRequested").asBoolean()).isTrue();
        assertThat(first.get("serviceName").asString()).isEqualTo("Haircut");
        // The default policy: free cancellation until 24 hours before, as an exact time.
        assertThat(booking.get("policy").get("freeCancellationUntil").asString())
                .isEqualTo(ten.minusSeconds(24 * 3600).toString());

        // While held (10:00-11:10 with his buffer), Abebe is not offered where he would overlap it; Sara is.
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .doesNotContain("09:00", "09:15", "10:00", "10:45", "11:00")
                .contains("11:15");
        assertThat(staffOf(search(shop, day(), item(shop.variant(), null)), "10:00", 0))
                .isEqualTo(shop.sara());

        Person person = newPerson();
        Call booked = book(shop, held, person.token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        assertThat(booked.text("status")).isEqualTo("CONFIRMED");
        assertThat(booked.text("reference")).matches("[A-Z2-9]{6}");

        // Booking again with the same hold returns the same appointment; someone else cannot use the hold.
        Call again = book(shop, held, person.token());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.text("id")).isEqualTo(booked.text("id"));
        assertThat(book(shop, held, newPerson().token()).status()).isEqualTo(404);

        // The hold's reservation became the appointment's: still exactly one live row for Abebe at that time.
        assertThat(system.fetchCount(
                        SCHEDULE_RESERVATIONS,
                        SCHEDULE_RESERVATIONS.STAFF_ID.eq(UUID.fromString(shop.abebe())),
                        SCHEDULE_RESERVATIONS.STATUS.eq("CONFIRMED")))
                .isEqualTo(1);
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .doesNotContain("10:00");

        // The person sees it, with where and with whom.
        Call mine = call(get("/v1/me/appointments"), null, person.token());
        assertThat(mine.status()).isEqualTo(200);
        JsonNode appointment = mine.body().get("items").get(0);
        assertThat(appointment.get("reference").asString()).isEqualTo(booked.text("reference"));
        assertThat(appointment.get("place").get("locationId").asString())
                .isEqualTo(shop.salon().location1().toString());
        assertThat(appointment.get("items").get(0).get("staffName").asString()).isEqualTo("Abebe");
        assertThat(call(get("/v1/me/appointments"), null, newPerson().token())
                        .body()
                        .get("items")
                        .size())
                .as("nobody else sees it")
                .isZero();

        // A second booking by the same person keeps one customer record in the business.
        Call later = hold(shop, time(day(), "14:00"), item(shop.variant(), null));
        assertThat(book(shop, later, person.token()).status()).isEqualTo(201);
        assertThat(system.fetchCount(CUSTOMERS, CUSTOMERS.ORG_ID.eq(shop.salon().id())))
                .isEqualTo(1);
        assertThat(system.fetchCount(
                        APPOINTMENTS, APPOINTMENTS.ORG_ID.eq(shop.salon().id())))
                .isEqualTo(2);
    }

    @Test
    void peopleRacingForTheSameTimeGetExactlyOneHold() throws Exception {
        Shop shop = shop();
        Instant eleven = time(day(), "11:00");
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(shop.variant(), shop.abebe())));
        body.put("start", eleven.toString());
        String content = json.writeValueAsString(body);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> racers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                racers.add(() -> mvc.perform(
                                post("/v1/public/locations/" + shop.salon().location1() + "/holds")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(content))
                        .andReturn()
                        .getResponse()
                        .getStatus());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(racers)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        } finally {
            pool.shutdown();
        }
        Call late = hold(shop, eleven, item(shop.variant(), shop.abebe()));
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.text("code")).isEqualTo("SLOT_TAKEN");
    }

    @Test
    void aHoldThatRanOutCannotBeBookedAndFreesTheTime() throws Exception {
        Shop shop = shop();
        Instant noon = time(day(), "12:00");
        Call held = hold(shop, noon, item(shop.variant(), shop.abebe()));
        assertThat(held.status()).isEqualTo(201);
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        UUID holdId = UUID.fromString(held.text("holdId"));
        system.update(HOLDS)
                .set(HOLDS.EXPIRES_AT, past)
                .where(HOLDS.ID.eq(holdId))
                .execute();
        system.update(SCHEDULE_RESERVATIONS)
                .set(SCHEDULE_RESERVATIONS.HOLD_EXPIRES_AT, past)
                .where(SCHEDULE_RESERVATIONS.HOLD_ID.eq(holdId))
                .execute();

        Call late = book(shop, held, newPerson().token());
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.text("code")).isEqualTo("HOLD_EXPIRED");
        // The time is offered again, and someone else can hold it (the expired hold is released on the way).
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .contains("12:00");
        assertThat(hold(shop, noon, item(shop.variant(), shop.abebe())).status())
                .isEqualTo(201);
    }

    @Test
    void aHoldCanBeLetGoOnlyByItsHolder() throws Exception {
        Shop shop = shop();
        Instant three = time(day(), "15:00");
        Call held = hold(shop, three, item(shop.variant(), shop.sara()));
        String path = "/v1/public/locations/" + shop.salon().location1() + "/holds/" + held.text("holdId");

        assertThat(call(delete(path).header("X-Hold-Key", "not-the-key"), null, null)
                        .status())
                .isEqualTo(204);
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.sara()))))
                .doesNotContain("15:00");

        assertThat(call(delete(path).header("X-Hold-Key", held.text("holderKey")), null, null)
                        .status())
                .isEqualTo(204);
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.sara()))))
                .contains("15:00");
        assertThat(book(shop, held, newPerson().token()).text("code")).isEqualTo("HOLD_EXPIRED");
    }

    @Test
    void aBusinessThatConfirmsEachBookingGetsARequest() throws Exception {
        Shop shop = shop();
        call(
                put(shop.salon().path("/booking-policies")),
                Map.of("requiresApproval", true),
                shop.salon().owner().token());
        Call held = hold(shop, time(day(), "09:00"), item(shop.variant(), null));
        assertThat(held.body()
                        .get("booking")
                        .get("policy")
                        .get("requiresApproval")
                        .asBoolean())
                .isTrue();
        Call booked = book(shop, held, newPerson().token());
        assertThat(booked.status()).isEqualTo(201);
        assertThat(booked.text("status")).isEqualTo("REQUESTED");
        // A request holds the time until the business answers.
        assertThat(starts(search(
                        shop,
                        day(),
                        item(
                                shop.variant(),
                                booked.body()
                                        .get("booking")
                                        .get("items")
                                        .get(0)
                                        .get("staffId")
                                        .asString()))))
                .doesNotContain("09:00");
    }

    @Test
    void twoServicesWithTheSamePersonBackToBackDoNotCollideWithEachOther() throws Exception {
        Shop shop = shop();
        // Haircut (60 min + 10 min buffer after) twice with Abebe: the first buffer overlaps the second haircut.
        Instant start = time(day(), "13:00");
        Call held = hold(shop, start, item(shop.variant(), shop.abebe()), item(shop.variant(), shop.abebe()));
        assertThat(held.status()).as(String.valueOf(held.body())).isEqualTo(201);
        assertThat(held.body().get("booking").get("end").asString())
                .isEqualTo(start.plusSeconds(2 * 3600).toString());
        assertThat(held.body().get("booking").get("totalMinor").asLong()).isEqualTo(100_000);
        // One merged reservation 13:00-15:10 for Abebe.
        assertThat(system.fetchCount(
                        SCHEDULE_RESERVATIONS, SCHEDULE_RESERVATIONS.HOLD_ID.eq(UUID.fromString(held.text("holdId")))))
                .isEqualTo(1);
        assertThat(book(shop, held, newPerson().token()).status()).isEqualTo(201);
    }

    @Test
    void bookingNeedsASignedInPersonAndAnOfferedTime() throws Exception {
        Shop shop = shop();
        Call held = hold(shop, time(day(), "16:00"), item(shop.variant(), null));
        assertThat(call(
                                post("/v1/me/appointments"),
                                Map.of(
                                        "locationId", shop.salon().location1().toString(),
                                        "holdId", held.text("holdId"),
                                        "holderKey", held.text("holderKey")),
                                null)
                        .status())
                .isEqualTo(401);
        // Not on the grid, before opening, and too soon are never held.
        assertThat(hold(shop, time(day(), "16:07"), item(shop.variant(), null)).text("code"))
                .isEqualTo("SLOT_TAKEN");
        assertThat(hold(shop, time(day(), "07:00"), item(shop.variant(), null)).text("code"))
                .isEqualTo("SLOT_TAKEN");
        assertThat(hold(shop, Instant.now().plusSeconds(600), item(shop.variant(), null))
                        .status())
                .isEqualTo(409);
        assertThat(call(get("/v1/me/appointments"), null, null).status()).isEqualTo(401);
    }

    Call booked(Shop shop, String hhmm, String staffId, Person person) throws Exception {
        Call held = hold(shop, time(day(), hhmm), item(shop.variant(), staffId));
        assertThat(held.status()).as(String.valueOf(held.body())).isEqualTo(201);
        Call booked = book(shop, held, person.token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        return booked;
    }

    long liveRowsFor(String staffId) {
        return system.fetchCount(
                SCHEDULE_RESERVATIONS,
                SCHEDULE_RESERVATIONS.STAFF_ID.eq(UUID.fromString(staffId)),
                SCHEDULE_RESERVATIONS.STATUS.in("CONFIRMED", "HELD"));
    }

    @Test
    void cancellingBeforeTheFreeTimeIsFreeAndFreesTheTime() throws Exception {
        Shop shop = shop();
        Person person = newPerson();
        Call booked = booked(shop, "10:00", shop.abebe(), person);
        String path = "/v1/me/appointments/" + booked.text("id");

        Call detail = call(get(path), null, person.token());
        assertThat(detail.status()).isEqualTo(200);
        JsonNode cancellation = detail.body().get("cancellation");
        assertThat(cancellation.get("allowed").asBoolean()).isTrue();
        assertThat(cancellation.get("free").asBoolean()).isTrue();
        assertThat(cancellation.get("feeMinor").asLong()).isZero();
        assertThat(detail.body().get("canReschedule").asBoolean()).isTrue();
        assertThat(detail.body().get("note").asString()).isEqualTo("First visit");
        assertThat(call(get(path), null, newPerson().token()).status())
                .as("someone else's")
                .isEqualTo(404);

        Call cancelled = call(post(path + "/cancel"), Map.of("reason", "Travelling"), person.token());
        assertThat(cancelled.status()).as(String.valueOf(cancelled.body())).isEqualTo(200);
        assertThat(cancelled.text("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.body().get("cancellation").get("allowed").asBoolean())
                .isFalse();
        assertThat(liveRowsFor(shop.abebe())).isZero();
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .contains("10:00");
        assertThat(call(post(path + "/cancel"), Map.of(), person.token()).text("code"))
                .isEqualTo("IMMUTABLE");
    }

    @Test
    void lateCancellationRecordsTheFeeAndCannotBeMovedOnline() throws Exception {
        Shop shop = shop();
        // Free cancellation ends 30 days before: this booking (3 days ahead) is already inside the window.
        call(
                put(shop.salon().path("/booking-policies")),
                Map.of("cancellationWindowMinutes", 43_200, "lateCancelFeePercent", 50),
                shop.salon().owner().token());
        Person person = newPerson();
        Call booked = booked(shop, "11:00", null, person);
        String path = "/v1/me/appointments/" + booked.text("id");
        JsonNode cancellation = call(get(path), null, person.token()).body().get("cancellation");
        assertThat(cancellation.get("free").asBoolean()).isFalse();
        assertThat(cancellation.get("feeMinor").asLong()).isEqualTo(25_000);
        assertThat(call(
                                post(path + "/reschedule"),
                                Map.of("start", time(day(), "12:00").toString()),
                                person.token())
                        .text("code"))
                .isEqualTo("IMMUTABLE");
        assertThat(call(post(path + "/cancel"), Map.of(), person.token()).text("status"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void reschedulingMovesTheVisitEvenOntoItsOwnTimeAndChangesNothingWhenTheNewTimeIsTaken() throws Exception {
        Shop shop = shop();
        Person person = newPerson();
        Call booked = booked(shop, "10:00", shop.abebe(), person);
        String path = "/v1/me/appointments/" + booked.text("id");

        // 15 minutes later with the same person: overlaps the old time, which is freed in the same transaction.
        Call moved = call(
                post(path + "/reschedule"), Map.of("start", time(day(), "10:15").toString()), person.token());
        assertThat(moved.status()).as(String.valueOf(moved.body())).isEqualTo(200);
        assertThat(moved.text("start")).isEqualTo(time(day(), "10:15").toString());
        assertThat(moved.text("reference")).isEqualTo(booked.text("reference"));
        assertThat(moved.body()
                        .get("booking")
                        .get("items")
                        .get(0)
                        .get("staffName")
                        .asString())
                .isEqualTo("Abebe");
        assertThat(moved.body().get("booking").get("totalMinor").asLong()).isEqualTo(50_000);
        assertThat(liveRowsFor(shop.abebe())).isEqualTo(1);

        // Abebe is taken at 14:00 by someone else: moving there fails and the visit stays at 10:15.
        booked(shop, "14:00", shop.abebe(), newPerson());
        Call taken = call(
                post(path + "/reschedule"), Map.of("start", time(day(), "14:00").toString()), person.token());
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.text("code")).isEqualTo("SLOT_TAKEN");
        Call after = call(get(path), null, person.token());
        assertThat(after.text("start")).isEqualTo(time(day(), "10:15").toString());
        assertThat(liveRowsFor(shop.abebe())).isEqualTo(2);
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .doesNotContain("10:15");
    }
}
