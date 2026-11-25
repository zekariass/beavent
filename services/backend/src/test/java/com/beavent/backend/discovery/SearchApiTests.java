package com.beavent.backend.discovery;

import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATION_HOURS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Discovery and search (plan step 8.1): the projector builds listings, the public API finds them. */
class SearchApiTests extends SchedulingTestSupport {

    @Autowired
    Listings listings;

    /** A word no other test uses, so searches only see this test's places. */
    static String marker() {
        return "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    record Place(Salon salon, String name) {}

    /**
     * A listed, active business with one location: open 09:00-18:00 every day, one braids service and one person.
     * {@code lat}/{@code lng} place it on the map.
     */
    Place place(String name, double lat, double lng, long price, Map<String, Object> nameTexts) throws Exception {
        Salon salon = newSalon(name);
        system.execute("UPDATE tenant.locations SET status = 'ARCHIVED' WHERE id = ?", salon.location2());
        system.execute(
                "UPDATE tenant.places SET geo = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography"
                        + " WHERE id = (SELECT place_id FROM tenant.locations WHERE id = ?)",
                lng,
                lat,
                salon.location1());
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
        Map<String, Object> variant = variant(90, 0, null, null);
        variant.put("priceMinor", price);
        Map<String, Object> extra = new HashMap<>();
        extra.put("categoryId", category("SERVICE", "BRAIDS"));
        extra.put("name", nameTexts);
        String serviceId = service(salon, "ignored", List.of(variant), extra).text("id");
        staff(salon, "Hana", List.of(serviceId), 0);
        system.execute(
                "UPDATE tenant.organizations SET status = 'ACTIVE', marketplace_status = 'LISTED' WHERE id = ?",
                salon.id());
        return new Place(salon, name);
    }

    Place place(String name, double lat, double lng, long price) throws Exception {
        return place(name, lat, lng, price, Map.of("en", "Box braids", "am", "ሹሩባ"));
    }

    <T> T one(String sql, Class<T> type, Object... binds) {
        return system.resultQuery(sql, binds).fetchOne(0, type);
    }

    void refresh() {
        listings.refreshAll(Instant.now());
    }

    JsonNode search(String query) throws Exception {
        Call response = call(get("/v1/public/search" + query), null, null);
        assertThat(response.status()).as(String.valueOf(response.body())).isEqualTo(200);
        return response.body();
    }

    static List<String> titles(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("title").asString()));
        return out;
    }

    // --- who is listed ----------------------------------------------------------------------------------------

    @Test
    void onlyActiveBusinessesApprovedForTheMarketplaceAreListed() throws Exception {
        String m = marker();
        Place listed = place(m + " listed", 9.01, 38.76, 100_000);
        Salon hidden = newSalon(m + " hidden");
        system.execute("UPDATE tenant.organizations SET status = 'ACTIVE' WHERE id = ?", hidden.id());
        refresh();

        assertThat(titles(search("?q=" + m))).containsExactly(m + " listed");

        // Delisting takes it out of search at the next refresh, and listing it again brings it back.
        system.execute(
                "UPDATE tenant.organizations SET marketplace_status = 'DELISTED' WHERE id = ?",
                listed.salon().id());
        refresh();
        assertThat(search("?q=" + m).get("items")).isEmpty();
        system.execute(
                "UPDATE tenant.organizations SET marketplace_status = 'LISTED' WHERE id = ?",
                listed.salon().id());
        refresh();
        assertThat(titles(search("?q=" + m))).containsExactly(m + " listed");
    }

    @Test
    void aListingCarriesWhatTheResultRowNeeds() throws Exception {
        String m = marker();
        Place p = place(m + " Studio", 9.02, 38.76, 180_000);
        refresh();

        JsonNode page = search("?q=" + m + "&limit=5");
        assertThat(page.get("total").asInt()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("kind").asString()).isEqualTo("LOCATION");
        assertThat(item.get("title").asString()).isEqualTo(m + " Studio");
        assertThat(item.get("priceFromMinor").asLong()).isEqualTo(180_000);
        assertThat(item.get("currency").asString()).isEqualTo("ETB");
        assertThat(item.get("path").asString())
                .startsWith("/")
                .endsWith("/" + item.get("locationSlug").asString());
        assertThat(item.get("businessSlug").asString()).isNotBlank();
        assertThat(item.get("landmark").asString()).isEqualTo("Near Edna Mall");
        assertThat(item.get("category").get("en").asString()).isEqualTo("Hair salon");
        assertThat(item.get("serviceCount").asInt()).isEqualTo(1);
        assertThat(item.get("timezone").asString()).isEqualTo("Africa/Addis_Ababa");
        assertThat(item.has("nextAvailableAt")).as("a bookable time was found").isTrue();
        assertThat(item.get("latitude").asDouble()).isEqualTo(9.02);
        assertThat(item.get("newOnBeavent").asBoolean()).isTrue();
        // The words typed here (the name) match no service, so no service is shown.
        assertThat(item.get("menu")).isEmpty();
        assertThat(p.name()).isNotBlank();
    }

    // --- words ------------------------------------------------------------------------------------------------

    @Test
    void wholePartialAndMisspeltWordsFindAPlace() throws Exception {
        String m = marker();
        place(m + " Aster", 9.03, 38.76, 100_000);
        refresh();

        assertThat(titles(search("?q=" + m))).hasSize(1);
        assertThat(titles(search("?q=" + m.substring(0, 6)))).contains(m + " Aster"); // a prefix
        assertThat(titles(search("?q=BRAIDS+" + m))).hasSize(1); // a service word and the name, any case
        assertThat(titles(search("?q=" + m + "+aster"))).hasSize(1);
        assertThat(titles(search("?q=" + m + "+nothing"))).isEmpty(); // every word must match
        // A small misspelling still finds the place (trigrams).
        assertThat(titles(search("?q=" + m + "+astter"))).isNotEmpty();
        // Services are searchable in both languages.
        assertThat(titles(search("?q=" + m + "+ሹሩባ"))).hasSize(1);
    }

    @Test
    void aLatinSearchFindsAGeezNameAndAGeezSearchFindsALatinName() throws Exception {
        String m = marker();
        place("ቦሌ ውበት " + m, 9.04, 38.77, 100_000);
        place("Bole Beauty " + m, 9.05, 38.77, 100_000);
        refresh();

        // "bole" finds the Ge'ez name by its Latin spelling, and "ቦሌ" finds the Latin name by its sound.
        assertThat(titles(search("?q=bole+" + m))).containsExactlyInAnyOrder("ቦሌ ውበት " + m, "Bole Beauty " + m);
        assertThat(titles(search("?q=" + "ቦሌ" + "+" + m))).containsExactlyInAnyOrder("ቦሌ ውበት " + m, "Bole Beauty " + m);
    }

    @Test
    void geezLettersThatSoundTheSameFindEachOther() throws Exception {
        String m = marker();
        place("ጸጉር ቤት " + m, 9.06, 38.77, 100_000);
        refresh();
        // The search uses ፀ; the name uses ጸ.
        assertThat(titles(search("?q=" + "ፀጉር" + "+" + m))).hasSize(1);
    }

    @Test
    void theMenuShowsServicesThatMatchWhatWasTyped() throws Exception {
        String m = marker();
        place(m + " Menu", 9.07, 38.77, 120_000, Map.of("en", "Knotless braids", "am", "ሹሩባ"));
        refresh();

        JsonNode menu = search("?q=knotless+" + m).get("items").get(0).get("menu");
        assertThat(menu).hasSize(1);
        assertThat(menu.get(0).get("name").get("en").asString()).isEqualTo("Knotless braids");
        assertThat(menu.get(0).get("priceMinor").asLong()).isEqualTo(120_000);
        assertThat(menu.get(0).get("durationMinutes").asInt()).isEqualTo(90);
        // Filtering by category shows its services too.
        assertThat(search("?q=" + m + "&category=BRAIDS").get("items").get(0).get("menu"))
                .hasSize(1);
    }

    // --- filters and order ------------------------------------------------------------------------------------

    @Test
    void categoryAndAreaNarrowTheResults() throws Exception {
        String m = marker();
        place(m + " One", 9.08, 38.78, 100_000);
        refresh();

        assertThat(titles(search("?q=" + m + "&category=BRAIDS"))).hasSize(1);
        assertThat(titles(search("?q=" + m + "&category=hair_salon"))).hasSize(1); // the business category, any case
        assertThat(titles(search("?q=" + m + "&category=NAILS"))).isEmpty();
        assertThat(titles(search("?q=" + m + "&category=NO_SUCH_CATEGORY"))).isEmpty();
        assertThat(titles(search("?q=" + m + "&area=AA"))).hasSize(1); // the city includes its sub-cities
        assertThat(titles(search("?q=" + m + "&area=DD"))).isEmpty();
        assertThat(titles(search("?q=" + m + "&market=ET"))).hasSize(1);
        assertThat(titles(search("?q=" + m + "&market=KE"))).isEmpty();
    }

    @Test
    void nearMeFiltersByDistanceAndOrdersNearestFirst() throws Exception {
        String m = marker();
        // About 1.1 km apart on a meridian: 0.01 degrees of latitude.
        place(m + " Far", 9.1100, 38.80, 100_000);
        place(m + " Near", 9.1000, 38.80, 100_000);
        place(m + " Away", 9.3000, 38.80, 100_000);
        refresh();

        JsonNode page = search("?q=" + m + "&lat=9.0990&lng=38.80&radius=3000&sort=NEAREST");
        assertThat(titles(page)).containsExactly(m + " Near", m + " Far");
        assertThat(page.get("items").get(0).get("distanceMeters").asInt()).isBetween(50, 300);
        assertThat(page.get("items").get(1).get("distanceMeters").asInt()).isBetween(900, 1400);
        // Without a point there is nothing to measure from.
        Call refused = call(get("/v1/public/search?sort=NEAREST"), null, null);
        assertThat(refused.status()).isEqualTo(400);
        Call lonely = call(get("/v1/public/search?lat=9.1"), null, null);
        assertThat(lonely.status()).isEqualTo(400);
    }

    @Test
    void theMapGetsPinsInsideItsWindow() throws Exception {
        String m = marker();
        place(m + " In", 9.2000, 38.90, 100_000);
        place(m + " Out", 9.4000, 38.90, 100_000);
        refresh();

        Call inside =
                call(get("/v1/public/search/pins?q=" + m + "&west=38.85&south=9.15&east=38.95&north=9.25"), null, null);
        assertThat(inside.status()).isEqualTo(200);
        assertThat(inside.body().get("pins")).hasSize(1);
        assertThat(inside.body().get("pins").get(0).get("title").asString()).isEqualTo(m + " In");
        assertThat(inside.body().get("pins").get(0).get("latitude").asDouble()).isEqualTo(9.2);
        assertThat(inside.body().get("truncated").asBoolean()).isFalse();
        assertThat(search("?q=" + m + "&west=38.85&south=9.15&east=38.95&north=9.25")
                        .get("items"))
                .hasSize(1);
        // A map needs a window or a point.
        assertThat(call(get("/v1/public/search/pins?q=" + m), null, null).status())
                .isEqualTo(400);
        assertThat(call(get("/v1/public/search/pins?west=39&south=9&east=38&north=10"), null, null)
                        .status())
                .isEqualTo(400);
    }

    @Test
    void priceOpeningHoursAndAtHomeFilters() throws Exception {
        String m = marker();
        place(m + " Cheap", 9.5, 39.0, 40_000);
        place(m + " Dear", 9.5, 39.0, 400_000);
        refresh();

        assertThat(titles(search("?q=" + m + "&maxPrice=100000"))).containsExactly(m + " Cheap");
        assertThat(titles(search("?q=" + m + "&sort=LOWEST_PRICE"))).containsExactly(m + " Cheap", m + " Dear");
        assertThat(titles(search("?q=" + m + "&atHome=true"))).isEmpty();
        assertThat(titles(search("?q=" + m + "&minRating=4"))).isEmpty(); // no reviews yet
        // Hours are 09:00-18:00 every day in Addis Ababa: open now only inside them.
        int hour = java.time.ZonedDateTime.now(ADDIS).getHour();
        boolean open = hour >= 9 && hour < 18;
        assertThat(titles(search("?q=" + m + "&openNow=true"))).hasSize(open ? 2 : 0);
        assertThat(titles(search("?q=" + m + "&audience=WOMEN"))).hasSize(2); // services for everyone count
        assertThat(titles(search("?q=" + m + "&language=am"))).isEmpty();
    }

    @Test
    void bookableTimesComeFromAServiceSomebodyOnTheTeamDoes() throws Exception {
        String m = marker();
        Place p = place(m + " Staffed", 9.61, 39.11, 100_000);
        // A shorter service that nobody does must not hide the times of the one the team does.
        Map<String, Object> extra = new HashMap<>();
        extra.put("categoryId", category("SERVICE", "HAIR"));
        service(p.salon(), "Quick", List.of(variant(30, 0, null, null)), extra);
        refresh();

        assertThat(search("?q=" + m).get("items").get(0).has("nextAvailableAt")).isTrue();
        assertThat(titles(search("?q=" + m + "&date=" + day()))).hasSize(1);
    }

    @Test
    void aDateFindsPlacesWithABookableTimeThatDay() throws Exception {
        String m = marker();
        place(m + " Booked", 9.6, 39.1, 100_000);
        refresh();

        String day = day().toString();
        assertThat(titles(search("?q=" + m + "&date=" + day))).hasSize(1);
        assertThat(titles(search("?q=" + m + "&date=" + day + "&part=MORNING"))).hasSize(1);
        assertThat(titles(search("?q=" + m + "&date=" + day + "&part=EVENING")))
                .isEmpty(); // closes at 18:00, last start before
        assertThat(titles(search("?q=" + m + "&date=" + day().plusDays(60)))).isEmpty(); // beyond the weeks listed
        assertThat(titles(search("?q=" + m + "&date=" + day().minusDays(1) + "&dateTo=" + day())))
                .hasSize(1);
        assertThat(callInvalid(get("/v1/public/search?date=not-a-date"), null, null)
                        .status())
                .isEqualTo(400);
        assertThat(call(get("/v1/public/search?part=MORNING"), null, null).status())
                .isEqualTo(400);
    }

    @Test
    void pagesFollowTheCursorWithoutRepeatsAndTheTotalComesFirst() throws Exception {
        String m = marker();
        for (int i = 0; i < 5; i++) {
            place(m + " P" + i, 9.7, 39.2, 100_000 + i * 10_000L);
        }
        refresh();

        for (String sort : List.of("RECOMMENDED", "LOWEST_PRICE", "NEWEST", "TOP_RATED", "SOONEST")) {
            List<String> seen = new ArrayList<>();
            JsonNode page = search("?q=" + m + "&limit=2&sort=" + sort);
            assertThat(page.get("total").asInt()).as(sort).isEqualTo(5);
            int pages = 1;
            while (true) {
                seen.addAll(titles(page));
                if (!page.has("nextCursor")) {
                    break;
                }
                page = search("?q=" + m + "&limit=2&sort=" + sort + "&cursor="
                        + page.get("nextCursor").asString());
                assertThat(page.has("total")).as("total only on the first page").isFalse();
                pages++;
            }
            assertThat(seen).as(sort).hasSize(5).doesNotHaveDuplicates();
            assertThat(pages).as(sort).isEqualTo(3);
        }
        List<String> byPrice = new ArrayList<>();
        JsonNode page = search("?q=" + m + "&limit=3&sort=LOWEST_PRICE");
        byPrice.addAll(titles(page));
        byPrice.addAll(titles(search("?q=" + m + "&limit=3&sort=LOWEST_PRICE&cursor="
                + page.get("nextCursor").asString())));
        assertThat(byPrice).containsExactly(m + " P0", m + " P1", m + " P2", m + " P3", m + " P4");
        assertThat(call(get("/v1/public/search?cursor=garbage"), null, null).status())
                .isEqualTo(400);
    }

    // --- suggestions ------------------------------------------------------------------------------------------

    @Test
    void suggestionsGroupCategoriesAndPlaces() throws Exception {
        String m = marker();
        place(m + " Suggest", 9.8, 39.3, 100_000);
        refresh();

        Call byName = call(get("/v1/public/search/suggestions?q=" + m.substring(0, 7)), null, null);
        assertThat(byName.status()).isEqualTo(200);
        assertThat(byName.body().get("places").get(0).get("title").asString()).isEqualTo(m + " Suggest");

        // "brai" finds the category in English, and the Amharic name finds it too.
        JsonNode braids =
                call(get("/v1/public/search/suggestions?q=brai"), null, null).body();
        assertThat(braids.get("categories").get(0).get("code").asString()).isEqualTo("BRAIDS");
        JsonNode amharic = call(get("/v1/public/search/suggestions?q=" + "ሹሩባ"), null, null)
                .body();
        assertThat(amharic.get("categories").get(0).get("code").asString()).isEqualTo("BRAIDS");
        assertThat(call(get("/v1/public/search/suggestions?q=zzzzzz"), null, null)
                        .body()
                        .get("categories"))
                .isEmpty();
    }

    // --- events -----------------------------------------------------------------------------------------------

    /** An event of the salon's business, with one date {@code startsInDays} from now. */
    UUID event(Salon salon, String title, String status, int startsInDays, String ticketPricing, long price) {
        UUID event = UUID.randomUUID();
        UUID legalEntity = one("SELECT id FROM tenant.legal_entities WHERE org_id = ? LIMIT 1", UUID.class, salon.id());
        system.execute(
                "INSERT INTO events.events (id, org_id, legal_entity_id, slug, category_id, kind, format, visibility,"
                        + " status, published_at, source_language, currency_code, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 'SINGLE', 'IN_PERSON', 'PUBLIC', ?, now(), 'en', 'ETB',"
                        + " (SELECT created_by_user_id FROM tenant.organizations WHERE id = ?))",
                event,
                salon.id(),
                legalEntity,
                "ev-" + event.toString().substring(0, 8),
                category("EVENT", "MUSIC"),
                status,
                salon.id());
        system.execute(
                "INSERT INTO events.event_translations (org_id, event_id, language_code, title, summary)"
                        + " VALUES (?, ?, 'en', ?, 'A night of music')",
                salon.id(),
                event,
                title);
        system.execute(
                "INSERT INTO events.occurrences (org_id, event_id, starts_at, ends_at, timezone, status)"
                        + " VALUES (?, ?, now() + make_interval(days => ?) + interval '2 hours',"
                        + " now() + make_interval(days => ?) + interval '5 hours', 'Africa/Addis_Ababa', 'ON_SALE')",
                salon.id(),
                event,
                startsInDays,
                startsInDays);
        system.execute(
                "INSERT INTO events.ticket_types (org_id, event_id, name_i18n, pricing, currency_code, price_minor)"
                        + " VALUES (?, ?, '{\"en\":\"General\"}'::jsonb, ?, 'ETB', ?)",
                salon.id(),
                event,
                ticketPricing,
                price);
        return event;
    }

    @Test
    void publicEventsAreListedWithTheirNextDateAndPrice() throws Exception {
        String m = marker();
        Place organizer = place(m + " Organizer", 9.9, 39.4, 100_000);
        event(organizer.salon(), m + " Jazz Night", "PUBLISHED", 10, "PAID", 90_000);
        event(organizer.salon(), m + " Free Concert", "PUBLISHED", 20, "FREE", 0);
        event(organizer.salon(), m + " Draft Show", "DRAFT", 30, "PAID", 50_000);
        refresh();

        JsonNode events = search("?q=" + m + "&kind=EVENT&sort=SOONEST");
        assertThat(titles(events)).containsExactly(m + " Jazz Night", m + " Free Concert");
        JsonNode jazz = events.get("items").get(0);
        assertThat(jazz.get("kind").asString()).isEqualTo("EVENT");
        assertThat(jazz.get("eventSlug").asString()).startsWith("ev-");
        assertThat(jazz.has("locationSlug")).isFalse();
        assertThat(jazz.get("priceFromMinor").asLong()).isEqualTo(90_000);
        assertThat(jazz.has("nextStartsAt")).isTrue();
        assertThat(jazz.get("category").get("en").asString()).isEqualTo("Music");
        assertThat(jazz.get("summary").get("en").asString()).isEqualTo("A night of music");

        assertThat(titles(search("?q=" + m + "&kind=EVENT&free=true"))).containsExactly(m + " Free Concert");
        assertThat(titles(search("?q=" + m + "&kind=EVENT&category=MUSIC"))).hasSize(2);
        assertThat(titles(search("?q=" + m + "&kind=EVENT&date="
                        + java.time.LocalDate.now(ADDIS).plusDays(10))))
                .hasSize(1);
        // Places and events come back together unless asked otherwise.
        assertThat(titles(search("?q=" + m))).hasSize(3);
        assertThat(titles(search("?q=" + m + "&kind=LOCATION"))).containsExactly(m + " Organizer");
    }

    @Test
    void anEventThatIsOverOrNoLongerPublicIsTakenDown() throws Exception {
        String m = marker();
        Place organizer = place(m + " Organizer", 9.9, 39.5, 100_000);
        UUID soon = event(organizer.salon(), m + " Soon", "PUBLISHED", 3, "PAID", 10_000);
        UUID over = event(organizer.salon(), m + " Over", "PUBLISHED", 3, "PAID", 10_000);
        refresh();
        assertThat(titles(search("?q=" + m + "&kind=EVENT"))).hasSize(2);

        system.execute("UPDATE events.occurrences SET status = 'CANCELLED' WHERE event_id = ?", over);
        system.execute("UPDATE events.events SET status = 'CANCELLED' WHERE id = ?", soon);
        refresh();
        assertThat(search("?q=" + m + "&kind=EVENT").get("items")).isEmpty();
    }

    @Test
    void refreshingOnePlaceOrEventSkipsTheRest() throws Exception {
        String m = marker();
        Place p = place(m + " Single", 9.95, 39.6, 100_000);
        Listings.Result first = listings.refreshLocation(
                one(
                        "SELECT id FROM tenant.locations WHERE org_id = ? AND status = 'ACTIVE'",
                        UUID.class,
                        p.salon().id()),
                Instant.now());
        assertThat(first).isEqualTo(new Listings.Result(1, 0));
        assertThat(titles(search("?q=" + m))).hasSize(1);
        // A second refresh with nothing changed leaves the row alone.
        UUID listing = one("SELECT id FROM discovery.listings WHERE title = ?", UUID.class, m + " Single");
        Instant before = one(
                        "SELECT updated_at FROM discovery.listings WHERE id = ?",
                        java.time.OffsetDateTime.class,
                        listing)
                .toInstant();
        listings.refreshLocation(
                one(
                        "SELECT id FROM tenant.locations WHERE org_id = ? AND status = 'ACTIVE'",
                        UUID.class,
                        p.salon().id()),
                Instant.now());
        Instant after = one(
                        "SELECT updated_at FROM discovery.listings WHERE id = ?",
                        java.time.OffsetDateTime.class,
                        listing)
                .toInstant();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void searchesAreRecordedWithoutPersonalData() throws Exception {
        String m = marker();
        place(m + " Logged", 9.97, 39.7, 100_000);
        refresh();
        search("?q=" + m + "&category=BRAIDS");

        var row = system.fetchOne(
                "SELECT query, result_count, filters::text AS filters, user_id FROM discovery.search_events"
                        + " WHERE query = ?",
                m);
        assertThat(row).isNotNull();
        assertThat(row.get("result_count", Integer.class)).isEqualTo(1);
        assertThat(row.get("filters", String.class)).contains("BRAIDS");
        assertThat(row.get("user_id")).isNull();
    }
}
