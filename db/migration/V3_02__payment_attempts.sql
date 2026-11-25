-- Plan Stage 3, slice 3.3 (deposits and prepayment at booking): hosted checkout lets the customer choose the method on
-- the provider's page (ArifPay, Chapa), so an attempt is recorded under one generic method; the method actually used
-- is read from the provider's statements at reconciliation (3.9).
INSERT INTO payments.methods (code, family, name, is_internal) VALUES
  ('PROVIDER_CHECKOUT', 'WALLET', 'Online payment (method chosen on the provider''s page)', false);

-- The sweep that expires unpaid bookings and the poller of open attempts read these by time.
CREATE INDEX payment_intents_open_idx ON payments.payment_intents (order_id) WHERE status IN ('REQUIRES_METHOD','PROCESSING','REQUIRES_ACTION');
