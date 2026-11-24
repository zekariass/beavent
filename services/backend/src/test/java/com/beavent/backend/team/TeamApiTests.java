package com.beavent.backend.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ApiTestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Team (plan step 1.2): profiles, pricing levels, working patterns, shifts, time off and the rota. */
class TeamApiTests extends ApiTestSupport {

    Map<String, Object> profile(String name, Salon salon, Object... extra) {
        Map<String, Object> input = new HashMap<>();
        input.put("displayName", name);
        input.put("locationIds", List.of(salon.location1()));
        for (int i = 0; i < extra.length; i += 2) {
            input.put((String) extra[i], extra[i + 1]);
        }
        return input;
    }

    String createStaff(Salon salon, Map<String, Object> input) throws Exception {
        Call created = call(post(salon.path("/staff")), input, salon.owner().token());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return created.text("id");
    }

    /** "date 1, 2 intervals: 05:30-09:30, 10:30-15:00" in UTC for one person's schedule. */
    static List<String> days(JsonNode schedule) {
        List<String> out = new ArrayList<>();
        for (JsonNode day : schedule.get("days")) {
            StringBuilder line = new StringBuilder(day.get("date").asString())
                    .append(" ")
                    .append(day.get("source").asString());
            for (JsonNode i : day.get("intervals")) {
                line.append(" ")
                        .append(i.get("startsAt").asString(), 11, 16)
                        .append("-")
                        .append(i.get("endsAt").asString(), 11, 16);
            }
            out.add(line.toString());
        }
        return out;
    }

    @Test
    void profilesPricingLevelsAndLogins() throws Exception {
        Salon salon = newSalon("Xi Barbers");
        String t = salon.owner().token();
        Call senior = call(post(salon.path("/pricing-levels")), Map.of("name", "Senior", "rank", 2), t);
        assertThat(senior.status()).isEqualTo(201);
        assertThat(call(post(salon.path("/pricing-levels")), Map.of("name", "senior"), t)
                        .status())
                .isEqualTo(409);

        String cut = call(
                        post(salon.path("/services")),
                        Map.of(
                                "categoryId", category("SERVICE", "HAIR"),
                                "name", Map.of("en", "Cut"),
                                "variants",
                                        List.of(Map.of(
                                                "durationMinutes", 30, "priceType", "FIXED", "priceMinor", 40_000))),
                        t)
                .text("id");
        Map<String, Object> input = profile(
                "Abebe",
                salon,
                "jobTitle",
                "Senior barber",
                "pricingLevelId",
                senior.text("id"),
                "languages",
                List.of("am", "en"),
                "serviceIds",
                List.of(cut),
                "calendarColor",
                "#5b3fd9");
        Call created = call(post(salon.path("/staff")), input, t);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("serviceIds").get(0).asString()).isEqualTo(cut);
        assertThat(call(get(salon.path("/services/" + cut)), null, t)
                        .body()
                        .get("staffIds")
                        .get(0)
                        .asString())
                .isEqualTo(created.text("id"));

        // Link the owner's login; a login belongs to one profile only.
        String ownerMember = call(get(salon.path("/members")), null, t)
                .body()
                .get("items")
                .get(0)
                .get("id")
                .asString();
        input.put("memberId", ownerMember);
        input.put("locationIds", List.of(salon.location1(), salon.location2()));
        Call linked =
                call(put(salon.path("/staff/" + created.text("id"))).header("If-Match", created.etag()), input, t);
        assertThat(linked.status()).isEqualTo(200);
        assertThat(linked.text("memberId")).isEqualTo(ownerMember);
        assertThat(call(post(salon.path("/staff")), profile("Copy", salon, "memberId", ownerMember), t)
                        .status())
                .isEqualTo(409);
        assertThat(call(put(salon.path("/staff/" + created.text("id"))).header("If-Match", created.etag()), input, t)
                        .status())
                .as("stale version")
                .isEqualTo(412);

        assertThat(call(delete(salon.path("/pricing-levels/" + senior.text("id"))), null, t)
                        .status())
                .as("level in use")
                .isEqualTo(409);
        assertThat(call(get(salon.path("/staff?locationId=" + salon.location2())), null, t)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(call(delete(salon.path("/staff/" + created.text("id"))), null, t)
                        .status())
                .isEqualTo(204);
        assertThat(call(get(salon.path("/staff")), null, t).body().get("items").size())
                .isZero();

        Salon other = newSalon("Omicron Spa");
        assertThat(call(
                                get(other.path("/staff/" + created.text("id"))),
                                null,
                                other.owner().token())
                        .status())
                .isEqualTo(404);
        assertThat(call(post(salon.path("/staff")), profile("Spy", salon, "locationIds", List.of(other.location1())), t)
                        .status())
                .isEqualTo(422);
    }

