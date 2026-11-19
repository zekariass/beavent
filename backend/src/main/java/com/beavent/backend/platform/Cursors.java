package com.beavent.backend.platform;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Opaque pagination cursors: the sort-key values of the last row, base64url-encoded. Clients pass them back
 * unchanged ({@code ?cursor=...}); tampering only changes the caller's own page boundaries.
 */
public final class Cursors {

    private static final char SEPARATOR = '\u001F';
    private static final int MAX_LENGTH = 1024;

    private Cursors() {}

    public static String encode(String... sortKeyValues) {
        for (String value : sortKeyValues) {
            if (value == null || value.indexOf(SEPARATOR) >= 0) {
                throw new IllegalArgumentException("cursor values must be non-null and must not contain U+001F");
            }
        }
        String joined = String.join(String.valueOf(SEPARATOR), sortKeyValues);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws ApiException {@link ProblemCode#VALIDATION_FAILED} if the cursor is malformed or has the wrong arity
     */
    public static List<String> decode(String cursor, int expectedParts) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid cursor.");
        }
        try {
            String joined = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            List<String> parts = Arrays.asList(joined.split(String.valueOf(SEPARATOR), -1));
            if (parts.size() != expectedParts) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid cursor.");
            }
            return parts;
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid cursor.");
        }
    }
}
