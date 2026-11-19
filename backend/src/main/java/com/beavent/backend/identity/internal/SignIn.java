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
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in with a one-time code (phone or email) or with Google/Apple. There are no passwords and no authenticator
 * apps: sensitive actions ask for a fresh code instead (step-up).
 */
@Component
public class SignIn {

    public record Outcome(TokenPair tokens, boolean newUser) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Accounts accounts;
    private final Sessions sessions;
    private final Otps otps;
    private final IdTokenVerifier idTokens;
    private final Audit audit;

    SignIn(
            @IdentityDb DSLContext dsl,
            @IdentityDb TransactionTemplate transactions,
            Accounts accounts,
            Sessions sessions,
            Otps otps,
            IdTokenVerifier idTokens,
            Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.accounts = accounts;
        this.sessions = sessions;
        this.otps = otps;
        this.idTokens = idTokens;
        this.audit = audit;
    }

    public Outcome withOtp(UUID challengeId, String code, ClientContext.Device device, String ip, String userAgent) {
        Otps.Verified verified = otps.verify(challengeId, code, "SIGN_IN");
        String clientApp = (String) verified.context().get("clientApp");
        String language = (String) verified.context().get("language");
        return transactions.execute(status -> {
            Accounts.Account account = verified.phone() != null
                    ? accounts.findOrCreateByVerifiedPhone(verified.phone(), language)
                    : accounts.findOrCreateByVerifiedEmail(verified.email(), language);
            return complete(
                    account.user(),
                    account.created(),
                    Assurance.OTP,
                    new ClientContext(clientApp, device, ip, userAgent));
        });
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
            if (linkedUser != null) {
                UsersRecord user = accounts.byId(linkedUser).orElseThrow();
                Accounts.requireActive(user);
                return complete(user, false, Assurance.PROVIDER, client);
            }
            if (identity.email() == null || !identity.emailVerified()) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED,
                        "Your " + provider + " account has no verified email. Please sign in with your phone.");
            }
            // The provider verified the email, so it joins the account that verified the same address (email code
            // or an earlier provider); an unverified copy elsewhere is released (Accounts).
            Accounts.Account account = accounts.findOrCreateByVerifiedEmail(identity.email(), null);
            dsl.insertInto(USER_IDENTITIES)
                    .set(USER_IDENTITIES.USER_ID, account.user().getId())
                    .set(USER_IDENTITIES.PROVIDER, provider.toUpperCase())
                    .set(USER_IDENTITIES.SUBJECT, identity.subject())
                    .set(USER_IDENTITIES.PROVIDER_EMAIL, identity.email())
                    .execute();
            return complete(account.user(), account.created(), Assurance.PROVIDER, client);
        });
    }

    private Outcome complete(UsersRecord user, boolean created, Assurance assurance, ClientContext client) {
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
        dsl.update(USERS)
                .set(USERS.LAST_LOGIN_AT, OffsetDateTime.now())
                .where(USERS.ID.eq(user.getId()))
                .execute();
        audit.userAction(user.getId(), "user.sign_in", "iam.user", user.getId().toString());
        return new Outcome(sessions.start(user.getId(), client, assurance), created);
    }
}
