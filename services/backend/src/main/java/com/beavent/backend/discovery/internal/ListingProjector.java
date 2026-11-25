package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Listings;
import com.beavent.backend.files.FileAccess;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.scheduling.Availability;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds {@code discovery.listings} rows from places and events (plan step 8.1). Reads run with the system role
 * (the projector spans businesses and is the only writer of the table); bookable times come from the availability
 * engine, run in each business's own context.
 *
 * <p>A place is listed when its business is active and approved for the marketplace and the location is active. An
 * event is listed while it is public, published and has a date that has not passed.
 */
@Component
class ListingProjector implements Listings {

    /** How many days of bookable times a place's listing carries, for the "when" filter. */
    static final int SLOT_DAYS = 14;

    /** How far ahead an event's dates are listed. */
    static final int EVENT_DAYS = 90;

    private static final int MENU_LINES = 24;
    private static final Logger log = LoggerFactory.getLogger(ListingProjector.class);
    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};

    private final DSLContext system;
    private final Availability availability;
    private final FileAccess files;
    private final JsonMapper json;

    ListingProjector(@SystemDb DSLContext system, Availability availability, FileAccess files, JsonMapper json) {
        this.system = system;
        this.availability = availability;
        this.files = files;
        this.json = json;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Places
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Result refreshLocation(UUID locationId, Instant now) {
        Record base = system.resultQuery("""
                        SELECT l.id, l.org_id, l.name AS location_name, l.slug::text AS location_slug, l.timezone,
                               l.currency_code, l.service_mode, l.online_booking,
                               o.slug::text AS org_slug, o.display_name, o.description_i18n, o.logo_file_id,
                               o.cover_file_id, o.home_market_id, o.primary_category_id, o.created_at AS org_created,
                               (o.status = 'ACTIVE' AND o.marketplace_status = 'LISTED' AND l.status = 'ACTIVE') AS eligible,
                               p.landmark, p.address_line, p.city,
                               ST_Y(p.geo::geometry) AS lat, ST_X(p.geo::geometry) AS lng,
                               aa.name_i18n AS area_name, aa.name AS area_en,
                               (SELECT count(*) FROM tenant.locations x
                                 WHERE x.org_id = l.org_id AND x.status = 'ACTIVE') AS org_locations
                          FROM tenant.locations l
                          JOIN tenant.organizations o ON o.id = l.org_id
                          JOIN tenant.places p ON p.id = l.place_id
                          LEFT JOIN ref.administrative_areas aa ON aa.id = p.admin_area_id
                         WHERE l.id = ?
                        """, locationId).fetchOne();
        if (base == null || !base.get("eligible", Boolean.class)) {
            int hidden = system.execute(
                    "UPDATE discovery.listings SET is_listed = false, updated_at = now()"
                            + " WHERE location_id = ? AND is_listed",
                    locationId);
            return new Result(0, hidden);
        }
        UUID orgId = base.get("org_id", UUID.class);
        String zoneId = base.get("timezone", String.class);
        String currency = base.get("currency_code", String.class);
        String serviceMode = base.get("service_mode", String.class);

        // The short menu, with each service's cheapest price and its default duration.
        List<Map<String, Object>> menu = new ArrayList<>();
        Set<UUID> serviceCategoryIds = new LinkedHashSet<>();
        Set<String> audiences = new LinkedHashSet<>();
        Set<String> delivery = new LinkedHashSet<>();
        List<String> serviceWords = new ArrayList<>();
        Long priceFrom = null;
        UUID quickestVariant = null;
        int quickestMinutes = Integer.MAX_VALUE;
        UUID unstaffedVariant = null;
        int unstaffedMinutes = Integer.MAX_VALUE;
        int serviceCount = 0;
        for (Record r :
                system.resultQuery("""
                        SELECT s.id, s.category_id, s.name_i18n, s.audience, s.delivery_modes::text[] AS delivery_modes,
                               s.requires_patch_test,
                               (SELECT v.id FROM catalog.service_variants v
                                 WHERE v.service_id = s.id AND v.status = 'ACTIVE'
                                 ORDER BY v.is_default DESC, v.sort_order, v.duration_minutes LIMIT 1) AS variant_id,
                               (SELECT v.duration_minutes FROM catalog.service_variants v
                                 WHERE v.service_id = s.id AND v.status = 'ACTIVE'
                                 ORDER BY v.is_default DESC, v.sort_order, v.duration_minutes LIMIT 1) AS minutes,
                               (SELECT min(CASE WHEN v.price_type = 'ON_CONSULTATION' THEN NULL
                                                WHEN v.price_type = 'FREE' THEN 0 ELSE
                                  least(v.price_minor, coalesce((SELECT min(sp.price_minor) FROM catalog.service_prices sp
                                       WHERE sp.variant_id = v.id AND sp.valid_from <= now() AND sp.valid_to > now()
                                         AND (sp.location_id IS NULL OR sp.location_id = ?)), v.price_minor)) END)
                                  FROM catalog.service_variants v
                                 WHERE v.service_id = s.id AND v.status = 'ACTIVE') AS price_min,
                               (SELECT bool_or(v.price_type IN ('FROM','ON_CONSULTATION')) OR count(*) > 1
                                  FROM catalog.service_variants v
                                 WHERE v.service_id = s.id AND v.status = 'ACTIVE') AS price_is_from,
                               EXISTS (SELECT 1 FROM catalog.staff_services ss
                                         JOIN team.staff_locations sl ON sl.staff_id = ss.staff_id AND sl.location_id = ?
                                         JOIN team.staff st ON st.id = ss.staff_id
                                        WHERE ss.service_id = s.id AND st.status = 'ACTIVE' AND st.is_bookable
                                          AND st.bookable_online) AS has_staff
                          FROM catalog.location_services ls
                          JOIN catalog.services s ON s.id = ls.service_id AND s.org_id = ls.org_id
                          LEFT JOIN catalog.service_groups g ON g.id = s.group_id
                         WHERE ls.location_id = ? AND s.status = 'ACTIVE' AND s.online_booking
                           AND EXISTS (SELECT 1 FROM catalog.service_variants v
                                        WHERE v.service_id = s.id AND v.status = 'ACTIVE')
                         ORDER BY g.sort_order NULLS LAST, s.sort_order, s.created_at
                        """, locationId, locationId, locationId).fetch()) {
            serviceCount++;
            Map<String, String> name = text(r.get("name_i18n", JSONB.class));
            serviceCategoryIds.add(r.get("category_id", UUID.class));
            audiences.add(r.get("audience", String.class));
            String[] modes = r.get("delivery_modes", String[].class);
            if (modes != null) {
                delivery.addAll(List.of(modes));
            }
            serviceWords.addAll(name.values());
            Long price = r.get("price_min", Long.class);
            if (price != null) {
                priceFrom = priceFrom == null ? price : Math.min(priceFrom, price);
            }
            Integer minutes = r.get("minutes", Integer.class);
            UUID variantId = r.get("variant_id", UUID.class);
            boolean onPremises = modes != null && List.of(modes).contains("ON_PREMISES");
            if (onPremises && !r.get("requires_patch_test", Boolean.class) && minutes != null) {
                // The service to probe for bookable times: the shortest one somebody on the team can do.
                boolean staffed = r.get("has_staff", Boolean.class);
                if (staffed && minutes < quickestMinutes) {
                    quickestMinutes = minutes;
                    quickestVariant = variantId;
                } else if (!staffed && minutes < unstaffedMinutes) {
                    unstaffedMinutes = minutes;
                    unstaffedVariant = variantId;
                }
            }
            if (menu.size() < MENU_LINES) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("id", r.get("id", UUID.class).toString());
                line.put("name", name);
                line.put("categoryId", r.get("category_id", UUID.class).toString());
                line.put("minutes", minutes == null ? 0 : minutes);
                line.put("priceMinor", price == null ? 0L : price);
                line.put(
                        "priceType",
                        price == null ? "ON_CONSULTATION" : r.get("price_is_from", Boolean.class) ? "FROM" : "FIXED");
                menu.add(line);
            }
        }
        boolean atHome = delivery.contains("AT_CUSTOMER") && !"ON_PREMISES".equals(serviceMode);

        // Categories: the business's own and its services', with their parents.
        Set<UUID> categorySeeds = new LinkedHashSet<>(serviceCategoryIds);
        UUID primary = base.get("primary_category_id", UUID.class);
        if (primary != null) {
            categorySeeds.add(primary);
        }
        Categories categories = categories(categorySeeds, primary);

        List<Map<String, Object>> hours = new ArrayList<>();
        for (Record r : system.resultQuery(
                        "SELECT iso_weekday, opens_at, closes_at FROM tenant.location_hours"
                                + " WHERE location_id = ? ORDER BY iso_weekday, opens_at",
                        locationId)
                .fetch()) {
            hours.add(Map.of(
                    "d", r.get("iso_weekday", Integer.class),
                    "o", time(r.get("opens_at", Time.class)),
                    "c", time(r.get("closes_at", Time.class))));
        }

        Set<String> languages = new LinkedHashSet<>();
        int team = 0;
        for (Record r : system.resultQuery("""
                        SELECT st.languages::text[] AS languages
                          FROM team.staff_locations sl
                          JOIN team.staff st ON st.id = sl.staff_id
                         WHERE sl.location_id = ? AND st.status = 'ACTIVE' AND st.is_bookable AND st.bookable_online
                        """, locationId).fetch()) {
            team++;
            String[] spoken = r.get("languages", String[].class);
            if (spoken != null) {
                languages.addAll(List.of(spoken));
            }
        }

        // Bookable times for the next weeks, from the availability engine in the business's own context.
        ZoneId zone = ZoneId.of(zoneId);
        Map<String, Set<String>> slots = new TreeMap<>();
        Instant nextAvailable = null;
        if (base.get("online_booking", Boolean.class)
                && (quickestVariant != null || unstaffedVariant != null)
                && !"MOBILE_ONLY".equals(serviceMode)) {
            LocalDate today = LocalDate.ofInstant(now, zone);
            Availability.Query query = new Availability.Query(
                    locationId,
                    List.of(new Availability.Item(
                            quickestVariant != null ? quickestVariant : unstaffedVariant, List.of(), null)),
                    today,
                    today.plusDays(SLOT_DAYS - 1L),
                    null);
            try {
                Availability.Result found =
                        RequestContext.forOrganization(null, orgId).call(() -> availability.find(query, now));
                for (Availability.Offer offer : found.slots()) {
                    ZonedDateTime local = offer.start().atZone(zone);
                    slots.computeIfAbsent(local.toLocalDate().toString(), k -> new LinkedHashSet<>())
                            .add(part(local.getHour()));
                    if (nextAvailable == null || offer.start().isBefore(nextAvailable)) {
                        nextAvailable = offer.start();
                    }
                }
            } catch (RuntimeException e) {
                log.debug("No bookable times for location {}: {}", locationId, e.toString());
            }
        }

        String orgName = base.get("display_name", String.class);
        String locationName = base.get("location_name", String.class);
        boolean multi = base.get("org_locations", Long.class) > 1;
        String title = multi ? orgName + " · " + locationName : orgName;
        Map<String, String> summary = text(base.get("description_i18n", JSONB.class));
        Map<String, String> area = text(base.get("area_name", JSONB.class));
        if (area.isEmpty() && base.get("area_en", String.class) != null) {
            area = Map.of("en", base.get("area_en", String.class));
        }

        Image image = image(base.get("cover_file_id", UUID.class), base.get("logo_file_id", UUID.class));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("businessSlug", base.get("org_slug", String.class));
        attributes.put("locationSlug", base.get("location_slug", String.class));
        attributes.put("locationName", locationName);
        attributes.put("timezone", zoneId);
        attributes.put("serviceMode", serviceMode);
        attributes.put("deliveryModes", new ArrayList<>(delivery));
        attributes.put("atHome", atHome);
        attributes.put("audiences", new ArrayList<>(audiences));
        attributes.put("languages", new ArrayList<>(languages));
        attributes.put("category", categories.primaryName());
        attributes.put("area", area);
        attributes.put("landmark", base.get("landmark", String.class));
        attributes.put("city", base.get("city", String.class));
        attributes.put("lat", base.get("lat", Double.class));
        attributes.put("lng", base.get("lng", Double.class));
        attributes.put("hours", hours);
        attributes.put("slots", slotMap(slots));
        attributes.put("serviceCount", serviceCount);
        attributes.put("menu", menu);
        attributes.put("imageVariants", image == null ? Map.of() : image.variants());

        double quality = 10
                + (image != null ? 15 : 0)
                + Math.min(serviceCount, 10) * 2
                + (team > 0 ? 5 : 0)
                + (!summary.isEmpty() ? 5 : 0)
                + (!hours.isEmpty() ? 5 : 0)
                + (nextAvailable != null ? 15 : 0);

        List<String> titleWords = List.of(orgName, locationName);
        List<String> categoryWords = new ArrayList<>(categories.words());
        categoryWords.addAll(serviceWords);
        List<String> placeWords = new ArrayList<>(List.of(
                nz(base.get("landmark", String.class)),
                nz(base.get("address_line", String.class)),
                nz(base.get("city", String.class))));
        placeWords.addAll(area.values());

        upsert(
                "LOCATION",
                base.get("org_id", UUID.class),
                locationId,
                null,
                base.get("home_market_id", UUID.class),
                "b/" + base.get("org_slug", String.class) + "/" + base.get("location_slug", String.class),
                title,
                Map.of(),
                summary,
                image == null ? null : image.url(),
                categories.ids(),
                List.of(),
                "(SELECT p.geo FROM tenant.locations l JOIN tenant.places p ON p.id = l.place_id WHERE l.id = ?)",
                locationId,
                "(SELECT aa.path FROM tenant.locations l JOIN tenant.places p ON p.id = l.place_id"
                        + " JOIN ref.administrative_areas aa ON aa.id = p.admin_area_id WHERE l.id = ?)",
                locationId,
                false,
                priceFrom,
                currency,
                nextAvailable,
                null,
                null,
                attributes,
                quality,
                SearchText.document(titleWords),
                SearchText.document(categoryWords),
                SearchText.document(placeWords));
        return new Result(1, 0);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Result refreshEvent(UUID eventId, Instant now) {
        Record base = system.resultQuery("""
                        SELECT e.id, e.org_id, e.slug::text AS event_slug, e.kind, e.format, e.source_language,
                               e.currency_code, e.category_id, e.cover_file_id, e.min_age, e.location_id,
                               o.slug::text AS org_slug, o.display_name AS org_name, o.home_market_id,
                               (o.status = 'ACTIVE' AND e.visibility = 'PUBLIC'
                                AND e.status IN ('PUBLISHED','SALES_PAUSED')) AS eligible
                          FROM events.events e
                          JOIN tenant.organizations o ON o.id = e.org_id
                         WHERE e.id = ?
                        """, eventId).fetchOne();
        if (base == null || !base.get("eligible", Boolean.class)) {
            return new Result(0, hideEvent(eventId));
        }
        UUID orgId = base.get("org_id", UUID.class);

        List<Record> dates = system.resultQuery("""
                        SELECT o.starts_at, o.ends_at, o.timezone, o.status, o.place_id
                          FROM events.occurrences o
                         WHERE o.event_id = ? AND o.ends_at > ?::timestamptz AND o.status NOT IN ('CANCELLED','COMPLETED')
                         ORDER BY o.starts_at
                        """, eventId, now.atOffset(java.time.ZoneOffset.UTC))
                .fetch();
        if (dates.isEmpty()) {
            return new Result(0, hideEvent(eventId));
        }
        Instant nextStarts = dates.getFirst()
                .get("starts_at", java.time.OffsetDateTime.class)
                .toInstant();
        Instant lastEnds = dates.stream()
                .map(r -> r.get("ends_at", java.time.OffsetDateTime.class).toInstant())
                .max(Instant::compareTo)
                .orElseThrow();
        String zoneId = dates.getFirst().get("timezone", String.class);
        boolean soldOut = dates.stream().allMatch(r -> "SOLD_OUT".equals(r.get("status", String.class)));

        Map<String, Set<String>> slots = new TreeMap<>();
        Instant horizon = now.plusSeconds(EVENT_DAYS * 86_400L);
        for (Record r : dates) {
            Instant starts = r.get("starts_at", java.time.OffsetDateTime.class).toInstant();
            if (starts.isAfter(horizon)) {
                break;
            }
            ZonedDateTime local = starts.atZone(ZoneId.of(r.get("timezone", String.class)));
            slots.computeIfAbsent(local.toLocalDate().toString(), k -> new LinkedHashSet<>())
                    .add(part(local.getHour()));
        }

        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, String> summaries = new LinkedHashMap<>();
        for (Record r : system.resultQuery(
                        "SELECT language_code, title, summary FROM events.event_translations WHERE event_id = ?"
                                + " ORDER BY language_code",
                        eventId)
                .fetch()) {
            titles.put(r.get("language_code", String.class), r.get("title", String.class));
            if (r.get("summary", String.class) != null) {
                summaries.put(r.get("language_code", String.class), r.get("summary", String.class));
            }
        }
        if (titles.isEmpty()) {
            return new Result(0, hideEvent(eventId)); // nothing to show yet
        }
        String sourceLanguage = base.get("source_language", String.class);
        String title =
                titles.getOrDefault(sourceLanguage, titles.values().iterator().next());

        List<String> tags = system.resultQuery(
                        "SELECT tag::text FROM events.event_tags WHERE event_id = ? ORDER BY tag", eventId)
                .fetch(r -> r.get(0, String.class));

        Long priceFrom = system.resultQuery("""
                        SELECT min(CASE WHEN pricing = 'PAID' THEN price_minor
                                        WHEN pricing = 'PAY_WHAT_YOU_WANT' THEN coalesce(min_price_minor, 0)
                                        ELSE 0 END)
                          FROM events.ticket_types
                         WHERE event_id = ? AND status = 'ACTIVE' AND visibility IN ('VISIBLE','HIDDEN_WHEN_UNAVAILABLE')
                        """, eventId).fetchOne(0, Long.class);

        // Where: the organization's own location, else the first dated place.
        UUID placeId = dates.stream()
                .map(r -> r.get("place_id", UUID.class))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        Record place = null;
        UUID locationId = base.get("location_id", UUID.class);
        if (locationId != null) {
            place = system.resultQuery("""
                            SELECT p.name, p.landmark, p.address_line, p.city, p.geo IS NOT NULL AS has_geo,
                                   ST_Y(p.geo::geometry) AS lat, ST_X(p.geo::geometry) AS lng,
                                   aa.name_i18n AS area_name, aa.name AS area_en
                              FROM tenant.locations l JOIN tenant.places p ON p.id = l.place_id
                              LEFT JOIN ref.administrative_areas aa ON aa.id = p.admin_area_id
                             WHERE l.id = ?
                            """, locationId).fetchOne();
        } else if (placeId != null) {
            place = system.resultQuery("""
                            SELECT p.name, p.landmark, p.address_line, p.city, p.geo IS NOT NULL AS has_geo,
                                   ST_Y(p.geo::geometry) AS lat, ST_X(p.geo::geometry) AS lng,
                                   aa.name_i18n AS area_name, aa.name AS area_en
                              FROM tenant.places p
                              LEFT JOIN ref.administrative_areas aa ON aa.id = p.admin_area_id
                             WHERE p.id = ?
                            """, placeId).fetchOne();
        }
        Map<String, String> area = Map.of();
        if (place != null) {
            area = text(place.get("area_name", JSONB.class));
            if (area.isEmpty() && place.get("area_en", String.class) != null) {
                area = Map.of("en", place.get("area_en", String.class));
            }
        }
        String format = base.get("format", String.class);
        boolean online = !"IN_PERSON".equals(format);

        Categories categories =
                categories(Set.of(base.get("category_id", UUID.class)), base.get("category_id", UUID.class));
        Image image = image(base.get("cover_file_id", UUID.class), null);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("businessSlug", base.get("org_slug", String.class));
        attributes.put("eventSlug", base.get("event_slug", String.class));
        attributes.put("organizer", base.get("org_name", String.class));
        attributes.put("timezone", zoneId);
        attributes.put("eventKind", base.get("kind", String.class));
        attributes.put("format", format);
        attributes.put("category", categories.primaryName());
        attributes.put("venue", place == null ? null : place.get("name", String.class));
        attributes.put("landmark", place == null ? null : place.get("landmark", String.class));
        attributes.put("city", place == null ? null : place.get("city", String.class));
        attributes.put("area", area);
        attributes.put("lat", place == null ? null : place.get("lat", Double.class));
        attributes.put("lng", place == null ? null : place.get("lng", Double.class));
        attributes.put("slots", slotMap(slots));
        attributes.put("soldOut", soldOut);
        attributes.put("minAge", base.get("min_age", Short.class));
        attributes.put("imageVariants", image == null ? Map.of() : image.variants());

        double quality = 10
                + (image != null ? 15 : 0)
                + (priceFrom != null ? 5 : 0)
                + (!summaries.isEmpty() ? 5 : 0)
                + (soldOut ? -5 : 0);

        List<String> titleWords = new ArrayList<>(titles.values());
        titleWords.add(base.get("org_name", String.class));
        List<String> categoryWords = new ArrayList<>(categories.words());
        categoryWords.addAll(tags);
        List<String> placeWords = new ArrayList<>();
        if (place != null) {
            placeWords.addAll(List.of(
                    nz(place.get("name", String.class)),
                    nz(place.get("landmark", String.class)),
                    nz(place.get("address_line", String.class)),
                    nz(place.get("city", String.class))));
            placeWords.addAll(area.values());
        }

        String geoSql = locationId != null
                ? "(SELECT p.geo FROM tenant.locations l JOIN tenant.places p ON p.id = l.place_id WHERE l.id = ?)"
                : "(SELECT p.geo FROM tenant.places p WHERE p.id = ?)";
        String areaSql = locationId != null
                ? "(SELECT aa.path FROM tenant.locations l JOIN tenant.places p ON p.id = l.place_id"
                        + " JOIN ref.administrative_areas aa ON aa.id = p.admin_area_id WHERE l.id = ?)"
                : "(SELECT aa.path FROM tenant.places p JOIN ref.administrative_areas aa ON aa.id = p.admin_area_id"
                        + " WHERE p.id = ?)";
        UUID geoKey = locationId != null ? locationId : placeId;

        upsert(
                "EVENT",
                orgId,
                null,
                eventId,
                base.get("home_market_id", UUID.class),
                "e/" + base.get("org_slug", String.class) + "/" + base.get("event_slug", String.class),
                title,
                titles,
                summaries,
                image == null ? null : image.url(),
                categories.ids(),
                tags,
                geoKey == null ? "NULL::geography" : geoSql,
                geoKey,
                geoKey == null ? "NULL::ltree" : areaSql,
                geoKey,
                online,
                priceFrom,
                base.get("currency_code", String.class),
                null,
                nextStarts,
                lastEnds,
                attributes,
                quality,
                SearchText.document(titleWords),
                SearchText.document(categoryWords),
                SearchText.document(placeWords));
        return new Result(1, 0);
    }

    private int hideEvent(UUID eventId) {
        return system.execute(
                "UPDATE discovery.listings SET is_listed = false, updated_at = now() WHERE event_id = ? AND is_listed",
                eventId);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Everything
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Result refreshAll(Instant now) {
        int listed = 0;
        int unlisted = 0;
        List<UUID> places = system.resultQuery("""
                        SELECT l.id FROM tenant.locations l JOIN tenant.organizations o ON o.id = l.org_id
                         WHERE l.status = 'ACTIVE' AND o.status = 'ACTIVE' AND o.marketplace_status = 'LISTED'
                        """).fetch(r -> r.get(0, UUID.class));
        for (UUID id : places) {
            try {
                Result r = refreshLocation(id, now);
                listed += r.listed();
                unlisted += r.unlisted();
            } catch (RuntimeException e) {
                log.warn("Could not list location {}; it stays as it was", id, e);
            }
        }
        unlisted += system.execute(
                "UPDATE discovery.listings SET is_listed = false, updated_at = now()"
                        + " WHERE kind = 'LOCATION' AND is_listed AND NOT (location_id = ANY(?::uuid[]))",
                (Object) places.toArray(UUID[]::new));

        List<UUID> events = system.resultQuery("""
                        SELECT e.id FROM events.events e JOIN tenant.organizations o ON o.id = e.org_id
                         WHERE e.visibility = 'PUBLIC' AND e.status IN ('PUBLISHED','SALES_PAUSED') AND o.status = 'ACTIVE'
                        """).fetch(r -> r.get(0, UUID.class));
        for (UUID id : events) {
            try {
                Result r = refreshEvent(id, now);
                listed += r.listed();
                unlisted += r.unlisted();
            } catch (RuntimeException e) {
                log.warn("Could not list event {}; it stays as it was", id, e);
            }
        }
        unlisted += system.execute(
                "UPDATE discovery.listings SET is_listed = false, updated_at = now()"
                        + " WHERE kind = 'EVENT' AND is_listed AND NOT (event_id = ANY(?::uuid[]))",
                (Object) events.toArray(UUID[]::new));
        return new Result(listed, unlisted);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------------------

    private void upsert(
            String kind,
            UUID orgId,
            UUID locationId,
            UUID eventId,
            UUID marketId,
            String slugPath,
            String title,
            Map<String, String> titleI18n,
            Map<String, String> summaryI18n,
            String imageUrl,
            List<UUID> categoryIds,
            List<String> tags,
            String geoSql,
            UUID geoKey,
            String areaSql,
            UUID areaKey,
            boolean online,
            Long priceFrom,
            String currency,
            Instant nextAvailable,
            Instant nextStarts,
            Instant lastEnds,
            Map<String, Object> attributes,
            double quality,
            String documentA,
            String documentB,
            String documentC) {
        String conflict = locationId != null
                ? "(location_id) WHERE location_id IS NOT NULL"
                : "(event_id) WHERE event_id IS NOT NULL";
        String searchText =
                String.join(" ", List.of(documentA, documentB, documentC)).trim();
        List<Object> binds = new ArrayList<>();
        binds.add(kind);
        binds.add(orgId);
        binds.add(locationId);
        binds.add(eventId);
        binds.add(marketId);
        binds.add(slugPath);
        binds.add(title);
        binds.add(JSONB.jsonb(json.writeValueAsString(titleI18n)));
        binds.add(JSONB.jsonb(json.writeValueAsString(summaryI18n)));
        binds.add(imageUrl);
        binds.add(categoryIds.toArray(UUID[]::new));
        binds.add(tags.toArray(String[]::new));
        if (geoKey != null) {
            binds.add(geoKey);
        }
        if (areaKey != null) {
            binds.add(areaKey);
        }
        binds.add(online);
        binds.add(priceFrom);
        binds.add(currency);
        binds.add(nextAvailable == null ? null : nextAvailable.atOffset(java.time.ZoneOffset.UTC));
        binds.add(nextStarts == null ? null : nextStarts.atOffset(java.time.ZoneOffset.UTC));
        binds.add(lastEnds == null ? null : lastEnds.atOffset(java.time.ZoneOffset.UTC));
        binds.add(JSONB.jsonb(json.writeValueAsString(attributes)));
        binds.add(java.math.BigDecimal.valueOf(quality));
        binds.add(documentA);
        binds.add(documentB);
        binds.add(documentC);
        binds.add(searchText);
        system.execute("""
                INSERT INTO discovery.listings
                  (kind, org_id, location_id, event_id, market_id, slug_path, title, title_i18n, summary_i18n,
                   image_url, category_ids, tags, geo, admin_area_path, is_online, price_from_minor, currency_code,
                   next_available_at, next_starts_at, last_ends_at, attributes, quality_score, is_listed,
                   search_document, search_text)
                VALUES (?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?::text, ?::uuid[], ?::text[], %s, %s, ?,
                        ?::bigint, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::jsonb, ?, true,
                        setweight(to_tsvector('simple', ?), 'A') || setweight(to_tsvector('simple', ?), 'B')
                          || setweight(to_tsvector('simple', ?), 'C'),
                        ?)
                ON CONFLICT %s DO UPDATE SET
                  org_id = excluded.org_id, market_id = excluded.market_id, slug_path = excluded.slug_path,
                  title = excluded.title, title_i18n = excluded.title_i18n, summary_i18n = excluded.summary_i18n,
                  image_url = excluded.image_url, category_ids = excluded.category_ids, tags = excluded.tags,
                  geo = excluded.geo, admin_area_path = excluded.admin_area_path, is_online = excluded.is_online,
                  price_from_minor = excluded.price_from_minor, currency_code = excluded.currency_code,
                  next_available_at = excluded.next_available_at, next_starts_at = excluded.next_starts_at,
                  last_ends_at = excluded.last_ends_at, attributes = excluded.attributes,
                  quality_score = excluded.quality_score, is_listed = true,
                  search_document = excluded.search_document, search_text = excluded.search_text, updated_at = now()
                WHERE (listings.slug_path, listings.title, listings.title_i18n, listings.summary_i18n,
                       listings.image_url, listings.category_ids, listings.tags, listings.admin_area_path,
                       listings.is_online, listings.price_from_minor, listings.currency_code,
                       listings.next_available_at, listings.next_starts_at, listings.last_ends_at,
                       listings.attributes, listings.quality_score, listings.is_listed, listings.search_text)
                  IS DISTINCT FROM
                      (excluded.slug_path, excluded.title, excluded.title_i18n, excluded.summary_i18n,
                       excluded.image_url, excluded.category_ids, excluded.tags, excluded.admin_area_path,
                       excluded.is_online, excluded.price_from_minor, excluded.currency_code,
                       excluded.next_available_at, excluded.next_starts_at, excluded.last_ends_at,
                       excluded.attributes, excluded.quality_score, excluded.is_listed, excluded.search_text)
                """.formatted(geoSql, areaSql, conflict), binds.toArray());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pieces
    // ---------------------------------------------------------------------------------------------------------

    private record Categories(List<UUID> ids, Map<String, String> primaryName, List<String> words) {}

    /** The seeds and their ancestors; {@code primary} names the listing's main category. */
    private Categories categories(Set<UUID> seeds, UUID primary) {
        if (seeds.isEmpty()) {
            return new Categories(List.of(), Map.of(), List.of());
        }
        List<UUID> ids = new ArrayList<>();
        List<String> words = new ArrayList<>();
        Map<String, String> primaryName = Map.of();
        for (Record r :
                system.resultQuery("""
                        WITH RECURSIVE tree AS (
                          SELECT c.id, c.parent_id FROM ref.categories c WHERE c.id = ANY(?::uuid[])
                          UNION
                          SELECT p.id, p.parent_id FROM ref.categories p JOIN tree t ON p.id = t.parent_id)
                        SELECT c.id, c.code, c.name_i18n FROM ref.categories c
                         WHERE c.id IN (SELECT id FROM tree) AND c.is_active ORDER BY c.kind, c.sort_order
                        """, (Object) seeds.toArray(UUID[]::new)).fetch()) {
            Map<String, String> name = text(r.get("name_i18n", JSONB.class));
            ids.add(r.get("id", UUID.class));
            words.addAll(name.values());
            if (r.get("id", UUID.class).equals(primary)) {
                primaryName = name;
            }
        }
        return new Categories(ids, primaryName, words);
    }

    private record Image(String url, Map<String, String> variants) {}

    private Image image(UUID first, UUID second) {
        for (UUID id : new UUID[] {first, second}) {
            if (id != null) {
                var found = files.publicImage(id);
                if (found.isPresent()) {
                    return new Image(found.get().url(), found.get().variants());
                }
            }
        }
        return null;
    }

    private Map<String, String> text(JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), TEXT);
    }

    private static Map<String, List<String>> slotMap(Map<String, Set<String>> slots) {
        Map<String, List<String>> out = new TreeMap<>();
        slots.forEach((date, parts) -> out.put(date, List.copyOf(parts)));
        return out;
    }

    private static String part(int hour) {
        return hour < 12 ? "MORNING" : hour < 17 ? "AFTERNOON" : "EVENING";
    }

    private static String time(Time time) {
        return time.toLocalTime().toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
