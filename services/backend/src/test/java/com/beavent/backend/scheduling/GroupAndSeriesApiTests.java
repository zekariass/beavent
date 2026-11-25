package com.beavent.backend.scheduling;

import static com.beavent.db.comms.Tables.NOTIFICATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Front-desk groups (several people at once) and series (every few weeks), and cancelling them (plan 2.6). */
class GroupAndSeriesApiTests extends SchedulingTestSupport {

    Map<String, Object> booking(Shop shop, LocalDate date, String hhmm, String staffId, Map<String, Object> customer) {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", shop.salon().location1().toString());
        body.put("start", at(date, hhmm).toInstant().toString());
        body.put("items", List.of(Map.of("variantId", shop.variant(), "staffId", staffId)));
        body.put("customer", customer);
        body.put("source", "PHONE");
        return body;
    }

    JsonNode calendar(Shop shop, LocalDate date) throws Exception {
        return call(
                        get(shop.salon()
                                .path("/calendar?locationId=" + shop.salon().location1() + "&from=" + date)),
                        null,
                        shop.salon().owner().token())
                .body();
    }

    long active(Shop shop) {
        return system.fetchCount(
                com.beavent.db.scheduling.Tables.APPOINTMENTS,
                com.beavent.db.scheduling.Tables.APPOINTMENTS
                        .ORG_ID
                        .eq(shop.salon().id())
                        .and(com.beavent.db.scheduling.Tables.APPOINTMENTS.STATUS.eq("CONFIRMED")));
    }

