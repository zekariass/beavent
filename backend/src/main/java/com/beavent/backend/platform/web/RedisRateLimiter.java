package com.beavent.backend.platform.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RateLimiter;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Fixed-window counters: {@code ratelimit:<key>:<window index>} with expiry. */
@Component
class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;

    RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void hit(String key, int limit, Duration window) {
        long windowSeconds = Math.max(1, window.toSeconds());
        long index = System.currentTimeMillis() / 1000 / windowSeconds;
        String redisKey = "ratelimit:" + key + ":" + index;
        Long count = redis.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            redis.expire(redisKey, Duration.ofSeconds(windowSeconds + 1));
        }
        if (count != null && count > limit) {
            throw new ApiException(ProblemCode.RATE_LIMITED);
        }
    }
}
