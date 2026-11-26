package com.beavent.backend.discovery;

import static com.beavent.db.iam.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Saved places, followed businesses and brand pages (plan step 8.2). */
class SavedAndFollowsApiTests extends SchedulingTestSupport {

    @Autowired
    Listings listings;

    /** A business with a service, approved for the marketplace and in search. */
    Salon listedSalon(String name) throws Exception {
        Salon salon = newSalon(name);
        Map<String, Object> extra = new HashMap<>();
        extra.put("categoryId", category("SERVICE", "BRAIDS"));
        service(salon, "Box braids", List.of(variant(90, 0, null, null)), extra);
        system.execute("UPDATE tenant.organizations SET marketplace_status = 'LISTED' WHERE id = ?", salon.id());
        listings.refreshOrganization(salon.id(), Instant.now());
        return salon;
    }

    String slug(Salon salon) {
        return system.resultQuery("SELECT slug::text FROM tenant.organizations WHERE id = ?", salon.id())
                .fetchOne(0, String.class);
    }

    JsonNode savedList(Person person) throws Exception {
        Call c = call(get("/v1/me/saved"), null, person.token());
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        return c.body().get("items");
    }

    int saveStatus(Person person, UUID location) throws Exception {
        return call(put("/v1/me/saved/locations/" + location), null, person.token())
                .status();
    }

    // --- saved ------------------------------------------------------------------------------------------------

    @Test
    void aPersonSavesAndUnsavesPlacesAndSeesOnlyTheirOwn() throws Exception {
        String name = SearchApiTests.marker() + " Saved";
        Salon salon = listedSalon(name);
        Person ana = newPerson();
        Person ben = newPerson();

        assertThat(savedList(ana)).isEmpty();
        assertThat(saveStatus(ana, salon.location1())).isEqualTo(204);
        assertThat(saveStatus(ana, salon.location1()))
                .as("saving again changes nothing")
                .isEqualTo(204);

        JsonNode items = savedList(ana);
        assertThat(items).hasSize(1);
        JsonNode place = items.get(0).get("place");
        assertThat(place.get("title").asString()).startsWith(name);
        assertThat(place.get("businessSlug").asString()).isEqualTo(slug(salon));
        assertThat(place.get("locationSlug").asString()).isNotBlank();
        assertThat(place.get("locationId").asString())
                .isEqualTo(salon.location1().toString());
        assertThat(items.get(0).has("savedAt")).isTrue();
        assertThat(savedList(ben)).as("one person never sees another's").isEmpty();

        assertThat(call(delete("/v1/me/saved/locations/" + salon.location1()), null, ana.token())
                        .status())
                .isEqualTo(204);
        assertThat(savedList(ana)).isEmpty();
        assertThat(call(delete("/v1/me/saved/locations/" + salon.location1()), null, ana.token())
                        .status())
                .as("nothing to take off")
                .isEqualTo(204);
    }

    @Test
    void onlyListedPlacesCanBeSavedAndOneTakenOffDropsOutUntilListedAgain() throws Exception {
        Person ana = newPerson();
        Salon hidden = newSalon(SearchApiTests.marker() + " Hidden");
        assertThat(saveStatus(ana, hidden.location1())).isEqualTo(404);
        assertThat(saveStatus(ana, UUID.randomUUID())).isEqualTo(404);
        assertThat(call(get("/v1/me/saved"), null, null).status()).isEqualTo(401);

        Salon salon = listedSalon(SearchApiTests.marker() + " Listed");
        assertThat(saveStatus(ana, salon.location1())).isEqualTo(204);
        system.execute("UPDATE tenant.organizations SET marketplace_status = 'DELISTED' WHERE id = ?", salon.id());
        listings.refreshOrganization(salon.id(), Instant.now());
        assertThat(savedList(ana)).as("taken off the marketplace").isEmpty();

        system.execute("UPDATE tenant.organizations SET marketplace_status = 'LISTED' WHERE id = ?", salon.id());
        listings.refreshOrganization(salon.id(), Instant.now());
        assertThat(savedList(ana)).as("back when listed again").hasSize(1);
    }

    // --- follows and brand pages ------------------------------------------------------------------------------