    @Test
    void theRotaCombinesRotationsShiftsAndTimeOff() throws Exception {
        Salon salon = newSalon("Pi Salon");
        String t = salon.owner().token();
        String abebe = createStaff(salon, profile("Abebe", salon));
        String loc = salon.location1().toString();
        // Two-week rotation from Monday 2026-11-23 (Addis Ababa is UTC+3).
        Call pattern = call(
                put(salon.path("/staff/" + abebe + "/working-pattern")),
                Map.of(
                        "effectiveFrom",
                        "2026-11-23",
                        "repeatWeeks",
                        2,
                        "entries",
                        List.of(
                                Map.of(
                                        "week",
                                        1,
                                        "locationId",
                                        loc,
                                        "isoWeekday",
                                        1,
                                        "startsAt",
                                        "08:30",
                                        "endsAt",
                                        "12:30"),
                                Map.of(
                                        "week",
                                        1,
                                        "locationId",
                                        loc,
                                        "isoWeekday",
                                        1,
                                        "startsAt",
                                        "13:30",
                                        "endsAt",
                                        "18:00"),
                                Map.of(
                                        "week",
                                        1,
                                        "locationId",
                                        loc,
                                        "isoWeekday",
                                        2,
                                        "startsAt",
                                        "09:00",
                                        "endsAt",
                                        "17:00"),
                                Map.of(
                                        "week",
                                        2,
                                        "locationId",
                                        loc,
                                        "isoWeekday",
                                        1,
                                        "startsAt",
                                        "10:00",
                                        "endsAt",
                                        "16:00"))),
                t);
        assertThat(pattern.status()).as(String.valueOf(pattern.body())).isEqualTo(200);
        assertThat(pattern.body().get("entries").size()).isEqualTo(4);

        // A one-off shift on Wednesday, approved time off all of Tuesday and one hour on Monday.
        assertThat(call(
                                post(salon.path("/shifts")),
                                Map.of(
                                        "staffId",
                                        abebe,
                                        "locationId",
                                        loc,
                                        "startsAt",
                                        "2026-11-25T11:00:00Z",
                                        "endsAt",
                                        "2026-11-25T17:00:00Z"),
                                t)
                        .status())
                .isEqualTo(201);
        assertThat(call(
                                post(salon.path("/shifts")),
                                Map.of(
                                        "staffId",
                                        abebe,
                                        "locationId",
                                        loc,
                                        "startsAt",
                                        "2026-11-25T16:00:00Z",
                                        "endsAt",
                                        "2026-11-25T18:00:00Z"),
                                t)
                        .status())
                .as("overlapping shift")
                .isEqualTo(409);
        assertThat(call(
                                post(salon.path("/shifts")),
                                Map.of(
                                        "staffId",
                                        abebe,
                                        "locationId",
                                        salon.location2(),
                                        "startsAt",
                                        "2026-11-26T11:00:00Z",
                                        "endsAt",
                                        "2026-11-26T17:00:00Z"),
                                t)
                        .status())
                .as("not one of their locations")
                .isEqualTo(422);
        Call dayOff = call(
                post(salon.path("/time-off")),
                Map.of(
                        "staffId",
                        abebe,
                        "startsAt",
                        "2026-11-23T21:00:00Z",
                        "endsAt",
                        "2026-11-24T21:00:00Z",
                        "kind",
                        "ANNUAL"),
                t);
        assertThat(dayOff.text("status"))
                .as("managers record approved time off")
                .isEqualTo("APPROVED");
        call(
                post(salon.path("/time-off")),
                Map.of(
                        "staffId",
                        abebe,
                        "startsAt",
                        "2026-11-23T07:00:00Z",
                        "endsAt",
                        "2026-11-23T08:00:00Z",
                        "kind",
                        "OTHER"),
                t);

        JsonNode schedule = call(get(salon.path("/schedule?from=2026-11-23&to=2026-11-30&staffId=" + abebe)), null, t)
                .body();
        assertThat(days(schedule))
                .containsExactly(
                        "2026-11-23 PATTERN 05:30-07:00 08:00-09:30 10:30-15:00",
                        "2026-11-24 PATTERN",
                        "2026-11-25 SHIFTS 11:00-17:00",
                        "2026-11-26 PATTERN",
                        "2026-11-27 PATTERN",
                        "2026-11-28 PATTERN",
                        "2026-11-29 PATTERN",
                        "2026-11-30 PATTERN 07:00-13:00");

        // A new pattern from December leaves November as it was.
        call(
                put(salon.path("/staff/" + abebe + "/working-pattern")),
                Map.of(
                        "effectiveFrom",
                        "2026-12-07",
                        "entries",
                        List.of(Map.of("locationId", loc, "isoWeekday", 1, "startsAt", "12:00", "endsAt", "20:00"))),
                t);
        assertThat(call(get(salon.path("/staff/" + abebe + "/working-pattern?date=2026-11-30")), null, t)
                        .body()
                        .get("repeatWeeks")
                        .asInt())
                .isEqualTo(2);
        assertThat(days(call(get(salon.path("/schedule?from=2026-12-07&to=2026-12-07")), null, t)
                        .body()))
                .containsExactly("2026-12-07 PATTERN 09:00-17:00");
        assertThat(callInvalid(get(salon.path("/schedule?from=2026-01-01&to=2026-12-31")), null, t)
                        .status())
                .as("range limit")
                .isEqualTo(400);
        assertThat(call(
                                put(salon.path("/staff/" + abebe + "/working-pattern")),
                                Map.of(
                                        "effectiveFrom",
                                        "2026-12-14",
                                        "entries",
                                        List.of(
                                                Map.of(
                                                        "locationId",
                                                        loc,
                                                        "isoWeekday",
                                                        1,
                                                        "startsAt",
                                                        "09:00",
                                                        "endsAt",
                                                        "13:00"),
                                                Map.of(
                                                        "locationId",
                                                        loc,
                                                        "isoWeekday",
                                                        1,
                                                        "startsAt",
                                                        "12:00",
                                                        "endsAt",
                                                        "18:00"))),
                                t)
                        .status())
                .as("overlapping hours")
                .isEqualTo(400);
    }

