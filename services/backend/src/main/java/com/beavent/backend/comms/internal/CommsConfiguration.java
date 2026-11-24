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
}
