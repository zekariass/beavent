package com.beavent.backend.tenancy.internal;

import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.MEMBER_LOCATIONS;
import static com.beavent.db.tenant.Tables.ROLES;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.CursorPage;
import com.beavent.backend.platform.Cursors;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.tenant.tables.records.MembersRecord;
import com.beavent.db.tenant.tables.records.RolesRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Team access: role, location scope and status of members. Changing someone's role, suspending or removing them
 * needs step-up; nobody can act on a member who holds permissions they lack; the owner's access is fixed (ownership
 * transfer is a separate, later flow).
 */
@Component
public class Members {

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Roles roles;
    private final People people;
    private final Audit audit;

    Members(DSLContext dsl, TransactionTemplate transactions, Roles roles, People people, Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.roles = roles;
        this.people = people;
        this.audit = audit;
    }

    public CursorPage<Bodies.Member> list(UUID orgId, String cursor, int limit) {
        return transactions.execute(status -> {
            // Explicit org filter: members_self_read also shows the caller's memberships in other organizations.
            Condition condition = MEMBERS.ORG_ID.eq(orgId).and(MEMBERS.STATUS.ne("REMOVED"));
            if (cursor != null) {
                try {
                    condition = condition.and(MEMBERS.ID.gt(
                            UUID.fromString(Cursors.decode(cursor, 1).getFirst())));
                } catch (IllegalArgumentException e) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid cursor.");
                }
            }
            List<MembersRecord> rows = dsl.selectFrom(MEMBERS)
                    .where(condition)
                    .orderBy(MEMBERS.ID)
                    .limit(limit + 1)
                    .fetch();
            boolean more = rows.size() > limit;
            List<Bodies.Member> items = views(more ? rows.subList(0, limit) : rows);
            return new CursorPage<>(
                    items, more ? Cursors.encode(items.getLast().id().toString()) : null);
        });
    }

    public Bodies.Member update(OrgAccess access, UUID memberId, Bodies.MemberUpdate input) {
        if (input.roleId() == null
                && input.status() == null
                && input.allLocations() == null
                && input.locationIds() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send at least one field to change.");
        }
        return transactions.execute(status -> {
            MembersRecord member = lockedTarget(access, memberId);
            Map<String, Object> before = snapshot(member);
            if (input.roleId() != null && !input.roleId().equals(member.getRoleId())) {
                RolesRecord role = roles.visibleRole(input.roleId());
                if (role.getIsSystem() && "OWNER".equals(role.getCode())) {
                    throw new ApiException(
                            ProblemCode.CONSTRAINT_VIOLATION, "Ownership can't be given by changing a role.");
                }
                if (!access.holdsAll(Set.copyOf(roles.permissionsOf(role.getId())))) {
                    throw new ApiException(ProblemCode.FORBIDDEN, "You can't grant permissions you don't have.");
                }
                Assurance.requireStepUp();
                member.setRoleId(role.getId());
            }
            if (input.status() != null && !input.status().equals(member.getStatus())) {
                Assurance.requireStepUp();
                member.setStatus(input.status());
            }
            if (input.allLocations() != null || input.locationIds() != null) {
                boolean all = input.allLocations() != null ? input.allLocations() : false;
                setLocations(access.orgId(), member, all, all ? List.of() : input.locationIds());
            }
            member.store();
            audit.record(entry(access, "member.updated", memberId, before, snapshot(member)));
            return view(access.orgId(), memberId);
        });
    }

    public void remove(OrgAccess access, UUID memberId) {
        transactions.executeWithoutResult(status -> {
            MembersRecord member = lockedTarget(access, memberId);
            Assurance.requireStepUp();
            Map<String, Object> before = snapshot(member);
            member.setStatus("REMOVED");
            member.setRemovedAt(OffsetDateTime.now());
            member.store();
            dsl.deleteFrom(MEMBER_LOCATIONS)
                    .where(MEMBER_LOCATIONS.MEMBER_ID.eq(memberId))
                    .execute();
            audit.record(entry(access, "member.removed", memberId, before, snapshot(member)));
        });
    }

    /** Replaces the member's location scope; every location must belong to the organization in context. */
    void setLocations(UUID orgId, MembersRecord member, boolean all, Collection<UUID> locationIds) {
        Set<UUID> ids = new LinkedHashSet<>(locationIds == null ? List.of() : locationIds);
        if (!ids.isEmpty() && dsl.fetchCount(LOCATIONS, LOCATIONS.ID.in(ids)) != ids.size()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown location.");
        }
        member.setAllLocations(all);
        dsl.deleteFrom(MEMBER_LOCATIONS)
                .where(MEMBER_LOCATIONS.MEMBER_ID.eq(member.getId()))
                .execute();
        for (UUID locationId : ids) {
            dsl.insertInto(MEMBER_LOCATIONS)
                    .set(MEMBER_LOCATIONS.ORG_ID, orgId)
                    .set(MEMBER_LOCATIONS.MEMBER_ID, member.getId())
                    .set(MEMBER_LOCATIONS.LOCATION_ID, locationId)
                    .execute();
        }
    }

    private MembersRecord lockedTarget(OrgAccess access, UUID memberId) {
        MembersRecord member = dsl.selectFrom(MEMBERS)
                .where(MEMBERS.ID.eq(memberId), MEMBERS.ORG_ID.eq(access.orgId()), MEMBERS.STATUS.ne("REMOVED"))
                .forUpdate()
                .fetchOne();
        if (member == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        if (member.getUserId().equals(access.userId())) {
            throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "You can't change your own access.");
        }
        if (member.getIsOwner()) {
            throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "The owner's access can't be changed.");
        }
        if (!access.holdsAll(Set.copyOf(roles.permissionsOf(member.getRoleId())))) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This member has permissions you don't have.");
        }
        return member;
    }

    Bodies.Member view(UUID orgId, UUID memberId) {
        MembersRecord member = dsl.selectFrom(MEMBERS)
                .where(MEMBERS.ID.eq(memberId), MEMBERS.ORG_ID.eq(orgId))
                .fetchOne();
        if (member == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return views(List.of(member)).getFirst();
    }

    private List<Bodies.Member> views(List<MembersRecord> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = members.stream().map(MembersRecord::getId).toList();
        Map<UUID, List<UUID>> locations = dsl.select(MEMBER_LOCATIONS.MEMBER_ID, MEMBER_LOCATIONS.LOCATION_ID)
                .from(MEMBER_LOCATIONS)
                .where(MEMBER_LOCATIONS.MEMBER_ID.in(ids))
                .fetchGroups(MEMBER_LOCATIONS.MEMBER_ID, MEMBER_LOCATIONS.LOCATION_ID);
        Map<UUID, String> roleCodes = dsl.select(ROLES.ID, ROLES.CODE)
                .from(ROLES)
                .where(ROLES.ID.in(
                        members.stream().map(MembersRecord::getRoleId).toList()))
                .fetchMap(ROLES.ID, ROLES.CODE);
        Map<UUID, People.Person> persons =
                people.byIds(members.stream().map(MembersRecord::getUserId).toList());
        return members.stream()
                .map(m -> {
                    People.Person person = persons.get(m.getUserId());
                    return new Bodies.Member(
                            m.getId(),
                            m.getUserId(),
                            person == null ? "Team member" : person.displayName(),
                            person == null ? null : person.phone(),
                            m.getRoleId(),
                            roleCodes.get(m.getRoleId()),
                            m.getStatus(),
                            m.getIsOwner(),
                            m.getIsOwner() || m.getAllLocations(),
                            locations.getOrDefault(m.getId(), List.of()),
                            Support.instant(m.getJoinedAt()));
                })
                .toList();
    }

    private static Map<String, Object> snapshot(MembersRecord member) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("roleId", member.getRoleId());
        state.put("status", member.getStatus());
        state.put("allLocations", member.getAllLocations());
        return state;
    }

    private static Audit.Entry entry(
            OrgAccess access, String action, UUID memberId, Map<String, Object> before, Map<String, Object> after) {
        return new Audit.Entry(
                Audit.Actor.USER,
                access.userId(),
                access.orgId(),
                action,
                "tenant.member",
                memberId.toString(),
                before,
                after,
                null);
    }
}