    @Test
    void teamMembersSeeTheirOwnScheduleAndRequestTimeOff() throws Exception {
        Salon salon = newSalon("Rho Nails");
        String t = salon.owner().token();
        Person receptionist = newMember(salon, "RECEPTIONIST");
        String memberId = null;
        for (JsonNode m : call(get(salon.path("/members")), null, t).body().get("items")) {
            if (!m.get("isOwner").asBoolean()) {
                memberId = m.get("id").asString();
            }
        }
        String hana = createStaff(salon, profile("Hana", salon, "memberId", memberId));
        String other = createStaff(salon, profile("Other", salon));

        Call request = call(
                post(salon.path("/time-off")),
                Map.of(
                        "staffId",
                        hana,
                        "startsAt",
                        "2026-12-01T06:00:00Z",
                        "endsAt",
                        "2026-12-01T15:00:00Z",
                        "kind",
                        "SICK"),
                receptionist.token());
        assertThat(request.status()).as(String.valueOf(request.body())).isEqualTo(201);
        assertThat(request.text("status")).isEqualTo("REQUESTED");
        assertThat(call(
                                post(salon.path("/time-off")),
                                Map.of(
                                        "staffId",
                                        other,
                                        "startsAt",
                                        "2026-12-01T06:00:00Z",
                                        "endsAt",
                                        "2026-12-01T15:00:00Z",
                                        "kind",
                                        "SICK"),
                                receptionist.token())
                        .status())
                .isEqualTo(403);
        assertThat(call(
                                get(salon.path("/schedule?from=2026-12-01&to=2026-12-07&staffId=" + hana)),
                                null,
                                receptionist.token())
                        .status())
                .as("own schedule")
                .isEqualTo(200);
        assertThat(call(
                                get(salon.path("/schedule?from=2026-12-01&to=2026-12-07&staffId=" + other)),
                                null,
                                receptionist.token())
                        .status())
                .isEqualTo(200)
                .as("receptionists see every calendar (calendar.view_all)");
        assertThat(call(
                                post(salon.path("/time-off/" + request.text("id") + "/decision")),
                                Map.of("decision", "APPROVE"),
                                receptionist.token())
                        .status())
                .isEqualTo(403);
        Call approved = call(
                post(salon.path("/time-off/" + request.text("id") + "/decision")), Map.of("decision", "APPROVE"), t);
        assertThat(approved.text("status")).isEqualTo("APPROVED");
        assertThat(call(
                                post(salon.path("/time-off/" + request.text("id") + "/decision")),
                                Map.of("decision", "REJECT"),
                                t)
                        .status())
                .isEqualTo(409);

        // "My schedule": the profile linked to your login.
        assertThat(call(get(salon.path("/staff/me")), null, receptionist.token())
                        .text("id"))
                .isEqualTo(hana);
        assertThat(call(get(salon.path("/staff/me")), null, t).status())
                .as("owner has no profile")
                .isEqualTo(404);

        // A professional without calendar.view_all sees only their own schedule.
        Person stylist = newMember(salon, "PROFESSIONAL");
        assertThat(call(
                                get(salon.path("/schedule?from=2026-12-01&to=2026-12-07&staffId=" + other)),
                                null,
                                stylist.token())
                        .status())
                .isEqualTo(403);
        assertThat(call(get(salon.path("/schedule?from=2026-12-01&to=2026-12-07")), null, stylist.token())
                        .status())
                .isEqualTo(403);
    }
}
