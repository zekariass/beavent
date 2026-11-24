package com.beavent.backend.platform.idempotency;

import static com.beavent.db.platform.Tables.IDEMPOTENCY_KEYS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Idempotency;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.platform.tables.records.IdempotencyKeysRecord;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores Idempotency-Key reservations and responses in platform.idempotency_keys (48 h by default). Bookkeeping
 * runs in its own short transactions; the action runs in whatever transaction it opens itself.
 */
@Component
class JooqIdempotency implements Idempotency {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,200}");
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    JooqIdempotency(DSLContext dsl, PlatformTransactionManager transactionManager, JsonMapper json) {
        this.dsl = dsl;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    @Override
    public ResponseEntity<?> execute(String scope, String key, Object request, Supplier<ResponseEntity<?>> action) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 8-200 characters of letters, digits, '_', '.', ':' or '-'.");
        }
        String hash = sha256(json.writeValueAsBytes(request));
        Optional<ResponseEntity<?>> replay = transactions.execute(status -> reserve(scope, key, hash));
        if (replay != null && replay.isPresent()) {
            return replay.get();
        }
        ResponseEntity<?> result;
        try {
            result = action.get();
        } catch (RuntimeException | Error e) {
            release(scope, key);
            throw e;
        }
        if (result.getStatusCode().is5xxServerError()) {
            release(scope, key);
        } else {
            complete(scope, key, result);
        }
        return result;
    }

    private Optional<ResponseEntity<?>> reserve(String scope, String key, String hash) {
        dsl.deleteFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope), IDEMPOTENCY_KEYS.KEY.eq(key))
                .and(IDEMPOTENCY_KEYS.EXPIRES_AT.lt(OffsetDateTime.now()))
                .execute();
        int inserted = dsl.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.SCOPE, scope)
                .set(IDEMPOTENCY_KEYS.KEY, key)
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, hash)
                .set(IDEMPOTENCY_KEYS.STATUS, IN_PROGRESS)
                .onConflictDoNothing()
                .execute();
        if (inserted == 1) {
            return Optional.empty();
        }
        IdempotencyKeysRecord existing = dsl.selectFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope), IDEMPOTENCY_KEYS.KEY.eq(key))
                .fetchOne();
        if (existing == null) {
            // Released between our insert attempt and the read: let the client retry.
            throw new ApiException(ProblemCode.IDEMPOTENCY_IN_PROGRESS);
        }
        if (!existing.getRequestHash().equals(hash)) {
            throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (!COMPLETED.equals(existing.getStatus())) {
            throw new ApiException(ProblemCode.IDEMPOTENCY_IN_PROGRESS);
        }
        JsonNode body = existing.getResponseBody() == null
                ? null
                : json.readTree(existing.getResponseBody().data());
        return Optional.of(ResponseEntity.status(existing.getResponseStatus())
                .header(REPLAYED_HEADER, "true")
                .body(body));
    }

    private void complete(String scope, String key, ResponseEntity<?> result) {
        JSONB body = result.getBody() == null ? null : JSONB.valueOf(json.writeValueAsString(result.getBody()));
        transactions.executeWithoutResult(status -> dsl.update(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.STATUS, COMPLETED)
                .set(IDEMPOTENCY_KEYS.RESPONSE_STATUS, (short)
                        result.getStatusCode().value())
                .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, body)
                .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope), IDEMPOTENCY_KEYS.KEY.eq(key))
                .execute());
    }

    private void release(String scope, String key) {
        transactions.executeWithoutResult(status -> dsl.deleteFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.SCOPE.eq(scope), IDEMPOTENCY_KEYS.KEY.eq(key))
                .execute());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
