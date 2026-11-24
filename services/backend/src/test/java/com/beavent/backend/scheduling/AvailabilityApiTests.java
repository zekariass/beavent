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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Availability end to end (plan step 2.1, slice 2): the facts are read from the real tables (rota, hours, closures,
 * holidays, reservations, resources, policy) and handed to the engine; the engine itself is tested in
 * {@link AvailabilityEngineTest} and {@link AvailabilityEnginePropertyTest}.
 */
class AvailabilityApiTests extends ApiTestSupport {

    static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");

    /** A day far enough ahead that the default one-hour notice never matters. */
    static LocalDate day() {
        return LocalDate.now(ADDIS).plusDays(3);
    }

    // --- set-up ----------------------------------------------------------------------------------------------

    /** A salon open 09:00-18:00 every day (no holiday closures), with a 60-minute haircut and two stylists. */
    record Shop(Salon salon, String haircut, String variant, String abebe, String sara) {}

    Shop shop() throws Exception {
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

    static Map<String, Object> variant(int minutes, int bufferAfter, List<?> stages, List<?> resources) {
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

    Call service(Salon salon, String name, List<Map<String, Object>> variants, Map<String, Object> extra)
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
    String staff(Salon salon, String name, List<String> serviceIds, int sortOrder) throws Exception {
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

    static java.time.OffsetDateTime at(LocalDate date, String time) {
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

    static Map<String, Object> item(String variant, String staff, String... addons) {
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

    // --- tests -----------------------------------------------------------------------------------------------

    @Test
    void offersTheGridInsideOpeningHoursAndTheFirstFreePersonDoesIt() throws Exception {
        Shop shop = shop();
        Call open = search(shop, day(), item(shop.variant(), null));
        List<String> starts = starts(open);
        // 60 minutes in a 09:00-18:00 day on a 15-minute grid: the last start is 17:00.
        assertThat(starts).hasSize(33).startsWith("09:00", "09:15").endsWith("16:45", "17:00");
        assertThat(open.body().get("timezone").asString()).isEqualTo("Africa/Addis_Ababa");
        assertThat(staffOf(open, "09:00", 0)).isEqualTo(shop.abebe());

        // Abebe is busy 10:00-11:00: his 10-minute buffer after a 09:00 haircut would run into it, so Sara takes 09:00.
        blockStaff(shop, shop.abebe(), day(), "10:00", "11:00");
        Call anyone = search(shop, day(), item(shop.variant(), null));
        assertThat(staffOf(anyone, "09:00", 0)).isEqualTo(shop.sara());
        assertThat(staffOf(anyone, "11:00", 0)).isEqualTo(shop.abebe());

        // Asking for Abebe alone: nothing from 09:00 (buffer) until 10:45 conflicts; the first start is 11:00.
        Call abebe = search(shop, day(), item(shop.variant(), shop.abebe()));
        assertThat(starts(abebe)).startsWith("11:00").doesNotContain("09:00", "09:15", "10:00", "10:45");
        assertThat(starts(abebe)).hasSize(25);
    }

    @Test
    void liveHoldsBlockAndExpiredHoldsDoNot() throws Exception {
        Shop shop = shop();
        blockStaff(shop, shop.sara(), day(), "09:00", "18:00");
        // Abebe is held 09:00-10:00 by someone who still has time to finish.
        holdStaff(shop, shop.abebe(), day(), "09:00", "10:00", Instant.now().plusSeconds(600));
        Call held = search(shop, day(), item(shop.variant(), null));
        assertThat(starts(held))
                .doesNotContain("09:00", "09:15", "09:30", "09:45")
                .contains("10:00");

        // Another day: the same hold, but its time has run out: the slot is free again.
        LocalDate next = day().plusDays(1);
        blockStaff(shop, shop.sara(), next, "09:00", "18:00");
        holdStaff(shop, shop.abebe(), next, "09:00", "10:00", Instant.now().minusSeconds(60));
        assertThat(starts(search(shop, next, item(shop.variant(), null)))).startsWith("09:00");
    }

    @Test
    void closuresAndPublicHolidaysCloseTheLocation() throws Exception {
        Shop shop = shop();
        LocalDate closed = day();
        assertThat(starts(search(shop, closed, item(shop.variant(), null)))).isNotEmpty();

        // A closure from noon: only the morning remains (the haircut must end by 12:00: last start 11:00).
        system.execute(
                "insert into tenant.location_closures (org_id, location_id, closed_during, reason) "
                        + "values (?, ?, tstzrange(?::timestamptz, ?::timestamptz), 'Water cut')",
                shop.salon().id(),
                shop.salon().location1(),
                at(closed, "12:00"),
                at(closed, "20:00"));
        List<String> morning = starts(search(shop, closed, item(shop.variant(), null)));
        assertThat(morning).startsWith("09:00").endsWith("11:00").hasSize(9);

        // A public holiday closes a location that closes on holidays, and not one that does not.
        LocalDate holiday = day().plusDays(2);
        system.execute(
                "insert into ref.public_holidays (country_code, holiday_date, name_i18n) values ('ET', ?, ?::jsonb)",
                holiday,
                "{\"en\":\"Test holiday\"}");
        try {
            assertThat(starts(search(shop, holiday, item(shop.variant(), null))))
                    .isNotEmpty();
            system.update(LOCATIONS)
                    .set(LOCATIONS.CLOSED_ON_PUBLIC_HOLIDAYS, true)
                    .where(LOCATIONS.ORG_ID.eq(shop.salon().id()))
                    .execute();
            assertThat(starts(search(shop, holiday, item(shop.variant(), null))))
                    .isEmpty();
            assertThat(starts(search(shop, holiday.plusDays(1), item(shop.variant(), null))))
                    .isNotEmpty();
        } finally {
            system.execute(
                    "delete from ref.public_holidays where holiday_date = ? and name_i18n->>'en' = 'Test holiday'",
                    holiday);
        }
    }

    @Test
    void processingTimeFreesThePersonAndAddOnsLengthenTheVisit() throws Exception {
        Shop shop = shop();
        // Colour: 30 min apply (busy), 35 min processing (free), 25 min rinse (busy) = 90 minutes.
        Call colour = service(
                shop.salon(),
                "Colour",
                List.of(variant(
                        90,
                        0,
                        List.of(
                                Map.of("kind", "SERVICE", "durationMinutes", 30),
                                Map.of("kind", "PROCESSING", "durationMinutes", 35, "staffBusy", false),
                                Map.of("kind", "FINISHING", "durationMinutes", 25)),
                        null)),
                null);
        String colourVariant = colour.body().get("variants").get(0).get("id").asString();
        String carla = staff(shop.salon(), "Carla", List.of(colour.text("id")), 2);

        // Carla is blocked 09:30-10:05: exactly the processing window of a 09:00 colour. She is free then.
        blockStaff(shop, carla, day(), "09:30", "10:05");
        Call free = search(shop, day(), item(colourVariant, carla));
        assertThat(starts(free)).contains("09:00");
        assertThat(starts(free)).doesNotContain("09:15"); // 09:15 would apply until 09:45: into the block

        // An add-on of 15 minutes lengthens a 30-minute trim to 45 minutes.
        Call addon = call(
                post(shop.salon().path("/addons")),
                Map.of("name", Map.of("en", "Deep conditioning"), "durationMinutes", 15, "priceMinor", 30_000),
                shop.salon().owner().token());
        assertThat(addon.status()).isEqualTo(201);
        Call trim = service(
                shop.salon(),
                "Trim",
                List.of(variant(30, 0, null, null)),
                Map.of("addonIds", List.of(addon.text("id"))));
        String trimVariant = trim.body().get("variants").get(0).get("id").asString();
        staff(shop.salon(), "Gina", List.of(trim.text("id")), 3);
        Call plain = search(shop, day(), item(trimVariant, null));
        Call longer = search(shop, day(), item(trimVariant, null, addon.text("id")));
        assertThat(starts(plain)).endsWith("17:30");
        assertThat(starts(longer)).endsWith("17:15");
        assertThat(Instant.parse(longer.body().get("slots").get(0).get("end").asString())
                        .atZone(ADDIS)
                        .toLocalTime())
                .isEqualTo(LocalTime.of(9, 45));
        // An add-on that the service does not offer is refused.
        assertThat(search(shop.salon().location1(), day(), day(), item(shop.variant(), null, addon.text("id")))
                        .status())
                .isEqualTo(422);
    }

    @Test
    void resourcesLimitWhatCanRunInParallel() throws Exception {
        Shop shop = shop();
        Call basin = call(
                post(shop.salon().path("/resources")),
                Map.of("locationId", shop.salon().location1(), "name", "Basin", "kind", "STATION", "units", 1),
                shop.salon().owner().token());
        assertThat(basin.status()).isEqualTo(201);
        Call wash = service(
                shop.salon(),
                "Wash",
                List.of(variant(30, 0, null, List.of(Map.of("resourceId", basin.text("id"), "units", 1)))),
                null);
        String washVariant = wash.body().get("variants").get(0).get("id").asString();
        staff(shop.salon(), "Dawit", List.of(wash.text("id")), 3);

        assertThat(starts(search(shop, day(), item(washVariant, null)))).startsWith("09:00");
        blockResource(shop, basin.text("id"), 1, day(), "09:00", "12:00");
        List<String> afterBlock = starts(search(shop, day(), item(washVariant, null)));
        assertThat(afterBlock).startsWith("12:00").doesNotContain("11:45");
    }

    @Test
    void severalServicesRunBackToBackEachWithItsOwnPerson() throws Exception {
        Shop shop = shop();
        Call manicure = service(shop.salon(), "Manicure", List.of(variant(45, 0, null, null)), null);
        String manicureVariant =
                manicure.body().get("variants").get(0).get("id").asString();
        String nina = staff(shop.salon(), "Nina", List.of(manicure.text("id")), 4);

        Call both = search(shop, day(), item(shop.variant(), null), item(manicureVariant, null));
        // Haircut 09:00-10:00 (+10 buffer) then manicure with Nina 10:00-10:45: the whole booking ends at 10:45.
        assertThat(starts(both)).startsWith("09:00");
        assertThat(staffOf(both, "09:00", 0)).isEqualTo(shop.abebe());
        assertThat(staffOf(both, "09:00", 1)).isEqualTo(nina);
        JsonNode first = both.body().get("slots").get(0);
        assertThat(Instant.parse(first.get("end").asString()).atZone(ADDIS).toLocalTime())
                .isEqualTo(LocalTime.of(10, 45));
        // 105 minutes in a 09:00-18:00 day: the last start is 16:15.
        assertThat(starts(both)).endsWith("16:15");
    }

    @Test
    void visitsAtTheCustomersPlaceIgnoreOpeningHoursButPayTravelTime() throws Exception {
        Shop shop = shop();
        Call home = service(
                shop.salon(),
                "Home braids",
                List.of(variant(60, 0, null, null)),
                Map.of("deliveryModes", List.of("AT_CUSTOMER")));
        String homeVariant = home.body().get("variants").get(0).get("id").asString();
        staff(shop.salon(), "Eden", List.of(home.text("id")), 5);
        system.update(LOCATIONS)
                .set(LOCATIONS.SERVICE_MODE, "BOTH")
                .where(LOCATIONS.ORG_ID.eq(shop.salon().id()))
                .execute();
        Call area = call(
                post(shop.salon().path("/locations/" + shop.salon().location1() + "/service-areas")),
                Map.of(
                        "name",
                        "Nearby",
                        "center",
                        Map.of("latitude", 8.9956, "longitude", 38.7891),
                        "radiusMeters",
                        5000,
                        "travelFeeMinor",
                        10_000,
                        "travelMinutes",
                        30),
                shop.salon().owner().token());
        assertThat(area.status()).isEqualTo(201);

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(homeVariant, null)));
        body.put("from", day().toString());
        body.put("to", day().toString());
        body.put("serviceAreaId", area.text("id"));
        Call visit = call(post("/v1/public/locations/" + shop.salon().location1() + "/availability"), body, null);
        // Eden works 09:00-18:00. Travel 30 minutes before and after: first start 09:30, last start 16:30.
        assertThat(starts(visit)).startsWith("09:30").endsWith("16:30");

        // The venue does not offer a home service at the venue.
        Call atVenue = search(shop.salon().location1(), day(), day(), item(homeVariant, null));
        assertThat(atVenue.status()).isEqualTo(422);
    }

    @Test
    void refusesWhatCannotBeBooked() throws Exception {
        Shop shop = shop();
        // Unknown location, a location that does not take online bookings, an unknown service.
        assertThat(search(UUID.randomUUID(), day(), day(), item(shop.variant(), null))
                        .status())
                .isEqualTo(404);
        assertThat(search(
                                shop.salon().location1(),
                                day(),
                                day(),
                                item(UUID.randomUUID().toString(), null))
                        .status())
                .isEqualTo(422);
        // A person who does not do the service.
        Call other = service(shop.salon(), "Facial", List.of(variant(45, 0, null, null)), null);
        String loner = staff(shop.salon(), "Fikir", List.of(other.text("id")), 6);
        assertThat(search(shop.salon().location1(), day(), day(), item(shop.variant(), loner))
                        .status())
                .isEqualTo(422);
        // A service hidden from online booking.
        Call hidden = service(
                shop.salon(), "Staff only", List.of(variant(30, 0, null, null)), Map.of("onlineBooking", false));
        assertThat(search(
                                shop.salon().location1(),
                                day(),
                                day(),
                                item(
                                        hidden.body()
                                                .get("variants")
                                                .get(0)
                                                .get("id")
                                                .asString(),
                                        null))
                        .status())
                .isEqualTo(422);
        // Too many days, a backwards range, no services.
        assertThat(callInvalid(
                                post("/v1/public/locations/" + shop.salon().location1() + "/availability"),
                                Map.of(
                                        "items",
                                        List.of(item(shop.variant(), null)),
                                        "from",
                                        day().toString(),
                                        "to",
                                        day().plusDays(20).toString()),
                                null)
                        .status())
                .isEqualTo(400);
        assertThat(callInvalid(
                                post("/v1/public/locations/" + shop.salon().location1() + "/availability"),
                                Map.of("items", List.of(), "from", day().toString(), "to", day().toString()),
                                null)
                        .status())
                .isEqualTo(400);
        system.update(LOCATIONS)
                .set(LOCATIONS.ONLINE_BOOKING, false)
                .where(LOCATIONS.ORG_ID.eq(shop.salon().id()))
                .execute();
        assertThat(search(shop.salon().location1(), day(), day(), item(shop.variant(), null))
                        .status())
                .isEqualTo(404);
    }

    @Test
    void oneLocationDayWithTenStaffAnswersWellWithinTheBudget() throws Exception {
        Shop shop = shop();
        Call manicure = service(shop.salon(), "Manicure", List.of(variant(45, 5, null, null)), null);
        Call colour = service(
                shop.salon(),
                "Colour",
                List.of(variant(
                        90,
                        0,
                        List.of(
                                Map.of("kind", "SERVICE", "durationMinutes", 30),
                                Map.of("kind", "PROCESSING", "durationMinutes", 35, "staffBusy", false),
                                Map.of("kind", "FINISHING", "durationMinutes", 25)),
                        null)),
                null);
        List<String> all = List.of(shop.haircut(), manicure.text("id"), colour.text("id"));
        List<String> people = new ArrayList<>(List.of(shop.abebe(), shop.sara()));
        for (int i = 0; i < 8; i++) {
            people.add(staff(shop.salon(), "Stylist " + i, all, 10 + i));
        }
        // Everyone also does the other services (the first two only did the haircut).
        for (String staffId : people.subList(0, 2)) {
            system.execute(
                    "insert into catalog.staff_services (org_id, staff_id, service_id) values (?, ?, ?), (?, ?, ?)",
                    shop.salon().id(),
                    UUID.fromString(staffId),
                    UUID.fromString(manicure.text("id")),
                    shop.salon().id(),
                    UUID.fromString(staffId),
                    UUID.fromString(colour.text("id")));
        }
        // 60 existing appointments' worth of reservations: six per person, scattered through the day.
        String[][] times = {
            {"09:00", "10:00"},
            {"10:15", "11:00"},
            {"11:30", "12:30"},
            {"13:00", "14:00"},
            {"14:30", "15:15"},
            {"16:00", "17:00"}
        };
        for (String staffId : people) {
            for (String[] t : times) {
                blockStaff(shop, staffId, day(), t[0], t[1]);
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put(
                "items",
                List.of(
                        item(shop.variant(), null),
                        item(manicure.body().get("variants").get(0).get("id").asString(), null),
                        item(colour.body().get("variants").get(0).get("id").asString(), null)));
        body.put("from", day().toString());
        body.put("to", day().toString());
        String json = this.json.writeValueAsString(body);

        List<Long> millis = new ArrayList<>();
        int status = 0;
        for (int i = 0; i < 9; i++) {
            long started = System.nanoTime();
            status = mvc.perform(MockMvcRequestBuilders.post(
                                    "/v1/public/locations/" + shop.salon().location1() + "/availability")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            millis.add((System.nanoTime() - started) / 1_000_000);
        }
        assertThat(status).isEqualTo(200);
        List<Long> warm = millis.subList(3, millis.size()).stream().sorted().toList();
        long median = warm.get(warm.size() / 2);
        System.out.println(
                "availability, 10 staff, 60 reservations, 3 items: " + millis + " ms (median " + median + ")");
        assertThat(median).as("median over warm requests, milliseconds").isLessThan(300);
    }
}
