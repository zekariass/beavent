-- Plan Stage 3, slice 3.6 (refunds for deposits): a sale that was cancelled or not attended is settled by the
-- business's cancellation terms. The part the business keeps is released like any sale, without waiting for a visit
-- that will not happen ("settled"); the rest is refunded.
ALTER TABLE ledger.scheduled_releases
  ADD COLUMN settled boolean NOT NULL DEFAULT false;

-- Who is paid and how finance should pay it: the refund queue reads by status.
CREATE INDEX refunds_queue_idx ON payments.refunds (status, created_at);
