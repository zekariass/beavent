package com.beavent.backend.scheduling;

import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.SCHEDULE_RESERVATIONS;
import static com.beavent.db.team.Tables.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The front desk (plan step 2.5): walk-ins and phone bookings, moving, blocking, finding customers. */
class FrontDeskApiTests extends SchedulingTestSupport {

    Call deskBook(
            Shop shop, String hhmm, String staffId, Map<String, Object> customer, Map<String, Object> extra, Person who)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", shop.salon().location1().toString());
        body.put("start", at(day(), hhmm).toInstant().toString());
        body.put("items", List.of(Map.of("variantId", shop.variant(), "staffId", staffId)));
        body.put("customer", customer);
        body.putAll(extra);
        return call(post(shop.salon().path("/appointments")), body, who.token());
    }

    JsonNode calendar(Shop shop) throws Exception {
        return call(
                        get(shop.salon()
                                .path("/calendar?locationId=" + shop.salon().location1() + "&from=" + day())),
                        null,
                        shop.salon().owner().token())
                .body();
    }

    String memberIdOf(Shop shop, String role) throws Exception {
        for (JsonNode m : call(
                        get(shop.salon().path("/members")),
                        null,
                        shop.salon().owner().token())
                .body()
                .get("items")) {
            if (m.get("roleCode").asString().equals(role)) return m.get("id").asString();
        }
        throw new AssertionError("no " + role);
    }

    @Test
    void theDeskBooksWalkInsAndCanBookAnyway() throws Exception {
        Shop shop = shop();
        Person desk = newMember(shop.salon(), "RECEPTIONIST");
        String phone = newPhone();
        Call walkIn = deskBook(
                shop,
                "10:00",
                shop.abebe(),
                Map.of("name", "Selam", "phone", phone),
                Map.of("source", "WALK_IN", "notifyCustomer", false),
                desk);
        assertThat(walkIn.status()).as(String.valueOf(walkIn.body())).isEqualTo(201);
        assertThat(walkIn.text("reference")).matches("[A-Z2-9]{6}");
        assertThat(system.fetchCount(CUSTOMERS, CUSTOMERS.PHONE_E164.eq(phone))).isEqualTo(1);

        // Abebe is busy at 10:00: refused, unless the desk books anyway (a deliberate overlap).
        Call busy = deskBook(shop, "10:00", shop.abebe(), Map.of("name", "Dawit"), Map.of(), desk);
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.text("code")).isEqualTo("SLOT_TAKEN");
        Call anyway = deskBook(shop, "10:00", shop.abebe(), Map.of("name", "Dawit"), Map.of("bookAnyway", true), desk);
        assertThat(anyway.status()).isEqualTo(201);
        assertThat(system.fetchCount(
                        SCHEDULE_RESERVATIONS,
                        SCHEDULE_RESERVATIONS.STAFF_ID.eq(UUID.fromString(shop.abebe())),
                        SCHEDULE_RESERVATIONS.ALLOW_OVERLAP.isTrue()))
                .isEqualTo(1);
        assertThat(calendar(shop).get("appointments").size()).isEqualTo(2);

        // The same phone later is the same customer; a phone booking by phone source; the walk-in got no message.
        Call again = deskBook(
                shop,
                "14:00",
                shop.sara(),
                Map.of("name", "Selam T.", "phone", phone),
                Map.of("source", "PHONE"),
                desk);
        assertThat(again.status()).isEqualTo(201);
        assertThat(system.fetchCount(CUSTOMERS, CUSTOMERS.PHONE_E164.eq(phone))).isEqualTo(1);
        assertThat(sms.messagesTo(phone)).noneMatch(m -> m.contains(walkIn.text("reference")));

        // Services hidden from online booking can be booked at the desk.
        Call hidden = service(
                shop.salon(),
                "Staff-only treatment",
                List.of(variant(30, 0, null, null)),
                Map.of("onlineBooking", false));
        system.execute(
                "insert into catalog.staff_services (org_id, staff_id, service_id) values (?, ?, ?)",
                shop.salon().id(),
                UUID.fromString(shop.sara()),
                UUID.fromString(hidden.text("id")));
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", shop.salon().location1().toString());
        body.put("start", at(day(), "16:00").toInstant().toString());
        body.put(
                "items",
                List.of(Map.of(
                        "variantId",
                        hidden.body().get("variants").get(0).get("id").asString(),
                        "staffId",
                        shop.sara())));
        body.put("customer", Map.of("name", "Hiwot"));
        assertThat(call(post(shop.salon().path("/appointments")), body, desk.token())
                        .status())
                .isEqualTo(201);
    }

    @Test
    void theDeskMovesAppointmentsBlocksTimeAndFindsCustomers() throws Exception {
        Shop shop = shop();
        Person desk = newMember(shop.salon(), "RECEPTIONIST");
        String phone = newPhone();
        Call visit =
                deskBook(shop, "10:00", shop.abebe(), Map.of("name", "Meron Tesfaye", "phone", phone), Map.of(), desk);
        String id = visit.text("id");

        // Move to 14:00 with Sara.
        Call moved = call(
                post(shop.salon().path("/appointments/" + id + "/move")),
                Map.of(
                        "start",
                        at(day(), "14:00").toInstant().toString(),
                        "staffId",
                        shop.sara(),
                        "notifyCustomer",
                        false),
                desk.token());
        assertThat(moved.status()).as(String.valueOf(moved.body())).isEqualTo(204);
        JsonNode item = calendar(shop).get("appointments").get(0).get("items").get(0);
        assertThat(item.get("staffId").asString()).isEqualTo(shop.sara());
        assertThat(item.get("start").asString())
                .isEqualTo(at(day(), "14:00").toInstant().toString());

        // A block for Abebe; refused over Sara's booking; removed again.
        Call block = call(
                post(shop.salon().path("/blocks")),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "staffId", shop.abebe(),
                        "start", at(day(), "12:00").toInstant().toString(),
                        "end", at(day(), "13:00").toInstant().toString(),
                        "reason", "BREAK",
                        "title", "Lunch"),
                desk.token());
        assertThat(block.status()).as(String.valueOf(block.body())).isEqualTo(201);
        assertThat(calendar(shop).get("blocks").get(0).get("title").asString()).isEqualTo("Lunch");
        assertThat(starts(search(shop, day(), item(shop.variant(), shop.abebe()))))
                .doesNotContain("12:00", "11:30");
        assertThat(call(
                                post(shop.salon().path("/blocks")),
                                Map.of(
                                        "locationId", shop.salon().location1().toString(),
                                        "staffId", shop.sara(),
                                        "start", at(day(), "14:30").toInstant().toString(),
                                        "end", at(day(), "15:00").toInstant().toString(),
                                        "reason", "MEETING"),
                                desk.token())
                        .text("code"))
                .isEqualTo("SLOT_TAKEN");
        assertThat(call(delete(shop.salon().path("/blocks/" + block.text("id"))), null, desk.token())
                        .status())
                .isEqualTo(204);
        assertThat(calendar(shop).get("blocks").size()).isZero();

        // Moving onto a busy time is refused unless booked anyway.
        deskBook(shop, "16:00", shop.abebe(), Map.of("name", "Other"), Map.of(), desk);
        Call clash = call(
                post(shop.salon().path("/appointments/" + id + "/move")),
                Map.of("start", at(day(), "16:00").toInstant().toString(), "staffId", shop.abebe()),
                desk.token());
        assertThat(clash.text("code")).isEqualTo("SLOT_TAKEN");
        assertThat(call(
                                post(shop.salon().path("/appointments/" + id + "/move")),
                                Map.of(
                                        "start",
                                        at(day(), "16:00").toInstant().toString(),
                                        "staffId",
                                        shop.abebe(),
                                        "bookAnyway",
                                        true),
                                desk.token())
                        .status())
                .isEqualTo(204);

        // Finding the customer by name or by the digits of a local phone number.
        JsonNode byName = call(get(shop.salon().path("/customers?q=meron")), null, desk.token())
                .body();
        assertThat(byName.get("items").get(0).get("name").asString()).isEqualTo("Meron Tesfaye");
        assertThat(byName.get("items").get(0).get("phone").asString()).isEqualTo(phone);
        String local = "0" + phone.substring(4);
        assertThat(call(get(shop.salon().path("/customers?q=" + local)), null, desk.token())
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
    }

    @Test
    void aProfessionalChangesOnlyTheirOwnColumn() throws Exception {
        Shop shop = shop();
        Person sara = newMember(shop.salon(), "PROFESSIONAL");
        system.update(STAFF)
                .set(STAFF.MEMBER_ID, UUID.fromString(memberIdOf(shop, "PROFESSIONAL")))
                .where(STAFF.ID.eq(UUID.fromString(shop.sara())))
                .execute();
        Map<String, Object> mine = Map.of(
                "locationId", shop.salon().location1().toString(),
                "staffId", shop.sara(),
                "start", at(day(), "12:00").toInstant().toString(),
                "end", at(day(), "12:30").toInstant().toString(),
                "reason", "PERSONAL");
        assertThat(call(post(shop.salon().path("/blocks")), mine, sara.token()).status())
                .isEqualTo(201);
        Map<String, Object> his = new HashMap<>(mine);
        his.put("staffId", shop.abebe());
        assertThat(call(post(shop.salon().path("/blocks")), his, sara.token()).status())
                .isEqualTo(403);
        assertThat(deskBook(shop, "15:00", shop.sara(), Map.of("name", "Walk-in"), Map.of(), sara)
                        .status())
                .isEqualTo(201);
        assertThat(deskBook(shop, "15:00", shop.abebe(), Map.of("name", "Walk-in"), Map.of(), sara)
                        .status())
                .isEqualTo(403);
        // A professional sees customers but not their phone numbers.
        assertThat(call(get(shop.salon().path("/customers?q=walk")), null, sara.token())
                        .body()
                        .get("items")
                        .get(0)
                        .has("phone"))
                .isFalse();
    }

    @Test
    void walkInsWithAPhoneAreToldWhenTheDeskWantsThem() throws Exception {
        Shop shop = shop();
        String phone = newPhone();
        Call told = deskBook(
                shop,
                "11:00",
                shop.abebe(),
                Map.of("name", "Liya", "phone", phone),
                Map.of("source", "PHONE"),
                shop.salon().owner());
        assertThat(told.status()).isEqualTo(201);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (sms.messagesTo(phone).stream().noneMatch(m -> m.contains(told.text("reference")))) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("no confirmation");
            Thread.sleep(100);
        }
    }
}
