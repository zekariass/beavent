package com.beavent.backend.platform;

import static com.beavent.db.crm.Tables.TAGS;
import static com.beavent.db.platform.Tables.OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beavent.backend.IntegrationTest;
import com.beavent.backend.TestTenants;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** A change and its event commit together; the relay publishes it; a consumer applies it exactly once. */
@IntegrationTest
class OutboxTests {

    static final String EVENT_TYPE = "test.tag_created";

    @Autowired
    DSLContext app;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    @SystemDb
    DSLContext system;

    @Autowired
    Outbox outbox;

    @Autowired
    ProcessedMessages processedMessages;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    String queue;

    @BeforeEach
    void bindTestQueue() {
        queue = "test.outbox." + UUID.randomUUID();
        admin.declareQueue(new Queue(queue, false, false, true));
        admin.declareBinding(BindingBuilder.bind(new Queue(queue))
                .to(new TopicExchange(Outbox.EXCHANGE))
                .with(EVENT_TYPE));
    }

    @AfterEach
    void deleteTestQueue() {
        admin.deleteQueue(queue);
    }

    @Test
    void eventCommittedWithTheChangeIsRelayedAndHandledExactlyOnce() {
        TestTenants tenants = new TestTenants(system);
        UUID user = tenants.user();
        UUID org = tenants.organization(user);
        UUID tagId = UUID.randomUUID();

        RequestContext.forOrganization(user, org)
                .run(() -> transactions.executeWithoutResult(s -> {
                    app.insertInto(TAGS)
                            .set(TAGS.ID, tagId)
                            .set(TAGS.ORG_ID, org)
                            .set(TAGS.NAME, "loyal")
                            .execute();
                    outbox.publish(new DomainEvent("crm.tag", tagId, EVENT_TYPE, 1, org, Map.of("name", "loyal")));
                }));

        Message message = rabbit.receive(queue, 15_000);
        assertThat(message).as("relayed message").isNotNull();
        // JSONB is stored normalised ({"name": "loyal"}); compare without whitespace.
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8).replaceAll("\\s", ""))
                .isEqualTo("{\"name\":\"loyal\"}");
        assertThat(message.getMessageProperties().<String>getHeader("org_id")).isEqualTo(org.toString());

        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        var row = system.selectFrom(OUTBOX).where(OUTBOX.ID.eq(messageId)).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getAggregateId()).isEqualTo(tagId);
        assertThat(row.getPublishedAt()).isNotNull();

        // At-least-once delivery: the same message arriving twice has its effect applied once.
        AtomicInteger applied = new AtomicInteger();
        assertThat(processedMessages.runOnce("test-consumer", messageId, applied::incrementAndGet))
                .isTrue();
        assertThat(processedMessages.runOnce("test-consumer", messageId, applied::incrementAndGet))
                .isFalse();
        assertThat(applied).hasValue(1);
        // A different consumer handles the same message independently.
        assertThat(processedMessages.runOnce("other-consumer", messageId, applied::incrementAndGet))
                .isTrue();
    }

    @Test
    void rolledBackChangeLeavesNoEvent() {
        TestTenants tenants = new TestTenants(system);
        UUID user = tenants.user();
        UUID org = tenants.organization(user);
        UUID aggregate = UUID.randomUUID();

        RequestContext.forOrganization(user, org)
                .run(() -> transactions.executeWithoutResult(s -> {
                    outbox.publish(new DomainEvent("crm.tag", aggregate, EVENT_TYPE, 1, org, Map.of()));
                    s.setRollbackOnly();
                }));

        assertThat(system.fetchCount(OUTBOX, OUTBOX.AGGREGATE_ID.eq(aggregate))).isZero();
    }

    @Test
    void failedHandlerLeavesNoMarkerSoTheMessageIsRetried() {
        UUID messageId = UUID.randomUUID();
        assertThatThrownBy(() -> processedMessages.runOnce("flaky", messageId, () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);
        AtomicInteger applied = new AtomicInteger();
        assertThat(processedMessages.runOnce("flaky", messageId, applied::incrementAndGet))
                .isTrue();
        assertThat(applied).hasValue(1);
    }

    @Test
    void publishingOutsideATransactionIsRefused() {
        assertThatThrownBy(() ->
                        outbox.publish(new DomainEvent("crm.tag", UUID.randomUUID(), EVENT_TYPE, 1, null, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside the transaction");
    }
}
