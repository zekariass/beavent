package com.beavent.backend.scheduling;

import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATION_HOURS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ApiTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Set-up shared by the scheduling API tests: a salon with hours, a haircut, two stylists, and ledger helpers. */
public abstract class SchedulingTestSupport extends ApiTestSupport {

    protected static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");

    /** A day far enough ahead that the default one-hour notice never matters. */
    protected static LocalDate day() {
        return LocalDate.now(ADDIS).plusDays(3);
    }

    // --- set-up ----------------------------------------------------------------------------------------------

    /** A salon open 09:00-18:00 every day (no holiday closures), with a 60-minute haircut and two stylists. */
    protected record Shop(Salon salon, String haircut, String variant, String abebe, String sara) {}

    protected Shop shop() throws Exception {
        Salon salon = newSalon("Availability " + UUID.randomUUID().toString().substring(0, 6));
        for (int weekday = 1; weekday <= 7; weekday++) {
            system.insertInto(LOCATION_HOURS)
                    .set(LOCATION_HOURS.ORG_ID, salon.id())
                    .set(LOCATION_HOURS.LOCATION_ID, salon.location1())
                    .set(LOCATION_HOURS.ISO_WEEKDAY, (short) weekday)
                    .set(LOCATION_HOURS.OPENS_AT, LocalTime.of(9, 0))
                    .set(LOCATION_HOURS.CLOSES_AT, LocalTime.of(18, 0))
                    .execute();
        }
        system.update(LOCATIONS)
                .set(LOCATIONS.CLOSED_ON_PUBLIC_HOLIDAYS, false)
                .where(LOCATIONS.ORG_ID.eq(salon.id()))
                .execute();
        Call service = service(salon, "Haircut", List.of(variant(60, 10, null, null)), null);
        String variant = service.body().get("variants").get(0).get("id").asString();
        String serviceId = service.text("id");
        String abebe = staff(salon, "Abebe", List.of(serviceId), 0);
        String sara = staff(salon, "Sara", List.of(serviceId), 1);
        return new Shop(salon, serviceId, variant, abebe, sara);
    }

    protected static Map<String, Object> variant(int minutes, int bufferAfter, List<?> stages, List<?> resources) {
        Map<String, Object> v = new HashMap<>();
        v.put("durationMinutes", minutes);
        v.put("bufferAfterMinutes", bufferAfter);
        v.put("priceType", "FIXED");
        v.put("priceMinor", 50_000);
        if (stages != null) {
            v.put("stages", stages);
        }
        if (resources != null) {
            v.put("resourceRequirements", resources);
        }
        return v;
    }

    protected Call service(Salon salon, String name, List<Map<String, Object>> variants, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("categoryId", category("SERVICE", "HAIR"));
        input.put("name", Map.of("en", name));
        input.put("variants", variants);
        input.put("locationIds", List.of(salon.location1()));
        if (extra != null) {
            input.putAll(extra);
        }
        Call created = call(post(salon.path("/services")), input, salon.owner().token());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return created;
    }

