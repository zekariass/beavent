package com.beavent.backend.discovery.internal;

import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProcessedMessages;
import com.beavent.backend.platform.SystemDb;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * After a person's first completed visit at a business they follow it (source {@code AFTER_VISIT}), so its news and
 * offers can reach them (plan step 8.2). Only the first visit: someone who stopped following is not signed up again
 * by the next one. Guests (no account) are not followed.
 */
@Component
class VisitFollows {

    static final String QUEUE = "beavent.discovery.visits";
    private static final String CONSUMER = "discovery.visits";

    private final DSLContext system;
    private final ProcessedMessages processedMessages;
    private final JsonMapper json;

    VisitFollows(@SystemDb DSLContext system, ProcessedMessages processedMessages, JsonMapper json) {
        this.system = system;
        this.processedMessages = processedMessages;
        this.json = json;
    }

    @Configuration(proxyBeanMethods = false)
    static class Queues {

        @Bean
        Queue discoveryVisitsQueue() {
            return QueueBuilder.durable(QUEUE)
                    .deadLetterExchange(Outbox.DEAD_LETTER_EXCHANGE)
                    .build();
        }

        @Bean
        Binding discoveryVisitsBinding(Queue discoveryVisitsQueue) {
            return BindingBuilder.bind(discoveryVisitsQueue)
                    .to(new TopicExchange(Outbox.EXCHANGE))
                    .with("appointment.completed");
        }
    }

    @RabbitListener(queues = QUEUE)
    void onVisitCompleted(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        UUID appointmentId = UUID.fromString(
                json.readTree(message.getBody()).get("appointmentId").asString());
        processedMessages.runOnce(CONSUMER, messageId, () -> afterVisit(appointmentId));
    }

    /** Follows the business for the person whose first completed visit this is. */
    void afterVisit(UUID appointmentId) {
        system.execute("""
                INSERT INTO discovery.follows (user_id, org_id, source)
                SELECT c.user_id, a.org_id, 'AFTER_VISIT'
                  FROM scheduling.appointments a
                  JOIN crm.customers c ON c.id = a.customer_id AND c.org_id = a.org_id
                 WHERE a.id = ? AND c.user_id IS NOT NULL
                   AND (SELECT count(*) FROM scheduling.appointments a2
                          JOIN crm.customers c2 ON c2.id = a2.customer_id AND c2.org_id = a2.org_id
                         WHERE a2.org_id = a.org_id AND c2.user_id = c.user_id AND a2.status = 'COMPLETED') = 1
                ON CONFLICT (user_id, org_id) DO NOTHING
                """, appointmentId);
    }
}
