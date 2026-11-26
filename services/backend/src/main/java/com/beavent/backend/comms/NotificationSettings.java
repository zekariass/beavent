package com.beavent.backend.comms;

import java.util.UUID;

/**
 * What a person chose about reminders and review requests (plan step 8.4): which channels carry them and when not to
 * be disturbed. Confirmations, changes, payments and security messages are not optional and ignore these settings.
 * Quiet hours are in the local time of the place the message is about; a reminder that would arrive in them waits until
 * they end, and one that cannot wait (the visit is too close) is not sent.
 */
public interface NotificationSettings {

    /**
     * @param remindersPush a push notification to the phone app, for a person with a registered device
     * @param quietFrom {@code HH:mm}, start of quiet hours
     * @param quietTo {@code HH:mm}, end of quiet hours (the next morning when earlier than {@code quietFrom})
     */
    record Settings(
            boolean remindersPush,
            boolean remindersSms,
            boolean remindersEmail,
            boolean quietHours,
            String quietFrom,
            String quietTo) {}

    /** The person's settings, or the defaults. */
    Settings get(UUID userId);

    /** @throws com.beavent.backend.platform.ApiException {@code VALIDATION_FAILED} for times that are not {@code HH:mm} or equal */
    Settings save(UUID userId, Settings settings);
}
