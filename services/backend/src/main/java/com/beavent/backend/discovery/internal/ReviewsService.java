package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Reviews;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.Cursors;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reviews in {@code discovery.reviews}. A review exists only for a completed appointment of the person who wrote it
 * (the database allows nothing else), so every review shown is verified. After each change the place's average and
 * count in {@code discovery.listings} are recomputed from its published reviews, in the same transaction.
 */
@Component
class ReviewsService implements Reviews {

    private static final Set<String> REPORT_REASONS =
            Set.of("INAPPROPRIATE", "HARASSMENT", "MISLEADING_PRICE", "UNSAFE", "OTHER");

    /** The visit's first service and who did it; the review is about the place, these only say what was had. */
    private static final String FIRST_ITEM = """
            LEFT JOIN LATERAL (
                SELECT i.service_name, sv.name_i18n AS service_i18n, s.display_name AS staff_name, i.staff_id
                  FROM scheduling.appointment_items i
                  LEFT JOIN team.staff s ON s.id = i.staff_id AND s.org_id = i.org_id
                  LEFT JOIN (SELECT v.id, sc.name_i18n FROM catalog.service_variants v
                               JOIN catalog.services sc ON sc.id = v.service_id AND sc.org_id = v.org_id) sv
                         ON sv.id = i.service_variant_id
                 WHERE i.appointment_id = a.id AND i.org_id = a.org_id
                 ORDER BY i.seq LIMIT 1) it ON true
            """;

    /** "Hana T.": the given name and the initial of the father's name; null when the person gave no name. */
    private static final String AUTHOR_NAME = """
            nullif(btrim(coalesce(nullif(btrim(p.first_name), ''), nullif(btrim(p.display_name), ''), '')
                   || CASE WHEN nullif(btrim(p.first_name), '') IS NOT NULL AND nullif(btrim(p.last_name), '') IS NOT NULL
                           THEN ' ' || left(btrim(p.last_name), 1) || '.' ELSE '' END), '')
            """;

    private static final tools.jackson.core.type.TypeReference<Map<String, String>> TEXT =
            new tools.jackson.core.type.TypeReference<>() {};

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Outbox outbox;
    private final Audit audit;
    private final tools.jackson.databind.json.JsonMapper json;

