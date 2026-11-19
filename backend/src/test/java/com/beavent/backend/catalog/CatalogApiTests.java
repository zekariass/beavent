package com.beavent.backend.catalog;

import static com.beavent.db.catalog.Tables.SERVICE_PRICES;
import static com.beavent.db.platform.Tables.FILES;
import static com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static com.beavent.db.team.Tables.PRICING_LEVELS;
import static com.beavent.db.team.Tables.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ApiTestSupport;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Catalogue (plan step 1.1): service menu, variants, price overrides, add-ons, bundles, resources, galleries. */
class CatalogApiTests extends ApiTestSupport {

    record Org(UUID id, Person owner, UUID location1, UUID location2) {
        String path(String rest) {
            return "/v1/orgs/" + id + rest;
        }
    }

    UUID category(String kind, String code) {
        return system.select(CATEGORIES.ID)
                .from(CATEGORIES)
                .where(CATEGORIES.KIND.eq(kind), CATEGORIES.CODE.eq(code))
                .fetchSingle(CATEGORIES.ID);
    }

    Org salon() throws Exception {
        Person owner = newPerson();
        UUID orgId = UUID.fromString(call(
                        post("/v1/orgs"),
                        Map.of(
                                "displayName", "Nu Hair Studio",
                                "kind", "BUSINESS",
                                "primaryCategoryId", category("BUSINESS", "HAIR_SALON"),
                                "defaultLanguage", "am"),
                        owner.token())
                .text("id"));
        String entity = call(
                        post("/v1/orgs/" + orgId + "/legal-entities"),
                        Map.of(
                                "countryCode",
                                "ET",
                                "entityType",
                                "PLC",
                                "registeredName",
                                "Nu PLC",
                                "taxRegime",
                                "TOT"),
                        owner.token())
                .text("id");
        UUID area = system.select(ADMINISTRATIVE_AREAS.ID)
                .from(ADMINISTRATIVE_AREAS)
                .where(ADMINISTRATIVE_AREAS.COUNTRY_CODE.eq("ET"), ADMINISTRATIVE_AREAS.LEVEL.eq((short) 2))
                .limit(1)
                .fetchSingle(ADMINISTRATIVE_AREAS.ID);
        UUID[] locations = new UUID[2];
        for (int i = 0; i < 2; i++) {
            locations[i] = UUID.fromString(call(
                            post("/v1/orgs/" + orgId + "/locations"),
                            Map.of(
                                    "name",
                                    "Branch " + (i + 1),
                                    "legalEntityId",
                                    entity,
                                    "timezone",
                                    "Africa/Addis_Ababa",
                                    "address",
                                    Map.of("countryCode", "ET", "adminAreaId", area, "landmark", "Near Edna Mall")),
                            owner.token())
                    .text("id"));
        }
        return new Org(orgId, owner, locations[0], locations[1]);
    }

    Map<String, Object> variant(Object id, String nameEn, int minutes, long price, List<?> stages) {
        Map<String, Object> v = new HashMap<>();
        if (id != null) {
            v.put("id", id);
        }
        v.put("name", Map.of("en", nameEn));
        v.put("durationMinutes", minutes);
        v.put("priceType", "FIXED");
        v.put("priceMinor", price);
        v.put("bufferAfterMinutes", 10);
        if (stages != null) {
            v.put("stages", stages);
        }
        return v;
    }

    Map<String, Object> colourService(Org org, List<Map<String, Object>> variants) {
        Map<String, Object> input = new HashMap<>();
        input.put("categoryId", category("SERVICE", "HAIR"));
        input.put("name", Map.of("en", "Colour", "am", "ቀለም"));
        input.put("description", Map.of("en", "Full colour with toner"));
        input.put("requiresPatchTest", true);
        input.put("patchTestValidityDays", 180);
        input.put("variants", variants);
        input.put("locationIds", List.of(org.location1()));
        return input;
    }

