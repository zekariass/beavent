package com.beavent.backend.crm;

import static com.beavent.db.crm.Tables.CUSTOMERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.crm.internal.ClientMerge;
import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Clients (plan step 2.6, P-13): the list, a client's page, notes and alerts, tags, merging, importing, blocking. */
class ClientsApiTests extends SchedulingTestSupport {

    String addClient(Shop shop, String name, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (phone != null) {
            body.put("phone", phone);
        }
        Call created = call(
                post(shop.salon().path("/customers")),
                body,
                shop.salon().owner().token());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return created.text("id");
    }

    Call deskBook(Shop shop, LocalDate date, String hhmm, String customerId) throws Exception {
        return call(
                post(shop.salon().path("/appointments")),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "start", at(date, hhmm).toInstant().toString(),
                        "items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.abebe())),
                        "customer", Map.of("customerId", customerId),
                        "notifyCustomer", false,
                        "bookAnyway", true),
                shop.salon().owner().token());
    }

    JsonNode client(Shop shop, String id, String token) throws Exception {
        Call got = call(get(shop.salon().path("/customers/" + id)), null, token);
        assertThat(got.status()).as(String.valueOf(got.body())).isEqualTo(200);
        return got.body();
    }

    static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("name").asString()));
        return out;
    }

    @Test
    void theListGoesByNameAPageAtATimeAndSearchFindsNamesAndPhoneDigits() throws Exception {
        Shop shop = shop();
        String token = shop.salon().owner().token();
        String phone = newPhone();
        addClient(shop, "Tigist Alemu", phone);
        addClient(shop, "abebech Kebede", null);
        addClient(shop, "Meron Haile", "0" + newPhone().substring(4));
        // Typed local numbers become international ones; the same number twice is refused.
        assertThat(system.fetchCount(CUSTOMERS, CUSTOMERS.PHONE_E164.startsWith("+2519")))
                .isGreaterThanOrEqualTo(2);
        Call twice = call(post(shop.salon().path("/customers")), Map.of("name", "Tigist A.", "phone", phone), token);
        assertThat(twice.status()).isEqualTo(409);
        Call bad = call(post(shop.salon().path("/customers")), Map.of("name", "X", "phone", "12ab"), token);
        assertThat(bad.status()).isEqualTo(400);

        JsonNode first =
                call(get(shop.salon().path("/customers?limit=2")), null, token).body();
        assertThat(names(first)).containsExactly("abebech Kebede", "Meron Haile");
        JsonNode second = call(
                        get(shop.salon()
                                .path("/customers?limit=2&cursor="
                                        + first.get("nextCursor").asString())),
                        null,
                        token)
                .body();
        assertThat(names(second)).containsExactly("Tigist Alemu");
        assertThat(second.has("nextCursor")).isFalse();

        assertThat(names(call(get(shop.salon().path("/customers?q=tigist")), null, token)
                        .body()))
                .containsExactly("Tigist Alemu");
        String local = "0" + phone.substring(4);
        JsonNode byPhone = call(get(shop.salon().path("/customers?q=" + local.substring(0, 7))), null, token)
                .body();
        assertThat(names(byPhone)).contains("Tigist Alemu");
        assertThat(byPhone.get("items").get(0).get("phone").asString()).startsWith("+251");

        // A professional sees clients but not their phone numbers, and cannot add any.
        Person sara = newMember(shop.salon(), "PROFESSIONAL");
        JsonNode seen =
                call(get(shop.salon().path("/customers")), null, sara.token()).body();
        assertThat(seen.get("items").get(0).has("phone")).isFalse();
        assertThat(call(post(shop.salon().path("/customers")), Map.of("name", "New"), sara.token())
                        .status())
                .isEqualTo(403);
    }

    @Test
    void aClientsPageHasNotesAlertsTagsAndVisitsAndAlertsShowOnTheCalendar() throws Exception {
        Shop shop = shop();
        String token = shop.salon().owner().token();
        String id = addClient(shop, "Selam Tesfaye", newPhone());
        assertThat(deskBook(shop, day(), "10:00", id).status()).isEqualTo(201);
        // A visit from last week (booked, then moved into the past and completed).
        Call past = deskBook(shop, day(), "13:00", id);
        assertThat(past.status()).as(String.valueOf(past.body())).isEqualTo(201);
        system.execute(
                "update scheduling.appointments set status = 'COMPLETED', starts_at = starts_at - interval '9 days', "
                        + "ends_at = ends_at - interval '9 days' where id = ?::uuid",
                past.text("id"));

        Call allergy = call(
                post(shop.salon().path("/customers/" + id + "/notes")),
                Map.of("kind", "ALLERGY", "body", "Allergic to ammonia hair dye", "pinned", true),
                token);
        assertThat(allergy.status()).as(String.valueOf(allergy.body())).isEqualTo(201);
        call(post(shop.salon().path("/customers/" + id + "/notes")), Map.of("body", "Prefers Abebe"), token);
        Call tags = call(
                put(shop.salon().path("/customers/" + id + "/tags")),
                Map.of("tags", List.of("VIP", "Braids", "vip")),
                token);
        assertThat(tags.body().get("tags").toString()).isEqualTo("[\"Braids\",\"VIP\"]");

        JsonNode page = client(shop, id, token);
        assertThat(page.get("notes").get(0).get("kind").asString()).isEqualTo("ALLERGY");
        assertThat(page.get("notes").size()).isEqualTo(2);
        assertThat(page.get("upcoming").size()).isEqualTo(1);
        assertThat(page.get("past").size()).isEqualTo(1);
        assertThat(page.get("stats").get("visits").asInt()).isEqualTo(1);
        assertThat(page.get("upcoming")
                        .get(0)
                        .get("items")
                        .get(0)
                        .get("staffName")
                        .asString())
                .isEqualTo("Abebe");

        // The list shows the tags and that there is an alert; tags filter the list.
        JsonNode listed = call(get(shop.salon().path("/customers?q=selam")), null, token)
                .body()
                .get("items")
                .get(0);
        assertThat(listed.get("alerts").asInt()).isEqualTo(1);
        assertThat(listed.get("visits").asInt()).isEqualTo(1);
        JsonNode allTags =
                call(get(shop.salon().path("/customer-tags")), null, token).body();
        String vip = null;
        for (JsonNode t : allTags.get("items")) {
            if (t.get("name").asString().equals("VIP")) vip = t.get("id").asString();
        }
        assertThat(names(call(get(shop.salon().path("/customers?tagId=" + vip)), null, token)
                        .body()))
                .containsExactly("Selam Tesfaye");

        // The alert travels with the client onto the calendar.
        JsonNode calendar = call(
                        get(shop.salon()
                                .path("/calendar?locationId=" + shop.salon().location1() + "&from=" + day())),
                        null,
                        token)
                .body();
        JsonNode customer = calendar.get("appointments").get(0).get("customer");
        assertThat(customer.get("alerts").get(0).get("body").asString()).isEqualTo("Allergic to ammonia hair dye");

        // Editing: a new name, and blocking with a reason.
        Call edited = call(
                patch(shop.salon().path("/customers/" + id)),
                Map.of("name", "Selam T.", "blocked", true, "blockReason", "Two no-shows"),
                token);
        assertThat(edited.status()).as(String.valueOf(edited.body())).isEqualTo(200);
        assertThat(edited.body().get("blocked").asBoolean()).isTrue();

        // Notes can be removed.
        String noteId = page.get("notes").get(1).get("id").asString();
        assertThat(call(delete(shop.salon().path("/customers/" + id + "/notes/" + noteId)), null, token)
                        .status())
                .isEqualTo(204);
        assertThat(client(shop, id, token).get("notes").size()).isEqualTo(1);
    }

    @Test
    void aBlockedClientCannotBookOnline() throws Exception {
        Shop shop = shop();
        Person person = newPerson();
        String id = addClient(shop, "Online person", person.phone());
        call(
                patch(shop.salon().path("/customers/" + id)),
                Map.of("blocked", true),
                shop.salon().owner().token());
        Call hold = call(
                post("/v1/public/locations/" + shop.salon().location1() + "/holds"),
                Map.of(
                        "items",
                        List.of(item(shop.variant(), shop.abebe())),
                        "start",
                        at(day(), "15:00").toInstant().toString()),
                null);
        assertThat(hold.status()).isEqualTo(201);
        Call booked = call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", hold.text("holdId"),
                        "holderKey", hold.text("holderKey")),
                person.token());
        assertThat(booked.status()).isEqualTo(403);
        // The team can still book them.
        assertThat(deskBook(shop, day(), "15:00", id).status()).isEqualTo(201);
    }

    @Test
    void mergingMovesEverythingToTheKeptClientAndCannotJoinTwoAccounts() throws Exception {
        Shop shop = shop();
        String token = shop.salon().owner().token();
        String phone = newPhone();
        String keep = addClient(shop, "Hiwot Bekele", null);
        String duplicate = addClient(shop, "Hiwot B", phone);
        deskBook(shop, day(), "09:00", duplicate);
        call(post(shop.salon().path("/customers/" + duplicate + "/notes")), Map.of("body", "Likes tea"), token);
        call(put(shop.salon().path("/customers/" + duplicate + "/tags")), Map.of("tags", List.of("VIP")), token);
        call(put(shop.salon().path("/customers/" + keep + "/tags")), Map.of("tags", List.of("VIP", "New")), token);

        Call merged =
                call(post(shop.salon().path("/customers/" + keep + "/merge")), Map.of("duplicateId", duplicate), token);
        assertThat(merged.status()).as(String.valueOf(merged.body())).isEqualTo(200);
        JsonNode kept = merged.body();
        assertThat(kept.get("name").asString()).isEqualTo("Hiwot Bekele");
        assertThat(kept.get("phone").asString()).isEqualTo(phone);
        assertThat(kept.get("upcoming").size()).isEqualTo(1);
        assertThat(kept.get("notes").size()).isEqualTo(1);
        assertThat(kept.get("tags").toString()).isEqualTo("[\"New\",\"VIP\"]");

        // The old link still opens the kept client; the duplicate is out of the list and cannot be merged again.
        assertThat(client(shop, duplicate, token).get("id").asString()).isEqualTo(keep);
        assertThat(names(call(get(shop.salon().path("/customers?q=hiwot")), null, token)
                        .body()))
                .containsExactly("Hiwot Bekele");
        assertThat(call(
                                post(shop.salon().path("/customers/" + keep + "/merge")),
                                Map.of("duplicateId", duplicate),
                                token)
                        .status())
                .isEqualTo(409);

        // Two records with their own Beavent accounts are two people.
        String a = addClient(shop, "Account A", null);
        String b = addClient(shop, "Account B", null);
        system.execute(
                "update crm.customers set user_id = (select id from iam.users order by created_at limit 1) "
                        + "where id = ?::uuid",
                a);
        system.execute(
                "update crm.customers set user_id = (select id from iam.users order by created_at desc limit 1) "
                        + "where id = ?::uuid",
                b);
        assertThat(call(post(shop.salon().path("/customers/" + a + "/merge")), Map.of("duplicateId", b), token)
                        .status())
                .isEqualTo(422);
    }

    /** A new table that names a client must be added to the merge (moved, special, or not mergeable yet). */
    @Test
    void everyReferenceToAClientIsHandledByTheMerge() {
        Set<String> inDatabase = new HashSet<>(system.fetch("select n.nspname || '.' || c.relname || '.' || a.attname "
                        + "from pg_constraint k "
                        + "join pg_class c on c.oid = k.conrelid "
                        + "join pg_namespace n on n.oid = c.relnamespace "
                        + "join pg_attribute a on a.attrelid = k.conrelid and a.attnum = any (k.conkey) "
                        + "where k.contype = 'f' and k.confrelid = 'crm.customers'::regclass "
                        + "and a.attname <> 'org_id'")
                .getValues(0, String.class));
        Set<String> handled = new HashSet<>();
        Stream.of(ClientMerge.MOVED, ClientMerge.SPECIAL, ClientMerge.NOT_YET_MERGEABLE)
                .flatMap(List::stream)
                .forEach(r -> handled.add(r.table() + "." + r.column()));
        assertThat(handled).isEqualTo(inDatabase);
    }

    @Test
    void importingShowsAPreviewThenAddsTheNewClients() throws Exception {
        Shop shop = shop();
        String token = shop.salon().owner().token();
        String known = newPhone();
        addClient(shop, "Already here", known);
        String fresh = newPhone();
        String csv = "First Name;Last Name;Mobile Number;Email\n"
                + "Dawit;Mekonnen;0" + fresh.substring(4) + ";dawit@example.com\n"
                + "\"Liya; the second\";Tadesse;;\n"
                + "Old;Friend;" + known + ";\n"
                + ";;0911;\n";
        Call preview = call(post(shop.salon().path("/customers/import")), Map.of("csv", csv), token);
        assertThat(preview.status()).as(String.valueOf(preview.body())).isEqualTo(200);
        JsonNode result = preview.body();
        assertThat(result.get("saved").asBoolean()).isFalse();
        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("existing").asInt()).isEqualTo(1);
        assertThat(result.get("errors").asInt()).isEqualTo(1);
        assertThat(result.get("rows").get(0).get("phone").asString()).isEqualTo(fresh);
        assertThat(result.get("rows").get(1).get("name").asString()).isEqualTo("Liya; the second Tadesse");
        assertThat(result.get("rows").get(3).get("line").asInt()).isEqualTo(5);
        assertThat(result.get("rows").get(3).get("problem").asString()).isEqualTo("NAME");
        assertThat(system.fetchCount(CUSTOMERS, CUSTOMERS.PHONE_E164.eq(fresh))).isZero();

        Call saved = call(post(shop.salon().path("/customers/import")), Map.of("csv", csv, "save", true), token);
        assertThat(saved.body().get("saved").asBoolean()).isTrue();
        assertThat(system.fetchCount(CUSTOMERS, CUSTOMERS.PHONE_E164.eq(fresh).and(CUSTOMERS.SOURCE.eq("IMPORT"))))
                .isEqualTo(1);
        assertThat(system.fetchCount(CUSTOMERS, CUSTOMERS.PHONE_E164.eq(known))).isEqualTo(1);

        Call noHeader = call(post(shop.salon().path("/customers/import")), Map.of("csv", "a,b\n1,2"), token);
        assertThat(noHeader.status()).isEqualTo(400);
    }
}
