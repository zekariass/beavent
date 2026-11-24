package com.beavent.backend.platform;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUIDv7 identifiers (RFC 9562), the same kind {@code platform.uuid_v7()} creates in the database.
 * Generate an id in the application when it must be known before the row is inserted — for example a new
 * organization's id, which becomes the request context before its row exists.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randomA = RANDOM.nextInt(1 << 12);
        long randomB = RANDOM.nextLong();
        long mostSignificant = (millis << 16) | 0x7000L | randomA;
        long leastSignificant = (randomB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
