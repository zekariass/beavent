package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_PROFILES;

import com.beavent.backend.identity.People;
import com.beavent.backend.platform.IdentityDb;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

@Component
class PeopleLookup implements People {

    private final DSLContext dsl;

    PeopleLookup(@IdentityDb DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Person> find(UUID userId) {
        return dsl.select(
                        USERS.ID,
                        USER_PROFILES.FIRST_NAME,
                        USER_PROFILES.LAST_NAME,
                        USER_PROFILES.DISPLAY_NAME,
                        USERS.PHONE_E164,
                        USERS.PHONE_VERIFIED_AT,
                        USERS.EMAIL,
                        USERS.EMAIL_VERIFIED_AT,
                        USERS.PREFERRED_LANGUAGE)
                .from(USERS)
                .leftJoin(USER_PROFILES)
                .on(USER_PROFILES.USER_ID.eq(USERS.ID))
                .where(USERS.ID.eq(userId), USERS.STATUS.eq("ACTIVE"))
                .fetchOptional(r -> new Person(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value6() == null ? null : r.value5(),
                        r.value8() == null ? null : r.value7(),
                        r.value9()));
    }
}
