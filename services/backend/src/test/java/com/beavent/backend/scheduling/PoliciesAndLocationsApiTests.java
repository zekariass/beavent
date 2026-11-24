package com.beavent.backend.scheduling;

import static com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Booking policies (step 1.3) and location hours, closures, holidays and at-home areas (step 1.4). */
class PoliciesAndLocationsApiTests extends ApiTestSupport {

    String service(Salon salon) throws Exception {
        return call(
                        post(salon.path("/services")),
                        Map.of(
                                "categoryId", category("SERVICE", "NAILS"),
                                "name", Map.of("en", "Gel manicure"),
                                "variants",
                                        List.of(Map.of(
                                                "durationMinutes", 60, "priceType", "FIXED", "priceMinor", 80_000))),
                        salon.owner().token())
                .text("id");
    }

    String effective(Salon salon, String query) throws Exception {
        JsonNode p = call(
                        get(salon.path("/booking-policies/effective" + query)),
                        null,
                        salon.owner().token())
                .body();
        return p.get("scope").asString() + " notice="
                + p.get("minNoticeMinutes").asInt() + " deposit="
                + p.get("deposit").get("mode").asString();
    }

    @Test
    void theMostSpecificBookingPolicyApplies() throws Exception {
        Salon salon = newSalon("Sigma Nails");
        String t = salon.owner().token();
        String manicure = service(salon);
        assertThat(effective(salon, "")).isEqualTo("PLATFORM_DEFAULT notice=60 deposit=NONE");

        assertThat(call(
                                put(salon.path("/booking-policies")),
                                Map.of("minNoticeMinutes", 120, "slotIntervalMinutes", 30),
                                t)
                        .status())
                .isEqualTo(200);
        Call location = call(
                put(salon.path("/booking-policies")),
                Map.of(
                        "locationId",
                        salon.location1(),
                        "minNoticeMinutes",
                        240,
                        "requiresApproval",
                        true,
                        "deposit",
                        Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "NEW_CUSTOMERS")),
                t);
        assertThat(location.text("scope")).isEqualTo("LOCATION");
        Call fixed = call(
                put(salon.path("/booking-policies")),
                Map.of(
                        "serviceId",
                        manicure,
                        "minNoticeMinutes",
                        30,
                        "deposit",
                        Map.of("mode", "FIXED", "fixedMinor", 50_000),
                        "lateCancelFeePercent",
                        50,
                        "noShowFeePercent",
                        100,
                        "policyText",
                        Map.of("en", "Cancel 24 hours ahead", "am", "ከ24 ሰዓት በፊት ይሰርዙ")),
                t);
        assertThat(fixed.text("currency")).isEqualTo("ETB");
        Call both = call(
                put(salon.path("/booking-policies")),
                Map.of("serviceId", manicure, "locationId", salon.location1(), "minNoticeMinutes", 15),
                t);
        assertThat(both.text("scope")).isEqualTo("SERVICE_AND_LOCATION");

        assertThat(effective(salon, "")).isEqualTo("ORGANIZATION notice=120 deposit=NONE");
        assertThat(effective(salon, "?locationId=" + salon.location1()))
                .isEqualTo("LOCATION notice=240 deposit=PERCENT");
        assertThat(effective(salon, "?locationId=" + salon.location2()))
                .isEqualTo("ORGANIZATION notice=120 deposit=NONE");
        assertThat(effective(salon, "?serviceId=" + manicure + "&locationId=" + salon.location2()))
                .isEqualTo("SERVICE notice=30 deposit=FIXED");
        assertThat(effective(salon, "?serviceId=" + manicure + "&locationId=" + salon.location1()))
                .isEqualTo("SERVICE_AND_LOCATION notice=15 deposit=NONE");

