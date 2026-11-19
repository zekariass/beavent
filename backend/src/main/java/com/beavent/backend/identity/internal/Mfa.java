package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.AUTH_CHALLENGES;
import static com.beavent.db.iam.Tables.MFA_FACTORS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.iam.tables.records.AuthChallengesRecord;
import com.beavent.db.iam.tables.records.MfaFactorsRecord;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticator-app (TOTP) second factor: enrolment, confirmation, sign-in challenges and verification with replay
 * protection (a code's time step can be used once). Secrets are stored encrypted (AES-GCM).
 */
@Component
public class Mfa {

    public record Enrollment(UUID factorId, String otpauthUri, String secret) {}

    public record PendingSignIn(UUID userId, Map<String, Object> context) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Crypto crypto;
    private final Audit audit;
    private final JsonMapper json;

    Mfa(
            @IdentityDb DSLContext dsl,
            @IdentityDb TransactionTemplate transactions,
            Crypto crypto,
            Audit audit,
            JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.crypto = crypto;
        this.audit = audit;
        this.json = json;
    }

    public boolean hasConfirmedTotp(UUID userId) {
        return dsl.fetchExists(
                MFA_FACTORS,
                MFA_FACTORS
                        .USER_ID
                        .eq(userId)
                        .and(MFA_FACTORS.KIND.eq("TOTP"))
                        .and(MFA_FACTORS.CONFIRMED_AT.isNotNull()));
    }

    public Enrollment enroll(UUID userId, String accountLabel) {
        byte[] secret = Crypto.randomBytes(20);
        UUID factorId = UUID.randomUUID();
        dsl.insertInto(MFA_FACTORS)
                .set(MFA_FACTORS.ID, factorId)
                .set(MFA_FACTORS.USER_ID, userId)
                .set(MFA_FACTORS.KIND, "TOTP")
                .set(MFA_FACTORS.LABEL, "Authenticator app")
                .set(MFA_FACTORS.SECRET_REF, crypto.encryptSecret(secret))
                .execute();
        String base32 = Totp.base32(secret);
        String label = URLEncoder.encode("Beavent:" + accountLabel, StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + base32 + "&issuer=Beavent&digits=6&period=30";
        return new Enrollment(factorId, uri, base32);
    }

    /** Confirms a pending factor with a first code; replaces any previously confirmed authenticator. */
    public void confirm(UUID userId, UUID factorId, String code) {
        Boolean ok = transactions.execute(status -> {
            MfaFactorsRecord factor = dsl.selectFrom(MFA_FACTORS)
                    .where(MFA_FACTORS.ID.eq(factorId), MFA_FACTORS.USER_ID.eq(userId), MFA_FACTORS.KIND.eq("TOTP"))
                    .forUpdate()
                    .fetchOne();
            if (factor == null || !acceptCode(factor, code)) {
                return false;
            }
            factor.setConfirmedAt(OffsetDateTime.now());
            factor.store();
            dsl.deleteFrom(MFA_FACTORS)
                    .where(MFA_FACTORS.USER_ID.eq(userId), MFA_FACTORS.KIND.eq("TOTP"), MFA_FACTORS.ID.ne(factorId))
                    .execute();
            audit.userAction(userId, "mfa.totp_enabled", "iam.mfa_factor", factorId.toString());
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "The code is incorrect. Check the time on your phone.");
        }
    }

    /** True if {@code code} is valid for the user's confirmed authenticator (marks the step as used). */
    public boolean verifyTotp(UUID userId, String code) {
        MfaFactorsRecord factor = dsl.selectFrom(MFA_FACTORS)
                .where(
                        MFA_FACTORS.USER_ID.eq(userId),
                        MFA_FACTORS.KIND.eq("TOTP"),
                        MFA_FACTORS.CONFIRMED_AT.isNotNull())
                .forUpdate()
                .fetchOne();
        return factor != null && acceptCode(factor, code);
    }

    /** Starts the second step of a sign-in; {@code context} is non-secret (client app, device, first factor). */
    public UUID startChallenge(UUID userId, Map<String, Object> context) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(AUTH_CHALLENGES)
                .set(AUTH_CHALLENGES.ID, id)
                .set(AUTH_CHALLENGES.USER_ID, userId)
                .set(AUTH_CHALLENGES.PURPOSE, "MFA")
                .set(AUTH_CHALLENGES.CHANNEL, "TOTP")
                .set(AUTH_CHALLENGES.CODE_HASH, crypto.tokenHash(Crypto.randomToken()))
                .set(
                        AUTH_CHALLENGES.EXPIRES_AT,
                        OffsetDateTime.ofInstant(Instant.now().plusSeconds(300), ZoneOffset.UTC))
                .set(AUTH_CHALLENGES.CONTEXT, JSONB.valueOf(json.writeValueAsString(context)))
                .execute();
        return id;
    }

    /** Verifies the second factor for a pending sign-in; failures are counted (committed) before throwing. */
    @SuppressWarnings("unchecked")
    public PendingSignIn completeChallenge(UUID challengeId, String code) {
        PendingSignIn pending = transactions.execute(status -> {
            AuthChallengesRecord challenge = dsl.selectFrom(AUTH_CHALLENGES)
                    .where(AUTH_CHALLENGES.ID.eq(challengeId), AUTH_CHALLENGES.PURPOSE.eq("MFA"))
                    .forUpdate()
                    .fetchOne();
            if (challenge == null
                    || challenge.getConsumedAt() != null
                    || challenge.getExpiresAt().isBefore(OffsetDateTime.now())
                    || challenge.getAttempts() >= challenge.getMaxAttempts()) {
                return null;
            }
            challenge.setAttempts((short) (challenge.getAttempts() + 1));
            boolean ok = verifyTotp(challenge.getUserId(), code);
            if (ok) {
                challenge.setConsumedAt(OffsetDateTime.now());
            }
            challenge.store();
            return ok
                    ? new PendingSignIn(
                            challenge.getUserId(),
                            json.readValue(challenge.getContext().data(), Map.class))
                    : null;
        });
        if (pending == null) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, Otps.FAILED);
        }
        return pending;
    }

    private boolean acceptCode(MfaFactorsRecord factor, String code) {
        byte[] secret = crypto.decryptSecret(factor.getSecretRef());
        long nowStep = Totp.step(Instant.now().getEpochSecond());
        long step = Totp.matchingStep(secret, code, nowStep);
        if (step < 0) {
            return false;
        }
        OffsetDateTime lastUsed = factor.getLastUsedAt();
        if (lastUsed != null && Totp.step(lastUsed.toEpochSecond()) >= step) {
            return false; // replay of an already used code
        }
        factor.setLastUsedAt(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(step * Totp.PERIOD_SECONDS), ZoneOffset.UTC));
        factor.store();
        return true;
    }
}
