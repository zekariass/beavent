package com.beavent.backend.discovery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The places a person saved (the Saved tab, plan step 8.2). Only places that are listed on the marketplace can be
 * saved, and a saved place that is later taken off the marketplace drops out of the list (it comes back if it is
 * listed again).
 */
public interface Saved {

    record Entry(Instant savedAt, Search.Item place) {}

    /** The person's saved places, newest first. */
    List<Entry> list(UUID userId, Instant now);

    /** Saves a listed place; saving it again changes nothing. {@code NOT_FOUND} when it is not on the marketplace. */
    void save(UUID userId, UUID locationId);

    /** Takes a place off the list; nothing happens when it was not saved. */
    void unsave(UUID userId, UUID locationId);
}