        // Saving the same scope again replaces it; removing it lets the broader one apply.
        call(
                put(salon.path("/booking-policies")),
                Map.of("serviceId", manicure, "locationId", salon.location1(), "minNoticeMinutes", 45),
                t);
        assertThat(call(get(salon.path("/booking-policies")), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(4);
        assertThat(call(delete(salon.path("/booking-policies/" + both.text("id"))), null, t)
                        .status())
                .isEqualTo(204);
        assertThat(effective(salon, "?serviceId=" + manicure + "&locationId=" + salon.location1()))
                .isEqualTo("SERVICE notice=30 deposit=FIXED");

        assertThat(call(put(salon.path("/booking-policies")), Map.of("deposit", Map.of("mode", "PERCENT")), t)
                        .status())
                .as("percent missing")
                .isEqualTo(400);
        assertThat(callInvalid(put(salon.path("/booking-policies")), Map.of("slotIntervalMinutes", 7), t)
                        .status())
                .isEqualTo(400);
        Person receptionist = newMember(salon, "RECEPTIONIST");
        assertThat(call(get(salon.path("/booking-policies")), null, receptionist.token())
                        .status())
                .isEqualTo(200);
        assertThat(call(put(salon.path("/booking-policies")), Map.of("minNoticeMinutes", 0), receptionist.token())
                        .status())
                .isEqualTo(403);
        Salon other = newSalon("Tau Spa");
        assertThat(call(
                                put(other.path("/booking-policies")),
                                Map.of("serviceId", manicure),
                                other.owner().token())
                        .status())
                .isEqualTo(422);
    }

    @Test
    void locationsCloseForHolidaysAndClosuresAndServeAreasAtHome() throws Exception {
        Salon salon = newSalon("Upsilon Beauty");
        String t = salon.owner().token();
        String locationPath = salon.path("/locations/" + salon.location1());
        Call location = call(get(locationPath), null, t);
        assertThat(location.body().get("closedOnPublicHolidays").asBoolean()).isTrue();

        Call closure = call(
                post(locationPath + "/closures"),
                Map.of("startsAt", "2026-12-24T06:00:00Z", "endsAt", "2026-12-26T06:00:00Z", "reason", "Renovation"),
                t);
        assertThat(closure.status()).isEqualTo(201);
        assertThat(call(get(locationPath + "/closures?from=2026-12-01&to=2026-12-31"), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(call(get(locationPath + "/closures?from=2027-01-01&to=2027-01-31"), null, t)
                        .body()
                        .get("items")
                        .size())
                .isZero();
        assertThat(call(
                                post(locationPath + "/closures"),
                                Map.of("startsAt", "2026-12-26T06:00:00Z", "endsAt", "2026-12-24T06:00:00Z"),
                                t)
                        .status())
                .isEqualTo(400);
        assertThat(call(delete(locationPath + "/closures/" + closure.text("id")), null, t)
                        .status())
                .isEqualTo(204);

        UUID bole = system.select(ADMINISTRATIVE_AREAS.ID)
                .from(ADMINISTRATIVE_AREAS)
                .where(ADMINISTRATIVE_AREAS.COUNTRY_CODE.eq("ET"), ADMINISTRATIVE_AREAS.LEVEL.eq((short) 2))
                .limit(1)
                .fetchSingle(ADMINISTRATIVE_AREAS.ID);
        Call subCity = call(
                post(locationPath + "/service-areas"),
                Map.of("name", "Bole", "adminAreaId", bole, "travelFeeMinor", 15_000, "travelMinutes", 30),
                t);
        assertThat(subCity.status()).isEqualTo(201);
        assertThat(subCity.text("currency")).isEqualTo("ETB");
        Call circle = call(
                post(locationPath + "/service-areas"),
                Map.of(
                        "name", "5 km around the salon",
                        "center", Map.of("latitude", 8.9956, "longitude", 38.7891),
                        "radiusMeters", 5000,
                        "travelFeeMinor", 10_000,
                        "travelMinutes", 20),
                t);
        assertThat(circle.status()).as(String.valueOf(circle.body())).isEqualTo(201);
        assertThat(circle.body().get("center").get("latitude").asDouble()).isEqualTo(8.9956);
        assertThat(call(
                                post(locationPath + "/service-areas"),
                                Map.of(
                                        "name",
                                        "Half",
                                        "center",
                                        Map.of("latitude", 9, "longitude", 38),
                                        "travelFeeMinor",
                                        0,
                                        "travelMinutes",
                                        0),
                                t)
                        .status())
                .as("radius missing")
                .isEqualTo(400);
        Call updated = call(
                put(locationPath + "/service-areas/" + circle.text("id")),
                Map.of(
                        "name",
                        "7 km around the salon",
                        "center",
                        Map.of("latitude", 8.9956, "longitude", 38.7891),
                        "radiusMeters",
                        7000,
                        "travelFeeMinor",
                        12_000,
                        "travelMinutes",
                        25,
                        "isActive",
                        false),
                t);
        assertThat(updated.body().get("radiusMeters").asInt()).isEqualTo(7000);
        assertThat(updated.body().get("isActive").asBoolean()).isFalse();
        assertThat(call(get(locationPath + "/service-areas"), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(2);
        assertThat(call(delete(locationPath + "/service-areas/" + subCity.text("id")), null, t)
                        .status())
                .isEqualTo(204);

        Salon other = newSalon("Phi Barbers");
        assertThat(call(
                                get(other.path("/locations/" + salon.location1() + "/service-areas")),
                                null,
                                other.owner().token())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void ethiopianPublicHolidaysArePublicReferenceData() throws Exception {
        JsonNode holidays = call(get("/v1/ref/public-holidays?countryCode=ET&year=2027"), null, null)
                .body()
                .get("items");
        assertThat(holidays.size()).isEqualTo(13);
        String all = holidays.toString();
        assertThat(all)
                .contains("\"date\":\"2027-09-12\"")
                .contains("Enkutatash")
                .contains("እንቁጣጣሽ");
        for (JsonNode h : holidays) {
            if (h.get("name").get("en").asString().startsWith("Eid")) {
                assertThat(h.get("isEstimated").asBoolean()).isTrue();
            }
        }
        assertThat(callInvalid(get("/v1/ref/public-holidays?countryCode=ET&year=1999"), null, null)
                        .status())
                .isEqualTo(400);
    }
}
