package com.beavent.backend.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Forms and patch tests (plan step 2.6, slice 2). */
class FormsApiTests extends SchedulingTestSupport {

    static Map<String, Object> consultation(String serviceId, String frequency) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", Map.of("en", "Hair consultation", "am", "የጸጉር ምክክር"));
        form.put("purpose", "CONSULTATION");
        form.put("intro", Map.of("en", "Tell us about your hair."));
        form.put(
                "fields",
                List.of(
                        Map.of(
                                "id",
                                "allergies",
                                "kind",
                                "YES_NO",
                                "label",
                                Map.of("en", "Any allergies?"),
                                "required",
                                true),
                        Map.of(
                                "id",
                                "details",
                                "kind",
                                "LONG_TEXT",
                                "label",
                                Map.of("en", "Tell us more"),
                                "required",
                                false),
                        Map.of(
                                "id",
                                "type",
                                "kind",
                                "SINGLE_CHOICE",
                                "label",
                                Map.of("en", "Hair type"),
                                "required",
                                true,
                                "options",
                                List.of(
                                        Map.of("id", "straight", "label", Map.of("en", "Straight")),
                                        Map.of("id", "curly", "label", Map.of("en", "Curly")))),
                        Map.of(
                                "id",
                                "agree",
                                "kind",
                                "CHECKBOX",
                                "label",
                                Map.of("en", "I have told you everything relevant."),
                                "required",
                                true)));
        form.put("signature", true);
        form.put("serviceIds", List.of(serviceId));
        form.put("frequency", frequency);
        return form;
    }

    /** A person signs in, holds a time and books it online; returns the appointment id. */
    String bookOnline(Shop shop, Person person, String variant, String staffId, String hhmm) throws Exception {
        Call hold = call(
                post("/v1/public/locations/" + shop.salon().location1() + "/holds"),
                Map.of(
                        "items",
                        List.of(item(variant, staffId)),
                        "start",
                        at(day(), hhmm).toInstant().toString()),
                null);
        assertThat(hold.status()).as(String.valueOf(hold.body())).isEqualTo(201);
        Call booked = call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", hold.text("holdId"),
                        "holderKey", hold.text("holderKey")),
                person.token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isIn(201, 422);
        return booked.status() == 201 ? booked.text("id") : "PATCH:" + booked.text("code");
    }

    JsonNode calendarAppointment(Shop shop, String appointmentId) throws Exception {
        for (JsonNode a : call(
                        get(shop.salon()
                                .path("/calendar?locationId=" + shop.salon().location1() + "&from=" + day())),
                        null,
                        shop.salon().owner().token())
                .body()
                .get("appointments")) {
            if (a.get("id").asString().equals(appointmentId)) return a;
        }
        throw new AssertionError("not on the calendar");
    }

    @Test
    void aClientFillsInTheFormBeforeTheVisitAndTheTeamSeesTheAnswers() throws Exception {
        Shop shop = shop();
        String owner = shop.salon().owner().token();
        Call created = call(post(shop.salon().path("/forms")), consultation(shop.haircut(), "EVERY_TIME"), owner);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        String formId = created.text("id");
        assertThat(created.body().get("version").asInt()).isEqualTo(1);

        Person person = newPerson();
        String appointment = bookOnline(shop, person, shop.variant(), shop.abebe(), "10:00");
        JsonNode onCalendar =
                calendarAppointment(shop, appointment).get("forms").get(0);
        assertThat(onCalendar.get("done").asBoolean()).isFalse();
        assertThat(onCalendar.get("title").get("en").asString()).isEqualTo("Hair consultation");

        // The client sees the form on the appointment, with its questions.
        String mine = "/v1/me/appointments/" + appointment + "/forms";
        JsonNode due = call(get(mine), null, person.token()).body();
        assertThat(due.get("items").get(0).get("formId").asString()).isEqualTo(formId);
        JsonNode questions =
                call(get(mine + "/" + formId), null, person.token()).body();
        assertThat(questions.get("fields").size()).isEqualTo(4);
        assertThat(questions.get("signature").asBoolean()).isTrue();

        // Missing answers, a wrong option and no signature are refused.
        Map<String, Object> answers = new HashMap<>(Map.of("allergies", true, "type", "curly", "agree", true));
        assertThat(call(post(mine + "/" + formId), Map.of("answers", Map.of("allergies", true)), person.token())
                        .status())
                .isEqualTo(400);
        assertThat(call(
                                post(mine + "/" + formId),
                                Map.of(
                                        "answers",
                                        Map.of("allergies", true, "type", "wavy", "agree", true),
                                        "signature",
                                        "Meron"),
                                person.token())
                        .status())
                .isEqualTo(400);
        assertThat(call(post(mine + "/" + formId), Map.of("answers", answers), person.token())
                        .status())
                .isEqualTo(400);
        answers.put("details", "Sensitive scalp");
        Call sent = call(
                post(mine + "/" + formId), Map.of("answers", answers, "signature", "Meron Tesfaye"), person.token());
        assertThat(sent.status()).as(String.valueOf(sent.body())).isEqualTo(201);
        assertThat(call(get(mine), null, person.token())
                        .body()
                        .get("items")
                        .get(0)
                        .get("done")
                        .asBoolean())
                .isTrue();
        assertThat(calendarAppointment(shop, appointment)
                        .get("forms")
                        .get(0)
                        .get("done")
                        .asBoolean())
                .isTrue();

        // Someone else cannot see or answer it.
        Person stranger = newPerson();
        assertThat(call(get(mine), null, stranger.token()).status()).isEqualTo(404);

        // The team sees the answers on the client, with the questions they answered.
        String customerId = call(
                        get(shop.salon().path("/customers?q=" + person.phone().substring(5))), null, owner)
                .body()
                .get("items")
                .get(0)
                .get("id")
                .asString();
        JsonNode client = call(get(shop.salon().path("/customers/" + customerId)), null, owner)
                .body();
        String submission = client.get("forms").get(0).get("id").asString();
        JsonNode answered = call(
                        get(shop.salon().path("/customers/" + customerId + "/form-submissions/" + submission)),
                        null,
                        owner)
                .body();
        assertThat(answered.get("answers").get("details").asString()).isEqualTo("Sensitive scalp");
        assertThat(answered.get("signature").get("name").asString()).isEqualTo("Meron Tesfaye");

        // Changing the questions makes version 2; the earlier answers keep version 1's questions.
        Map<String, Object> changed = consultation(shop.haircut(), "EVERY_TIME");
        changed.put(
                "fields",
                List.of(Map.of(
                        "id", "colour", "kind", "TEXT", "label", Map.of("en", "Last colour?"), "required", false)));
        Call saved = call(put(shop.salon().path("/forms/" + formId)), changed, owner);
        assertThat(saved.body().get("version").asInt()).isEqualTo(2);
        assertThat(call(put(shop.salon().path("/forms/" + formId)), changed, owner)
                        .body()
                        .get("version")
                        .asInt())
                .isEqualTo(2);
        assertThat(call(
                                get(shop.salon().path("/customers/" + customerId + "/form-submissions/" + submission)),
                                null,
                                owner)
                        .body()
                        .get("fields")
                        .size())
                .isEqualTo(4);

        // An archived form is no longer asked for.
        assertThat(call(delete(shop.salon().path("/forms/" + formId)), null, owner)
                        .status())
                .isEqualTo(204);
        assertThat(call(get(mine), null, person.token()).body().get("items").size())
                .isZero();
    }

    @Test
    void aFormAskedOnceCountsForLaterVisitsAndOneAskedEveryTimeDoesNot() throws Exception {
        Shop shop = shop();
        String owner = shop.salon().owner().token();
        Map<String, Object> once = consultation(shop.haircut(), "ONCE");
        once.put("signature", false);
        String formId = call(post(shop.salon().path("/forms")), once, owner).text("id");
        Person person = newPerson();
        String first = bookOnline(shop, person, shop.variant(), shop.abebe(), "10:00");
        String mine = "/v1/me/appointments/" + first + "/forms/" + formId;
        assertThat(call(
                                post(mine),
                                Map.of("answers", Map.of("allergies", false, "type", "straight", "agree", true)),
                                person.token())
                        .status())
                .isEqualTo(201);
        String second = bookOnline(shop, person, shop.variant(), shop.abebe(), "14:00");
        assertThat(calendarAppointment(shop, second)
                        .get("forms")
                        .get(0)
                        .get("done")
                        .asBoolean())
                .isTrue();

        Map<String, Object> every = consultation(shop.haircut(), "EVERY_TIME");
        every.put("serviceIds", List.of(shop.haircut()));
        call(put(shop.salon().path("/forms/" + formId)), every, owner);
        assertThat(calendarAppointment(shop, second)
                        .get("forms")
                        .get(0)
                        .get("done")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void badFormsAreRefused() throws Exception {
        Shop shop = shop();
        String owner = shop.salon().owner().token();
        Map<String, Object> form = consultation(shop.haircut(), "EVERY_TIME");
        form.put(
                "fields",
                List.of(Map.of(
                        "id",
                        "x",
                        "kind",
                        "SINGLE_CHOICE",
                        "label",
                        Map.of("en", "Pick"),
                        "required",
                        true,
                        "options",
                        List.of(Map.of("id", "a", "label", Map.of("en", "A"))))));
        assertThat(call(post(shop.salon().path("/forms")), form, owner).status())
                .isEqualTo(400);
        form.put("fields", List.of(Map.of("id", "x", "kind", "TEXT", "label", Map.of("en", ""), "required", true)));
        assertThat(call(post(shop.salon().path("/forms")), form, owner).status())
                .isEqualTo(400);
        Map<String, Object> days = consultation(shop.haircut(), "EVERY_N_DAYS");
        assertThat(call(post(shop.salon().path("/forms")), days, owner).status())
                .isEqualTo(400);
        days.put("frequencyDays", 90);
        assertThat(call(post(shop.salon().path("/forms")), days, owner).status())
                .isEqualTo(201);
        // A professional reads forms but cannot change them.
        Person sara = newMember(shop.salon(), "PROFESSIONAL");
        assertThat(call(get(shop.salon().path("/forms")), null, sara.token())
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(call(post(shop.salon().path("/forms")), days, sara.token()).status())
                .isEqualTo(403);
    }

    @Test
    void aServiceThatNeedsAPatchTestIsBookedOnlineOnlyWithAValidPass() throws Exception {
        Shop shop = shop();
        String owner = shop.salon().owner().token();
        Call colour = service(
                shop.salon(),
                "Colour",
                List.of(variant(60, 0, null, null)),
                Map.of("requiresPatchTest", true, "patchTestValidityDays", 30));
        String colourVariant = colour.body().get("variants").get(0).get("id").asString();
        system.execute(
                "insert into catalog.staff_services (org_id, staff_id, service_id) values (?, ?, ?)",
                shop.salon().id(),
                UUID.fromString(shop.abebe()),
                UUID.fromString(colour.text("id")));

        Person person = newPerson();
        assertThat(bookOnline(shop, person, colourVariant, shop.abebe(), "11:00"))
                .isEqualTo("PATCH:PATCH_TEST_REQUIRED");

        // The client is on file now (from the haircut); the team records a failed test, then a pass.
        bookOnline(shop, person, shop.variant(), shop.abebe(), "09:00");
        String customerId = call(
                        get(shop.salon().path("/customers?q=" + person.phone().substring(5))), null, owner)
                .body()
                .get("items")
                .get(0)
                .get("id")
                .asString();
        String tests = shop.salon().path("/customers/" + customerId + "/patch-tests");
        assertThat(call(post(tests), Map.of("serviceId", colour.text("id"), "result", "FAIL"), owner)
                        .status())
                .isEqualTo(201);
        assertThat(bookOnline(shop, person, colourVariant, shop.abebe(), "12:00"))
                .isEqualTo("PATCH:PATCH_TEST_REQUIRED");
        Call pass = call(
                post(tests),
                Map.of("serviceId", colour.text("id"), "result", "PASS", "notes", "Behind the ear"),
                owner);
        assertThat(pass.status()).isEqualTo(201);
        assertThat(bookOnline(shop, person, colourVariant, shop.abebe(), "13:00"))
                .doesNotStartWith("PATCH");

        JsonNode client = call(get(shop.salon().path("/customers/" + customerId)), null, owner)
                .body();
        assertThat(client.get("patchTests").size()).isEqualTo(2);
        assertThat(client.get("patchTests").get(0).has("validUntil")).isTrue();

        // A test recorded by mistake can be removed; the front desk can book without one.
        assertThat(call(delete(tests + "/" + pass.text("id")), null, owner).status())
                .isEqualTo(204);
        Call desk = call(
                post(shop.salon().path("/appointments")),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "start", at(day(), "16:00").toInstant().toString(),
                        "items", List.of(Map.of("variantId", colourVariant, "staffId", shop.abebe())),
                        "customer", Map.of("customerId", customerId),
                        "notifyCustomer", false),
                owner);
        assertThat(desk.status()).as(String.valueOf(desk.body())).isEqualTo(201);
    }
}
