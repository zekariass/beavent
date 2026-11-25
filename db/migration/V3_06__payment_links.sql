-- Plan Stage 3, slice 3.7 (payment links): a link pays one payment of an order without the payer signing in. One link
-- is live per payment: sending a new one revokes the earlier.
CREATE UNIQUE INDEX payment_links_one_active ON payments.payment_links (intent_id) WHERE status = 'ACTIVE';
CREATE INDEX payment_links_intent_idx ON payments.payment_links (intent_id, created_at DESC);
