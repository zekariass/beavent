package com.beavent.backend.admin.internal;

import static com.beavent.db.platform.Tables.AUDIT_LOG;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.platform.tables.records.AuditLogRecord;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Read access to the audit log, newest first. Staff limited to some markets must name an organization. */
@Component
public class AuditTrail {

    static final String VIEW = "admin.audit.view";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final DSLContext system;
    private final JsonMapper json;

    AuditTrail(@SystemDb DSLContext system, JsonMapper json) {
        this.system = system;
        this.json = json;
    }

    public AdminBodies.Page<AdminBodies.AuditEntry> search(
            StaffAccess staff, UUID orgId, UUID actorUserId, String action, String targetId, String cursor, int limit) {
        staff.require(VIEW);
        Condition condition = DSL.noCondition();
        if (orgId != null) {
            UUID market = system.select(ORGANIZATIONS.HOME_MARKET_ID)
                    .from(ORGANIZATIONS)
                    .where(ORGANIZATIONS.ID.eq(orgId))
                    .fetchOne(ORGANIZATIONS.HOME_MARKET_ID);
            if (market == null) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            staff.requireInMarket(VIEW, market);
            condition = condition.and(AUDIT_LOG.ORG_ID.eq(orgId));
        } else if (!staff.global(VIEW)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose an organization in your markets.");
        }
        if (actorUserId != null) {
            condition = condition.and(AUDIT_LOG.ACTOR_USER_ID.eq(actorUserId));
        }
        if (action != null) {
            condition = condition.and(AUDIT_LOG.ACTION.eq(action));
        }
        if (targetId != null) {
            condition = condition.and(AUDIT_LOG.TARGET_ID.eq(targetId));
        }
        if (cursor != null) {
            condition = condition.and(AUDIT_LOG.ID.lt(Directory.cursorId(cursor)));
        }
        List<AdminBodies.AuditEntry> rows = system.selectFrom(AUDIT_LOG)
                .where(condition)
                .orderBy(AUDIT_LOG.ID.desc())
                .limit(limit + 1)
                .fetch(this::entry);
        return Directory.page(rows, limit, AdminBodies.AuditEntry::id);
    }

    List<AdminBodies.AuditEntry> recent(UUID orgId, int count) {
        return system.selectFrom(AUDIT_LOG)
                .where(AUDIT_LOG.ORG_ID.eq(orgId))
                .orderBy(AUDIT_LOG.ID.desc())
                .limit(count)
                .fetch(this::entry);
    }

    private AdminBodies.AuditEntry entry(AuditLogRecord row) {
        return new AdminBodies.AuditEntry(
                row.getId(),
                row.getOccurredAt().toInstant(),
                row.getActorKind(),
                row.getActorUserId(),
                row.getOrgId(),
                row.getAction(),
                row.getTargetType(),
                row.getTargetId(),
                map(row.getBeforeState()),
                map(row.getAfterState()),
                row.getReason(),
                row.getRequestId());
    }

    private Map<String, Object> map(JSONB value) {
        return value == null ? null : json.readValue(value.data(), MAP);
    }
}
