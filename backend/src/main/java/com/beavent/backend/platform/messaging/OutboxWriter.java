package com.beavent.backend.platform.messaging;

import static com.beavent.db.platform.Tables.OUTBOX;

import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.persistence.CurrentTransactionDsl;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes events into platform.outbox using whichever role's transaction is active on this thread. */
@Component
class OutboxWriter implements Outbox {

    private final CurrentTransactionDsl transactionDsl;
    private final JsonMapper json;

    OutboxWriter(CurrentTransactionDsl transactionDsl, JsonMapper json) {
        this.transactionDsl = transactionDsl;
        this.json = json;
    }

    @Override
    public void publish(DomainEvent event) {
        transactionDsl
                .require("Outbox.publish (" + event.eventType() + ")")
                .insertInto(OUTBOX)
                .set(OUTBOX.ORG_ID, event.orgId())
                .set(OUTBOX.AGGREGATE_TYPE, event.aggregateType())
                .set(OUTBOX.AGGREGATE_ID, event.aggregateId())
                .set(OUTBOX.EVENT_TYPE, event.eventType())
                .set(OUTBOX.EVENT_VERSION, (short) event.version())
                .set(OUTBOX.PAYLOAD, JSONB.valueOf(json.writeValueAsString(event.payload())))
                .execute();
    }
}
