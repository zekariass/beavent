package com.beavent.backend.admin.internal;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_PROFILES;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATION_CAPABILITIES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Cursors;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/** Organization and user search for support and operations, limited to the staff member's markets. */
@Component
public class Directory {

    static final String ORGS_VIEW = "admin.orgs.view";

    private final DSLContext system;
    private final Kyb kyb;
    private final AuditTrail auditTrail;

    Directory(@SystemDb DSLContext system, Kyb kyb, AuditTrail auditTrail) {
        this.system = system;
        this.kyb = kyb;
        this.auditTrail = auditTrail;
    }

    public AdminBodies.Page<AdminBodies.OrgSummary> searchOrganizations(
            StaffAccess staff, String query, String status, String cursor, int limit) {
        staff.require(ORGS_VIEW);
        Condition condition = staff.marketFilter(ORGS_VIEW, ORGANIZATIONS.HOME_MARKET_ID);
        if (query != null && !query.isBlank()) {
            String q = query.strip();
            UUID id = parseUuid(q);
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            condition = condition.and(
                    id != null
                            ? ORGANIZATIONS.ID.eq(id)
                            : ORGANIZATIONS
                                    .DISPLAY_NAME
                                    .likeIgnoreCase(like)
                                    .or(ORGANIZATIONS.SLUG.likeIgnoreCase(like)));
        }
        if (status != null) {
            condition = condition.and(ORGANIZATIONS.STATUS.eq(status));
        }
        if (cursor != null) {
            condition = condition.and(ORGANIZATIONS.ID.lt(cursorId(cursor)));
        }
        List<AdminBodies.OrgSummary> rows = system.select(ORGANIZATIONS.asterisk(), MARKETS.CODE)
                .from(ORGANIZATIONS)
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .where(condition)
                .orderBy(ORGANIZATIONS.ID.desc())
                .limit(limit + 1)
                .fetch(Directory::orgSummary);
        return page(rows, limit, AdminBodies.OrgSummary::id);
    }

    public AdminBodies.Org360 organization(StaffAccess staff, UUID orgId) {
        Record row = system.select(ORGANIZATIONS.asterisk(), MARKETS.CODE)
                .from(ORGANIZATIONS)
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        staff.requireInMarket(ORGS_VIEW, row.get(ORGANIZATIONS.HOME_MARKET_ID));
        List<String> capabilities = system.select(ORGANIZATION_CAPABILITIES.CAPABILITY)
                .from(ORGANIZATION_CAPABILITIES)
                .where(ORGANIZATION_CAPABILITIES.ORG_ID.eq(orgId), ORGANIZATION_CAPABILITIES.STATUS.eq("ENABLED"))
                .orderBy(ORGANIZATION_CAPABILITIES.CAPABILITY)
                .fetch(ORGANIZATION_CAPABILITIES.CAPABILITY);
        AdminBodies.Person owner = system.select(
                        USERS.ID,
                        USERS.PHONE_E164,
                        USER_PROFILES.DISPLAY_NAME,
                        USER_PROFILES.FIRST_NAME,
                        USER_PROFILES.LAST_NAME)
                .from(MEMBERS)
                .join(USERS)
                .on(USERS.ID.eq(MEMBERS.USER_ID))
                .leftJoin(USER_PROFILES)
                .on(USER_PROFILES.USER_ID.eq(USERS.ID))
                .where(MEMBERS.ORG_ID.eq(orgId), MEMBERS.IS_OWNER.isTrue(), MEMBERS.STATUS.eq("ACTIVE"))
                .fetchOptional(r -> new AdminBodies.Person(
                        r.get(USERS.ID),
                        name(
                                r.get(USER_PROFILES.DISPLAY_NAME),
                                r.get(USER_PROFILES.FIRST_NAME),
                                r.get(USER_PROFILES.LAST_NAME)),
                        r.get(USERS.PHONE_E164)))
                .orElse(null);
        int members = system.fetchCount(MEMBERS, MEMBERS.ORG_ID.eq(orgId).and(MEMBERS.STATUS.ne("REMOVED")));
        int locations = system.fetchCount(LOCATIONS, LOCATIONS.ORG_ID.eq(orgId));
        List<AdminBodies.LegalEntitySummary> entities = system.selectFrom(LEGAL_ENTITIES)
                .where(LEGAL_ENTITIES.ORG_ID.eq(orgId))
                .orderBy(LEGAL_ENTITIES.CREATED_AT)
                .fetch(e -> new AdminBodies.LegalEntitySummary(
                        e.getId(),
                        e.getCountryCode(),
                        e.getRegisteredName(),
                        e.getTin(),
                        e.getTaxRegime(),
                        e.getVerificationStatus()));
        return new AdminBodies.Org360(
                orgSummary(row),
                capabilities,
                owner,
                members,
                locations,
                entities,
                kyb.casesOf(orgId),
                staff.can("admin.audit.view") ? auditTrail.recent(orgId, 20) : List.of());
    }

