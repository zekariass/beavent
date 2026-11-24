-- Booking (plan step 2.3): a hold remembers what it holds, so confirming it writes exactly what availability checked,
-- and which appointment it became, so a repeated confirm returns that appointment instead of booking twice.
ALTER TABLE scheduling.holds
  ADD COLUMN location_id    uuid,
  ADD COLUMN plan           jsonb,
  ADD COLUMN appointment_id uuid,
  ADD CONSTRAINT holds_location_fk FOREIGN KEY (org_id, location_id) REFERENCES tenant.locations (org_id, id),
  ADD CONSTRAINT holds_appointment_fk FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id);

-- Live holds by holder (the per-holder limit) and by expiry (the expiry job).
CREATE INDEX holds_holder_live_idx ON scheduling.holds (holder_key) WHERE status = 'ACTIVE';
CREATE INDEX holds_expiry_idx ON scheduling.holds (expires_at) WHERE status = 'ACTIVE';

-- The person's name as the customer saw it when booking (the "my bookings" list reads it without the team's records).
ALTER TABLE scheduling.appointment_items ADD COLUMN staff_name text;
