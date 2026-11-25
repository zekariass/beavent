package com.beavent.backend.scheduling.internal;

import static com.beavent.db.scheduling.Tables.APPOINTMENTS;

import com.beavent.backend.payments.Refunds;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProcessedMessages;
import com.beavent.backend.platform.SystemDb;
import java.time.Instant;
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
 * A booking that was cancelled, declined or not attended settles what its customer paid (plan 3.6): the business's
 * terms say what it keeps, the rest is refunded. Reacting to the booking's events keeps cancelling and paying apart;
 * the payments side repeats safely and sweeps what an event missed.
 */
@Component
class RefundTriggers {

    static final String QUEUE = "beavent.scheduling.refunds";

    @Configuration(proxyBeanMethods = false)
    static class Topology {

        @Bean
        Queue schedulingRefundsQueue() {
            return QueueBuilder.durable(QUEUE)
                    .deadLetterExchange(Outbox.DEAD_LETTER_EXCHANGE)
                    .build();
        }

        @Bean
        Binding schedulingRefundsCancelled(Queue schedulingRefundsQueue) {
            return BindingBuilder.bind(schedulingRefundsQueue)
                    .to(new TopicExchange(Outbox.EXCHANGE))
                    .with("appointment.cancelled");
        }

        @Bean
        Binding schedulingRefundsNoShow(Queue schedulingRefundsQueue) {
            return BindingBuilder.bind(schedulingRefundsQueue)
                    .to(new TopicExchange(Outbox.EXCHANGE))
                    .with("appointment.no_show");
        }

        @Bean
        Binding schedulingRefundsDeclined(Queue schedulingRefundsQueue) {
            return BindingBuilder.bind(schedulingRefundsQueue)
                    .to(new TopicExchange(Outbox.EXCHANGE))
                    .with("appointment.declined");
        }
    }

    private final DSLContext system;
    private final Refunds refunds;
    private final ProcessedMessages processedMessages;
    private final JsonMapper json;

    RefundTriggers(@SystemDb DSLContext system, Refunds refunds, ProcessedMessages processedMessages, JsonMapper json) {
        this.system = system;
        this.refunds = refunds;
        this.processedMessages = processedMessages;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE)
    void onAppointmentEvent(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        UUID appointmentId = UUID.fromString(
                json.readTree(message.getBody()).get("appointmentId").asString());
        processedMessages.runOnce("scheduling.refunds", messageId, () -> settle(appointmentId, Instant.now()));
    }

    void settle(UUID appointmentId, Instant now) {
        var a = system.select(APPOINTMENTS.ORG_ID, APPOINTMENTS.ORDER_ID)
                .from(APPOINTMENTS)
                .where(APPOINTMENTS.ID.eq(appointmentId))
                .fetchOne();
        if (a == null || a.value2() == null) return; // nothing was ordered, so nothing was paid
        refunds.settleOrder(a.value1(), a.value2(), now);
    }
}
