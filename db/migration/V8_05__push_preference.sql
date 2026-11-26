-- =============================================================================
-- 8.4: push notifications
--      reminders and review requests may also come as a push notification to the person's phone app
-- =============================================================================

ALTER TABLE comms.notification_settings
  ADD COLUMN reminders_push boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN comms.notification_settings.reminders_push IS
  'Reminders and review requests as a push notification to the phone app (a person with a registered device).';
