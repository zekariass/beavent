-- =============================================================================
-- 8.4: notification settings
--      what a person chooses about reminders and review requests: which channels, and quiet hours. Transactional
--      messages (confirmations, changes, payments) are not optional and ignore these settings.
-- =============================================================================

CREATE TABLE comms.notification_settings (
  user_id             uuid PRIMARY KEY REFERENCES iam.users(id) ON DELETE CASCADE,
  reminders_sms       boolean     NOT NULL DEFAULT true,
  reminders_email     boolean     NOT NULL DEFAULT true,
  quiet_hours_enabled boolean     NOT NULL DEFAULT false,
  -- In the local time of the place the message is about (one zone today).
  quiet_from          time        NOT NULL DEFAULT '21:00',
  quiet_to            time        NOT NULL DEFAULT '07:00',
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (quiet_from <> quiet_to)
);

COMMENT ON TABLE comms.notification_settings IS
  'Reminder channels and quiet hours of a person; absent row = defaults (reminders on both channels, no quiet hours).';

GRANT ALL ON comms.notification_settings TO beavent_system;
GRANT SELECT ON comms.notification_settings TO beavent_readonly;
