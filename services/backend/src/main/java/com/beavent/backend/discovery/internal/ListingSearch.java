package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Search;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Cursors;
import com.beavent.backend.platform.ProblemCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Search over {@code discovery.listings}: PostgreSQL full text (the {@code simple} configuration: no stemming, which
 * suits Ge'ez) for whole and partial words, trigrams for misspellings, PostGIS for distance and map windows
 * (docs/03 §5, docs/06 §5.5). Results are ordered by a numeric sort key and the id; the cursor holds both.
 */
@Component
class ListingSearch implements Search {

    private static final Logger log = LoggerFactory.getLogger(ListingSearch.class);
    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_PINS = 200;
    private static final int NEW_DAYS = 30;
    private static final int MENU_SHOWN = 3;
    private static final String FAR = "99999999999";

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    ListingSearch(DSLContext dsl, TransactionTemplate transactions, JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.json = json;
    }

    /** A growing WHERE clause with its bind values. */
    private static final class Where {
        final StringBuilder sql = new StringBuilder("li.is_listed");
        final List<Object> binds = new ArrayList<>();

        Where and(String condition, Object... values) {
            sql.append(" AND ").append(condition);
            binds.addAll(List.of(values));
            return this;
        }
    }

    private record Built(
            Where where,
            boolean ascending,
            String distance,
            List<Object> distanceBinds,
            Set<UUID> categoryIds,
            Set<String> words) {}

    private Built build(Request r, Instant now) {
        Where w = new Where();
        Set<String> words = new LinkedHashSet<>();
        String typed = r.text() == null ? "" : r.text().trim();
        String tsQuery = SearchText.tsQuery(typed);
        String fuzzy = SearchText.fuzzy(typed);
        if (!tsQuery.isEmpty()) {
            words.addAll(SearchText.words(fuzzy));
            // Every typed word must match: whole or as a prefix, in either spelling, or nearly (trigrams).
            for (SearchText.Term term : SearchText.terms(typed)) {
                StringBuilder condition = new StringBuilder("(li.search_document @@ to_tsquery('simple', ?)");
                List<Object> values = new ArrayList<>();
                values.add(term.tsQuery());
                for (String spelling : term.fuzzy()) {
                    condition.append(" OR ? <% li.search_text");
                    values.add(spelling);
                }
                w.and(condition.append(")").toString(), values.toArray());
            }
        }
        if (r.kinds() != null && !r.kinds().isEmpty()) {
            w.and("li.kind = ANY(?::text[])", (Object)
                    r.kinds().stream().map(Enum::name).toArray(String[]::new));
        }
        if (r.market() != null && !r.market().isBlank()) {
            w.and(
                    "li.market_id = (SELECT m.id FROM ref.markets m WHERE m.code = ?)",
                    r.market().trim().toUpperCase());
        }
        Set<UUID> categoryIds = Set.of();
        if (r.category() != null && !r.category().isBlank()) {
            categoryIds = categoryIds(r.category().trim());
            w.and("li.category_ids && ?::uuid[]", (Object) categoryIds.toArray(UUID[]::new));
        }
        if (r.area() != null && !r.area().isBlank()) {
            String area = r.area().trim();
            if (UUID_TEXT.matcher(area).matches()) {
                w.and(
                        "li.admin_area_path <@ ANY (SELECT a.path FROM ref.administrative_areas a WHERE a.id = ?::uuid)",
                        area);
            } else {
                w.and(
                        "li.admin_area_path <@ ANY (SELECT a.path FROM ref.administrative_areas a"
                                + " WHERE upper(a.code) = upper(?))",
                        area);
            }
        }
        String distance = "NULL::double precision";
        List<Object> distanceBinds = List.of();
        if (r.near() != null) {
            Near n = r.near();
            w.and(
                    "ST_DWithin(li.geo, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)",
                    n.longitude(),
                    n.latitude(),
                    (double) n.radiusMeters());
            distance = "ST_Distance(li.geo, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)";
            distanceBinds = List.of(n.longitude(), n.latitude());
        }
        if (r.box() != null) {
            Box b = r.box();
            w.and(
                    "ST_Intersects(li.geo, ST_MakeEnvelope(?, ?, ?, ?, 4326)::geography)",
                    b.west(),
                    b.south(),
                    b.east(),
                    b.north());
        }
        if (r.maxPriceMinor() != null) {
            w.and("li.price_from_minor <= ?", r.maxPriceMinor());
        }
        if (r.freeOnly()) {
            w.and("li.kind = 'EVENT' AND li.price_from_minor = 0");
        }
        if (r.minRating() != null) {
            w.and("li.rating_avg >= ?", BigDecimal.valueOf(r.minRating()));
        }
        if (r.openNow()) {
            w.and(
                    "li.kind = 'LOCATION' AND discovery.is_open_at(li.attributes -> 'hours', li.attributes ->> 'timezone', ?::timestamptz)",
                    now.atOffset(ZoneOffset.UTC));
        }
        if (r.atHome()) {
            w.and("(li.attributes ->> 'atHome') = 'true'");
        }
        if (r.audience() != null && !r.audience().isBlank()) {
            w.and(
                    "jsonb_exists_any(li.attributes -> 'audiences', ARRAY[?, 'EVERYONE'])",
                    r.audience().trim().toUpperCase());
        }
        if (r.language() != null && !r.language().isBlank()) {
            w.and(
                    "jsonb_exists(li.attributes -> 'languages', ?)",
                    r.language().trim().toLowerCase());
        }
        if (r.from() != null) {
            String to = (r.to() == null ? r.from() : r.to()).toString();
            if (r.dayPart() == null) {
                w.and(
                        "EXISTS (SELECT 1 FROM jsonb_object_keys(coalesce(li.attributes -> 'slots', jsonb_build_object())) k"
                                + " WHERE k BETWEEN ? AND ?)",
                        r.from().toString(),
                        to);
            } else {
                w.and(
                        "EXISTS (SELECT 1 FROM jsonb_each(coalesce(li.attributes -> 'slots', jsonb_build_object())) s"
                                + " WHERE s.key BETWEEN ? AND ? AND jsonb_exists(s.value, ?))",
                        r.from().toString(),
                        to,
                        r.dayPart().name());
            }
        }
        if (r.online()) {
            w.and("li.is_online");
        }
        if (r.business() != null && !r.business().isBlank()) {
            w.and("(li.attributes ->> 'businessSlug') = ?", r.business().trim().toLowerCase());
        }
        if (r.locations() != null && !r.locations().isEmpty()) {
            w.and("li.location_id = ANY(?::uuid[])", (Object) r.locations().toArray(UUID[]::new));
        }

        Sort sort = r.sort() == null ? Sort.RECOMMENDED : r.sort();
        if (sort == Sort.NEAREST && r.near() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Sorting by distance needs a location.");
        }
        boolean ascending = sort == Sort.NEAREST || sort == Sort.LOWEST_PRICE || sort == Sort.SOONEST;
        return new Built(w, ascending, distance, distanceBinds, categoryIds, words);
    }

    /** How close a word must be to match by trigrams: "astter" finds "aster", "bake" does not find "bole". */
    private void tolerate() {
        dsl.execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.5");
    }

    /** Ids of the categories with this code (or this id) and everything below them. */
    private Set<UUID> categoryIds(String codeOrId) {
        List<UUID> ids = UUID_TEXT.matcher(codeOrId).matches()
                ? dsl.resultQuery("""
                                WITH RECURSIVE t AS (
                                  SELECT c.id FROM ref.categories c WHERE c.id = ?::uuid
                                  UNION SELECT c.id FROM ref.categories c JOIN t ON c.parent_id = t.id)
                                SELECT id FROM t
                                """, codeOrId).fetch(r -> r.get(0, UUID.class))
                : dsl.resultQuery("""
                                WITH RECURSIVE t AS (
                                  SELECT c.id FROM ref.categories c WHERE upper(c.code) = upper(?) AND c.is_active
                                  UNION SELECT c.id FROM ref.categories c JOIN t ON c.parent_id = t.id)
                                SELECT id FROM t
                                """, codeOrId).fetch(r -> r.get(0, UUID.class));
        // A category nobody has: matches nothing (an array holding a nil id), not everything.
        return ids.isEmpty() ? Set.of(new UUID(0, 0)) : new LinkedHashSet<>(ids);
    }

    @Override
    public List<Item> items(List<UUID> listingIds, Instant now) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        List<Record> rows = transactions.execute(status ->
                dsl.resultQuery("""
                        SELECT li.id, li.location_id, li.kind, li.title, li.title_i18n, li.summary_i18n, li.image_url, li.attributes,
                               li.price_from_minor, li.currency_code, li.rating_avg, li.rating_count,
                               li.next_available_at, li.next_starts_at, li.last_ends_at, li.is_online, li.listed_since,
                               NULL::double precision AS distance
                          FROM discovery.listings li
                         WHERE li.id = ANY(?::uuid[]) AND li.is_listed
                        """, (Object) listingIds.toArray(UUID[]::new)).fetch());
        Built none = new Built(new Where(), true, "NULL::double precision", List.of(), Set.of(), Set.of());
        Map<UUID, Item> byId = new LinkedHashMap<>();
        for (Record row : rows) {
            Item item = item(row, none, now);
            byId.put(item.id(), item);
        }
        List<Item> ordered = new ArrayList<>();
        for (UUID id : listingIds) {
            Item item = byId.get(id);
            if (item != null) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    @Override
    public Page search(Request request, Instant now) {
        return transactions.execute(status -> page(request, now));
    }

    private Page page(Request r, Instant now) {
        tolerate();
        Built b = build(r, now);
        int limit = Math.max(1, Math.min(r.limit(), 50));
        Sort sort = r.sort() == null ? Sort.RECOMMENDED : r.sort();

        // Inner query computes the sort key once; the outer one applies the cursor and the page size.
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM (
                  SELECT li.id, li.location_id, li.kind, li.title, li.title_i18n, li.summary_i18n, li.image_url, li.attributes,
                         li.price_from_minor, li.currency_code, li.rating_avg, li.rating_count, li.next_available_at,
                         li.next_starts_at, li.last_ends_at, li.is_online, li.listed_since,
                """);
        List<Object> binds = new ArrayList<>();
        sql.append("         ").append(b.distance()).append(" AS distance,\n");
        binds.addAll(b.distanceBinds());
        sql.append("         round((").append(sortKeyFor(sort, r, b, binds)).append(")::numeric, 6) AS sort_key\n");
        sql.append("    FROM discovery.listings li WHERE ")
                .append(b.where().sql)
                .append("\n  ) x\n");
        binds.addAll(b.where().binds);
        if (r.cursor() != null && !r.cursor().isBlank()) {
            List<String> parts = Cursors.decode(r.cursor(), 2);
            sql.append(" WHERE (x.sort_key, x.id) ")
                    .append(b.ascending() ? ">" : "<")
                    .append(" (?::numeric, ?::uuid)\n");
            binds.add(parts.get(0));
            binds.add(parts.get(1));
        }
        sql.append(" ORDER BY x.sort_key ")
                .append(b.ascending() ? "ASC" : "DESC")
                .append(", x.id ")
                .append(b.ascending() ? "ASC" : "DESC")
                .append(" LIMIT ?");
        binds.add(limit + 1);

        List<Record> rows =
                new ArrayList<>(dsl.resultQuery(sql.toString(), binds.toArray()).fetch());
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            Record last = rows.getLast();
            next = Cursors.encode(
                    last.get("sort_key", BigDecimal.class).toPlainString(),
                    last.get("id", UUID.class).toString());
        }
        List<Item> items = new ArrayList<>();
        for (Record row : rows) {
            items.add(item(row, b, now));
        }
        Integer total = null;
        if (r.cursor() == null || r.cursor().isBlank()) {
            total = dsl.resultQuery(
                            "SELECT count(*) FROM discovery.listings li WHERE " + b.where().sql,
                            b.where().binds.toArray())
                    .fetchOne(0, Integer.class);
        }
        return new Page(items, next, total);
    }

    /** The sort key's SQL, with its binds appended in the order of their placeholders. */
    private String sortKeyFor(Sort sort, Request r, Built b, List<Object> binds) {
        return switch (sort) {
            case NEAREST -> {
                binds.addAll(b.distanceBinds());
                yield b.distance();
            }
            case TOP_RATED -> "coalesce(li.rating_avg, 0) * 100000 + least(li.rating_count, 99999)";
            case LOWEST_PRICE -> "coalesce(li.price_from_minor, 9999999999999)";
            case SOONEST ->
                "coalesce(extract(epoch FROM coalesce(li.next_starts_at, li.next_available_at)), " + FAR + ")";
            case NEWEST -> "extract(epoch FROM li.listed_since)";
            case RECOMMENDED -> {
                String tsQuery =
                        SearchText.tsQuery(r.text() == null ? "" : r.text().trim());
                if (tsQuery.isEmpty()) {
                    yield "li.quality_score";
                }
                binds.add(tsQuery);
                binds.add(SearchText.fuzzy(r.text().trim()));
                yield "ts_rank_cd(li.search_document, to_tsquery('simple', ?), 32) * 100"
                        + " + word_similarity(?, li.search_text) * 20 + li.quality_score";
            }
        };
    }

    private Item item(Record row, Built b, Instant now) {
        JsonNode a = json.readTree(row.get("attributes", JSONB.class).data());
        Kind kind = Kind.valueOf(row.get("kind", String.class));
        Instant listedSince = row.get("listed_since", OffsetDateTime.class).toInstant();
        Double distance = row.get("distance", Double.class);
        Long price = row.get("price_from_minor", Long.class);
        BigDecimal rating = row.get("rating_avg", BigDecimal.class);
        String path = "/" + slug(a, "businessSlug") + "/"
                + (kind == Kind.LOCATION ? slug(a, "locationSlug") : slug(a, "eventSlug"));
        List<String> modes = new ArrayList<>();
        a.path("deliveryModes").forEach(n -> modes.add(n.asString()));
        Map<String, String> imageVariants = new LinkedHashMap<>();
        a.path("imageVariants")
                .properties()
                .forEach(e -> imageVariants.put(e.getKey(), e.getValue().asString()));
        return new Item(
                row.get("id", UUID.class),
                row.get("location_id", UUID.class),
                kind,
                row.get("title", String.class),
                text(row.get("summary_i18n", JSONB.class)),
                row.get("image_url", String.class),
                imageVariants,
                slug(a, "businessSlug"),
                kind == Kind.LOCATION ? slug(a, "locationSlug") : null,
                kind == Kind.EVENT ? slug(a, "eventSlug") : null,
                path,
                map(a.path("category")),
                map(a.path("area")),
                a.path("landmark").isNull() || a.path("landmark").isMissingNode()
                        ? null
                        : a.path("landmark").asString(),
                a.hasNonNull("lat") ? a.get("lat").asDouble() : null,
                a.hasNonNull("lng") ? a.get("lng").asDouble() : null,
                distance == null ? null : (int) Math.round(distance),
                price,
                row.get("currency_code", String.class),
                rating == null ? null : rating.doubleValue(),
                row.get("rating_count", Integer.class),
                instant(row.get("next_available_at", OffsetDateTime.class)),
                instant(row.get("next_starts_at", OffsetDateTime.class)),
                instant(row.get("last_ends_at", OffsetDateTime.class)),
                Boolean.TRUE.equals(row.get("is_online", Boolean.class)),
                a.path("timezone").asString(null),
                a.hasNonNull("serviceMode") ? a.get("serviceMode").asString() : null,
                modes,
                a.path("serviceCount").asInt(0),
                menu(a.path("menu"), b),
                listedSince.isAfter(now.minus(NEW_DAYS, ChronoUnit.DAYS)));
    }

    private List<MenuLine> menu(JsonNode menu, Built b) {
        if (!menu.isArray() || (b.words().isEmpty() && b.categoryIds().isEmpty())) {
            return List.of();
        }
        List<MenuLine> matches = new ArrayList<>();
        for (JsonNode line : menu) {
            Map<String, String> name = map(line.path("name"));
            String haystack = SearchText.document(new ArrayList<>(name.values()));
            boolean byWord = b.words().stream().anyMatch(w -> haystack.contains(w));
            boolean byCategory = line.hasNonNull("categoryId")
                    && b.categoryIds()
                            .contains(UUID.fromString(line.get("categoryId").asString()));
            if (byWord || byCategory) {
                matches.add(new MenuLine(
                        UUID.fromString(line.get("id").asString()),
                        name,
                        line.path("minutes").asInt(),
                        line.path("priceMinor").asLong(),
                        line.path("priceType").asString("FIXED"),
                        true));
            }
        }
        matches.sort(Comparator.comparingLong(MenuLine::priceMinor));
        return matches.size() > MENU_SHOWN ? List.copyOf(matches.subList(0, MENU_SHOWN)) : List.copyOf(matches);
    }

    @Override
    public Pins pins(Request r, Instant now) {
        if (r.box() == null && r.near() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The map needs a window or a location.");
        }
        Built b = build(r, now);
        String sql =
                "SELECT li.id, li.location_id, li.kind, li.title, li.attributes, li.price_from_minor, li.currency_code, li.rating_avg"
                        + " FROM discovery.listings li WHERE " + b.where().sql + " AND li.geo IS NOT NULL"
                        + " ORDER BY li.quality_score DESC, li.id LIMIT " + (MAX_PINS + 1);
        List<Record> rows = transactions.execute(
                status -> dsl.resultQuery(sql, b.where().binds.toArray()).fetch());
        List<Pin> pins = new ArrayList<>();
        for (Record row : rows.subList(0, Math.min(rows.size(), MAX_PINS))) {
            JsonNode a = json.readTree(row.get("attributes", JSONB.class).data());
            if (!a.hasNonNull("lat") || !a.hasNonNull("lng")) {
                continue;
            }
            Kind kind = Kind.valueOf(row.get("kind", String.class));
            BigDecimal rating = row.get("rating_avg", BigDecimal.class);
            pins.add(new Pin(
                    row.get("id", UUID.class),
                    kind,
                    row.get("title", String.class),
                    "/" + slug(a, "businessSlug") + "/"
                            + (kind == Kind.LOCATION ? slug(a, "locationSlug") : slug(a, "eventSlug")),
                    a.get("lat").asDouble(),
                    a.get("lng").asDouble(),
                    row.get("price_from_minor", Long.class),
                    row.get("currency_code", String.class),
                    rating == null ? null : rating.doubleValue()));
        }
        return new Pins(pins, rows.size() > MAX_PINS);
    }

    @Override
    public Suggestions suggest(String typed, String market, int limit) {
        String normalized = SearchText.normalize(typed);
        if (normalized.isEmpty()) {
            return new Suggestions(List.of(), List.of(), List.of());
        }
        Set<String> needles = new LinkedHashSet<>(SearchText.words(normalized));
        needles.addAll(SearchText.words(SearchText.romanize(normalized)));
        List<CategoryHit> categories = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Record r : transactions.execute(status -> dsl.resultQuery(
                        "SELECT code, kind, name_i18n FROM ref.categories WHERE is_active"
                                + " ORDER BY CASE kind WHEN 'SERVICE' THEN 0 WHEN 'EVENT' THEN 1 ELSE 2 END, sort_order")
                .fetch())) {
            Map<String, String> name = text(r.get("name_i18n", JSONB.class));
            String haystack = SearchText.document(new ArrayList<>(name.values()));
            boolean all = needles.stream().allMatch(n -> haystack.contains(n))
                    || SearchText.words(normalized).stream().allMatch(n -> haystack.contains(n));
            String englishKey = SearchText.normalize(name.getOrDefault("en", r.get("code", String.class)));
            if (all && seen.add(englishKey) && categories.size() < limit) {
                categories.add(new CategoryHit(r.get("code", String.class), r.get("kind", String.class), name));
            }
        }
        Instant now = Instant.now();
        Request base = new Request(
                typed,
                Set.of(Kind.LOCATION),
                market,
                null,
                null,
                null,
                null,
                Sort.RECOMMENDED,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                limit);
        List<Item> places = search(base, now).items();
        Request events = new Request(
                typed,
                Set.of(Kind.EVENT),
                market,
                null,
                null,
                null,
                null,
                Sort.RECOMMENDED,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                limit);
        return new Suggestions(categories, places, search(events, now).items());
    }

    @Override
    public void record(Request r, int resultCount) {
        try {
            Map<String, Object> filters = new LinkedHashMap<>();
            put(
                    filters,
                    "kinds",
                    r.kinds() == null || r.kinds().isEmpty()
                            ? null
                            : r.kinds().stream().map(Enum::name).sorted().toList());
            put(filters, "category", r.category());
            put(filters, "area", r.area());
            put(filters, "sort", r.sort() == null ? null : r.sort().name());
            put(filters, "near", r.near() != null ? true : null);
            put(filters, "maxPriceMinor", r.maxPriceMinor());
            put(filters, "minRating", r.minRating());
            put(filters, "openNow", r.openNow() ? true : null);
            put(filters, "atHome", r.atHome() ? true : null);
            put(filters, "audience", r.audience());
            put(filters, "language", r.language());
            put(filters, "from", r.from() == null ? null : r.from().toString());
            put(filters, "dayPart", r.dayPart() == null ? null : r.dayPart().name());
            String query =
                    r.text() == null || r.text().isBlank() ? null : r.text().trim();
            if (query != null && query.length() > 200) {
                query = query.substring(0, 200);
            }
            String q = query;
            transactions.executeWithoutResult(status -> dsl.execute(
                    "INSERT INTO discovery.search_events (market_id, query, filters, result_count)"
                            + " VALUES ((SELECT m.id FROM ref.markets m WHERE m.code = ?), ?, ?::jsonb, ?)",
                    r.market() == null ? null : r.market().trim().toUpperCase(),
                    q,
                    JSONB.jsonb(json.writeValueAsString(filters)),
                    resultCount));
        } catch (RuntimeException e) {
            log.debug("Search not recorded", e);
        }
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String slug(JsonNode a, String field) {
        return a.path(field).asString("");
    }

    private Map<String, String> text(JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), TEXT);
    }

    private Map<String, String> map(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        }
        return out;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
