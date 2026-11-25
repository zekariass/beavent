package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Follows;
import com.beavent.backend.files.FileAccess;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Followed businesses, kept in {@code discovery.follows}. Every query names the person. */
@Component
class FollowsService implements Follows {

    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};
    private static final String VISIBLE = "o.status IN ('ACTIVE','ONBOARDING')";

    private final DSLContext system;
    private final FileAccess files;
    private final JsonMapper json;

    FollowsService(@SystemDb DSLContext system, FileAccess files, JsonMapper json) {
        this.system = system;
        this.files = files;
        this.json = json;
    }

    private static final String SELECT = """
            SELECT o.slug::text AS slug, o.display_name, c.name_i18n AS category, o.logo_file_id,
                   f.created_at, f.source, f.notify
              FROM discovery.follows f
              JOIN tenant.organizations o ON o.id = f.org_id
              LEFT JOIN ref.categories c ON c.id = o.primary_category_id
             WHERE f.user_id = ?
            """;

    private Followed followed(Record r) {
        UUID logo = r.get("logo_file_id", UUID.class);
        JSONB category = r.get("category", JSONB.class);
        return new Followed(
                r.get("slug", String.class),
                r.get("display_name", String.class),
                category == null ? Map.of() : json.readValue(category.data(), TEXT),
                logo == null
                        ? null
                        : files.publicImage(logo)
                                .map(FileAccess.PublicImage::url)
                                .orElse(null),
                r.get("created_at", OffsetDateTime.class).toInstant(),
                r.get("source", String.class),
                r.get("notify", Boolean.class));
    }

    @Override
    public List<Followed> list(UUID userId) {
        List<Followed> out = new ArrayList<>();
        for (Record r : system.resultQuery(SELECT + " AND " + VISIBLE + " ORDER BY f.created_at DESC, o.slug", userId)
                .fetch()) {
            out.add(followed(r));
        }
        return out;
    }

    @Override
    public Followed follow(UUID userId, String businessSlug, boolean notify) {
        UUID org = system.resultQuery(
                        "SELECT o.id FROM tenant.organizations o WHERE o.slug = ? AND " + VISIBLE,
                        businessSlug.toLowerCase())
                .fetchOne(0, UUID.class);
        if (org == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "This business is not on Beavent.");
        }
        system.execute(
                "INSERT INTO discovery.follows (user_id, org_id, source, notify) VALUES (?, ?, 'MANUAL', ?)"
                        + " ON CONFLICT (user_id, org_id) DO UPDATE SET notify = excluded.notify",
                userId,
                org,
                notify);
        return followed(
                system.resultQuery(SELECT + " AND o.id = ?", userId, org).fetchSingle());
    }

    @Override
    public void unfollow(UUID userId, String businessSlug) {
        system.execute(
                "DELETE FROM discovery.follows WHERE user_id = ?"
                        + " AND org_id = (SELECT id FROM tenant.organizations WHERE slug = ?)",
                userId,
                businessSlug.toLowerCase());
    }
}
