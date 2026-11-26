package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Listings;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings the listings up to date on a timer. Every run is idempotent and rewrites a row only when something changed, so
 * several API instances can run it at once; a change shows in search within one interval (a minute by default).
 */
@Component
class ProjectorSweep {

    private static final Logger log = LoggerFactory.getLogger(ProjectorSweep.class);

    private final Listings listings;

    ProjectorSweep(Listings listings) {
        this.listings = listings;
    }

    @Scheduled(
            initialDelayString = "${beavent.discovery.sweep-interval-ms:60000}",
            fixedDelayString = "${beavent.discovery.sweep-interval-ms:60000}")
    void run() {
        try {
            Listings.Result result = listings.refreshAll(Instant.now());
            log.debug("Listings refreshed: {} listed, {} unlisted", result.listed(), result.unlisted());
        } catch (RuntimeException e) {
            log.warn("Listing refresh failed; it runs again shortly", e);
        }
    }
}
