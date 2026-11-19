package com.beavent.backend.identity.internal;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_CONSENTS;
import static com.beavent.db.iam.Tables.USER_PROFILES;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.ref.Tables.LEGAL_DOCUMENT_VERSIONS;

import com.beavent.backend.identity.AvatarFiles;
import com.beavent.backend.identity.MembershipDirectory;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.db.iam.tables.records.UserProfilesRecord;
import com.beavent.db.iam.tables.records.UsersRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The signed-in person's own profile, preferences and consents. Call inside an identity-role transaction. */
@Component
public class Profiles {

    public record MeView(
            UsersRecord user,
            UserProfilesRecord profile,
            boolean platformStaff,
            List<MembershipDirectory.Membership> memberships) {}

    private final DSLContext dsl;
    private final ObjectProvider<MembershipDirectory> memberships;
    private final ObjectProvider<AvatarFiles> avatarFiles;

    Profiles(
            @IdentityDb DSLContext dsl,
            ObjectProvider<MembershipDirectory> memberships,
            ObjectProvider<AvatarFiles> avatarFiles) {
        this.dsl = dsl;
        this.memberships = memberships;
        this.avatarFiles = avatarFiles;
    }

    public MeView me(UUID userId) {
        UsersRecord user = dsl.selectFrom(USERS)
                .where(USERS.ID.eq(userId))
                .fetchOptional()
                .orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "This account no longer exists."));
        UserProfilesRecord profile = dsl.selectFrom(USER_PROFILES)
                .where(USER_PROFILES.USER_ID.eq(userId))
                .fetchOne();
        if (profile == null) {
            profile = dsl.newRecord(USER_PROFILES);
            profile.setUserId(userId);
            profile.store();
        }
        boolean staff = dsl.fetchExists(
                USER_STAFF_ROLES, USER_STAFF_ROLES.USER_ID.eq(userId).and(USER_STAFF_ROLES.REVOKED_AT.isNull()));
        MembershipDirectory directory = memberships.getIfAvailable();
        List<MembershipDirectory.Membership> list = directory == null ? List.of() : directory.activeMemberships(userId);
        return new MeView(user, profile, staff, list);
    }

    /** JSON merge-patch semantics: absent = unchanged, null = cleared. */
    public void update(UUID userId, JsonNode patch) {
        var users = dsl.updateQuery(USERS);
        users.addConditions(USERS.ID.eq(userId));
        boolean usersChanged = false;
        if (patch.has("email")) {
            users.addValue(USERS.EMAIL, text(patch, "email"));
            users.addValue(USERS.EMAIL_VERIFIED_AT, (java.time.OffsetDateTime) null);
            usersChanged = true;
        }
        if (patch.has("preferredLanguage")) {
            users.addValue(USERS.PREFERRED_LANGUAGE, text(patch, "preferredLanguage"));
            usersChanged = true;
        }
        if (patch.has("displayCalendar")) {
            users.addValue(USERS.DISPLAY_CALENDAR, text(patch, "displayCalendar"));
            usersChanged = true;
        }
        if (patch.has("timezone")) {
            users.addValue(USERS.TIMEZONE, text(patch, "timezone"));
            usersChanged = true;
        }
        if (usersChanged) {
            users.execute();
        }
        var profile = dsl.updateQuery(USER_PROFILES);
        profile.addConditions(USER_PROFILES.USER_ID.eq(userId));
        boolean profileChanged = false;
        for (var field : List.of(
                new Object[] {"firstName", USER_PROFILES.FIRST_NAME},
                new Object[] {"lastName", USER_PROFILES.LAST_NAME},
                new Object[] {"displayName", USER_PROFILES.DISPLAY_NAME})) {
            String name = (String) field[0];
            if (patch.has(name)) {
                @SuppressWarnings("unchecked")
                org.jooq.Field<String> column = (org.jooq.Field<String>) field[1];
                profile.addValue(column, text(patch, name));
                profileChanged = true;
            }
        }
        if (patch.has("avatarFileId")) {
            String value = text(patch, "avatarFileId");
            UUID fileId = value == null ? null : UUID.fromString(value);
            AvatarFiles checker = avatarFiles.getIfAvailable();
            if (fileId != null && (checker == null || !checker.isUsableAvatar(userId, fileId))) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Upload the picture first, then use it.");
            }
            profile.addValue(USER_PROFILES.AVATAR_FILE_ID, fileId);
            profileChanged = true;
        }
        if (profileChanged) {
            profile.execute();
        }
    }

    public void acceptDocuments(UUID userId, List<UUID> documentVersionIds, String clientApp, String ip) {
        int known = dsl.fetchCount(LEGAL_DOCUMENT_VERSIONS, LEGAL_DOCUMENT_VERSIONS.ID.in(documentVersionIds));
        if (known != documentVersionIds.stream().distinct().count()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown legal document version.");
        }
        for (UUID versionId : documentVersionIds) {
            dsl.insertInto(USER_CONSENTS)
                    .set(USER_CONSENTS.USER_ID, userId)
                    .set(USER_CONSENTS.DOCUMENT_VERSION_ID, versionId)
                    .set(USER_CONSENTS.CLIENT_APP, clientApp)
                    .set(USER_CONSENTS.IP_ADDRESS, (Object) org.jooq.impl.DSL.field(
                            "cast({0} as inet)", Object.class, org.jooq.impl.DSL.val(ip, String.class)))
                    .onConflictDoNothing()
                    .execute();
        }
    }

    private static String text(JsonNode patch, String name) {
        JsonNode value = patch.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }
}
