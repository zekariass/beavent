package com.beavent.backend.tenancy.internal;

import static com.beavent.db.iam.Tables.PERMISSIONS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.MEMBER_LOCATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATION_CAPABILITIES;
import static com.beavent.db.tenant.Tables.ROLES;
import static com.beavent.db.tenant.Tables.ROLE_PERMISSIONS;

import com.beavent.backend.identity.MembershipDirectory;
import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.tenancy.OrgAccess;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Membership checks. Runs on the system role because it must decide <em>whether</em> the caller may enter an
 * organization's context before that context (and its row-level security) is set; it reads only the caller's own
 * membership.
 */
@Component
public class Memberships implements MembershipDirectory {

    /** Organization status as seen by a member: CLOSED organizations are gone; SUSPENDED ones are read-only. */
    public record Entry(OrgAccess access, String orgStatus) {}

    private final DSLContext system;

    Memberships(@SystemDb DSLContext system) {
        this.system = system;
    }

    /** The caller's active membership, or {@code null} if they are not an active member of an open organization. */
    public Entry find(UUID orgId, UUID userId) {
        var row = system.select(
                        MEMBERS.ID,
                        MEMBERS.IS_OWNER,
                        MEMBERS.ALL_LOCATIONS,
                        MEMBERS.ROLE_ID,
                        ROLES.CODE,
                        ORGANIZATIONS.STATUS)
                .from(MEMBERS)
                .join(ROLES)
                .on(ROLES.ID.eq(MEMBERS.ROLE_ID))
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(MEMBERS.ORG_ID))
                .where(MEMBERS.ORG_ID.eq(orgId), MEMBERS.USER_ID.eq(userId), MEMBERS.STATUS.eq("ACTIVE"))
                .and(ORGANIZATIONS.STATUS.ne("CLOSED"))
                .fetchOne();
        if (row == null) {
            return null;
        }
        boolean owner = row.get(MEMBERS.IS_OWNER);
        Set<String> permissions = owner
                ? new HashSet<>(system.select(PERMISSIONS.CODE)
                        .from(PERMISSIONS)
                        .where(PERMISSIONS.SCOPE.eq("ORGANIZATION"))
                        .fetch(PERMISSIONS.CODE))
                : new HashSet<>(system.select(ROLE_PERMISSIONS.PERMISSION_CODE)
                        .from(ROLE_PERMISSIONS)
                        .where(ROLE_PERMISSIONS.ROLE_ID.eq(row.get(MEMBERS.ROLE_ID)))
                        .fetch(ROLE_PERMISSIONS.PERMISSION_CODE));
        boolean allLocations = owner || row.get(MEMBERS.ALL_LOCATIONS);
        Set<UUID> locations = allLocations
                ? Set.of()
                : new HashSet<>(system.select(MEMBER_LOCATIONS.LOCATION_ID)
                        .from(MEMBER_LOCATIONS)
                        .where(MEMBER_LOCATIONS.MEMBER_ID.eq(row.get(MEMBERS.ID)))
                        .fetch(MEMBER_LOCATIONS.LOCATION_ID));
        return new Entry(
                new OrgAccess(
                        orgId,
                        userId,
                        row.get(MEMBERS.ID),
                        row.get(ROLES.CODE),
                        owner,
                        permissions,
                        allLocations,
                        locations),
                row.get(ORGANIZATIONS.STATUS));
    }

    @Override
    public List<Membership> activeMemberships(UUID userId) {
        var rows = system.select(
                        ORGANIZATIONS.ID,
                        ORGANIZATIONS.DISPLAY_NAME,
                        ORGANIZATIONS.SLUG,
                        ROLES.CODE,
                        ROLES.NAME,
                        MEMBERS.IS_OWNER)
                .from(MEMBERS)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(MEMBERS.ORG_ID))
                .join(ROLES)
                .on(ROLES.ID.eq(MEMBERS.ROLE_ID))
                .where(MEMBERS.USER_ID.eq(userId), MEMBERS.STATUS.eq("ACTIVE"), ORGANIZATIONS.STATUS.ne("CLOSED"))
                .orderBy(MEMBERS.JOINED_AT)
                .fetch();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> capabilities = system.select(
                        ORGANIZATION_CAPABILITIES.ORG_ID, ORGANIZATION_CAPABILITIES.CAPABILITY)
                .from(ORGANIZATION_CAPABILITIES)
                .where(ORGANIZATION_CAPABILITIES.ORG_ID.in(rows.getValues(ORGANIZATIONS.ID)))
                .and(ORGANIZATION_CAPABILITIES.STATUS.eq("ENABLED"))
                .orderBy(ORGANIZATION_CAPABILITIES.CAPABILITY)
                .fetchGroups(ORGANIZATION_CAPABILITIES.ORG_ID, ORGANIZATION_CAPABILITIES.CAPABILITY);
        return rows.stream()
                .map(r -> new Membership(
                        r.get(ORGANIZATIONS.ID),
                        r.get(ORGANIZATIONS.DISPLAY_NAME),
                        r.get(ORGANIZATIONS.SLUG),
                        r.get(ROLES.CODE),
                        r.get(ROLES.NAME),
                        r.get(MEMBERS.IS_OWNER),
                        capabilities.getOrDefault(r.get(ORGANIZATIONS.ID), List.of())))
                .collect(Collectors.toList());
    }
}
