package com.beavent.backend.tenancy.internal;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_PROFILES;

import com.beavent.backend.platform.SystemDb;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Names and verified contact details of people, for team lists and invitations. Row-level security lets a person
 * read only their own account, so this narrow lookup runs on the system role; callers pass only ids of members of
 * the organization in context.
 */
@Component
class People {

    record Person(UUID userId, String displayName, String phone, String verifiedPhone, String verifiedEmail) {}

    private final DSLContext system;

    People(@SystemDb DSLContext system) {
        this.system = system;
    }

    Map<UUID, Person> byIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return system
                .select(
                        USERS.ID,
                        USERS.PHONE_E164,
                        USERS.PHONE_VERIFIED_AT,
                        USERS.EMAIL,
                        USERS.EMAIL_VERIFIED_AT,
                        USER_PROFILES.DISPLAY_NAME,
                        USER_PROFILES.FIRST_NAME,
                        USER_PROFILES.LAST_NAME)
                .from(USERS)
                .leftJoin(USER_PROFILES)
                .on(USER_PROFILES.USER_ID.eq(USERS.ID))
                .where(USERS.ID.in(userIds))
                .fetch()
                .stream()
                .map(r -> new Person(
                        r.get(USERS.ID),
                        displayName(
                                r.get(USER_PROFILES.DISPLAY_NAME),
                                r.get(USER_PROFILES.FIRST_NAME),
                                r.get(USER_PROFILES.LAST_NAME),
                                r.get(USERS.PHONE_E164)),
                        r.get(USERS.PHONE_E164),
                        r.get(USERS.PHONE_VERIFIED_AT) == null ? null : r.get(USERS.PHONE_E164),
                        r.get(USERS.EMAIL_VERIFIED_AT) == null ? null : r.get(USERS.EMAIL)))
                .collect(Collectors.toMap(Person::userId, p -> p));
    }

    Person byId(UUID userId) {
        return byIds(java.util.List.of(userId)).get(userId);
    }

    /** The account that verified this phone number, if any. */
    UUID userIdByVerifiedPhone(String phone) {
        return system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(phone), USERS.PHONE_VERIFIED_AT.isNotNull())
                .fetchOne(USERS.ID);
    }

    private static String displayName(String display, String first, String last, String phone) {
        if (display != null && !display.isBlank()) {
            return display;
        }
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        if (!full.isEmpty()) {
            return full;
        }
        // No name yet: show the number with its middle digits hidden.
        return phone == null || phone.length() < 8
                ? "Team member"
                : phone.substring(0, 4) + " •••• " + phone.substring(phone.length() - 3);
    }
}
