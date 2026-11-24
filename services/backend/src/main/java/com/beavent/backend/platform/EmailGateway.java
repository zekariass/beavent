package com.beavent.backend.platform;

/**
 * Sends a plain-text email through the configured provider ({@code beavent.email.provider}). Templated, localized
 * messages arrive with the communications module (Stage 8); until then the {@code log} provider writes messages to the
 * log (and Mailpit catches nothing).
 */
public interface EmailGateway {

    /** @throws IllegalStateException if the message could not be accepted by the provider */
    void send(String to, String subject, String text);
}
