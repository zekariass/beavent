package com.beavent.backend.platform.messaging;

import com.beavent.backend.platform.Outbox;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RabbitMQ topology owned by the platform: the domain-event topic exchange and a dead-letter exchange + queue.
 * Each consuming module declares its own queue bound to the event types it needs, with
 * {@code x-dead-letter-exchange = beavent.dead-letter}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class MessagingConfiguration {

    static final String DEAD_LETTER_QUEUE = "beavent.dead-letters";

    @Bean
    TopicExchange domainEventsExchange() {
        return ExchangeBuilder.topicExchange(Outbox.EXCHANGE).durable(true).build();
    }

    @Bean
    FanoutExchange deadLetterExchange() {
        return ExchangeBuilder.fanoutExchange(Outbox.DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }
}
