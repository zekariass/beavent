package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_PROFILES;
import static com.beavent.db.ref.Tables.COUNTRIES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.iam.tables.records.UsersRecord;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/** Accounts: lookup, phone-first creation, status checks. Call inside an identity-role transaction. */
@Component
public class Accounts {

    public record Account(UsersRecord user, boolean created) {}

    private final DSLContext dsl;
    private final Outbox outbox;
    private final Audit audit;

    Accounts(@IdentityDb DSLContext dsl, Outbox outbox, Audit audit) {
        this.dsl = dsl;
        this.outbox = outbox;
        this.audit = audit;
    }

    public Optional<UsersRecord> byId(UUID userId) {
        return dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).fetchOptional();
    }

    public Optional<UsersRecord> byPhone(String phone) {
        return dsl.selectFrom(USERS).where(USERS.PHONE_E164.eq(phone)).fetchOptional();
    }

    public Optional<UsersRecord> byEmail(String email) {
        return dsl.selectFrom(USERS).where(USERS.EMAIL.eq(email)).fetchOptional();
    }

    /** Phone-first sign-up: returns the account for {@code phone}, creating it (phone verified) if new. */
    public Account findOrCreateByVerifiedPhone(String phone, String language) {
        Optional<UsersRecord> existing = byPhone(phone);
        if (existing.isPresent()) {
            UsersRecord user = existing.get();
            requireActive(user);
            if (user.getPhoneVerifiedAt() == null) {
                user.setPhoneVerifiedAt(OffsetDateTime.now());
                user.store();
            }
            return new Account(user, false);
        }
        UUID id = UUID.randomUUID();
        dsl.insertInto(USERS)
                .set(USERS.ID, id)
                .set(USERS.PHONE_E164, phone)
                .set(USERS.PHONE_VERIFIED_AT, OffsetDateTime.now())
                .set(USERS.STATUS, "ACTIVE")
                .set(USERS.COUNTRY_CODE, countryForPhone(phone))
                .set(USERS.PREFERRED_LANGUAGE, language)
                .execute();
        return created(id);
    }

    /**
     * Email sign-in: the account whose verified email this is, or a new one. An unverified copy of the address on
     * another account is released first — typing an email into a profile never gives its owner's inbox access to
     * that account, and proving the inbox never gives access to someone else's account.
     */
    public Account findOrCreateByVerifiedEmail(String email, String language) {
        Optional<UsersRecord> existing = byEmail(email);
        if (existing.isPresent() && existing.get().getEmailVerifiedAt() != null) {
            requireActive(existing.get());
            return new Account(existing.get(), false);
        }
        existing.ifPresent(other -> {
            dsl.update(USERS)
                    .setNull(USERS.EMAIL)
                    .where(USERS.ID.eq(other.getId()))
                    .execute();
            audit.userAction(
                    other.getId(),
                    "user.unverified_email_released",
                    "iam.user",
                    other.getId().toString());
        });
        return createWithVerifiedEmail(email, language);
    }

    /** Creates an account with a verified email (email code, Google or Apple). */
    public Account createWithVerifiedEmail(String email, String language) {
        UUID id = UUID.randomUUID();
        dsl.insertInto(USERS)
                .set(USERS.ID, id)
                .set(USERS.EMAIL, email)
                .set(USERS.EMAIL_VERIFIED_AT, OffsetDateTime.now())
                .set(USERS.STATUS, "ACTIVE")
                .set(USERS.PREFERRED_LANGUAGE, language)
                .execute();
        return created(id);
    }

    private Account created(UUID id) {
        dsl.insertInto(USER_PROFILES).set(USER_PROFILES.USER_ID, id).execute();
        outbox.publish(new DomainEvent("iam.user", id, "user.registered", 1, null, Map.of("userId", id.toString())));
        audit.userAction(id, "user.register", "iam.user", id.toString());
        return new Account(byId(id).orElseThrow(), true);
    }

    public static void requireActive(UsersRecord user) {
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This account is not active. Please contact support.");
        }
    }

    /** Country by longest matching calling code (+251 → ET), or null. */
    String countryForPhone(String phone) {
        return dsl.select(COUNTRIES.CODE)
                .from(COUNTRIES)
                .where(DSL.val(phone).startsWith(COUNTRIES.CALLING_CODE))
                .orderBy(DSL.length(COUNTRIES.CALLING_CODE).desc())
                .limit(1)
                .fetchOne(COUNTRIES.CODE);
    }
}
