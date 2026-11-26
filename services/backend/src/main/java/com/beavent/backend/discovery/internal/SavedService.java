package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Saved;
import com.beavent.backend.discovery.Search;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

/** Saved places, kept in {@code discovery.saved_items}. Every query names the person, so one cannot see another's. */
@Component
class SavedService implements Saved {

    private final DSLContext system;
    private final Search search;

    SavedService(@SystemDb DSLContext system, Search search) {
        this.system = system;
        this.search = search;
    }

    @Override
    public List<Entry> list(UUID userId, Instant now) {
        List<Record> rows = system.resultQuery(
                        "SELECT listing_id, created_at FROM discovery.saved_items"
                                + " WHERE user_id = ? AND listing_id IS NOT NULL ORDER BY created_at DESC, id DESC",
                        userId)
                .fetch();
        List<UUID> ids = new ArrayList<>();
        Map<UUID, Instant> savedAt = new java.util.HashMap<>();
        for (Record row : rows) {
            UUID id = row.get("listing_id", UUID.class);
            ids.add(id);
            savedAt.put(id, row.get("created_at", OffsetDateTime.class).toInstant());
        }
        List<Entry> out = new ArrayList<>();
        for (Search.Item item : search.items(ids, now)) {
            out.add(new Entry(savedAt.get(item.id()), item));
        }
        return out;
    }

    @Override
    public void save(UUID userId, UUID locationId) {
        UUID listing = system.resultQuery(
                        "SELECT id FROM discovery.listings WHERE location_id = ? AND is_listed", locationId)
                .fetchOne(0, UUID.class);
        if (listing == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "This place is not on Beavent.");
        }
        system.execute(
                "INSERT INTO discovery.saved_items (user_id, listing_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId,
                listing);
    }

    @Override
    public void unsave(UUID userId, UUID locationId) {
        system.execute(
                "DELETE FROM discovery.saved_items WHERE user_id = ?"
                        + " AND listing_id IN (SELECT id FROM discovery.listings WHERE location_id = ?)",
                userId,
                locationId);
    }
}
