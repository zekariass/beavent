package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.DEVICES;
import static com.beavent.db.iam.Tables.SESSIONS;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.iam.tables.records.SessionsRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Sign-in sessions with rotating refresh tokens. Each refresh replaces the session row with a new one in the same
 * family; presenting an already-rotated token is treated as theft and revokes the whole family (docs/03 §2.5).
 * Methods must run inside an identity-role transaction.
 */
@Component
public class Sessions {

    public record SessionView(
            UUID familyId,
            String clientApp,
            String deviceLabel,
            String ipAddress,
            OffsetDateTime createdAt,
            OffsetDateTime lastUsedAt) {}

    private final DSLContext dsl;
    private final JwtKeys keys;
    private final Crypto crypto;
    private final IdentityProperties properties;
    private final Audit audit;

    Sessions(@IdentityDb DSLContext dsl, JwtKeys keys, Crypto crypto, IdentityProperties properties, Audit audit) {
        this.dsl = dsl;
        this.keys = keys;
        this.crypto = crypto;
        this.properties = properties;
        this.audit = audit;
    }

    /** Starts a new session family after a completed sign-in. */
    public TokenPair start(UUID userId, ClientContext client, Assurance assurance) {
        UUID deviceId = upsertDevice(userId, client.device());
        UUID familyId = UUID.randomUUID();
        TokenPair tokens =
                insert(userId, familyId, client.clientApp(), deviceId, assurance, client, accessTtl(assurance));
        audit.record(new Audit.Entry(
                Audit.Actor.USER,
                userId,
                null,
                "session.start",
                "iam.session",
                familyId.toString(),
                null,
                Map.of("clientApp", client.clientApp(), "assurance", assurance.name()),
                null));
        return tokens;
    }

    /** Exchanges a refresh token for a new pair. Reuse of a rotated token revokes the family. */
    public RotationResult rotate(String refreshToken, ClientContext client) {
        SessionsRecord current = dsl.selectFrom(SESSIONS)
                .where(SESSIONS.REFRESH_TOKEN_HASH.eq(crypto.tokenHash(refreshToken)))
                .forUpdate()
                .fetchOne();
        if (current == null) {
            return RotationResult.rejected();
        }
        if (current.getRevokedAt() != null) {
            if ("ROTATED".equals(current.getRevokeReason())) {
                revokeFamily(current.getFamilyId(), "REUSE_DETECTED");
                audit.record(new Audit.Entry(
                        Audit.Actor.SYSTEM,
                        current.getUserId(),
                        null,
                        "session.refresh_reuse_detected",
                        "iam.session",
                        current.getFamilyId().toString(),
                        null,
                        null,
                        "A rotated refresh token was presented again; the session family was revoked"));
                return RotationResult.reused();
            }
            return RotationResult.rejected();
        }
        if (current.getExpiresAt().toInstant().isBefore(Instant.now())) {
            return RotationResult.rejected();
        }
        Assurance assurance = Assurance.valueOf(current.getAssuranceLevel());
        return RotationResult.ok(replace(current, client, assurance, accessTtl(assurance)));
    }

    /** Re-issues the caller's session with a short-lived STEP_UP access token. */
    public TokenPair stepUp(UUID userId, UUID familyId, ClientContext client) {
        SessionsRecord current = active(userId, familyId);
        TokenPair base =
                replace(current, client, Assurance.valueOf(current.getAssuranceLevel()), properties.stepUpTokenTtl());
        Instant now = Instant.now();
        Instant expires = now.plus(properties.stepUpTokenTtl());
        String stepUpAccess = keys.sign(userId, familyId, current.getClientApp(), Assurance.STEP_UP, now, expires);
        audit.userAction(userId, "session.step_up", "iam.session", familyId.toString());
        return new TokenPair(
                userId, familyId, stepUpAccess, expires, base.refreshToken(), base.refreshTokenExpiresAt());
    }

    public void revokeFamily(UUID familyId, String reason) {
        dsl.update(SESSIONS)
                .set(SESSIONS.REVOKED_AT, OffsetDateTime.now())
                .set(SESSIONS.REVOKE_REASON, reason)
                .where(SESSIONS.FAMILY_ID.eq(familyId), SESSIONS.REVOKED_AT.isNull())
                .execute();
    }

