-- =============================================================================
-- 8.5: basic marketing
--      SMS campaigns to people who agreed to hear from a business (followers, clients with recorded consent), with a
--      link to the business that is counted, and a way to stop that works from the message itself.
-- =============================================================================

-- Audiences a business can pick without building segments: its followers, its clients who agreed, those who came
-- lately, those who have not been for a while.
ALTER TABLE marketing.campaigns DROP CONSTRAINT campaigns_audience_check;
ALTER TABLE marketing.campaigns
  ADD CONSTRAINT campaigns_audience_check CHECK (audience IN
    ('SEGMENT','FOLLOWERS','EVENT_ATTENDEES','ALL_CUSTOMERS','CLIENTS','PAST_CLIENTS','LAPSED_CLIENTS')),
  ADD COLUMN location_id uuid,
  ADD COLUMN days        integer CHECK (days IS NULL OR days BETWEEN 1 AND 1095),
  ADD FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id);

COMMENT ON COLUMN marketing.campaigns.content IS
  'Message text per language: {"am": "...", "en": "..."}; the link and the stop line are added when it is sent.';
COMMENT ON COLUMN marketing.campaigns.location_id IS 'The place the link in the message opens (default: the first active location).';
COMMENT ON COLUMN marketing.campaigns.days IS 'PAST_CLIENTS: came within this many days. LAPSED_CLIENTS: not within this many days.';

-- One row per person the campaign was queued for; `token` is in the message's link and stop line (not guessable).
ALTER TABLE marketing.campaign_recipients
  ADD COLUMN token           text,
  ADD COLUMN notification_id uuid;
CREATE UNIQUE INDEX campaign_recipients_token_uq ON marketing.campaign_recipients (token) WHERE token IS NOT NULL;
CREATE INDEX campaign_recipients_notification_idx ON marketing.campaign_recipients (notification_id) WHERE notification_id IS NOT NULL;
CREATE INDEX campaign_recipients_campaign_idx ON marketing.campaign_recipients (campaign_id, status);

CREATE INDEX campaigns_due_idx ON marketing.campaigns (scheduled_at) WHERE status = 'SCHEDULED';
CREATE INDEX campaigns_org_idx ON marketing.campaigns (org_id, created_at DESC);

-- Tracking links a business shares itself (Instagram, a poster): which place they open and who made them.
ALTER TABLE marketing.tracking_links
  ADD COLUMN created_by uuid REFERENCES iam.users(id);
CREATE INDEX tracking_links_org_idx ON marketing.tracking_links (org_id, created_at DESC);
