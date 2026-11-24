package com.beavent.backend.platform;

import java.util.Map;
import java.util.UUID;

/** Append-only audit trail (platform.audit_log), written in the caller's transaction. */
public interface Audit {

    enum Actor {
        USER,
        PLATFORM_STAFF,
        SYSTEM,
        API_CLIENT,
        PROVIDER
    }

    record Entry(
            Actor actor,
            UUID actorUserId,
            UUID orgId,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason) {}

    void record(Entry entry);

    /** Shorthand for a user acting on their own account. */
    default void userAction(UUID userId, String action, String targetType, String targetId) {
        record(new Entry(Actor.USER, userId, null, action, targetType, targetId, null, null, null));
    }
}
