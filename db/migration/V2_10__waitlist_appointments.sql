-- Plan 2.6, slice 3 (waitlist, S-15): an appointment offer holds the time with a scheduling hold, and an accepted
-- offer is an appointment (orders arrive with payments in Stage 3). Entries are matched per location, oldest first.
ALTER TABLE commerce.waitlist_offers
  ADD COLUMN hold_id uuid,
  ADD COLUMN appointment_id uuid,
  ADD CONSTRAINT waitlist_offers_hold_fk FOREIGN KEY (org_id, hold_id) REFERENCES scheduling.holds (org_id, id),
  ADD CONSTRAINT waitlist_offers_appointment_fk
    FOREIGN KEY (org_id, appointment_id) REFERENCES scheduling.appointments (org_id, id);
ALTER TABLE commerce.waitlist_offers DROP CONSTRAINT waitlist_offers_check1;
ALTER TABLE commerce.waitlist_offers ADD CONSTRAINT waitlist_offers_accepted_check
  CHECK ((status = 'ACCEPTED') = (order_id IS NOT NULL OR appointment_id IS NOT NULL));

CREATE INDEX waitlist_location_idx ON commerce.waitlist_entries (location_id, created_at)
  WHERE status IN ('WAITING', 'OFFERED') AND location_id IS NOT NULL;
CREATE INDEX waitlist_entries_user_idx ON commerce.waitlist_entries (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX waitlist_offers_entry_idx ON commerce.waitlist_offers (entry_id, offered_at DESC);
CREATE INDEX waitlist_offers_open_idx ON commerce.waitlist_offers (expires_at) WHERE status = 'OPEN';

-- A person sees their own waitlist entries and offers across businesses (Bookings → Waitlists).
CREATE POLICY consumer_own_waitlist_entries ON commerce.waitlist_entries FOR SELECT
  USING (user_id = (SELECT platform.current_user_id()));
CREATE POLICY consumer_own_waitlist_offers ON commerce.waitlist_offers FOR SELECT
  USING (EXISTS (SELECT 1 FROM commerce.waitlist_entries e WHERE e.id = entry_id));  -- the entry's visibility decides
