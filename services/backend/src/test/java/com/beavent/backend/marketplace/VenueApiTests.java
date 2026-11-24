package com.beavent.backend.marketplace;

import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATION_HOURS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ApiTestSupport;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The public venue page (plan step 2.2): what anyone with a shared link sees of a business. */
class VenueApiTests extends ApiTestSupport {

    String orgSlug(Salon salon) {
        return system.select(ORGANIZATIONS.SLUG)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(salon.id()))
                .fetchSingle(ORGANIZATIONS.SLUG);
    }

    String locationSlug(UUID location) {
        return system.select(LOCATIONS.SLUG)
                .from(LOCATIONS)
                .where(LOCATIONS.ID.eq(location))
                .fetchSingle(LOCATIONS.SLUG);
    }

    Call service(Salon salon, String name, Map<String, Object> extra, int minutes, long price) throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("categoryId", category("SERVICE", "HAIR"));
        input.put("name", Map.of("en", name, "am", name + " (አማ)"));
        input.put("variants", List.of(Map.of("durationMinutes", minutes, "priceType", "FIXED", "priceMinor", price)));
        input.put("locationIds", List.of(salon.location1()));
        input.putAll(extra);
        Call created = call(post(salon.path("/services")), input, salon.owner().token());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return created;
    }

    @Test
    void aSharedLinkShowsTheBusinessItsMenuTeamHoursAndRules() throws Exception {
        Salon salon = newSalon("Abeba Beauty");
        String t = salon.owner().token();
        for (int weekday = 1; weekday <= 6; weekday++) {
            system.insertInto(LOCATION_HOURS)
                    .set(LOCATION_HOURS.ORG_ID, salon.id())
                    .set(LOCATION_HOURS.LOCATION_ID, salon.location1())
                    .set(LOCATION_HOURS.ISO_WEEKDAY, (short) weekday)
                    .set(LOCATION_HOURS.OPENS_AT, LocalTime.of(9, 0))
                    .set(LOCATION_HOURS.CLOSES_AT, LocalTime.of(18, 0))
                    .execute();
        }
        String group = call(post(salon.path("/service-groups")), Map.of("name", Map.of("en", "Hair")), t)
                .text("id");
        Call addon = call(
                post(salon.path("/addons")),
                Map.of("name", Map.of("en", "Deep conditioning"), "durationMinutes", 15, "priceMinor", 30_000),
                t);
        Call braids = service(
                salon, "Box braids", Map.of("groupId", group, "addonIds", List.of(addon.text("id"))), 210, 180_000);
        Call wash = service(salon, "Wash", Map.of(), 30, 40_000);
        Call hidden = service(salon, "Secret", Map.of("onlineBooking", false), 30, 10_000);
        // A person charges less for braids at this location: the page shows a price range.
        String variant = braids.body().get("variants").get(0).get("id").asString();
        String hana = call(
                        post(salon.path("/staff")),
                        Map.of(
                                "displayName",
                                "Hana",
                                "jobTitle",
                                "Senior stylist",
                                "languages",
                                List.of("am", "en"),
                                "locationIds",
                                List.of(salon.location1()),
                                "serviceIds",
                                List.of(braids.text("id"))),
                        t)
                .text("id");
        assertThat(call(
                                put(salon.path("/services/" + braids.text("id") + "/prices")),
                                Map.of(
                                        "items",
                                        List.of(Map.of("variantId", variant, "staffId", hana, "priceMinor", 150_000))),
                                t)
                        .status())
                .isEqualTo(200);
        call(
                put(salon.path("/booking-policies")),
                Map.of(
                        "cancellationWindowMinutes",
                        1440,
                        "requiresApproval",
                        true,
                        "deposit",
                        Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "NEW_CUSTOMERS")),
                t);

        Call page =
                call(get("/v1/public/venues/" + orgSlug(salon) + "/" + locationSlug(salon.location1())), null, null);
        assertThat(page.status()).as(String.valueOf(page.body())).isEqualTo(200);
        JsonNode venue = page.body();
        assertThat(venue.get("businessName").asString()).isEqualTo("Abeba Beauty");
        assertThat(venue.get("locationId").asString())
                .isEqualTo(salon.location1().toString());
        assertThat(venue.get("landmark").asString()).isEqualTo("Near Edna Mall");
        assertThat(venue.get("timezone").asString()).isEqualTo("Africa/Addis_Ababa");
        assertThat(venue.get("hours").size()).isEqualTo(6);
        assertThat(venue.get("onlineBooking").asBoolean()).isTrue();
        assertThat(venue.get("listed").asBoolean()).isFalse();
        assertThat(venue.get("otherLocations").size()).isEqualTo(1);

        // The menu: the Hair group, then the ungrouped service; the service hidden from online booking is not there.
        JsonNode groups = venue.get("groups");
        assertThat(groups.size()).isEqualTo(2);
        assertThat(groups.get(0).get("name").get("en").asString()).isEqualTo("Hair");
        JsonNode box = groups.get(0).get("services").get(0);
        assertThat(box.get("name").get("am").asString()).isEqualTo("Box braids (አማ)");
        assertThat(box.get("variants").get(0).get("durationMinutes").asInt()).isEqualTo(210);
        assertThat(box.get("variants").get(0).get("priceMinMinor").asLong()).isEqualTo(150_000);
        assertThat(box.get("variants").get(0).get("priceMaxMinor").asLong()).isEqualTo(180_000);
        assertThat(box.get("addOns").get(0).get("priceMinor").asLong()).isEqualTo(30_000);
        assertThat(groups.get(1).get("name").size()).isZero();
        assertThat(groups.get(1).get("services").get(0).get("name").get("en").asString())
                .isEqualTo("Wash");
        assertThat(venue.toString()).doesNotContain("Secret");

        JsonNode team = venue.get("team");
        assertThat(team.size()).isEqualTo(1);
        assertThat(team.get(0).get("displayName").asString()).isEqualTo("Hana");
        assertThat(team.get(0).get("jobTitle").asString()).isEqualTo("Senior stylist");
        assertThat(team.get(0).get("serviceIds").get(0).asString()).isEqualTo(braids.text("id"));

        JsonNode policy = venue.get("policy");
        assertThat(policy.get("requiresApproval").asBoolean()).isTrue();
        assertThat(policy.get("depositMode").asString()).isEqualTo("PERCENT");
        assertThat(policy.get("cancellationWindowMinutes").asInt()).isEqualTo(1440);

        // The business's other branch has its own page and none of this branch's services.
        Call branch2 =
                call(get("/v1/public/venues/" + orgSlug(salon) + "/" + locationSlug(salon.location2())), null, null);
        assertThat(branch2.status()).isEqualTo(200);
        assertThat(branch2.body().get("groups").size()).isZero();
        assertThat(branch2.body().get("onlineBooking").asBoolean())
                .as("nothing to book yet")
                .isFalse();

        Call slugs = call(get("/v1/public/venues/" + orgSlug(salon) + "/locations"), null, null);
        assertThat(slugs.body().get("locationSlugs").size()).isEqualTo(2);
        assertThat(wash.status()).isEqualTo(201);
        assertThat(hidden.status()).isEqualTo(201);
    }

    @Test
    void placesThatAreNotOnBeaventLookLikeTheyDoNotExist() throws Exception {
        Salon salon = newSalon("Closed Salon");
        String org = orgSlug(salon);
        String loc = locationSlug(salon.location1());
        assertThat(call(get("/v1/public/venues/" + org + "/" + loc), null, null).status())
                .isEqualTo(200);
        assertThat(call(get("/v1/public/venues/" + org + "/no-such-branch"), null, null)
                        .status())
                .isEqualTo(404);
        assertThat(call(get("/v1/public/venues/no-such-business/" + loc), null, null)
                        .status())
                .isEqualTo(404);
        assertThat(call(get("/v1/public/venues/no-such-business/locations"), null, null)
                        .status())
                .isEqualTo(404);
        // A branch that no longer takes online bookings still has a page (it takes bookings by phone).
        system.update(LOCATIONS)
                .set(LOCATIONS.ONLINE_BOOKING, false)
                .where(LOCATIONS.ID.eq(salon.location1()))
                .execute();
        Call phoneOnly = call(get("/v1/public/venues/" + org + "/" + loc), null, null);
        assertThat(phoneOnly.status()).isEqualTo(200);
        assertThat(phoneOnly.body().get("onlineBooking").asBoolean()).isFalse();
        // A suspended business disappears, an archived branch too.
        system.update(ORGANIZATIONS)
                .set(ORGANIZATIONS.STATUS, "SUSPENDED")
                .where(ORGANIZATIONS.ID.eq(salon.id()))
                .execute();
        assertThat(call(get("/v1/public/venues/" + org + "/" + loc), null, null).status())
                .isEqualTo(404);
        system.update(ORGANIZATIONS)
                .set(ORGANIZATIONS.STATUS, "ACTIVE")
                .where(ORGANIZATIONS.ID.eq(salon.id()))
                .execute();
        system.update(LOCATIONS)
                .set(LOCATIONS.STATUS, "ARCHIVED")
                .where(LOCATIONS.ID.eq(salon.location1()))
                .execute();
        assertThat(call(get("/v1/public/venues/" + org + "/" + loc), null, null).status())
                .isEqualTo(404);
    }
}
