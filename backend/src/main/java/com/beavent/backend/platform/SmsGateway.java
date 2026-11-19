package com.beavent.backend.platform;

/**
 * Sends a text message through the configured provider ({@code beavent.sms.provider}). Provider adapters
 * (Ethio telecom, Africa's Talking, Telegram bot, WhatsApp ...) implement this; the choice is a deployment decision
 * (plan dependency D4). Locally the {@code log} provider writes messages to the log.
 */
public interface SmsGateway {

    enum Channel {
        SMS,
        TELEGRAM,
        WHATSAPP,
        VOICE
    }

    /**
     * @param to E.164 phone number
     * @throws IllegalStateException if the message could not be accepted by the provider
     */
    void send(String to, Channel channel, String text);
}
