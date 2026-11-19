package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.USER_CREDENTIALS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.iam.tables.records.UserCredentialsRecord;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id password hashes with lockout after repeated failures. Call inside an identity-role transaction. */
@Component
public class Passwords {

    private final DSLContext dsl;
    private final IdentityProperties.Password config;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** Compared against when the account does not exist, so response time does not reveal accounts. */
    private final String dummyHash = encoder.encode("not-a-real-password-" + UUID.randomUUID());

    Passwords(@IdentityDb DSLContext dsl, IdentityProperties properties) {
        this.dsl = dsl;
        this.config = properties.password();
    }

    public boolean hasPassword(UUID userId) {
        return dsl.fetchExists(USER_CREDENTIALS, USER_CREDENTIALS.USER_ID.eq(userId));
    }

    public void set(UUID userId, String newPassword) {
        if (newPassword == null || newPassword.length() < config.minLength()) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "The password must be at least " + config.minLength() + " characters.");
        }
        String hash = encoder.encode(newPassword);
        dsl.insertInto(USER_CREDENTIALS)
                .set(USER_CREDENTIALS.USER_ID, userId)
                .set(USER_CREDENTIALS.PASSWORD_HASH, hash)
                .onConflict(USER_CREDENTIALS.USER_ID)
                .doUpdate()
                .set(USER_CREDENTIALS.PASSWORD_HASH, hash)
                .set(USER_CREDENTIALS.PASSWORD_CHANGED_AT, OffsetDateTime.now())
                .set(USER_CREDENTIALS.FAILED_ATTEMPTS, (short) 0)
                .setNull(USER_CREDENTIALS.LOCKED_UNTIL)
                .execute();
    }

    /** Result is committed by the caller's transaction even when the password is wrong (failure counting). */
    public boolean verify(UUID userId, String password) {
        UserCredentialsRecord credentials = userId == null
                ? null
                : dsl.selectFrom(USER_CREDENTIALS)
                        .where(USER_CREDENTIALS.USER_ID.eq(userId))
                        .forUpdate()
                        .fetchOne();
        if (credentials == null) {
            encoder.matches(password, dummyHash);
            return false;
        }
        if (credentials.getLockedUntil() != null && credentials.getLockedUntil().isAfter(OffsetDateTime.now())) {
            encoder.matches(password, dummyHash);
            return false;
        }
        if (encoder.matches(password, credentials.getPasswordHash())) {
            credentials.setFailedAttempts((short) 0);
            credentials.setLockedUntil(null);
            credentials.store();
            return true;
        }
        int failures = credentials.getFailedAttempts() + 1;
        credentials.setFailedAttempts((short) failures);
        if (failures >= config.maxFailures()) {
            credentials.setLockedUntil(OffsetDateTime.now().plus(config.lockout()));
            credentials.setFailedAttempts((short) 0);
        }
        credentials.store();
        return false;
    }

    public boolean currentPasswordMatches(UUID userId, String password) {
        UserCredentialsRecord credentials = dsl.selectFrom(USER_CREDENTIALS)
                .where(USER_CREDENTIALS.USER_ID.eq(userId))
                .fetchOne();
        return credentials != null && password != null && encoder.matches(password, credentials.getPasswordHash());
    }
}