    /** Revokes the session of {@code refreshToken} if it belongs to {@code userId}. */
    public void revokeByRefreshToken(UUID userId, String refreshToken) {
        SessionsRecord row = dsl.selectFrom(SESSIONS)
                .where(SESSIONS.REFRESH_TOKEN_HASH.eq(crypto.tokenHash(refreshToken)), SESSIONS.USER_ID.eq(userId))
                .fetchOne();
        if (row != null) {
            revokeFamily(row.getFamilyId(), "LOGOUT");
        }
    }

    /** Revokes every session of the user except {@code keepFamilyId} (may be null). */
    public int revokeAll(UUID userId, UUID keepFamilyId, String reason) {
        var condition = SESSIONS.USER_ID.eq(userId).and(SESSIONS.REVOKED_AT.isNull());
        if (keepFamilyId != null) {
            condition = condition.and(SESSIONS.FAMILY_ID.ne(keepFamilyId));
        }
        return dsl.update(SESSIONS)
                .set(SESSIONS.REVOKED_AT, OffsetDateTime.now())
                .set(SESSIONS.REVOKE_REASON, reason)
                .where(condition)
                .execute();
    }

    public boolean revokeOwnFamily(UUID userId, UUID familyId) {
        int changed = dsl.update(SESSIONS)
                .set(SESSIONS.REVOKED_AT, OffsetDateTime.now())
                .set(SESSIONS.REVOKE_REASON, "LOGOUT")
                .where(SESSIONS.USER_ID.eq(userId), SESSIONS.FAMILY_ID.eq(familyId), SESSIONS.REVOKED_AT.isNull())
                .execute();
        return changed > 0;
    }

    public List<SessionView> active(UUID userId) {
        Field<String> ip = DSL.field("host({0})", String.class, SESSIONS.IP_ADDRESS);
        return dsl.select(
                        SESSIONS.FAMILY_ID,
                        SESSIONS.CLIENT_APP,
                        DEVICES.PLATFORM,
                        DEVICES.APP_VERSION,
                        ip,
                        SESSIONS.CREATED_AT,
                        SESSIONS.LAST_USED_AT)
                .from(SESSIONS)
                .leftJoin(DEVICES)
                .on(DEVICES.ID.eq(SESSIONS.DEVICE_ID))
                .where(
                        SESSIONS.USER_ID.eq(userId),
                        SESSIONS.REVOKED_AT.isNull(),
                        SESSIONS.EXPIRES_AT.gt(OffsetDateTime.now()))
                .orderBy(SESSIONS.CREATED_AT.desc())
                .fetch(r -> new SessionView(
                        r.get(SESSIONS.FAMILY_ID),
                        r.get(SESSIONS.CLIENT_APP),
                        r.get(DEVICES.PLATFORM) == null
                                ? null
                                : r.get(DEVICES.PLATFORM)
                                        + (r.get(DEVICES.APP_VERSION) == null ? "" : " " + r.get(DEVICES.APP_VERSION)),
                        r.get(ip),
                        r.get(SESSIONS.CREATED_AT),
                        r.get(SESSIONS.LAST_USED_AT)));
    }

