package com.beavent.backend.files.internal;

import com.beavent.backend.platform.Outbox;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FilesProperties.class)
class FilesConfiguration {

    static final String QUEUE = "beavent.files.processing";

    @Bean
    Queue filesProcessingQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(Outbox.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    Binding filesProcessingBinding(Queue filesProcessingQueue) {
        return BindingBuilder.bind(filesProcessingQueue)
                .to(new TopicExchange(Outbox.EXCHANGE))
                .with("file.uploaded");
    }

    @Bean
    MalwareScanner malwareScanner(FilesProperties properties) {
        return switch (properties.scanner()) {
            case "eicar" -> new MalwareScanner.Eicar();
            case "clamav" -> new MalwareScanner.ClamAv(properties.clamavHost(), properties.clamavPort());
            default -> throw new IllegalStateException("Unknown beavent.files.scanner: " + properties.scanner());
        };
    }
}
