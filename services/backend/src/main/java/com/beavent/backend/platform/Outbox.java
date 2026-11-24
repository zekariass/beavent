package com.beavent.backend.platform;

/**
 * Transactional outbox (platform.outbox). {@link #publish} writes the event in the <em>current</em> database
 * transaction, so the event exists if and only if the change committed. A relay publishes it to RabbitMQ afterwards
 * (at-least-once); consumers must be idempotent, see {@link ProcessedMessages}.
 */
public interface Outbox {

    /** Topic exchange all domain events are published to; routing key = event type. */
    String EXCHANGE = "beavent.events";

    /** Dead-letter exchange consumers' queues should point at. */
    String DEAD_LETTER_EXCHANGE = "beavent.dead-letter";

    /**
     * Records {@code event} in the current transaction.
     *
     * @throws IllegalStateException if no application- or system-role transaction is active
     */
    void publish(DomainEvent event);
}
