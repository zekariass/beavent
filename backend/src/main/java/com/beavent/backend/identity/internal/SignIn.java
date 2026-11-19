package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_IDENTITIES;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.iam.tables.records.UsersRecord;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in orchestration: verifies the first factor, then either issues tokens or — when the account has an
 * authenticator — returns a pending MFA challenge that carries the sign-in context to the second step.
 */
@Component
public class SignIn {

    /** Exactly one of {@code tokens} / {@code mfaChallengeId} is set. */
    public record Outcome(TokenPair tokens, UUID mfaChallengeId, boolean newUser) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Accounts accounts;
    private final Sessions sessions;
    private final Otps otps;
    private final Passwords passwords;
    private final Mfa mfa;
    private final IdTokenVerifier idTokens;
    private final Audit audit;

    SignIn(
            @IdentityDb DSLContext dsl,
            @IdentityDb TransactionTemplate transactions,
            Accounts accounts,
            Sessions sessions,
            Otps otps,
            Passwords passwords,
            Mfa mfa,
            IdTokenVerifier idTokens,
            Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.accounts = accounts;
        this.sessions = sessions;
        this.otps = otps;
        this.passwords = passwords;
        this.mfa = mfa;
        this.idTokens = idTokens;
        this.audit = audit;
    }

    public Outcome withOtp(UUID challengeId, String code, ClientContext.Device device, String ip, String userAgent) {
        Otps.Verified verified = otps.verify(challengeId, code, "SIGN_IN");
        String clientApp = (String) verified.context().get("clientApp");
        String language = (String) verified.context().get("language");
        return transactions.execute(status -> {
            Accounts.Account account = accounts.findOrCreateByVerifiedPhone(verified.phone(), language);
            return complete(
                    account.user(),
                    account.created(),
                    Assurance.OTP,
                    new ClientContext(clientApp, device, ip, userAgent));
        });
    }

    public Outcome withPassword(String identifier, String password, ClientContext client) {
        Outcome outcome = transactions.execute(status -> {
            Optional<UsersRecord> user =
                    identifier.startsWith("+") ? accounts.byPhone(identifier) : accounts.byEmail(identifier.trim());
            boolean ok = passwords.verify(user.map(UsersRecord::getId).orElse(null), password);
            if (!ok || user.isEmpty()) {
                return null; // failure counters commit with this transaction
            }
            Accounts.requireActive(user.get());
            return complete(user.get(), false, Assurance.PASSWORD, client);
        });
        if (outcome == null) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The phone/email or password is incorrect.");
        }
        return outcome;
    }

    public Outcome withProvider(String provider, String idToken, String nonce, ClientContext client) {
        IdTokenVerifier.VerifiedIdentity identity = idTokens.verify(provider, idToken, nonce);
        return transactions.execute(status -> {
            UUID linkedUser = dsl.select(USER_IDENTITIES.USER_ID)
                    .from(USER_IDENTITIES)
                    .where(
                            USER_IDENTITIES.PROVIDER.eq(provider.toUpperCase()),
                            USER_IDENTITIES.SUBJECT.eq(identity.subject()))
                    .fetchOne(USER_IDENTITIES.USER_ID);
            UsersRecord user;
            boolean created = false;
            if (linkedUser != null) {
                user = accounts.byId(linkedUser).orElseThrow();
                Accounts.requireActive(user);
            } else {
                if (identity.email() == null || !identity.emailVerified()) {
                    throw new ApiException(
                            ProblemCode.VALIDATION_FAILED,
                            "Your " + provider + " account has no verified email. Please sign in with your phone.");
                }
                if (accounts.byEmail(identity.email()).isPresent()) {
                    // Never link accounts on email alone (docs/03 §2.5): the owner must sign in and link explicitly.
                    throw new ApiException(
                            ProblemCode.CONFLICT,
                            "An account already uses this email. Sign in with your phone number first.");
                }
                Accounts.Account account = accounts.createWithVerifiedEmail(identity.email(), null);
                user = account.user();
                created = true;
                dsl.insertInto(USER_IDENTITIES)
                        .set(USER_IDENTITIES.USER_ID, user.getId())
                        .set(USER_IDENTITIES.PROVIDER, provider.toUpperCase())
                        .set(USER_IDENTITIES.SUBJECT, identity.subject())
                        .set(USER_IDENTITIES.PROVIDER_EMAIL, identity.email())
                        .execute();
            }
            return complete(user, created, Assurance.PASSWORD, client);
        });
    }

    public TokenPair completeMfa(UUID mfaChallengeId, String code) {
        Mfa.PendingSignIn pending = mfa.completeChallenge(mfaChallengeId, code);
        Map<String, Object> context = pending.context();
        @SuppressWarnings("unchecked")
        Map<String, Object> device = (Map<String, Object>) context.get("device");
        ClientContext client = new ClientContext(
                (String) context.get("clientApp"),
                device == null
                        ? null
                        : new ClientContext.Device(
                                (String) device.get("installationId"), (String) device.get("platform"), (String)
                                        device.get("appVersion")),
                (String) context.get("ip"),
                (String) context.get("userAgent"));
        return transactions.execute(status -> {
            UsersRecord user = accounts.byId(pending.userId()).orElseThrow();
            Accounts.requireActive(user);
            markSignedIn(user);
            return sessions.start(user.getId(), client, Assurance.MFA);
        });
    }

    private Outcome complete(UsersRecord user, boolean created, Assurance firstFactor, ClientContext client) {
        if ("ADMIN_CONSOLE".equals(client.clientApp())
                && !dsl.fetchExists(
                        USER_STAFF_ROLES,
                        USER_STAFF_ROLES
                                .USER_ID
                                .eq(user.getId())
                                .and(USER_STAFF_ROLES.REVOKED_AT.isNull())
                                .and(USER_STAFF_ROLES
                                        .EXPIRES_AT
                                        .isNull()
                                        .or(USER_STAFF_ROLES.EXPIRES_AT.gt(OffsetDateTime.now()))))) {
            // Admin-console tokens exist only for platform staff.
            throw new ApiException(ProblemCode.FORBIDDEN, "This account has no access to the admin console.");
        }
        if (mfa.hasConfirmedTotp(user.getId())) {
            Map<String, Object> context = new HashMap<>();
            context.put("clientApp", client.clientApp());
            context.put("firstFactor", firstFactor.name());
            context.put("ip", client.ipAddress());
            context.put("userAgent", client.userAgent());
            if (client.device() != null) {
                context.put(
                        "device",
                        Map.of(
                                "installationId", client.device().installationId(),
                                "platform", client.device().platform(),
                                "appVersion",
                                        client.device().appVersion() == null
                                                ? ""
                                                : client.device().appVersion()));
            }
            return new Outcome(null, mfa.startChallenge(user.getId(), context), created);
        }
        markSignedIn(user);
        return new Outcome(sessions.start(user.getId(), client, firstFactor), null, created);
    }

    private void markSignedIn(UsersRecord user) {
        dsl.update(USERS)
                .set(USERS.LAST_LOGIN_AT, OffsetDateTime.now())
                .where(USERS.ID.eq(user.getId()))
                .execute();
        audit.userAction(user.getId(), "user.sign_in", "iam.user", user.getId().toString());
    }
}
