package com.beavent.backend.platform;

/**
 * Optimistic concurrency for aggregates with a {@code version} column: responses carry {@code ETag: "<version>"};
 * updates must send {@code If-Match} with that value.
 */
public final class ETags {

    private ETags() {}

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * @throws ApiException {@link ProblemCode#PRECONDITION_REQUIRED} without If-Match,
     *     {@link ProblemCode#PRECONDITION_FAILED} if it does not match {@code currentVersion}
     */
    public static void requireMatch(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ProblemCode.PRECONDITION_REQUIRED);
        }
        String expected = of(currentVersion);
        for (String candidate : ifMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals("*") || tag.equals(expected)) {
                return;
            }
        }
        throw new ApiException(ProblemCode.PRECONDITION_FAILED);
    }
}
