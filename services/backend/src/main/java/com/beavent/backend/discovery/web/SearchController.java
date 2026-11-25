package com.beavent.backend.discovery.web;

import com.beavent.backend.discovery.Search;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import com.beavent.backend.platform.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public search over places and events (services/backend/src/main/openapi/discovery). */
@RestController
@RequestMapping("/v1/public/search")
class SearchController {

    private static final int SEARCHES_PER_MINUTE = 120;
    private static final int SUGGESTIONS_PER_MINUTE = 300;
    private static final int DEFAULT_RADIUS_METERS = 5_000;
    private static final int MAX_RADIUS_METERS = 50_000;
    private static final int MAX_TEXT = 100;
    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofSeconds(15)).cachePublic();

    private final Search search;
    private final RateLimiter rateLimiter;

    SearchController(Search search, RateLimiter rateLimiter) {
        this.search = search;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    ResponseEntity<Search.Page> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double south,
            @RequestParam(required = false) Double east,
            @RequestParam(required = false) Double north,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false, defaultValue = "false") boolean openNow,
            @RequestParam(required = false, defaultValue = "false") boolean atHome,
            @RequestParam(required = false) String audience,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String part,
            @RequestParam(required = false, defaultValue = "false") boolean free,
            @RequestParam(required = false, defaultValue = "false") boolean online,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit,
            HttpServletRequest http) {
        hit(http, "search", SEARCHES_PER_MINUTE);
        Search.Request request = request(
                q, kind, market, category, area, lat, lng, radius, west, south, east, north, sort, maxPrice, minRating,
                openNow, atHome, audience, language, date, dateTo, part, free, online, cursor, limit);
        Search.Page page = search.search(request, Instant.now());
        if (cursor == null && q != null && !q.isBlank()) {
            search.record(request, page.total() == null ? page.items().size() : page.total());
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(page);
    }

    @GetMapping("/pins")
    ResponseEntity<Search.Pins> pins(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double south,
            @RequestParam(required = false) Double east,
            @RequestParam(required = false) Double north,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false, defaultValue = "false") boolean openNow,
            @RequestParam(required = false, defaultValue = "false") boolean atHome,
            @RequestParam(required = false) String audience,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String part,
            @RequestParam(required = false, defaultValue = "false") boolean free,
            @RequestParam(required = false, defaultValue = "false") boolean online,
            HttpServletRequest http) {
        hit(http, "pins", SEARCHES_PER_MINUTE);
        Search.Request request = request(
                q, kind, market, category, area, lat, lng, radius, west, south, east, north, null, maxPrice, minRating,
                openNow, atHome, audience, language, date, dateTo, part, free, online, null, 1);
        return ResponseEntity.ok().cacheControl(CACHE).body(search.pins(request, Instant.now()));
    }

    @GetMapping("/suggestions")
    ResponseEntity<Search.Suggestions> suggestions(
            @RequestParam String q,
            @RequestParam(required = false) String market,
            @RequestParam(required = false, defaultValue = "3") int limit,
            HttpServletRequest http) {
        hit(http, "suggest", SUGGESTIONS_PER_MINUTE);
        if (q.length() > MAX_TEXT) {
            throw invalid("The search text is too long.");
        }
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(search.suggest(q, market, Math.max(1, Math.min(limit, 10))));
    }

    private Search.Request request(
            String q,
            String kind,
            String market,
            String category,
            String area,
            Double lat,
            Double lng,
            Integer radius,
            Double west,
            Double south,
            Double east,
            Double north,
            String sort,
            Long maxPrice,
            Double minRating,
            boolean openNow,
            boolean atHome,
            String audience,
            String language,
            String date,
            String dateTo,
            String part,
            boolean free,
            boolean online,
            String cursor,
            int limit) {
        if (q != null && q.length() > MAX_TEXT) {
            throw invalid("The search text is too long.");
        }
        Search.Near near = null;
        if (lat != null || lng != null) {
            if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                throw invalid("A location needs a latitude and a longitude.");
            }
            int meters = radius == null ? DEFAULT_RADIUS_METERS : radius;
            if (meters < 100 || meters > MAX_RADIUS_METERS) {
                throw invalid("The radius is between 100 and " + MAX_RADIUS_METERS + " metres.");
            }
            near = new Search.Near(lat, lng, meters);
        }
        Search.Box box = null;
        if (west != null || south != null || east != null || north != null) {
            if (west == null
                    || south == null
                    || east == null
                    || north == null
                    || west >= east
                    || south >= north
                    || south < -90
                    || north > 90
                    || west < -180
                    || east > 180) {
                throw invalid("A map window needs west < east and south < north.");
            }
            box = new Search.Box(west, south, east, north);
        }
        LocalDate from = date(date, "date");
        LocalDate to = date(dateTo, "dateTo");
        if (to != null && from == null) {
            throw invalid("dateTo needs date.");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw invalid("dateTo is before date.");
        }
        if (from != null && to != null && from.plusDays(60).isBefore(to)) {
            throw invalid("Choose at most 60 days.");
        }
        if (minRating != null && (minRating < 0 || minRating > 5)) {
            throw invalid("The rating is between 0 and 5.");
        }
        if (part != null && from == null) {
            throw invalid("part needs date.");
        }
        return new Search.Request(
                q,
                kinds(kind),
                market,
                category,
                area,
                near,
                box,
                sort == null || sort.isBlank() ? null : enumOf(Search.Sort.class, sort, "sort"),
                maxPrice,
                minRating,
                openNow,
                atHome,
                audience,
                language,
                from,
                to,
                part == null || part.isBlank() ? null : enumOf(Search.DayPart.class, part, "part"),
                free,
                online,
                cursor,
                Math.max(1, Math.min(limit, 50)));
    }

    private static Set<Search.Kind> kinds(String kind) {
        return kind == null || kind.isBlank() ? Set.of() : EnumSet.of(enumOf(Search.Kind.class, kind, "kind"));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalid("Unknown " + name + ": " + value);
        }
    }

    private static LocalDate date(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw invalid(name + " is a date like 2026-12-05.");
        }
    }

    private void hit(HttpServletRequest http, String what, int perMinute) {
        rateLimiter.hit("discovery:" + what + ":ip:" + http.getRemoteAddr(), perMinute, Duration.ofMinutes(1));
    }

    private static ApiException invalid(String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message);
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {

        @Bean
        PublicEndpoints discoveryPublicEndpoints() {
            return () -> List.of("/v1/public/search", "/v1/public/search/**");
        }
    }
}