    static List<Map<String, Object>> colourStages() {
        return List.of(
                Map.of("kind", "SERVICE", "durationMinutes", 30),
                Map.of("kind", "PROCESSING", "durationMinutes", 35, "staffBusy", false),
                Map.of("kind", "FINISHING", "durationMinutes", 25));
    }

    // --- tests -----------------------------------------------------------------------------------------------

    @Test
    void aSalonBuildsItsMenuWithVariantsStagesAndLocations() throws Exception {
        Org org = salon();
        String t = org.owner().token();
        Call group = call(post(org.path("/service-groups")), Map.of("name", Map.of("en", "Hair", "am", "ፀጉር")), t);
        assertThat(group.status()).isEqualTo(201);

        Map<String, Object> input = colourService(
                org,
                List.of(
                        variant(null, "Short hair", 60, 150_000, null),
                        variant(null, "Long hair", 90, 220_000, colourStages())));
        input.put("groupId", group.text("id"));
        Call created = call(post(org.path("/services")), input, t);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        assertThat(created.text("currency")).isEqualTo("ETB");
        JsonNode variants = created.body().get("variants");
        assertThat(variants.size()).isEqualTo(2);
        assertThat(variants.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(variants.get(1).get("stages").get(1).get("staffBusy").asBoolean())
                .isFalse();
        assertThat(created.body().get("locationIds").toString())
                .contains(org.location1().toString());
        String serviceId = created.text("id");

        assertThat(call(get(org.path("/services?locationId=" + org.location1())), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(call(get(org.path("/services?locationId=" + org.location2())), null, t)
                        .body()
                        .get("items")
                        .size())
                .isZero();

        // Replace: keep "Long hair", drop "Short hair" (archived), add "Medium"; stale version is refused.
        String longId = variants.get(1).get("id").asString();
        Map<String, Object> changed = colourService(
                org,
                List.of(
                        variant(longId, "Long hair", 90, 240_000, colourStages()),
                        variant(null, "Medium hair", 75, 190_000, null)));
        changed.put("locationIds", List.of(org.location1(), org.location2()));
        changed.put("groupId", group.text("id"));
        assertThat(call(put(org.path("/services/" + serviceId)).header("If-Match", "\"1\""), changed, t)
                        .status())
                .isEqualTo(412);
        Call updated = call(put(org.path("/services/" + serviceId)).header("If-Match", created.etag()), changed, t);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("variants").size()).isEqualTo(2);
        assertThat(updated.body().get("variants").get(0).get("id").asString()).isEqualTo(longId);
        assertThat(updated.body().get("variants").get(0).get("priceMinor").asLong())
                .isEqualTo(240_000);
        assertThat(updated.body().get("locationIds").size()).isEqualTo(2);

        // Rules: stages must add up, fixed prices are positive, categories are service categories, ETB only.
        assertThat(call(
                                post(org.path("/services")),
                                colourService(org, List.of(variant(null, "Bad", 60, 1000, colourStages()))),
                                t)
                        .status())
                .isEqualTo(400);
        assertThat(call(post(org.path("/services")), colourService(org, List.of(variant(null, "Zero", 60, 0, null))), t)
                        .status())
                .isEqualTo(400);
        Map<String, Object> wrongCategory = colourService(org, List.of(variant(null, "A", 60, 1000, null)));
        wrongCategory.put("categoryId", category("BUSINESS", "HAIR_SALON"));
        assertThat(call(post(org.path("/services")), wrongCategory, t).status()).isEqualTo(422);
        Map<String, Object> dollars = colourService(org, List.of(variant(null, "A", 60, 1000, null)));
        dollars.put("currency", "USD");
        assertThat(call(post(org.path("/services")), dollars, t).status()).isEqualTo(422);

        assertThat(call(delete(org.path("/service-groups/" + group.text("id"))), null, t)
                        .status())
                .as("section still has services")
                .isEqualTo(409);
        assertThat(call(delete(org.path("/services/" + serviceId)), null, t).status())
                .isEqualTo(204);
        assertThat(call(get(org.path("/services")), null, t).body().get("items").size())
                .isZero();
        assertThat(call(get(org.path("/services?status=ARCHIVED")), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(call(delete(org.path("/service-groups/" + group.text("id"))), null, t)
                        .status())
                .isEqualTo(204);
    }

    @Test
    void theMostSpecificPriceWinsAndChangesKeepHistory() throws Exception {
        Org org = salon();
        String t = org.owner().token();
        Call service = call(
                post(org.path("/services")), colourService(org, List.of(variant(null, "Cut", 45, 50_000, null))), t);
        String serviceId = service.text("id");
        String variantId = service.body().get("variants").get(0).get("id").asString();

        UUID senior = UUID.randomUUID();
        system.insertInto(PRICING_LEVELS)
                .set(PRICING_LEVELS.ID, senior)
                .set(PRICING_LEVELS.ORG_ID, org.id())
                .set(PRICING_LEVELS.NAME, "Senior")
                .execute();
        UUID abebe = staff(org, "Abebe", null);
        UUID sara = staff(org, "Sara", senior);

        Call set = call(
                put(org.path("/services/" + serviceId + "/prices")),
                Map.of(
                        "items",
                        List.of(
                                Map.of("variantId", variantId, "locationId", org.location2(), "priceMinor", 60_000),
                                Map.of("variantId", variantId, "pricingLevelId", senior, "priceMinor", 70_000),
                                Map.of(
                                        "variantId",
                                        variantId,
                                        "staffId",
                                        abebe,
                                        "locationId",
                                        org.location2(),
                                        "priceMinor",
                                        90_000,
                                        "durationMinutes",
                                        75))),
                t);
        assertThat(set.status()).as(String.valueOf(set.body())).isEqualTo(200);

        String price = org.path("/services/" + serviceId + "/variants/" + variantId + "/price");
        assertThat(resolve(price, "", t)).isEqualTo("DEFAULT 50000 45");
        assertThat(resolve(price, "?locationId=" + org.location2(), t)).isEqualTo("LOCATION 60000 45");
        assertThat(resolve(price, "?locationId=" + org.location1() + "&staffId=" + sara, t))
                .isEqualTo("LEVEL 70000 45");
        assertThat(resolve(price, "?locationId=" + org.location2() + "&staffId=" + abebe, t))
                .isEqualTo("STAFF_AND_LOCATION 90000 75");
        assertThat(resolve(price, "?locationId=" + org.location1() + "&staffId=" + abebe, t))
                .isEqualTo("DEFAULT 50000 45");

        // A change ends the current prices instead of deleting them.
        call(
                put(org.path("/services/" + serviceId + "/prices")),
                Map.of(
                        "items",
                        List.of(Map.of("variantId", variantId, "locationId", org.location2(), "priceMinor", 65_000))),
                t);
        assertThat(call(get(org.path("/services/" + serviceId + "/prices")), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(resolve(price, "?locationId=" + org.location2(), t)).isEqualTo("LOCATION 65000 45");
        int ended = system.fetchCount(
                SERVICE_PRICES,
                SERVICE_PRICES
                        .VARIANT_ID
                        .eq(UUID.fromString(variantId))
                        .and(SERVICE_PRICES.VALID_TO.lt(OffsetDateTime.now().plusYears(1))));
        assertThat(ended).as("earlier prices kept as history").isEqualTo(3);

        assertThat(call(
                                put(org.path("/services/" + serviceId + "/prices")),
                                Map.of(
                                        "items",
                                        List.of(Map.of(
                                                "variantId",
                                                variantId,
                                                "staffId",
                                                abebe,
                                                "pricingLevelId",
                                                senior,
                                                "priceMinor",
                                                1))),
                                t)
                        .status())
                .isEqualTo(400);
        assertThat(call(
                                put(org.path("/services/" + serviceId + "/prices")),
                                Map.of(
                                        "items",
                                        List.of(Map.of(
                                                "variantId",
                                                UUID.randomUUID(),
                                                "locationId",
                                                org.location1(),
                                                "priceMinor",
                                                1))),
                                t)
                        .status())
                .isEqualTo(422);
    }

    String resolve(String path, String query, String token) throws Exception {
        JsonNode p = call(get(path + query), null, token).body();
        return p.get("source").asString() + " " + p.get("priceMinor").asLong() + " "
                + p.get("durationMinutes").asInt();
    }

    UUID staff(Org org, String name, UUID level) {
        UUID id = UUID.randomUUID();
        system.insertInto(STAFF)
                .set(STAFF.ID, id)
                .set(STAFF.ORG_ID, org.id())
                .set(STAFF.DISPLAY_NAME, name)
                .set(STAFF.PRICING_LEVEL_ID, level)
                .execute();
        return id;
    }

    @Test
    void addOnsResourcesAndBundles() throws Exception {
        Org org = salon();
        String t = org.owner().token();
        Call addon = call(
                post(org.path("/addons")),
                Map.of("name", Map.of("en", "Deep conditioning"), "durationMinutes", 15, "priceMinor", 30_000),
                t);
        assertThat(addon.status()).isEqualTo(201);
        Call chair = call(
                post(org.path("/resources")),
                Map.of("locationId", org.location1(), "name", "Wash basin", "kind", "STATION", "units", 2),
                t);
        assertThat(chair.status()).isEqualTo(201);

        Map<String, Object> wash = variant(null, "Wash", 30, 40_000, null);
        wash.put("resourceRequirements", List.of(Map.of("resourceId", chair.text("id"), "units", 1)));
        Map<String, Object> input = colourService(org, List.of(wash));
        input.put("name", Map.of("en", "Wash & blow-dry"));
        input.put("addonIds", List.of(addon.text("id")));
        Call service = call(post(org.path("/services")), input, t);
        assertThat(service.status()).isEqualTo(201);
        assertThat(service.body().get("addonIds").get(0).asString()).isEqualTo(addon.text("id"));
        assertThat(service.body()
                        .get("variants")
                        .get(0)
                        .get("resourceRequirements")
                        .get(0)
                        .get("resourceId")
                        .asString())
                .isEqualTo(chair.text("id"));
        String washVariant = service.body().get("variants").get(0).get("id").asString();
        String cutVariant = call(
                        post(org.path("/services")),
                        colourService(org, List.of(variant(null, "Cut", 45, 60_000, null))),
                        t)
                .body()
                .get("variants")
                .get(0)
                .get("id")
                .asString();

        Call percent = call(
                post(org.path("/bundles")),
                Map.of(
                        "name",
                        Map.of("en", "Wash, cut and style"),
                        "pricing",
                        "PERCENT_OFF",
                        "percentOff",
                        10,
                        "items",
                        List.of(Map.of("serviceVariantId", washVariant), Map.of("serviceVariantId", cutVariant))),
                t);
        assertThat(percent.status()).as(String.valueOf(percent.body())).isEqualTo(201);
        assertThat(percent.body().get("totalPriceMinor").asLong()).isEqualTo(90_000); // (40,000 + 60,000) - 10 %
        assertThat(percent.body().get("totalDurationMinutes").asInt()).isEqualTo(75);
        assertThat(call(
                                post(org.path("/bundles")),
                                Map.of(
                                        "name", Map.of("en", "Bad"),
                                        "pricing", "FIXED_PRICE",
                                        "items",
                                                List.of(
                                                        Map.of("serviceVariantId", washVariant),
                                                        Map.of("serviceVariantId", cutVariant))),
                                t)
                        .status())
                .as("fixed price missing")
                .isEqualTo(400);
        assertThat(call(get(org.path("/bundles")), null, t).body().get("items").size())
                .isEqualTo(1);
        assertThat(call(delete(org.path("/addons/" + addon.text("id"))), null, t)
                        .status())
                .isEqualTo(204);
        assertThat(call(get(org.path("/addons")), null, t).body().get("items").size())
                .isZero();
    }

    @Test
    void galleriesUseTheOrganizationsProcessedImagesOnly() throws Exception {
        Org org = salon();
        String t = org.owner().token();
        UUID image = file(org.id(), "GALLERY", "PUBLIC", "beavent-public", "CLEAN");
        UUID document = file(org.id(), "KYC", "SENSITIVE", "beavent-private", "CLEAN");
        Call added = call(
                post(org.path("/gallery")),
                Map.of("fileId", image, "locationId", org.location1(), "altText", Map.of("en", "Our chairs")),
                t);
        assertThat(added.status()).as(String.valueOf(added.body())).isEqualTo(201);
        assertThat(added.text("url")).contains("/beavent-public/");
        assertThat(call(get(org.path("/gallery?locationId=" + org.location1())), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(call(get(org.path("/gallery")), null, t).body().get("items").size())
                .as("organization gallery")
                .isZero();
        assertThat(call(post(org.path("/gallery")), Map.of("fileId", document), t)
                        .status())
                .isEqualTo(422);
        Org other = salon();
        assertThat(call(
                                post(other.path("/gallery")),
                                Map.of("fileId", image),
                                other.owner().token())
                        .status())
                .as("another organization's image")
                .isEqualTo(422);
    }

    UUID file(UUID orgId, String purpose, String visibility, String bucket, String scan) {
        UUID id = UUID.randomUUID();
        system.insertInto(FILES)
                .set(FILES.ID, id)
                .set(FILES.ORG_ID, orgId)
                .set(FILES.PURPOSE, purpose)
                .set(FILES.VISIBILITY, visibility)
                .set(FILES.BUCKET, bucket)
                .set(FILES.STORAGE_KEY, "orgs/" + orgId + "/" + id + "/original.jpg")
                .set(FILES.CONTENT_TYPE, "image/jpeg")
                .set(FILES.SIZE_BYTES, 1000L)
                .set(FILES.UPLOADED_AT, OffsetDateTime.now())
                .set(FILES.SCAN_STATUS, scan)
                .execute();
        return id;
    }

    @Test
    void membersReadTheMenuOnlyManagersChangeItAndOrganizationsStaySeparate() throws Exception {
        Org org = salon();
        Org other = salon();
        String t = org.owner().token();
        String serviceId = call(
                        post(org.path("/services")),
                        colourService(org, List.of(variant(null, "Cut", 45, 50_000, null))),
                        t)
                .text("id");

        String phone = newPhone();
        String receptionistRole = null;
        for (JsonNode role : call(get(org.path("/roles")), null, t).body().get("items")) {
            if (role.get("code").asString().equals("RECEPTIONIST")) {
                receptionistRole = role.get("id").asString();
            }
        }
        call(post(org.path("/invitations")), Map.of("phone", phone, "roleId", receptionistRole), t);
        String token = sms.lastMessageTo(phone).replaceAll("(?s).*token=([A-Za-z0-9_-]+).*", "$1");
        String receptionist = signInWithOtp(phone).text("accessToken");
        call(post("/v1/invitations/accept"), Map.of("token", token), receptionist);
        assertThat(call(get(org.path("/services/" + serviceId)), null, receptionist)
                        .status())
                .isEqualTo(200);
        assertThat(call(
                                post(org.path("/services")),
                                colourService(org, List.of(variant(null, "X", 30, 1000, null))),
                                receptionist)
                        .status())
                .isEqualTo(403);

        String o = other.owner().token();
        assertThat(call(get(other.path("/services/" + serviceId)), null, o).status())
                .isEqualTo(404);
        assertThat(call(get(org.path("/services")), null, o).status()).isEqualTo(404);
        Map<String, Object> borrowed = colourService(other, List.of(variant(null, "X", 30, 1000, null)));
        borrowed.put("locationIds", List.of(org.location1()));
        assertThat(call(post(other.path("/services")), borrowed, o).status()).isEqualTo(422);
    }
}
