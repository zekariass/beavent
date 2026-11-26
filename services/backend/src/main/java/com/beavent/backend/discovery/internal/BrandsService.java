package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Brands;
import com.beavent.backend.files.FileAccess;
import com.beavent.backend.platform.SystemDb;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class BrandsService implements Brands {

    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};

    private final DSLContext system;
    private final FileAccess files;
    private final JsonMapper json;

    BrandsService(@SystemDb DSLContext system, FileAccess files, JsonMapper json) {
        this.system = system;
        this.files = files;
        this.json = json;
    }

    @Override
    public Optional<Brand> brand(String businessSlug) {
        Record r = system.resultQuery("""
                        SELECT o.id, o.slug::text AS slug, o.display_name, o.description_i18n, o.logo_file_id,
                               c.name_i18n AS category,
                               (SELECT count(*) FROM discovery.listings l
                                 WHERE l.org_id = o.id AND l.kind = 'LOCATION' AND l.is_listed) AS places,
                               (SELECT count(*) FROM discovery.follows f WHERE f.org_id = o.id) AS followers
                          FROM tenant.organizations o
                          LEFT JOIN ref.categories c ON c.id = o.primary_category_id
                         WHERE o.slug = ? AND o.status IN ('ACTIVE','ONBOARDING')
                        """, businessSlug.toLowerCase()).fetchOne();
        if (r == null) {
            return Optional.empty();
        }
        UUID logo = r.get("logo_file_id", UUID.class);
        var image = logo == null ? Optional.<FileAccess.PublicImage>empty() : files.publicImage(logo);
        return Optional.of(new Brand(
                r.get("slug", String.class),
                r.get("display_name", String.class),
                text(r.get("description_i18n", JSONB.class)),
                text(r.get("category", JSONB.class)),
                image.map(FileAccess.PublicImage::url).orElse(null),
                image.map(FileAccess.PublicImage::variants).orElse(Map.of()),
                r.get("places", Integer.class),
                r.get("followers", Integer.class)));
    }

    private Map<String, String> text(JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), TEXT);
    }
}
