package com.beavent.backend.platform.messaging;

import static com.beavent.db.platform.Tables.OUTBOX;

import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.SystemDb;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Writes events into platform.outbox using whichever role's transaction is active on this thread. */
@Component
class OutboxWriter implements Outbox {

    private final DataSource appDataSource;
    private final DSLContext appDsl;
    private final DataSource systemDataSource;
    private final DSLContext systemDsl;
    private final JsonMapper json;

    OutboxWriter(
            DataSource appDataSource,
            DSLContext appDsl,
            @SystemDb DataSource systemDataSource,
            @SystemDb DSLContext systemDsl,
            JsonMapper json) {
        this.appDataSource = appDataSource;
        this.appDsl = appDsl;
        this.systemDataSource = systemDataSource;
        this.systemDsl = systemDsl;
        this.json = json;
    }

    @Override
    public void publish(DomainEvent event) {
        DSLContext dsl;
        if (TransactionSynchronizationManager.getResource(appDataSource) != null) {
            dsl = appDsl;
        } else if (TransactionSynchronizationManager.getResource(systemDataSource) != null) {
            dsl = systemDsl;
        } else {
            throw new IllegalStateException(
                    "Outbox.publish must run inside the transaction that changes the aggregate (" + event.eventType()
                            + ")");
        }
        dsl.insertInto(OUTBOX)
                .set(OUTBOX.ORG_ID, event.orgId())
                .set(OUTBOX.AGGREGATE_TYPE, event.aggregateType())
                .set(OUTBOX.AGGREGATE_ID, event.aggregateId())
                .set(OUTBOX.EVENT_TYPE, event.eventType())
                .set(OUTBOX.EVENT_VERSION, (short) event.version())
                .set(OUTBOX.PAYLOAD, JSONB.valueOf(json.writeValueAsString(event.payload())))
                .execute();
    }
}
