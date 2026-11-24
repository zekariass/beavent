package com.beavent.backend.tenancy.internal;

import static com.beavent.db.tenant.Tables.INVITATIONS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ROLES;
import static com.beavent.db.tenant.Tables.ROLE_PERMISSIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.tenant.tables.records.RolesRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * System role templates (read-only) and the organization's custom roles. Nobody can grant permissions they do not
 * hold themselves (owners hold all), and granting sensitive permissions needs step-up.
 */
@Component
public class Roles {

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final PermissionCatalog catalog;
    private final Audit audit;

    Roles(DSLContext dsl, TransactionTemplate transactions, PermissionCatalog catalog, Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.catalog = catalog;
        this.audit = audit;
    }

    public List<Bodies.Role> list() {
        return transactions.execute(status -> {
            Map<UUID, List<String>> permissions = dsl.select(ROLE_PERMISSIONS.ROLE_ID, ROLE_PERMISSIONS.PERMISSION_CODE)
                    .from(ROLE_PERMISSIONS)
                    .orderBy(ROLE_PERMISSIONS.PERMISSION_CODE)
                    .fetchGroups(ROLE_PERMISSIONS.ROLE_ID, ROLE_PERMISSIONS.PERMISSION_CODE);
            return dsl.selectFrom(ROLES)
                    .orderBy(ROLES.IS_SYSTEM.desc(), ROLES.NAME)
                    .fetch(r -> view(r, permissions.getOrDefault(r.getId(), List.of())));
        });
    }

    public Bodies.Role create(OrgAccess access, Bodies.RoleCreate input) {
        return transactions.execute(status -> {
            Set<String> permissions = new TreeSet<>(input.permissions());
            if (permissions.isEmpty() && input.basedOnRoleId() != null) {
                permissions.addAll(
                        permissionsOf(visibleRole(input.basedOnRoleId()).getId()));
            }
            guardGrant(access, permissions);
            requireFreeName(input.name(), null);
            RolesRecord role = dsl.newRecord(ROLES);
            role.setOrgId(access.orgId());
            role.setCode(freeCode(input.name()));
            role.setName(input.name().strip());
            role.setDescription(input.description());
            role.setIsSystem(false);
            role.store();
            writePermissions(role.getId(), permissions);
            audit.record(entry(access, "role.created", role.getId(), null, Map.of("permissions", permissions)));
            return view(role, List.copyOf(permissions));
        });
    }

    public Bodies.Role update(OrgAccess access, UUID roleId, Bodies.RoleUpdate input) {
        if (input.name() == null && input.description() == null && input.permissions() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send at least one field to change.");
        }
        return transactions.execute(status -> {
            RolesRecord role = customRole(roleId);
            Set<String> current = new TreeSet<>(permissionsOf(roleId));
            if (!access.holdsAll(current)) {
                throw new ApiException(ProblemCode.FORBIDDEN, "This role has permissions you don't have.");
            }
            if (input.name() != null && !input.name().strip().equalsIgnoreCase(role.getName())) {
                requireFreeName(input.name(), roleId);
                role.setName(input.name().strip());
            }
            if (input.description() != null) {
                role.setDescription(input.description());
            }
            role.store();
            Set<String> permissions = current;
            if (input.permissions() != null) {
                permissions = new TreeSet<>(input.permissions());
                Set<String> added = new HashSet<>(permissions);
                added.removeAll(current);
                guardGrant(access, added);
                catalog.requireKnown(permissions);
                dsl.deleteFrom(ROLE_PERMISSIONS)
                        .where(ROLE_PERMISSIONS.ROLE_ID.eq(roleId))
                        .execute();
                writePermissions(roleId, permissions);
            }
            audit.record(entry(
                    access,
                    "role.updated",
                    roleId,
                    Map.of("permissions", current),
                    Map.of("permissions", permissions)));
            return view(role, List.copyOf(permissions));
        });
    }

    public void delete(OrgAccess access, UUID roleId) {
        transactions.executeWithoutResult(status -> {
            RolesRecord role = customRole(roleId);
            Set<String> current = new TreeSet<>(permissionsOf(roleId));
            if (!access.holdsAll(current)) {
                throw new ApiException(ProblemCode.FORBIDDEN, "This role has permissions you don't have.");
            }
            boolean inUse = dsl.fetchExists(MEMBERS, MEMBERS.ROLE_ID.eq(roleId).and(MEMBERS.STATUS.ne("REMOVED")))
                    || dsl.fetchExists(
                            INVITATIONS, INVITATIONS.ROLE_ID.eq(roleId).and(INVITATIONS.STATUS.eq("PENDING")));
            if (inUse) {
                throw new ApiException(
                        ProblemCode.CONFLICT, "Members or pending invitations use this role. Change them first.");
            }
            // Former members keep a role reference for history; point them at the read-only template.
            UUID viewer = dsl.select(ROLES.ID)
                    .from(ROLES)
                    .where(ROLES.ORG_ID.isNull(), ROLES.CODE.eq("VIEWER"))
                    .fetchSingle(ROLES.ID);
            dsl.update(MEMBERS)
                    .set(MEMBERS.ROLE_ID, viewer)
                    .where(MEMBERS.ROLE_ID.eq(roleId))
                    .execute();
            dsl.update(INVITATIONS)
                    .set(INVITATIONS.ROLE_ID, viewer)
                    .where(INVITATIONS.ROLE_ID.eq(roleId))
                    .execute();
            role.delete();
            audit.record(entry(access, "role.deleted", roleId, Map.of("name", role.getName()), null));
        });
    }

