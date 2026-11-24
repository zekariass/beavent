package com.beavent.backend.platform;

import java.time.Duration;

/** Fixed-window request limits shared by all API instances (Redis). */
public interface RateLimiter {

    /**
     * Counts one hit for {@code key} in the current window.
     *
     * @throws ApiException {@link ProblemCode#RATE_LIMITED} when {@code limit} is exceeded
     */
    void hit(String key, int limit, Duration window);
}
