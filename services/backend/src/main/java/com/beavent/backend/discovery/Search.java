package com.beavent.backend.discovery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Search over the marketplace read model {@code discovery.listings}: places to book and things to do, together
 * (plan step 8.1, docs/06 §5.5). Everything here is public: it reads only what the projector published.
 */
public interface Search {

    enum Kind {
        LOCATION,
        EVENT
    }

    enum Sort {
        /** Relevance when words were typed, otherwise the listing's quality score. */
        RECOMMENDED,
        NEAREST,
        TOP_RATED,
        LOWEST_PRICE,
        /** Events: the next start first. */
        SOONEST,
        /** Most recently listed first. */
        NEWEST
    }

    enum DayPart {
        MORNING,
        AFTERNOON,
        EVENING
    }

    /** A point and a radius, for "near me". */
    record Near(double latitude, double longitude, int radiusMeters) {}

    /** A map window; west {@literal <} east. */
    record Box(double west, double south, double east, double north) {}

    /**
     * @param text what the person typed; any script, Latin or Ge'ez
     * @param kinds {@code null} or empty = places and events
     * @param market market code (e.g. {@code ET}); {@code null} = all
     * @param category category code (e.g. {@code BRAIDS}) or id; matches the business's and its services' categories
     * @param area administrative area code (e.g. {@code BOLE}, {@code AA}) or id; includes the areas inside it
     * @param near only places within the radius, and distances in the results
     * @param box only places inside the map window
     * @param maxPriceMinor the listing's "from" price is at most this (minor units of its currency)
     * @param minRating average rating at least this
     * @param openNow places open at {@code now} (hours only; announced closures are not applied)
     * @param atHome places whose professionals come to the customer
     * @param audience {@code WOMEN}, {@code MEN} or {@code CHILDREN}: places with services for them (or for everyone)
     * @param language a language a professional speaks (e.g. {@code am})
     * @param from first local date (inclusive) with a bookable time (places) or an occurrence (events)
     * @param to last local date (inclusive); defaults to {@code from}
     * @param dayPart restricts {@code from…to} to part of the day, in the place's local time
     * @param freeOnly events that cost nothing
     * @param online events held online
     * @param cursor from the previous page
     * @param limit 1–50
     */
    record Request(
            String text,
            Set<Kind> kinds,
            String market,
            String category,
            String area,
            Near near,
            Box box,
            Sort sort,
            Long maxPriceMinor,
            Double minRating,
            boolean openNow,
            boolean atHome,
            String audience,
            String language,
            LocalDate from,
            LocalDate to,
            DayPart dayPart,
            boolean freeOnly,
            boolean online,
            String cursor,
            int limit) {}

    /** One line of a place's short menu, for the result row. */
    record MenuLine(
            UUID serviceId,
            Map<String, String> name,
            int durationMinutes,
            long priceMinor,
            String priceType,
            boolean matches) {}

    /**
     * A search result. Places carry {@code businessSlug} and {@code locationSlug} (the venue page is
     * {@code /b/{businessSlug}/{locationSlug}}); events carry {@code businessSlug} and {@code eventSlug}.
     */
    record Item(
            UUID id,
            Kind kind,
            String title,
            Map<String, String> summary,
            String imageUrl,
            Map<String, String> imageVariants,
            String businessSlug,
            String locationSlug,
            String eventSlug,
            String path,
            Map<String, String> category,
            Map<String, String> area,
            String landmark,
            Double latitude,
            Double longitude,
            Integer distanceMeters,
            Long priceFromMinor,
            String currency,
            Double ratingAvg,
            int ratingCount,
            Instant nextAvailableAt,
            Instant nextStartsAt,
            Instant lastEndsAt,
            boolean online,
            String timezone,
            String serviceMode,
            List<String> deliveryModes,
            int serviceCount,
            List<MenuLine> menu,
            boolean newOnBeavent) {}

    /** @param total how many listings match, on the first page only ({@code null} afterwards) */
    record Page(List<Item> items, String nextCursor, Integer total) {}

    /** A point on the map: enough to draw a pin and open the card. */
    record Pin(
            UUID id,
            Kind kind,
            String title,
            String path,
            double latitude,
            double longitude,
            Long priceFromMinor,
            String currency,
            Double ratingAvg) {}

    /** @param truncated more listings match than {@code pins} holds: zoom in or narrow the filters */
    record Pins(List<Pin> pins, boolean truncated) {}

    record CategoryHit(String code, String kind, Map<String, String> name) {}

    record Suggestions(List<CategoryHit> categories, List<Item> places, List<Item> events) {}

    Page search(Request request, Instant now);

    /** Pins for the map, same filters as {@link #search}; needs {@code box} or {@code near}. */
    Pins pins(Request request, Instant now);

    /** Categories, places and events matching what has been typed so far. */
    Suggestions suggest(String text, String market, int limitPerGroup);

    /** Records that a search was made, for ranking and analytics (best effort; no personal data). */
    void record(Request request, int resultCount);
}
