/**
 * Communications (plan step 2.3, minimal; Stage 8.4 completes it): turns domain events into notifications for the
 * people concerned and delivers them by SMS and email in their language, with Ethiopian and international time.
 * Notifications are the durable queue ({@code comms.notifications.send_after} for reminders; deliveries with retries).
 * Runs with the system role (it serves every business).
 *
 * <p>No public API yet: other modules publish events through the outbox.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Communications")
package com.beavent.backend.comms;
