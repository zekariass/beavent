package com.beavent.backend.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The businesses and organizers a person follows (plan step 8.2): by choice, or automatically after the first
 * completed visit. Following is by the business's public slug, so any page that shows a business can offer it.
 */
public interface Follows {

    /**
     * @param source {@code MANUAL}, {@code AFTER_PURCHASE} or {@code AFTER_VISIT}
     * @param notifications the person wants to hear about new events and offers from this business
     */
    record Followed(
            String businessSlug,
            String name,
            Map<String, String> category,
            String logoUrl,
            Instant since,
            String source,
            boolean notifications) {}

    /** Newest first. */
    List<Followed> list(UUID userId);

    /** Follows (or changes the notification choice of) a business; {@code NOT_FOUND} when it is not on Beavent. */
    Followed follow(UUID userId, String businessSlug, boolean notify);

    /** Stops following; nothing happens when the business was not followed. */
    void unfollow(UUID userId, String businessSlug);
}