    /** A role the organization may assign: a system template or one of its own custom roles. */
    RolesRecord visibleRole(UUID roleId) {
        RolesRecord role = dsl.selectFrom(ROLES).where(ROLES.ID.eq(roleId)).fetchOne();
        if (role == null) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown role.");
        }
        return role;
    }

    List<String> permissionsOf(UUID roleId) {
        if (dsl.fetchExists(
                ROLES, ROLES.ID.eq(roleId).and(ROLES.CODE.eq("OWNER")).and(ROLES.ORG_ID.isNull()))) {
            // Owners hold every permission, including ones added after the template was seeded.
            return dsl.select(com.beavent.db.iam.Tables.PERMISSIONS.CODE)
                    .from(com.beavent.db.iam.Tables.PERMISSIONS)
                    .where(com.beavent.db.iam.Tables.PERMISSIONS.SCOPE.eq("ORGANIZATION"))
                    .fetch(com.beavent.db.iam.Tables.PERMISSIONS.CODE);
        }
        return dsl.select(ROLE_PERMISSIONS.PERMISSION_CODE)
                .from(ROLE_PERMISSIONS)
                .where(ROLE_PERMISSIONS.ROLE_ID.eq(roleId))
                .fetch(ROLE_PERMISSIONS.PERMISSION_CODE);
    }

    /** Stops privilege escalation and requires step-up for sensitive grants. */
    void guardGrant(OrgAccess access, Set<String> permissions) {
        catalog.requireKnown(permissions);
        if (!access.holdsAll(permissions)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "You can't grant permissions you don't have.");
        }
        catalog.requireStepUpIfSensitive(permissions);
    }

    private RolesRecord customRole(UUID roleId) {
        RolesRecord role = dsl.selectFrom(ROLES).where(ROLES.ID.eq(roleId)).fetchOne();
        if (role == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        if (role.getIsSystem()) {
            throw new ApiException(
                    ProblemCode.IMMUTABLE, "System roles can't be changed. Create a custom role based on it.");
        }
        return role;
    }

    private void requireFreeName(String name, UUID self) {
        boolean taken = dsl.fetchExists(
                ROLES,
                ROLES.ORG_ID
                        .isNotNull()
                        .and(DSL.lower(ROLES.NAME).eq(name.strip().toLowerCase(Locale.ROOT)))
                        .and(self == null ? DSL.noCondition() : ROLES.ID.ne(self)));
        if (taken) {
            throw new ApiException(ProblemCode.CONFLICT, "A role with this name already exists.");
        }
    }

    private String freeCode(String name) {
        String base = Support.slugify(name, 40).toUpperCase(Locale.ROOT).replace('-', '_');
        base = "CUSTOM_" + (base.isEmpty() ? Support.randomSuffix(6).toUpperCase(Locale.ROOT) : base);
        String candidate = base;
        while (dsl.fetchExists(ROLES, ROLES.CODE.eq(candidate).and(ROLES.ORG_ID.isNotNull()))) {
            candidate = base + "_" + Support.randomSuffix(4).toUpperCase(Locale.ROOT);
        }
        return candidate;
    }

    private void writePermissions(UUID roleId, Set<String> permissions) {
        for (String code : permissions) {
            dsl.insertInto(ROLE_PERMISSIONS)
                    .set(ROLE_PERMISSIONS.ROLE_ID, roleId)
                    .set(ROLE_PERMISSIONS.PERMISSION_CODE, code)
                    .execute();
        }
    }

    private static Bodies.Role view(RolesRecord role, List<String> permissions) {
        return new Bodies.Role(
                role.getId(), role.getCode(), role.getName(), role.getDescription(), role.getIsSystem(), permissions);
    }

    private static Audit.Entry entry(
            OrgAccess access, String action, UUID roleId, Map<String, Object> before, Map<String, Object> after) {
        return new Audit.Entry(
                Audit.Actor.USER,
                access.userId(),
                access.orgId(),
                action,
                "tenant.role",
                roleId.toString(),
                before,
                after,
                null);
    }
}