    /** A team member at location 1 who does the services and works 09:00-18:00 every day. */
    protected String staff(Salon salon, String name, List<String> serviceIds, int sortOrder) throws Exception {
        Call created = call(
                post(salon.path("/staff")),
                Map.of(
                        "displayName",
                        name,
                        "locationIds",
                        List.of(salon.location1()),
                        "serviceIds",
                        serviceIds,
                        "sortOrder",
                        sortOrder),
                salon.owner().token());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            entries.add(Map.of(
                    "week",
                    1,
                    "locationId",
                    salon.location1().toString(),
                    "isoWeekday",
                    weekday,
                    "startsAt",
                    "09:00",
                    "endsAt",
                    "18:00"));
        }
        Call pattern = call(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        salon.path("/staff/" + created.text("id") + "/working-pattern")),
                Map.of("effectiveFrom", day().minusDays(14).toString(), "repeatWeeks", 1, "entries", entries),
                salon.owner().token());
        assertThat(pattern.status()).as(String.valueOf(pattern.body())).isEqualTo(200);
        return created.text("id");
    }

    // --- facts the API does not write yet (calendar blocks, holds) --------------------------------------------

    protected static java.time.OffsetDateTime at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(ADDIS).toOffsetDateTime();
    }

    /** A block of one person's time (a break, a meeting): the reservation ledger row the calendar writes. */
    void blockStaff(Shop shop, String staffId, LocalDate date, String from, String to) {
        UUID block = UUID.randomUUID();
        system.execute(
                "insert into scheduling.calendar_blocks (id, org_id, location_id, staff_id, during, reason) "
                        + "values (?, ?, ?, ?, tstzrange(?::timestamptz, ?::timestamptz), 'BREAK')",
                block,
                shop.salon().id(),
                shop.salon().location1(),
                UUID.fromString(staffId),
                at(date, from),
                at(date, to));
        system.execute(
                "insert into scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, "
                        + "calendar_block_id) values (?, ?, 'STAFF', ?, tstzrange(?::timestamptz, ?::timestamptz), 'BLOCKED', ?)",
                shop.salon().id(),
                shop.salon().location1(),
                UUID.fromString(staffId),
                at(date, from),
                at(date, to),
                block);
    }

    void blockResource(Shop shop, String resourceId, int unit, LocalDate date, String from, String to) {
        UUID block = UUID.randomUUID();
        system.execute(
                "insert into scheduling.calendar_blocks (id, org_id, location_id, resource_id, during, reason) "
                        + "values (?, ?, ?, ?, tstzrange(?::timestamptz, ?::timestamptz), 'MAINTENANCE')",
                block,
                shop.salon().id(),
                shop.salon().location1(),
                UUID.fromString(resourceId),
                at(date, from),
                at(date, to));
        system.execute(
                "insert into scheduling.schedule_reservations (org_id, location_id, kind, resource_id, resource_unit, "
                        + "during, status, calendar_block_id) values (?, ?, 'RESOURCE', ?, ?, tstzrange(?::timestamptz, ?::timestamptz), "
                        + "'BLOCKED', ?)",
                shop.salon().id(),
                shop.salon().location1(),
                UUID.fromString(resourceId),
                (short) unit,
                at(date, from),
                at(date, to),
                block);
    }

    /** A customer's hold on a person's time, running until {@code expires}. */
    void holdStaff(Shop shop, String staffId, LocalDate date, String from, String to, Instant expires) {
        UUID hold = UUID.randomUUID();
        system.execute(
                "insert into scheduling.holds (id, org_id, holder_key, expires_at) values (?, ?, 'test', ?::timestamptz)",
                hold,
                shop.salon().id(),
                expires.atOffset(ZoneOffset.UTC));
        system.execute(
                "insert into scheduling.schedule_reservations (org_id, location_id, kind, staff_id, during, status, "
                        + "hold_id, hold_expires_at) values (?, ?, 'STAFF', ?, tstzrange(?::timestamptz, ?::timestamptz), 'HELD', ?, ?::timestamptz)",
                shop.salon().id(),
                shop.salon().location1(),
                UUID.fromString(staffId),
                at(date, from),
                at(date, to),
                hold,
                expires.atOffset(ZoneOffset.UTC));
    }

    // --- asking ----------------------------------------------------------------------------------------------

    protected static Map<String, Object> item(String variant, String staff, String... addons) {
        Map<String, Object> item = new HashMap<>();
        item.put("variantId", variant);
        if (staff != null) {
            item.put("staffId", staff);
        }
        if (addons.length > 0) {
            item.put("addonIds", List.of(addons));
        }
        return item;
    }

    Call search(UUID location, LocalDate from, LocalDate to, Map<String, Object>... items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(items));
        body.put("from", from.toString());
        body.put("to", to.toString());
        return call(post("/v1/public/locations/" + location + "/availability"), body, null);
    }

    Call search(Shop shop, LocalDate date, Map<String, Object>... items) throws Exception {
        Call result = search(shop.salon().location1(), date, date, items);
        assertThat(result.status()).as(String.valueOf(result.body())).isEqualTo(200);
        return result;
    }

    /** "09:00" style local start times. */
    static List<String> starts(Call result) {
        List<String> out = new ArrayList<>();
        for (JsonNode slot : result.body().get("slots")) {
            out.add(Instant.parse(slot.get("start").asString())
                    .atZone(ADDIS)
                    .toLocalTime()
                    .toString());
        }
        return out;
    }

    static String staffOf(Call result, String start, int itemIndex) {
        for (JsonNode slot : result.body().get("slots")) {
            if (Instant.parse(slot.get("start").asString())
                    .atZone(ADDIS)
                    .toLocalTime()
                    .toString()
                    .equals(start)) {
                return slot.get("items").get(itemIndex).get("staffId").asString();
            }
        }
        throw new AssertionError("no slot at " + start);
    }
}
