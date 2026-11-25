package com.beavent.backend.comms.internal;

import com.beavent.backend.platform.Outbox;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CommsConfiguration {

    static final String APPOINTMENTS_QUEUE = "beavent.comms.appointments";
    static final String WAITLIST_QUEUE = "beavent.comms.waitlist";
    static final String PAYOUTS_QUEUE = "beavent.comms.payouts";

    /** A payout account was added or changed: the people who manage payouts are told (plan 3.4). */
    @Bean
    Queue commsPayoutsQueue() {
        return QueueBuilder.durable(PAYOUTS_QUEUE)
                .deadLetterExchange(Outbox.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    Binding commsPayoutsBinding(Queue commsPayoutsQueue) {
        return BindingBuilder.bind(commsPayoutsQueue)
                .to(new TopicExchange(Outbox.EXCHANGE))
                .with("payout_account.*");
    }

    /** A payout was paid, failed or rejected (plan 3.5). */
    @Bean
    Binding commsPayoutOutcomeBinding(Queue commsPayoutsQueue) {
        return BindingBuilder.bind(commsPayoutsQueue)
                .to(new TopicExchange(Outbox.EXCHANGE))
                .with("payout.*");
    }

    @Bean
    Queue commsWaitlistQueue() {
        return QueueBuilder.durable(WAITLIST_QUEUE)
                .deadLetterExchange(Outbox.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    Binding commsWaitlistBinding(Queue commsWaitlistQueue) {
        return BindingBuilder.bind(commsWaitlistQueue)
                .to(new TopicExchange(Outbox.EXCHANGE))
                .with("waitlist.*");
    }

    @Bean
    Queue commsAppointmentsQueue() {
        return QueueBuilder.durable(APPOINTMENTS_QUEUE)
                .deadLetterExchange(Outbox.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    Binding commsAppointmentsBinding(Queue commsAppointmentsQueue) {
        return BindingBuilder.bind(commsAppointmentsQueue)
                .to(new TopicExchange(Outbox.EXCHANGE))
                .with("appointment.*");
    }

    /** A refund was paid: the customer is told (plan 3.6). */
    @Bean
    Binding commsRefundsBinding(Queue commsAppointmentsQueue) {
        return BindingBuilder.bind(commsAppointmentsQueue)
                .to(new TopicExchange(Outbox.EXCHANGE))
                .with("refund.succeeded");
    }
}
