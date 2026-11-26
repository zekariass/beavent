package com.beavent.backend.platform;

import java.util.List;
import java.util.Map;

/**
 * Sends push notifications to phones through the configured provider ({@code beavent.push.provider}): the Expo push
 * service in staging and production, which forwards to Apple and Google, or the {@code log} provider locally.
 */
public interface PushGateway {

    /**
     * @param title the first line
     * @param body the text
     * @param data what the app needs when the notification is tapped, e.g. {@code route}
     */
    record Message(String title, String body, Map<String, String> data) {}

    enum Outcome {
        /** The provider accepted the message. */
        SENT,
        /** The device can no longer receive pushes (uninstalled, or permission withdrawn): stop sending to its token. */
        DEVICE_GONE
    }

    /**
     * Sends one message to each token.
     *
     * @return the outcome per token
     * @throws IllegalStateException if the provider could not be reached or refused the request (try again later)
     */
    Map<String, Outcome> send(List<String> tokens, Message message);
}
