package com.beavent.backend.platform;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A fact another module may react to, e.g. {@code appointment.confirmed}. Published through the {@link Outbox} in
 * the same transaction as the change, then relayed to RabbitMQ (exchange {@link Outbox#EXCHANGE}, routing key =
 * {@code eventType}).
 *
 * @param aggregateType e.g. {@code scheduling.appointment}
 * @param aggregateId id of the changed aggregate
 * @param eventType dotted, lower-case, e.g. {@code order.paid}
 * @param version payload schema version (starts at 1)
 * @param orgId owning organization, or {@code null} for platform-level events
 * @param payload serialised to JSON
 */
public record DomainEvent(
        String aggregateType, UUID aggregateId, String eventType, int version, UUID orgId, Object payload) {

    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");

    public DomainEvent {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("eventType must be dotted lower-case, e.g. order.paid: " + eventType);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }
}
