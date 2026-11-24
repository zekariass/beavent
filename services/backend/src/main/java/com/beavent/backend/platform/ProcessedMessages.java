package com.beavent.backend.platform;

import java.util.UUID;

/**
 * Exactly-once <em>effect</em> for at-least-once delivery. The handler runs inside a {@link SystemDb} transaction
 * together with the "already processed?" marker (platform.processed_messages), so a redelivered message is skipped
 * and a failed handler leaves no marker (the message is retried).
 */
public interface ProcessedMessages {

    /**
     * Runs {@code handler} unless {@code consumer} already processed {@code messageId}.
     *
     * @return {@code true} if the handler ran, {@code false} if this message was already processed
     */
    boolean runOnce(String consumer, UUID messageId, Runnable handler);
}