    @Test
    void aGroupIsBookedTogetherOrNotAtAllAndCancelledTogether() throws Exception {
        Shop shop = shop();
        String owner = shop.salon().owner().token();
        Map<String, Object> group = booking(shop, day(), "10:00", shop.abebe(), Map.of("name", "Selam (bride)"));
        group.put(
                "guests",
                List.of(Map.of(
                        "customer", Map.of("name", "Liya"),
                        "items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.sara())))));
        Call booked = call(post(shop.salon().path("/appointments")), group, owner);
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        assertThat(booked.body().get("appointments").size()).isEqualTo(2);
        String groupId = booked.text("groupId");
        JsonNode day = calendar(shop, day());
        assertThat(day.get("appointments").size()).isEqualTo(2);
        day.get("appointments")
                .forEach(a -> assertThat(a.get("groupId").asString()).isEqualTo(groupId));

        // Two people with the same stylist at the same time: refused, and nothing is booked.
        Map<String, Object> clash = booking(shop, day(), "14:00", shop.abebe(), Map.of("name", "Hiwot"));
        clash.put(
                "guests",
                List.of(Map.of(
                        "customer", Map.of("name", "Meron"),
                        "items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.abebe())))));
        long before = active(shop);
        Call refused = call(post(shop.salon().path("/appointments")), clash, owner);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("code")).isEqualTo("SLOT_TAKEN");
        assertThat(active(shop)).isEqualTo(before);

        // Cancelling the group frees both times.
        Call cancelled = call(
                post(shop.salon().path("/appointments/" + booked.text("id") + "/cancel")),
                Map.of("scope", "GROUP", "notifyCustomer", false),
                owner);
        assertThat(cancelled.status()).as(String.valueOf(cancelled.body())).isEqualTo(200);
        assertThat(cancelled.body().get("cancelled").size()).isEqualTo(2);
        assertThat(calendar(shop, day()).get("appointments").size()).isZero();
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .contains("10:00");
    }

    @Test
    void aSeriesRepeatsWeeklySkipsTakenDatesAndTheRestCanBeCancelled() throws Exception {
        Shop shop = shop();
        String owner = shop.salon().owner().token();
        String phone = newPhone();
        Map<String, Object> weekly =
                booking(shop, day(), "10:00", shop.abebe(), Map.of("name", "Hana T.", "phone", phone));
        weekly.put("repeat", Map.of("everyWeeks", 1, "count", 4));
        Call series = call(post(shop.salon().path("/appointments")), weekly, owner);
        assertThat(series.status()).as(String.valueOf(series.body())).isEqualTo(201);
        assertThat(series.body().get("appointments").size()).isEqualTo(4);
        assertThat(Instant.parse(
                        series.body().get("appointments").get(3).get("start").asString()))
                .isEqualTo(at(day().plusDays(21), "10:00").toInstant());
        UUID hana = (UUID) system.fetchValue(
                "select customer_id from scheduling.appointments where id = ?::uuid",
                UUID.fromString(series.text("id")));
        JsonNode second = calendar(shop, day().plusDays(7)).get("appointments").get(0);
        assertThat(second.get("series").get("index").asInt()).isEqualTo(2);
        assertThat(second.get("series").get("count").asInt()).isEqualTo(4);
        assertThat(second.get("series").get("everyWeeks").asInt()).isEqualTo(1);
        // One confirmation for the whole series; every visit keeps its reminders.
        BookingMessagesTests.waitFor(
                "the series' messages",
                () -> system.fetchCount(
                                NOTIFICATIONS,
                                NOTIFICATIONS
                                        .TEMPLATE_CODE
                                        .eq("APPOINTMENT_CONFIRMED")
                                        .and(NOTIFICATIONS.RECIPIENT_CUSTOMER_ID.eq(hana)))
                        == 1);

        // A second weekly series at the same time with the other stylist, Sara, who is away one week.
        blockStaff(shop, shop.sara(), day().plusDays(14), "09:00", "18:00");
        Map<String, Object> other = booking(shop, day(), "10:00", shop.sara(), Map.of("name", "Dawit"));
        other.put("repeat", Map.of("everyWeeks", 1, "count", 3));
        Call taken = call(post(shop.salon().path("/appointments")), other, owner);
        assertThat(taken.status()).isEqualTo(409);
        other.put("skipTaken", true);
        Call skipping = call(post(shop.salon().path("/appointments")), other, owner);
        assertThat(skipping.status()).as(String.valueOf(skipping.body())).isEqualTo(201);
        assertThat(skipping.body().get("appointments").size()).isEqualTo(2);
        assertThat(Instant.parse(skipping.body().get("skipped").get(0).asString()))
                .isEqualTo(at(day().plusDays(14), "10:00").toInstant());

        // "This and the following" from the second visit cancels three, keeps the first.
        String secondId = series.body().get("appointments").get(1).get("id").asString();
        Call rest = call(
                post(shop.salon().path("/appointments/" + secondId + "/cancel")),
                Map.of("scope", "FOLLOWING", "reason", "Moving away"),
                owner);
        assertThat(rest.body().get("cancelled").size()).isEqualTo(3);
        assertThat(system.fetchValue(
                        "select status from scheduling.appointments where id = ?::uuid",
                        UUID.fromString(series.text("id"))))
                .isEqualTo("CONFIRMED");
        assertThat(system.fetchValue(
                        "select status from scheduling.appointment_series where id = ?::uuid",
                        UUID.fromString(series.text("seriesId"))))
                .isEqualTo("ENDED");
        // The customer hears once about the cancellation.
        BookingMessagesTests.waitFor(
                "one cancellation message",
                () -> system.fetchCount(
                                NOTIFICATIONS,
                                NOTIFICATIONS
                                        .TEMPLATE_CODE
                                        .eq("APPOINTMENT_CANCELLED")
                                        .and(NOTIFICATIONS.RECIPIENT_CUSTOMER_ID.eq(hana)))
                        == 1);
        // A visit that is over (or cancelled) cannot be cancelled again.
        assertThat(call(post(shop.salon().path("/appointments/" + secondId + "/cancel")), Map.of(), owner)
                        .status())
                .isEqualTo(409);
    }

    @Test
    void groupsAndSeriesDoNotMixAndProfessionalsStayInTheirColumn() throws Exception {
        Shop shop = shop();
        Map<String, Object> both = booking(shop, day(), "10:00", shop.abebe(), Map.of("name", "A"));
        both.put("repeat", Map.of("everyWeeks", 2, "count", 2));
        both.put(
                "guests",
                List.of(Map.of(
                        "customer", Map.of("name", "B"),
                        "items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.sara())))));
        assertThat(call(
                                post(shop.salon().path("/appointments")),
                                both,
                                shop.salon().owner().token())
                        .status())
                .isEqualTo(400);

        Person sara = newMember(shop.salon(), "PROFESSIONAL");
        system.execute(
                "update team.staff set member_id = (select id from tenant.members where org_id = ? and "
                        + "role_id in (select id from tenant.roles where code = 'PROFESSIONAL') limit 1) where id = ?",
                shop.salon().id(),
                UUID.fromString(shop.sara()));
        both.remove("repeat");
        // Sara books herself and a guest with Abebe: Abebe's column is not hers.
        Map<String, Object> mine = new HashMap<>(both);
        mine.put("items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.sara())));
        mine.put(
                "guests",
                List.of(Map.of(
                        "customer", Map.of("name", "B"),
                        "items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.abebe())))));
        assertThat(call(post(shop.salon().path("/appointments")), mine, sara.token())
                        .status())
                .isEqualTo(403);
    }
}
