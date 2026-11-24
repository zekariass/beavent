package com.beavent.backend.platform.persistence;

import static com.beavent.db.platform.Tables.AUDIT_LOG;

import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.web.RequestIdFilter;
import java.util.Map;
import org.jooq.JSONB;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class JooqAudit implements Audit {

    private final CurrentTransactionDsl transactionDsl;
    private final JsonMapper json;

    JooqAudit(CurrentTransactionDsl transactionDsl, JsonMapper json) {
        this.transactionDsl = transactionDsl;
        this.json = json;
    }

    @Override
    public void record(Entry entry) {
        transactionDsl
                .require("Audit.record (" + entry.action() + ")")
                .insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.ACTOR_KIND, entry.actor().name())
                .set(AUDIT_LOG.ACTOR_USER_ID, entry.actorUserId())
                .set(AUDIT_LOG.ORG_ID, entry.orgId())
                .set(AUDIT_LOG.ACTION, entry.action())
                .set(AUDIT_LOG.TARGET_TYPE, entry.targetType())
                .set(AUDIT_LOG.TARGET_ID, entry.targetId())
                .set(AUDIT_LOG.BEFORE_STATE, jsonb(entry.before()))
                .set(AUDIT_LOG.AFTER_STATE, jsonb(entry.after()))
                .set(AUDIT_LOG.REASON, entry.reason())
                .set(AUDIT_LOG.REQUEST_ID, MDC.get(RequestIdFilter.MDC_KEY))
                .execute();
    }

    private JSONB jsonb(Map<String, Object> state) {
        return state == null ? null : JSONB.valueOf(json.writeValueAsString(state));
    }
}
