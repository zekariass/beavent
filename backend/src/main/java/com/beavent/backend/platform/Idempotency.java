package com.beavent.backend.platform;

import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

/**
 * {@code Idempotency-Key} handling for state-changing requests (orders, payments, refunds, payouts, tickets).
 *
 * <ul>
 *   <li>First request with a key runs {@code action} and stores its response.
 *   <li>A retry with the same key and the same body returns the stored response (header
 *       {@code Idempotent-Replayed: true}) without running {@code action} again.
 *   <li>The same key with a different body fails with {@link ProblemCode#IDEMPOTENCY_KEY_REUSED}.
 *   <li>A retry while the first request is still running fails with {@link ProblemCode#IDEMPOTENCY_IN_PROGRESS}.
 *   <li>If {@code action} throws or returns 5xx, the key is released so the client can retry.
 * </ul>
 */
public interface Idempotency {

    String HEADER = "Idempotency-Key";
    String REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * @param scope who owns the key, e.g. {@code user:<id>} or {@code org:<id>} (keys are unique per scope)
     * @param key the client's Idempotency-Key header value
     * @param request the request body (hashed to detect key reuse with a different body)
     */
    ResponseEntity<?> execute(String scope, String key, Object request, Supplier<ResponseEntity<?>> action);
}