    /** Registers or updates a device and its push token for {@code userId}. */
    public UUID registerDevice(
            UUID userId, String installationId, String platform, String pushToken, String appVersion, String locale) {
        // A push token moves with the device; clear it from any other installation first (unique).
        if (pushToken != null) {
            dsl.update(DEVICES)
                    .setNull(DEVICES.PUSH_TOKEN)
                    .where(DEVICES.PUSH_TOKEN.eq(pushToken), DEVICES.INSTALLATION_ID.ne(installationId))
                    .execute();
        }
        return dsl.insertInto(DEVICES)
                .set(DEVICES.INSTALLATION_ID, installationId)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.PLATFORM, platform)
                .set(DEVICES.PUSH_TOKEN, pushToken)
                .set(DEVICES.APP_VERSION, appVersion)
                .set(DEVICES.LOCALE, locale)
                .set(DEVICES.LAST_SEEN_AT, OffsetDateTime.now())
                .onConflict(DEVICES.INSTALLATION_ID)
                .doUpdate()
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.PLATFORM, platform)
                .set(DEVICES.PUSH_TOKEN, pushToken)
                .set(DEVICES.APP_VERSION, appVersion)
                .set(DEVICES.LOCALE, locale)
                .set(DEVICES.LAST_SEEN_AT, OffsetDateTime.now())
                .returning(DEVICES.ID)
                .fetchOne(DEVICES.ID);
    }

    // --- internals ----------------------------------------------------------------------------------------------

    private UUID upsertDevice(UUID userId, ClientContext.Device device) {
        if (device == null) {
            return null;
        }
        return registerDevice(userId, device.installationId(), device.platform(), null, device.appVersion(), null);
    }

    private SessionsRecord active(UUID userId, UUID familyId) {
        SessionsRecord row = dsl.selectFrom(SESSIONS)
                .where(SESSIONS.USER_ID.eq(userId), SESSIONS.FAMILY_ID.eq(familyId), SESSIONS.REVOKED_AT.isNull())
                .forUpdate()
                .fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "This session has ended. Please sign in again.");
        }
        return row;
    }

    private TokenPair replace(
            SessionsRecord current, ClientContext client, Assurance assurance, java.time.Duration accessTtl) {
        current.setRevokedAt(OffsetDateTime.now());
        current.setRevokeReason("ROTATED");
        current.setRotatedAt(OffsetDateTime.now());
        current.store();
        ClientContext merged = new ClientContext(
                current.getClientApp(),
                null,
                client == null ? null : client.ipAddress(),
                client == null ? null : client.userAgent());
        return insert(
                current.getUserId(),
                current.getFamilyId(),
                current.getClientApp(),
                current.getDeviceId(),
                assurance,
                merged,
                accessTtl);
    }

    private TokenPair insert(
            UUID userId,
            UUID familyId,
            String clientApp,
            UUID deviceId,
            Assurance assurance,
            ClientContext client,
            java.time.Duration accessTtl) {
        String refreshToken = Crypto.randomToken();
        Instant now = Instant.now();
        Instant refreshExpires = now.plus(properties.refreshTokenTtl());
        dsl.insertInto(SESSIONS)
                .set(SESSIONS.USER_ID, userId)
                .set(SESSIONS.CLIENT_APP, clientApp)
                .set(SESSIONS.DEVICE_ID, deviceId)
                .set(SESSIONS.FAMILY_ID, familyId)
                .set(SESSIONS.REFRESH_TOKEN_HASH, crypto.tokenHash(refreshToken))
                .set(SESSIONS.ASSURANCE_LEVEL, assurance == Assurance.STEP_UP ? Assurance.MFA.name() : assurance.name())
                .set(SESSIONS.IP_ADDRESS, (Object) DSL.field(
                        "cast({0} as inet)",
                        Object.class, DSL.val(client == null ? null : client.ipAddress(), String.class)))
                .set(SESSIONS.USER_AGENT, client == null ? null : truncate(client.userAgent(), 500))
                .set(SESSIONS.LAST_USED_AT, OffsetDateTime.now())
                .set(SESSIONS.EXPIRES_AT, OffsetDateTime.ofInstant(refreshExpires, ZoneOffset.UTC))
                .execute();
        Instant accessExpires = now.plus(accessTtl);
        String access = keys.sign(userId, familyId, clientApp, assurance, now, accessExpires);
        return new TokenPair(userId, familyId, access, accessExpires, refreshToken, refreshExpires);
    }

    private java.time.Duration accessTtl(Assurance assurance) {
        return assurance == Assurance.STEP_UP ? properties.stepUpTokenTtl() : properties.accessTokenTtl();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record RotationResult(TokenPair tokens, boolean reuseDetected) {
        static RotationResult ok(TokenPair tokens) {
            return new RotationResult(tokens, false);
        }

        static RotationResult rejected() {
            return new RotationResult(null, false);
        }

        static RotationResult reused() {
            return new RotationResult(null, true);
        }
    }
}
