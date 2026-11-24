package com.beavent.backend.scheduling;

import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.team.Tables.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The business calendar and front-desk status changes (plan step 2.5). */
class CalendarApiTests extends SchedulingTestSupport {

    Call booked(Shop shop, String hhmm, String staffId, Person person) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(shop.variant(), staffId)));
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

    Call calendar(Shop shop, Person who) throws Exception {
        return call(
                get(shop.salon().path("/calendar?locationId=" + shop.salon().location1() + "&from=" + day())),
                null,
                who.token());
    }

    Call status(Shop shop, String appointmentId, String status, Person who) throws Exception {
        return call(
                post(shop.salon().path("/appointments/" + appointmentId + "/status")),
                Map.of("status", status),
                who.token());
    }

    /** A professional (own calendar only) whose team profile is Sara's. */
    Person saraHerself(Shop shop) throws Exception {
        Person professional = newMember(shop.salon(), "PROFESSIONAL");
        String memberId = null;
        for (JsonNode m : call(
                        get(shop.salon().path("/members")),
                        null,
                        shop.salon().owner().token())
                .body()
                .get("items")) {
            if (m.get("roleCode").asString().equals("PROFESSIONAL")) {
                memberId = m.get("id").asString();
            }
        }
        system.update(STAFF)
                .set(STAFF.MEMBER_ID, UUID.fromString(memberId))
                .where(STAFF.ID.eq(UUID.fromString(shop.sara())))
                .execute();
        return professional;
    }

    @Test
    void theFrontDeskSeesEveryoneAndAProfessionalOnlyThemselves() throws Exception {
        Shop shop = shop();
        Person customer = newPerson();
        Call withAbebe = booked(shop, "10:00", shop.abebe(), customer);
        Call withSara = booked(shop, "11:00", shop.sara(), newPerson());

        Call all = calendar(shop, shop.salon().owner());
        assertThat(all.status()).as(String.valueOf(all.body())).isEqualTo(200);
        JsonNode body = all.body();
        assertThat(body.get("timezone").asString()).isEqualTo("Africa/Addis_Ababa");
        assertThat(body.get("staff").size()).isEqualTo(2);
        assertThat(body.get("staff").get(0).get("name").asString()).isEqualTo("Abebe");
        assertThat(body.get("staff").get(0).get("working").get(0).get("start").asString())
                .isEqualTo(at(day(), "09:00").toInstant().toString());
        assertThat(body.get("appointments").size()).isEqualTo(2);
        JsonNode first = body.get("appointments").get(0);
        assertThat(first.get("reference").asString()).isEqualTo(withAbebe.text("reference"));
        assertThat(first.get("customer").get("phone").asString()).isEqualTo(customer.phone());
        assertThat(first.get("items").get(0).get("staffId").asString()).isEqualTo(shop.abebe());
        assertThat(first.get("items").get(0).get("stages").get(0).get("kind").asString())
                .isEqualTo("SERVICE");

        // Sara's own view: her column and her appointment only, without phone numbers.
        Person sara = saraHerself(shop);
        Call own = calendar(shop, sara);
        assertThat(own.status()).as(String.valueOf(own.body())).isEqualTo(200);
        assertThat(own.body().get("staff").size()).isEqualTo(1);
        assertThat(own.body().get("staff").get(0).get("name").asString()).isEqualTo("Sara");
        assertThat(own.body().get("appointments").size()).isEqualTo(1);
        assertThat(own.body().get("appointments").get(0).get("id").asString()).isEqualTo(withSara.text("id"));
        assertThat(own.body().get("appointments").get(0).get("customer").has("phone"))
                .isFalse();

        // A cancelled visit leaves the calendar.
        call(post("/v1/me/appointments/" + withAbebe.text("id") + "/cancel"), Map.of(), customer.token());
        assertThat(calendar(shop, shop.salon().owner())
                        .body()
                        .get("appointments")
                        .size())
                .isEqualTo(1);

        // Another business's calendar, or a location the member may not use, is not there.
        Salon other = newSalon("Elsewhere");
        assertThat(call(
                                get(other.path(
                                        "/calendar?locationId=" + shop.salon().location1() + "&from=" + day())),
                                null,
                                other.owner().token())
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                get(shop.salon()
                                        .path("/calendar?locationId="
                                                + shop.salon().location1() + "&from=" + day() + "&to="
                                                + day().plusDays(9))),
                                null,
                                shop.salon().owner().token())
                        .status())
                .isEqualTo(400);
    }

    @Test
    void theFrontDeskMovesAVisitThroughArrivedStartedAndCompleted() throws Exception {
        Shop shop = shop();
        Person reception = newMember(shop.salon(), "RECEPTIONIST");
        Call visit = booked(shop, "10:00", shop.abebe(), newPerson());
        String id = visit.text("id");

        // Not before the start: no-show; nothing skips back.
        assertThat(status(shop, id, "NO_SHOW", reception).text("code")).isEqualTo("CONSTRAINT_VIOLATION");
        assertThat(status(shop, id, "ARRIVED", reception).text("status")).isEqualTo("ARRIVED");
        assertThat(status(shop, id, "ARRIVED", reception).text("code")).isEqualTo("IMMUTABLE");
        assertThat(status(shop, id, "IN_PROGRESS", reception).text("status")).isEqualTo("IN_PROGRESS");

        // Sara may change only her own appointments.
        Person sara = saraHerself(shop);
        assertThat(status(shop, id, "COMPLETED", sara).status()).isEqualTo(403);
        assertThat(status(shop, id, "COMPLETED", reception).text("status")).isEqualTo("COMPLETED");
        assertThat(system.select(APPOINTMENTS.STATUS)
                        .from(APPOINTMENTS)
                        .where(APPOINTMENTS.ID.eq(UUID.fromString(id)))
                        .fetchSingle(APPOINTMENTS.STATUS))
                .isEqualTo("COMPLETED");

        // A visit that should have started can be a no-show; Sara marks her own.
        Call hers = booked(shop, "15:00", shop.sara(), newPerson());
        system.update(APPOINTMENTS)
                .set(APPOINTMENTS.STARTS_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20))
                .set(APPOINTMENTS.ENDS_AT, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(40))
                .where(APPOINTMENTS.ID.eq(UUID.fromString(hers.text("id"))))
                .execute();
        assertThat(status(shop, hers.text("id"), "NO_SHOW", sara).text("status"))
                .isEqualTo("NO_SHOW");
    }
}
