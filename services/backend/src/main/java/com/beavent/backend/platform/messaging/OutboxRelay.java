package com.beavent.backend.platform.messaging;

import static com.beavent.db.platform.Tables.OUTBOX;

import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.platform.tables.records.OutboxRecord;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Date;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox rows to RabbitMQ (at-least-once). Rows are claimed with {@code FOR UPDATE SKIP LOCKED},
 * so any number of instances can run the relay without publishing the same row concurrently. Each message is
 * confirmed by the broker before the row is marked published; a crash in between causes a re-publish, which
 * consumers absorb via {@link com.beavent.backend.platform.ProcessedMessages}.
 */
@Component
@ConditionalOnProperty(name = "beavent.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int BATCH_SIZE = 100;

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;

    OutboxRelay(@SystemDb DSLContext dsl, @SystemDb TransactionTemplate transactions, RabbitTemplate rabbit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${beavent.outbox.relay.interval-ms:500}")
    void relay() {
        Integer published;
        do {
            published = transactions.execute(status -> publishBatch());
        } while (published != null && published == BATCH_SIZE);
    }

    /** @return number of rows published in this batch */
    int publishBatch() {
        var batch = dsl.selectFrom(OUTBOX)
                .where(OUTBOX.PUBLISHED_AT.isNull())
                .orderBy(OUTBOX.OCCURRED_AT, OUTBOX.ID)
                .limit(BATCH_SIZE)
                .forUpdate()
                .skipLocked()
                .fetch();
        int published = 0;
        for (OutboxRecord row : batch) {
            try {
                send(row);
            } catch (AmqpException | IllegalStateException e) {
                log.warn("Outbox publish failed for {} ({}); will retry", row.getId(), row.getEventType(), e);
                dsl.update(OUTBOX)
                        .set(OUTBOX.ATTEMPTS, (short) (row.getAttempts() + 1))
                        .set(OUTBOX.LAST_ERROR, String.valueOf(e.getMessage()))
                        .where(OUTBOX.ID.eq(row.getId()))
                        .execute();
                break; // keep ordering; retry from this row on the next run
            }
            dsl.update(OUTBOX)
                    .set(OUTBOX.PUBLISHED_AT, OffsetDateTime.now())
                    .where(OUTBOX.ID.eq(row.getId()))
                    .execute();
            published++;
        }
        return published;
    }

    private void send(OutboxRecord row) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(row.getId().toString());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setTimestamp(Date.from(row.getOccurredAt().toInstant()));
        properties.setType(row.getEventType());
        properties.setHeader("aggregate_type", row.getAggregateType());
        properties.setHeader("aggregate_id", row.getAggregateId().toString());
        properties.setHeader("event_version", row.getEventVersion());
        if (row.getOrgId() != null) {
            properties.setHeader("org_id", row.getOrgId().toString());
        }
        Message message = new Message(row.getPayload().data().getBytes(StandardCharsets.UTF_8), properties);
        rabbit.invoke(operations -> {
            operations.send(Outbox.EXCHANGE, row.getEventType(), message);
            operations.waitForConfirmsOrDie(5_000);
            return null;
        });
    }
}
