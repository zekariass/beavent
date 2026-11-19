package com.beavent.backend.platform;

import static com.beavent.db.platform.Tables.IDEMPOTENCY_KEYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beavent.backend.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@IntegrationTest
class IdempotencyTests {

    @Autowired
    Idempotency idempotency;

    @Autowired
    @SystemDb
    DSLContext system;

    final String scope = "user:" + UUID.randomUUID();

    @Test
    void retryWithSameKeyAndBodyReplaysTheStoredResponseWithoutRunningAgain() {
        AtomicInteger runs = new AtomicInteger();
        var request = Map.of("amount_minor", 150000, "currency", "ETB");
        ResponseEntity<?> first = idempotency.execute(scope, "key-0001", request, () -> {
            runs.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("orderId", "abc"));
        });
        ResponseEntity<?> retry = idempotency.execute(scope, "key-0001", request, () -> {
            runs.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("orderId", "other"));
        });

        assertThat(runs).hasValue(1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(String.valueOf(retry.getBody())).contains("\"orderId\":\"abc\"");
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        idempotency.execute(scope, "key-0002", Map.of("a", 1), () -> ResponseEntity.ok(Map.of()));
        assertThatThrownBy(
                        () -> idempotency.execute(scope, "key-0002", Map.of("a", 2), () -> ResponseEntity.ok(Map.of())))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void requestStillInProgressIsRejected() {
        system.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.SCOPE, scope)
                .set(IDEMPOTENCY_KEYS.KEY, "key-0003")
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, "unused")
                .execute();
        assertThatThrownBy(() -> idempotency.execute(scope, "key-0003", Map.of(), () -> ResponseEntity.ok(Map.of())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code())
                                .isIn(ProblemCode.IDEMPOTENCY_IN_PROGRESS, ProblemCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void failedActionReleasesTheKeySoTheClientCanRetry() {
        assertThatThrownBy(() -> idempotency.execute(scope, "key-0004", Map.of(), () -> {
                    throw new IllegalStateException("provider timeout");
                }))
                .isInstanceOf(IllegalStateException.class);
        AtomicInteger runs = new AtomicInteger();
        idempotency.execute(scope, "key-0004", Map.of(), () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok(Map.of());
        });
        assertThat(runs).hasValue(1);
    }

    @Test
    void keysAreScopedPerCaller() {
        AtomicInteger runs = new AtomicInteger();
        idempotency.execute(scope, "key-0005", Map.of(), () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok(Map.of());
        });
        idempotency.execute("user:" + UUID.randomUUID(), "key-0005", Map.of(), () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok(Map.of());
        });
        assertThat(runs).hasValue(2);
    }

    @Test
    void malformedKeyIsRejected() {
        assertThatThrownBy(() -> idempotency.execute(scope, "bad key!", Map.of(), () -> ResponseEntity.ok(Map.of())))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.VALIDATION_FAILED));
    }
}