    ReviewsService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            Outbox outbox,
            Audit audit,
            tools.jackson.databind.json.JsonMapper json) {
        this.json = json;
        this.system = system;
        this.transactions = transactions;
        this.outbox = outbox;
        this.audit = audit;
    }

    // --- shared -----------------------------------------------------------------------------------------------

    /** The service in every language the business named it in; the booking's own wording when there is no menu entry. */
    private Map<String, String> service(Record r) {
        org.jooq.JSONB names = r.get("service_i18n", org.jooq.JSONB.class);
        if (names != null) {
            Map<String, String> map = json.readValue(names.data(), TEXT);
            if (!map.isEmpty()) {
                return map;
            }
        }
        String snapshot = r.get("service_name", String.class);
        return snapshot == null ? null : Map.of("en", snapshot);
    }

    private static Instant instant(Record r, String field) {
        OffsetDateTime at = r.get(field, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private static Response response(Record r) {
        String body = r.get("response_body", String.class);
        return body == null ? null : new Response(body, instant(r, "responded_at"));
    }

    private static LocalDate visitedOn(Record r) {
        return r.get("starts_at", OffsetDateTime.class)
                .atZoneSameInstant(ZoneId.of(r.get("timezone", String.class)))
                .toLocalDate();
    }

    private static String clean(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.strip();
        if (s.codePointCount(0, s.length()) > max) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Keep it under " + max + " characters.");
        }
        return s;
    }

    private static void checkRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose 1 to 5 stars.");
        }
    }

    private Summary summary(String where, Object... binds) {
        Record r = system.resultQuery("""
                        SELECT count(*) AS n, round(avg(rating), 2) AS avg,
                               count(*) FILTER (WHERE rating = 1) AS s1, count(*) FILTER (WHERE rating = 2) AS s2,
                               count(*) FILTER (WHERE rating = 3) AS s3, count(*) FILTER (WHERE rating = 4) AS s4,
                               count(*) FILTER (WHERE rating = 5) AS s5
                          FROM discovery.reviews r
                         WHERE r.status = 'PUBLISHED' AND\s""" + where, binds).fetchSingle();
        int n = r.get("n", Integer.class);
        return new Summary(
                n == 0 ? null : r.get("avg", java.math.BigDecimal.class).doubleValue(),
                n,
                List.of(
                        r.get("s1", Integer.class),
                        r.get("s2", Integer.class),
                        r.get("s3", Integer.class),
                        r.get("s4", Integer.class),
                        r.get("s5", Integer.class)));
    }

    /** The place's rating in search follows its published reviews. */
    private void refreshRating(UUID locationId) {
        system.execute("""
                UPDATE discovery.listings li
                   SET rating_avg = s.avg, rating_count = s.n, updated_at = now()
                  FROM (SELECT round(avg(rating), 2) AS avg, count(*)::int AS n
                          FROM discovery.reviews WHERE location_id = ? AND status = 'PUBLISHED') s
                 WHERE li.location_id = ? AND (li.rating_avg, li.rating_count) IS DISTINCT FROM (s.avg, s.n)
                """, locationId, locationId);
    }

    private Record reviewRow(UUID reviewId) {
        Record r = system.resultQuery("SELECT * FROM discovery.reviews WHERE id = ?", reviewId)
                .fetchOne();
        if (r == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such review.");
        }
        return r;
    }

    // --- the public -------------------------------------------------------------------------------------------

    @Override
    public Page forBusiness(String businessSlug, String locationSlug, String cursor, int limit) {
        Record scope = system.resultQuery("""
                        SELECT o.id AS org_id, (SELECT l.id FROM tenant.locations l
                                                 WHERE l.org_id = o.id AND l.slug = ?::citext) AS location_id
                          FROM tenant.organizations o
                         WHERE o.slug = ? AND o.status IN ('ACTIVE','ONBOARDING')
                        """, locationSlug, businessSlug.toLowerCase())
                .fetchOne();
        if (scope == null || (locationSlug != null && scope.get("location_id") == null)) {
            return null;
        }
        UUID orgId = scope.get("org_id", UUID.class);
        UUID locationId = scope.get("location_id", UUID.class);
        String where = locationId == null ? "r.org_id = ?" : "r.location_id = ?";
        Object scopeId = locationId == null ? orgId : locationId;
        Summary summary = summary(where, scopeId);

        List<Object> binds = new ArrayList<>();
        binds.add(scopeId);
        String after = "";
        if (cursor != null && !cursor.isBlank()) {
            List<String> parts = Cursors.decode(cursor, 2);
            after = " AND (r.created_at, r.id) < (?::timestamptz, ?::uuid)";
            binds.add(parts.get(0));
            binds.add(parts.get(1));
        }
        binds.add(limit + 1);
        List<Record> rows = system.resultQuery("""
                SELECT r.id, r.rating, r.body, r.created_at, r.response_body, r.responded_at,
                       l.slug::text AS location_slug, l.name AS place_name, a.starts_at, a.timezone,
                       it.service_name, it.service_i18n, it.staff_name, %s AS author_name
                  FROM discovery.reviews r
                  JOIN tenant.locations l ON l.id = r.location_id
                  JOIN scheduling.appointments a ON a.id = r.appointment_id AND a.org_id = r.org_id
                  LEFT JOIN iam.user_profiles p ON p.user_id = r.author_user_id
                  %s
                 WHERE r.status = 'PUBLISHED' AND %s%s
                 ORDER BY r.created_at DESC, r.id DESC
                 LIMIT ?
                """.formatted(AUTHOR_NAME, FIRST_ITEM, where, after), binds.toArray())
                .fetch();
        List<Review> items = new ArrayList<>();
        for (Record r : rows.subList(0, Math.min(limit, rows.size()))) {
            items.add(new Review(
                    r.get("id", UUID.class),
                    r.get("rating", Integer.class),
                    r.get("body", String.class),
                    r.get("author_name", String.class),
                    r.get("location_slug", String.class),
                    r.get("place_name", String.class),
                    service(r),
                    r.get("staff_name", String.class),
                    visitedOn(r),
                    instant(r, "created_at"),
                    response(r)));
        }
        String next = rows.size() > limit && !items.isEmpty()
                ? Cursors.encode(
                        items.getLast().createdAt().toString(),
                        items.getLast().id().toString())
                : null;
        return new Page(summary, items, next);
    }

    // --- the reviewer -----------------------------------------------------------------------------------------

    @Override
    public MyReviews mine(UUID userId, Instant now) {
        List<Pending> pending = new ArrayList<>();
        for (Record r : system.resultQuery(
                        """
                        SELECT a.id, a.location_id, a.ends_at, o.slug::text AS business_slug, l.slug::text AS location_slug,
                               l.name AS place_name, it.service_name, it.service_i18n, it.staff_name
                          FROM scheduling.appointments a
                          JOIN crm.customers c ON c.id = a.customer_id AND c.org_id = a.org_id
                          JOIN tenant.organizations o ON o.id = a.org_id
                          JOIN tenant.locations l ON l.id = a.location_id
                          %s
                         WHERE c.user_id = ? AND a.status = 'COMPLETED'
                           AND a.ends_at > ?::timestamptz - make_interval(days => ?)
                           AND NOT EXISTS (SELECT 1 FROM discovery.reviews r
                                            WHERE r.appointment_id = a.id AND r.author_user_id = c.user_id)
                         ORDER BY a.ends_at DESC LIMIT 20
                        """.formatted(FIRST_ITEM), userId, now.atOffset(java.time.ZoneOffset.UTC), WINDOW_DAYS)
                .fetch()) {
            Instant ended = instant(r, "ends_at");
            pending.add(new Pending(
                    r.get("id", UUID.class),
                    r.get("location_id", UUID.class),
                    r.get("business_slug", String.class),
                    r.get("location_slug", String.class),
                    r.get("place_name", String.class),
                    service(r),
                    r.get("staff_name", String.class),
                    ended,
                    ended.plus(java.time.Duration.ofDays(WINDOW_DAYS))));
        }
        List<Mine> reviews = new ArrayList<>();
        for (Record r : system.resultQuery(
                        MINE_SELECT + " WHERE r.author_user_id = ? ORDER BY r.created_at DESC, r.id DESC LIMIT 100",
                        userId)
                .fetch()) {
            reviews.add(mine(r));
        }
        return new MyReviews(pending, reviews);
    }

    private static final String MINE_SELECT = """
            SELECT r.id, r.appointment_id, r.rating, r.body, r.status, r.created_at, r.response_body, r.responded_at,
                   o.slug::text AS business_slug, l.slug::text AS location_slug, l.name AS place_name, it.service_name, it.service_i18n
              FROM discovery.reviews r
              JOIN tenant.organizations o ON o.id = r.org_id
              JOIN tenant.locations l ON l.id = r.location_id
              JOIN scheduling.appointments a ON a.id = r.appointment_id AND a.org_id = r.org_id
              """ + FIRST_ITEM;

    private Mine mine(Record r) {
        return new Mine(
                r.get("id", UUID.class),
                r.get("appointment_id", UUID.class),
                r.get("business_slug", String.class),
                r.get("location_slug", String.class),
                r.get("place_name", String.class),
                service(r),
                r.get("rating", Integer.class),
                r.get("body", String.class),
                r.get("status", String.class),
                instant(r, "created_at"),
                response(r));
    }

    private Mine mineOne(UUID reviewId) {
        return mine(
                system.resultQuery(MINE_SELECT + " WHERE r.id = ?", reviewId).fetchSingle());
    }

    @Override
    public Mine write(UUID userId, UUID appointmentId, int rating, String body, Instant now) {
        checkRating(rating);
        String text = clean(body, MAX_BODY);
        UUID reviewId = transactions.execute(s -> {
            Record a = system.resultQuery("""
                            SELECT a.id, a.org_id, a.location_id, a.status, a.ends_at, it.staff_id
                              FROM scheduling.appointments a
                              JOIN crm.customers c ON c.id = a.customer_id AND c.org_id = a.org_id
                              %s
                             WHERE a.id = ? AND c.user_id = ?
                            """.formatted(FIRST_ITEM), appointmentId, userId)
                    .fetchOne();
            if (a == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such visit.");
            }
            if (!"COMPLETED".equals(a.get("status", String.class))) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION, "You can review a visit once it has been completed.");
            }
            if (instant(a, "ends_at").isBefore(now.minus(java.time.Duration.ofDays(WINDOW_DAYS)))) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "Reviews can be written within " + WINDOW_DAYS + " days of the visit.");
            }
            UUID id = system.resultQuery(
                            """
                            INSERT INTO discovery.reviews (org_id, location_id, staff_id, author_user_id, appointment_id,
                                                           rating, body)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT DO NOTHING RETURNING id
                            """,
                            a.get("org_id"),
                            a.get("location_id"),
                            a.get("staff_id"),
                            userId,
                            appointmentId,
                            rating,
                            text)
                    .fetchOne(0, UUID.class);
            if (id == null) {
                throw new ApiException(ProblemCode.CONFLICT, "You already reviewed this visit.");
            }
            UUID orgId = a.get("org_id", UUID.class);
            UUID locationId = a.get("location_id", UUID.class);
            refreshRating(locationId);
            outbox.publish(new DomainEvent(
                    "review",
                    id,
                    "review.published",
                    1,
                    orgId,
                    Map.of("reviewId", id, "locationId", locationId, "rating", rating)));
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "review.created",
                    "discovery.review",
                    id.toString(),
                    null,
                    Map.of("rating", rating),
                    null));
            return id;
        });
        return mineOne(reviewId);
    }

    @Override
    public Mine edit(UUID userId, UUID reviewId, int rating, String body) {
        checkRating(rating);
        String text = clean(body, MAX_BODY);
        transactions.executeWithoutResult(s -> {
            Record r = ownPublished(userId, reviewId);
            system.execute(
                    "UPDATE discovery.reviews SET rating = ?, body = ?, updated_at = now() WHERE id = ?",
                    rating,
                    text,
                    reviewId);
            refreshRating(r.get("location_id", UUID.class));
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    r.get("org_id", UUID.class),
                    "review.edited",
                    "discovery.review",
                    reviewId.toString(),
                    Map.of("rating", r.get("rating", Integer.class)),
                    Map.of("rating", rating),
                    null));
        });
        return mineOne(reviewId);
    }

    @Override
    public void delete(UUID userId, UUID reviewId) {
        transactions.executeWithoutResult(s -> {
            Record r = ownPublished(userId, reviewId);
            system.execute("DELETE FROM trust.content_reports WHERE review_id = ?", reviewId);
            system.execute("DELETE FROM discovery.reviews WHERE id = ?", reviewId);
            refreshRating(r.get("location_id", UUID.class));
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    r.get("org_id", UUID.class),
                    "review.deleted",
                    "discovery.review",
                    reviewId.toString(),
                    Map.of("rating", r.get("rating", Integer.class)),
                    null,
                    null));
        });
    }

    private Record ownPublished(UUID userId, UUID reviewId) {
        Record r = system.resultQuery(
                        "SELECT * FROM discovery.reviews WHERE id = ? AND author_user_id = ?", reviewId, userId)
                .fetchOne();
        if (r == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such review.");
        }
        if (!"PUBLISHED".equals(r.get("status", String.class))) {
            throw new ApiException(ProblemCode.CONFLICT, "Beavent took this review down; it cannot be changed.");
        }
        return r;
    }

    // --- the business -----------------------------------------------------------------------------------------

    private static final String FOR_BUSINESS = """
            SELECT r.id, r.rating, r.body, r.status, r.created_at, r.response_body, r.responded_at, r.location_id,
                   l.name AS place_name, a.starts_at, a.timezone, it.service_name, it.service_i18n, it.staff_name, %s AS author_name,
                   EXISTS (SELECT 1 FROM trust.content_reports cr
                            WHERE cr.review_id = r.id AND cr.status IN ('OPEN','IN_REVIEW')) AS reported
              FROM discovery.reviews r
              JOIN tenant.locations l ON l.id = r.location_id
              JOIN scheduling.appointments a ON a.id = r.appointment_id AND a.org_id = r.org_id
              LEFT JOIN iam.user_profiles p ON p.user_id = r.author_user_id
              %s
            """.formatted(AUTHOR_NAME, FIRST_ITEM);

    private ForBusiness forBusiness(Record r) {
        return new ForBusiness(
                r.get("id", UUID.class),
                r.get("rating", Integer.class),
                r.get("body", String.class),
                r.get("author_name", String.class),
                r.get("location_id", UUID.class),
                r.get("place_name", String.class),
                service(r),
                r.get("staff_name", String.class),
                visitedOn(r),
                instant(r, "created_at"),
                r.get("status", String.class),
                response(r),
                r.get("reported", Boolean.class));
    }

    private ForBusiness businessOne(UUID orgId, UUID reviewId) {
        Record r = system.resultQuery(FOR_BUSINESS + " WHERE r.id = ? AND r.org_id = ?", reviewId, orgId)
                .fetchOne();
        if (r == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such review.");
        }
        return forBusiness(r);
    }

    @Override
    public BusinessPage ofOrganization(UUID orgId, boolean unanswered, String cursor, int limit) {
        List<Object> binds = new ArrayList<>();
        binds.add(orgId);
        String filter = unanswered ? " AND r.response_body IS NULL AND r.status = 'PUBLISHED'" : "";
        if (cursor != null && !cursor.isBlank()) {
            List<String> parts = Cursors.decode(cursor, 2);
            filter += " AND (r.created_at, r.id) < (?::timestamptz, ?::uuid)";
            binds.add(parts.get(0));
            binds.add(parts.get(1));
        }
        binds.add(limit + 1);
        List<Record> rows = system.resultQuery(
                        FOR_BUSINESS + " WHERE r.org_id = ?" + filter
                                + " ORDER BY r.created_at DESC, r.id DESC LIMIT ?",
                        binds.toArray())
                .fetch();
        List<ForBusiness> items = new ArrayList<>();
        for (Record r : rows.subList(0, Math.min(limit, rows.size()))) {
            items.add(forBusiness(r));
        }
        String next = rows.size() > limit && !items.isEmpty()
                ? Cursors.encode(
                        items.getLast().createdAt().toString(),
                        items.getLast().id().toString())
                : null;
        return new BusinessPage(summary("r.org_id = ?", orgId), items, next);
    }

    private Record ownedBy(UUID orgId, UUID reviewId) {
        Record r = system.resultQuery("SELECT * FROM discovery.reviews WHERE id = ? AND org_id = ?", reviewId, orgId)
                .fetchOne();
        if (r == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such review.");
        }
        return r;
    }

    @Override
    public ForBusiness respond(UUID orgId, UUID userId, UUID reviewId, String body) {
        String text = clean(body, MAX_RESPONSE);
        if (text == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Write an answer.");
        }
        transactions.executeWithoutResult(s -> {
            Record r = ownedBy(orgId, reviewId);
            if (!"PUBLISHED".equals(r.get("status", String.class))) {
                throw new ApiException(ProblemCode.CONFLICT, "This review is not published.");
            }
            system.execute(
                    "UPDATE discovery.reviews SET response_body = ?, responded_by = ?, responded_at = now(),"
                            + " updated_at = now() WHERE id = ?",
                    text,
                    userId,
                    reviewId);
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "review.answered",
                    "discovery.review",
                    reviewId.toString(),
                    null,
                    Map.of("answered", true),
                    null));
        });
        return businessOne(orgId, reviewId);
    }

    @Override
    public ForBusiness removeResponse(UUID orgId, UUID userId, UUID reviewId) {
        transactions.executeWithoutResult(s -> {
            ownedBy(orgId, reviewId);
            system.execute(
                    "UPDATE discovery.reviews SET response_body = NULL, responded_by = NULL, responded_at = NULL,"
                            + " updated_at = now() WHERE id = ?",
                    reviewId);
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "review.answer_removed",
                    "discovery.review",
                    reviewId.toString(),
                    null,
                    null,
                    null));
        });
        return businessOne(orgId, reviewId);
    }

    @Override
    public ForBusiness report(UUID orgId, UUID userId, UUID reviewId, String reason, String details) {
        if (!REPORT_REASONS.contains(reason)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a reason from the list.");
        }
        String text = clean(details, 500);
        transactions.executeWithoutResult(s -> {
            ownedBy(orgId, reviewId);
            boolean open = system.resultQuery(
                            "SELECT EXISTS (SELECT 1 FROM trust.content_reports WHERE review_id = ?"
                                    + " AND status IN ('OPEN','IN_REVIEW'))",
                            reviewId)
                    .fetchOne(0, Boolean.class);
            if (!open) {
                system.execute(
                        "INSERT INTO trust.content_reports (reporter_user_id, org_id, review_id, reason, details)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        userId,
                        orgId,
                        reviewId,
                        reason,
                        text);
                audit.record(new Audit.Entry(
                        Audit.Actor.USER,
                        userId,
                        orgId,
                        "review.reported",
                        "discovery.review",
                        reviewId.toString(),
                        null,
                        Map.of("reason", reason),
                        text));
            }
        });
        return businessOne(orgId, reviewId);
    }

    // --- staff ------------------------------------------------------------------------------------------------

    @Override
    public UUID marketOf(UUID reviewId) {
        return system.resultQuery("""
                        SELECT o.home_market_id FROM discovery.reviews r
                          JOIN tenant.organizations o ON o.id = r.org_id WHERE r.id = ?
                        """, reviewId).fetchOne(0, UUID.class);
    }

    @Override
    public List<ForStaff> forStaff(List<UUID> reviewIds) {
        if (reviewIds.isEmpty()) {
            return List.of();
        }
        UUID[] ids = reviewIds.toArray(UUID[]::new);
        Map<UUID, List<Report>> reports = new HashMap<>();
        for (Record r : system.resultQuery(
                        "SELECT id, review_id, reason, details, status, created_at FROM trust.content_reports"
                                + " WHERE review_id = ANY(?::uuid[]) ORDER BY created_at DESC",
                        (Object) ids)
                .fetch()) {
            reports.computeIfAbsent(r.get("review_id", UUID.class), k -> new ArrayList<>())
                    .add(new Report(
                            r.get("id", UUID.class),
                            r.get("reason", String.class),
                            r.get("details", String.class),
                            r.get("status", String.class),
                            instant(r, "created_at")));
        }
        Map<UUID, ForStaff> byId = new HashMap<>();
        for (Record r :
                system.resultQuery("""
                        SELECT r.id, r.org_id, o.display_name AS business_name, l.name AS place_name, r.rating, r.body,
                               r.status, r.created_at, r.response_body, r.responded_at, %s AS author_name
                          FROM discovery.reviews r
                          JOIN tenant.organizations o ON o.id = r.org_id
                          JOIN tenant.locations l ON l.id = r.location_id
                          LEFT JOIN iam.user_profiles p ON p.user_id = r.author_user_id
                         WHERE r.id = ANY(?::uuid[])
                        """.formatted(AUTHOR_NAME), (Object) ids).fetch()) {
            UUID id = r.get("id", UUID.class);
            byId.put(
                    id,
                    new ForStaff(
                            id,
                            r.get("org_id", UUID.class),
                            r.get("business_name", String.class),
                            r.get("place_name", String.class),
                            r.get("rating", Integer.class),
                            r.get("body", String.class),
                            r.get("author_name", String.class),
                            r.get("status", String.class),
                            instant(r, "created_at"),
                            response(r),
                            reports.getOrDefault(id, List.of())));
        }
        List<ForStaff> out = new ArrayList<>();
        for (UUID id : reviewIds) {
            if (byId.containsKey(id)) {
                out.add(byId.get(id));
            }
        }
        return out;
    }

    @Override
    public ForStaff moderate(UUID reviewId, UUID staffUserId, Decision decision, String reason) {
        transactions.executeWithoutResult(s -> {
            Record r = reviewRow(reviewId);
            String status = r.get("status", String.class);
            UUID orgId = r.get("org_id", UUID.class);
            boolean reported = system.resultQuery(
                            "SELECT EXISTS (SELECT 1 FROM trust.content_reports WHERE review_id = ?"
                                    + " AND status IN ('OPEN','IN_REVIEW'))",
                            reviewId)
                    .fetchOne(0, Boolean.class);
            switch (decision) {
                case HIDE -> {
                    if (!"PUBLISHED".equals(status)) {
                        throw new ApiException(ProblemCode.CONFLICT, "This review is not published.");
                    }
                    system.execute(
                            "UPDATE discovery.reviews SET status = 'HIDDEN', updated_at = now() WHERE id = ?",
                            reviewId);
                    closeReports(reviewId, "ACTIONED", staffUserId);
                    action(reviewId, orgId, "HIDE", reason, staffUserId);
                }
                case RESTORE -> {
                    if (!"HIDDEN".equals(status)) {
                        throw new ApiException(ProblemCode.CONFLICT, "This review is not hidden.");
                    }
                    system.execute(
                            "UPDATE discovery.reviews SET status = 'PUBLISHED', updated_at = now() WHERE id = ?",
                            reviewId);
                    closeReports(reviewId, "DISMISSED", staffUserId);
                    action(reviewId, orgId, "RESTORE", reason, staffUserId);
                }
                case DISMISS -> {
                    if (!system.resultQuery(
                                    "SELECT EXISTS (SELECT 1 FROM trust.content_reports WHERE review_id = ?"
                                            + " AND status IN ('OPEN','IN_REVIEW'))",
                                    reviewId)
                            .fetchOne(0, Boolean.class)) {
                        throw new ApiException(ProblemCode.CONFLICT, "There is no open report on this review.");
                    }
                    closeReports(reviewId, "DISMISSED", staffUserId);
                }
            }
            refreshRating(r.get("location_id", UUID.class));
            Map<String, Object> event = new java.util.HashMap<>();
            event.put("reviewId", reviewId);
            event.put("locationId", r.get("location_id", UUID.class));
            event.put("authorUserId", r.get("author_user_id", UUID.class));
            event.put("decision", decision.name());
            event.put("reported", reported);
            event.put("reason", reason);
            event.put("at", Instant.now().toString());
            outbox.publish(new DomainEvent("review", reviewId, "review.moderated", 1, orgId, event));
            audit.record(new Audit.Entry(
                    Audit.Actor.PLATFORM_STAFF,
                    staffUserId,
                    orgId,
                    "review." + decision.name().toLowerCase(java.util.Locale.ROOT),
                    "discovery.review",
                    reviewId.toString(),
                    Map.of("status", status),
                    null,
                    reason));
        });
        return forStaff(List.of(reviewId)).getFirst();
    }

    private void closeReports(UUID reviewId, String status, UUID staffUserId) {
        system.execute(
                "UPDATE trust.content_reports SET status = ?, resolved_by = ?, resolved_at = now()"
                        + " WHERE review_id = ? AND status IN ('OPEN','IN_REVIEW')",
                status,
                staffUserId,
                reviewId);
    }

    private void action(UUID reviewId, UUID orgId, String action, String reason, UUID staffUserId) {
        system.execute(
                "INSERT INTO trust.moderation_actions (org_id, review_id, action, reason, actor_user_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                orgId,
                reviewId,
                action,
                reason,
                staffUserId);
    }
}