    public AdminBodies.Page<AdminBodies.UserSummary> searchUsers(
            StaffAccess staff, String query, String cursor, int limit) {
        if (!staff.can("admin.users.manage") && !staff.can("admin.support")) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Your staff role does not allow searching people.");
        }
        Condition condition = DSL.noCondition();
        if (query != null && !query.isBlank()) {
            String q = query.strip();
            String digits = q.replaceAll("[^0-9]", "");
            UUID id = parseUuid(q);
            if (id != null) {
                condition = USERS.ID.eq(id);
            } else if (q.contains("@")) {
                condition = USERS.EMAIL.eq(q);
            } else if (digits.length() >= 6
                    && digits.length() == q.replaceAll("[\\s+()-]", "").length()) {
                // Phone numbers are matched on their last nine digits (local formats: 09..., +2519...).
                condition = USERS.PHONE_E164.endsWith(digits.substring(Math.max(0, digits.length() - 9)));
            } else {
                String like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
                condition = USER_PROFILES
                        .DISPLAY_NAME
                        .likeIgnoreCase(like)
                        .or(DSL.concat(USER_PROFILES.FIRST_NAME, DSL.val(" "), USER_PROFILES.LAST_NAME)
                                .likeIgnoreCase(like));
            }
        }
        if (cursor != null) {
            condition = condition.and(USERS.ID.lt(cursorId(cursor)));
        }
        var staffRoles = DSL.exists(DSL.selectOne()
                .from(USER_STAFF_ROLES)
                .where(USER_STAFF_ROLES.USER_ID.eq(USERS.ID), USER_STAFF_ROLES.REVOKED_AT.isNull()));
        var memberships = DSL.selectCount()
                .from(MEMBERS)
                .where(MEMBERS.USER_ID.eq(USERS.ID), MEMBERS.STATUS.eq("ACTIVE"))
                .asField("memberships");
        List<AdminBodies.UserSummary> rows = system.select(
                        USERS.asterisk(),
                        USER_PROFILES.DISPLAY_NAME,
                        USER_PROFILES.FIRST_NAME,
                        USER_PROFILES.LAST_NAME,
                        DSL.field(staffRoles).as("is_staff"),
                        memberships)
                .from(USERS)
                .leftJoin(USER_PROFILES)
                .on(USER_PROFILES.USER_ID.eq(USERS.ID))
                .where(condition)
                .orderBy(USERS.ID.desc())
                .limit(limit + 1)
                .fetch(r -> new AdminBodies.UserSummary(
                        r.get(USERS.ID),
                        r.get(USERS.PHONE_E164),
                        r.get(USERS.EMAIL),
                        name(
                                r.get(USER_PROFILES.DISPLAY_NAME),
                                r.get(USER_PROFILES.FIRST_NAME),
                                r.get(USER_PROFILES.LAST_NAME)),
                        r.get(USERS.STATUS),
                        r.get(USERS.COUNTRY_CODE),
                        r.get(USERS.CREATED_AT).toInstant(),
                        r.get(USERS.LAST_LOGIN_AT) == null
                                ? null
                                : r.get(USERS.LAST_LOGIN_AT).toInstant(),
                        r.get("is_staff", Boolean.class),
                        r.get("memberships", Integer.class)));
        return page(rows, limit, AdminBodies.UserSummary::id);
    }

    static AdminBodies.OrgSummary orgSummary(Record row) {
        return new AdminBodies.OrgSummary(
                row.get(ORGANIZATIONS.ID),
                row.get(ORGANIZATIONS.SLUG),
                row.get(ORGANIZATIONS.DISPLAY_NAME),
                row.get(ORGANIZATIONS.KIND),
                row.get(ORGANIZATIONS.STATUS),
                row.get(ORGANIZATIONS.VERIFICATION_STATUS),
                row.get(MARKETS.CODE),
                row.get(ORGANIZATIONS.CREATED_AT).toInstant());
    }

    static String name(String display, String first, String last) {
        if (display != null && !display.isBlank()) {
            return display;
        }
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        return full.isEmpty() ? null : full;
    }

    static <T> AdminBodies.Page<T> page(List<T> rows, int limit, java.util.function.Function<T, UUID> id) {
        boolean more = rows.size() > limit;
        List<T> items = more ? rows.subList(0, limit) : rows;
        return new AdminBodies.Page<>(
                items,
                new AdminBodies.PageInfo(
                        more ? Cursors.encode(id.apply(items.getLast()).toString()) : null));
    }

    static UUID cursorId(String cursor) {
        try {
            return UUID.fromString(Cursors.decode(cursor, 1).getFirst());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid cursor.");
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