    @Test
    void aPersonFollowsAndUnfollowsABusinessAndTheBrandPageCountsFollowers() throws Exception {
        Salon salon = listedSalon(SearchApiTests.marker() + " Brand");
        String slug = slug(salon);
        Person ana = newPerson();
        Person ben = newPerson();

        Call brand = call(get("/v1/public/businesses/" + slug), null, null);
        assertThat(brand.status()).as(String.valueOf(brand.body())).isEqualTo(200);
        assertThat(brand.body().get("name").asString()).contains("Brand");
        assertThat(brand.body().get("places").asInt()).isEqualTo(2); // both locations are listed
        assertThat(brand.body().get("followers").asInt()).isZero();

        Call followed = call(put("/v1/me/follows/" + slug), Map.of(), ana.token());
        assertThat(followed.status()).as(String.valueOf(followed.body())).isEqualTo(200);
        assertThat(followed.text("source")).isEqualTo("MANUAL");
        assertThat(followed.body().get("notifications").asBoolean()).isTrue();
        assertThat(followed.text("businessSlug")).isEqualTo(slug);
        assertThat(call(put("/v1/me/follows/" + slug), Map.of("notifications", false), ana.token())
                        .body()
                        .get("notifications")
                        .asBoolean())
                .isFalse();
        call(put("/v1/me/follows/" + slug), Map.of(), ben.token());

        JsonNode mine = call(get("/v1/me/follows"), null, ana.token()).body().get("items");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("name").asString()).contains("Brand");
        assertThat(call(get("/v1/public/businesses/" + slug), null, null)
                        .body()
                        .get("followers")
                        .asInt())
                .isEqualTo(2);

        assertThat(call(delete("/v1/me/follows/" + slug), null, ana.token()).status())
                .isEqualTo(204);
        assertThat(call(get("/v1/me/follows"), null, ana.token()).body().get("items"))
                .isEmpty();
        assertThat(call(get("/v1/public/businesses/" + slug), null, null)
                        .body()
                        .get("followers")
                        .asInt())
                .isEqualTo(1);
        assertThat(call(delete("/v1/me/follows/" + slug), null, ana.token()).status())
                .isEqualTo(204);

        assertThat(call(put("/v1/me/follows/no-such-business"), Map.of(), ana.token())
                        .status())
                .isEqualTo(404);
        assertThat(call(get("/v1/public/businesses/no-such-business"), null, null)
                        .status())
                .isEqualTo(404);
        assertThat(call(put("/v1/me/follows/" + slug), Map.of(), null).status()).isEqualTo(401);
    }

    @Test
    void searchCanBeNarrowedToABusinessOrToSomePlaces() throws Exception {
        String m = SearchApiTests.marker();
        Salon one = listedSalon(m + " One");
        Salon two = listedSalon(m + " Two");

        JsonNode both = call(get("/v1/public/search?q=" + m), null, null).body().get("items");
        assertThat(both).hasSize(4);
        JsonNode ofOne = call(get("/v1/public/search?business=" + slug(one)), null, null)
                .body()
                .get("items");
        assertThat(ofOne).hasSize(2);
        ofOne.forEach(i -> assertThat(i.get("businessSlug").asString()).isEqualTo(slug(one)));

        JsonNode some = call(get("/v1/public/search?location=" + one.location1() + "," + two.location2()), null, null)
                .body()
                .get("items");
        assertThat(some).hasSize(2);
        assertThat(call(get("/v1/public/search?location=nope"), null, null).status())
                .isEqualTo(400);
        String many = String.join(
                ",",
                java.util.stream.Stream.generate(() -> UUID.randomUUID().toString())
                        .limit(21)
                        .toList());
        assertThat(call(get("/v1/public/search?location=" + many), null, null).status())
                .isEqualTo(400);
    }

    // --- after a visit ----------------------------------------------------------------------------------------

    UUID userOf(Person person) {
        return system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
    }

    Call bookAt(Shop shop, String hhmm, Person person) throws Exception {
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

    void complete(Shop shop, String appointmentId) throws Exception {
        for (String status : List.of("ARRIVED", "IN_PROGRESS", "COMPLETED")) {
            Call c = call(
                    post(shop.salon().path("/appointments/" + appointmentId + "/status")),
                    Map.of("status", status),
                    shop.salon().owner().token());
            assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        }
    }

    boolean follows(UUID user, UUID org) {
        return system.resultQuery("SELECT count(*) FROM discovery.follows WHERE user_id = ? AND org_id = ?", user, org)
                        .fetchOne(0, Integer.class)
                > 0;
    }

    @Test
    void aPersonFollowsABusinessAfterTheirFirstCompletedVisitOnlyOnce() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        UUID user = userOf(ana);

        complete(shop, bookAt(shop, "10:00", ana).text("id"));
        long deadline = System.currentTimeMillis() + 15_000;
        while (!follows(user, shop.salon().id()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(follows(user, shop.salon().id()))
                .as("followed after the first visit")
                .isTrue();
        assertThat(system.resultQuery(
                                "SELECT source FROM discovery.follows WHERE user_id = ? AND org_id = ?",
                                user,
                                shop.salon().id())
                        .fetchOne(0, String.class))
                .isEqualTo("AFTER_VISIT");

        // Someone who stopped following is not signed up again by the next visit.
        system.execute("DELETE FROM discovery.follows WHERE user_id = ?", user);
        complete(shop, bookAt(shop, "13:00", ana).text("id"));
        Thread.sleep(3_000);
        assertThat(follows(user, shop.salon().id())).isFalse();
    }
}
